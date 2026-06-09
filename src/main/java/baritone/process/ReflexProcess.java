/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.process;

import baritone.Baritone;
import baritone.ai.AgentTelemetry;
import baritone.ai.GoalTracker;
import baritone.ai.MistralAgent;
import baritone.ai.planner.DeathWatch;
import baritone.ai.ReflexLog;
import baritone.ai.reflex.BehaviorId;
import baritone.ai.reflex.BlockPosSpec;
import baritone.ai.reflex.Detectors;
import baritone.ai.reflex.MobInfo;
import baritone.ai.reflex.ReflexEngine;
import baritone.ai.reflex.ReflexMath;
import baritone.ai.reflex.ReflexTuning;
import baritone.ai.reflex.WorldSnapshot;
import baritone.api.Settings;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.utils.BaritoneProcessHelper;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.skeleton.AbstractSkeleton;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.phys.AABB;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Survival reflexes: a deterministic, every-tick guardian that keeps the bot alive without waiting
 * on the AI model (whose round-trip can be minutes on a local model). Implemented as a temporary,
 * high-priority Baritone process so any interrupted mine/follow/goto process resumes automatically
 * when the danger has passed.
 *
 * <p>Since the scored-threat redesign this class is a thin adapter: it samples the world into a
 * pure {@link WorldSnapshot}, lets the {@link ReflexEngine} (detectors → arbiter → behavior FSMs,
 * all unit-tested without Minecraft) decide, and executes the returned actions via
 * {@link ReflexExecutor}. All decision logic lives in {@code baritone.ai.reflex} — change it
 * there, not here.
 */
public final class ReflexProcess extends BaritoneProcessHelper {

    /** Melee weapons best→worst (matched by Items constants — ProGuard-safe). */
    private static final Item[] MELEE_WEAPONS = {
            Items.NETHERITE_SWORD, Items.DIAMOND_SWORD, Items.IRON_SWORD, Items.STONE_SWORD,
            Items.GOLDEN_SWORD, Items.WOODEN_SWORD,
            Items.NETHERITE_AXE, Items.DIAMOND_AXE, Items.IRON_AXE, Items.STONE_AXE,
            Items.GOLDEN_AXE, Items.WOODEN_AXE
    };

    /**
     * Live status for get_state / "#reflex status": "idle" or e.g.
     * "fleeing danger (creeper sev 80)". Written on the game thread, read from the agent thread.
     */
    public static volatile String ACTIVE_STATUS = "idle";

    private final ReflexEngine engine = new ReflexEngine();
    private final ReflexTuning tuning = new ReflexTuning();
    private final ReflexExecutor executor;

    private long lastHurtAt = Long.MIN_VALUE;
    private long lastWorkingAt = Long.MIN_VALUE;
    private ReflexEngine.Output lastOutput;

    /** Per-entity distance last tick, so we can derive each mob's closing speed. */
    private Map<Integer, Double> prevMobDist = new HashMap<>();
    private long prevSnapshotTick = Long.MIN_VALUE;

    public ReflexProcess(Baritone baritone) {
        super(baritone);
        this.executor = new ReflexExecutor(baritone, ctx);
    }

    @Override
    public boolean isActive() {
        LocalPlayer player = ctx.player();
        // Feed the planner's death detector every tick — BEFORE the reflexesEnabled gate, so
        // death recovery works even with reflexes off. isActive() runs every tick regardless.
        if (player != null && ctx.world() != null) {
            String dim;
            try {
                dim = player.level().dimension().identifier().toString();
            } catch (RuntimeException e) {
                dim = "unknown";
            }
            DeathWatch.onClientTick(player.isDeadOrDying(),
                    player.getX(), player.getY(), player.getZ(), dim, ctx.world().getGameTime());
            // Auto-respawn: a dead bot is stuck on the death screen and can't move, so NONE of the
            // planner's death recovery (re-verify plan, go get drops, re-gear) can run. Respawn it
            // right away so the planner takes over a LIVING bot.
            if (player.isDeadOrDying() && Baritone.settings().aiAutoRespawn.value) {
                try {
                    net.minecraft.client.Minecraft mc = ctx.minecraft();
                    if (mc.player != null) {
                        mc.player.respawn();
                        if (mc.screen instanceof net.minecraft.client.gui.screens.DeathScreen) {
                            mc.setScreen(null);
                        }
                    }
                } catch (RuntimeException ignored) {
                    // never let respawn bookkeeping break the tick
                }
            }
        }
        if (player == null || ctx.world() == null || !Baritone.settings().reflexesEnabled.value) {
            if (engine.active() != BehaviorId.NONE) {
                ReflexLog.record("[reflex] " + engine.active().describe + " stopped (reflexes disabled)");
                engine.abort();
                executor.cleanup();
            }
            lastOutput = null;
            ACTIVE_STATUS = "idle";
            return false;
        }
        long now = ctx.world().getGameTime();
        if (player.hurtTime > 0) {
            lastHurtAt = now;
        }
        if (isWorking()) {
            lastWorkingAt = now;
        }

        refreshTuning();
        ReflexEngine.Output out = engine.tick(snapshot(player, now), tuning);
        lastOutput = out;
        if (out.plan.behavior == BehaviorId.NONE) {
            ACTIVE_STATUS = "idle";
        } else if (out.plan.cause != null) {
            ACTIVE_STATUS = out.plan.behavior.describe + " (" +
                    out.plan.cause.type.name().toLowerCase(Locale.ROOT) + " sev " + out.plan.cause.severity + ")";
        } else {
            ACTIVE_STATUS = out.plan.behavior.describe;
        }
        if (out.released) {
            executor.cleanup();
            ReflexLog.record("[reflex] " + out.previous.describe + " ended after " + (out.previousTicks / 20) + "s");
            emitTelemetry("done", out.previous, null, out.previousTicks);
        }
        if (out.engaged) {
            String note = "[reflex] " + out.plan.behavior.describe;
            ReflexLog.record(note);
            logDirect(note, ChatFormatting.GOLD);
            GoalTracker.setStatus(note);
            emitTelemetry("engage", out.plan.behavior, out.plan.cause, 0);
        }
        if (out.resolvedMode != null) {
            String how;
            switch (out.resolvedMode) {
                case PILLAR:
                    how = "pillaring out of reach";
                    break;
                case WALL:
                    how = "walling it off";
                    break;
                default:
                    how = "trying a new direction";
                    break;
            }
            String note = "[reflex] can't shake it - " + how;
            ReflexLog.record(note);
            logDirect(note, ChatFormatting.GOLD);
            Map<String, Object> data = new HashMap<>();
            data.put("phase", "resolve");
            data.put("behavior", "flee");
            data.put("mode", out.resolvedMode.name().toLowerCase(Locale.ROOT));
            AgentTelemetry.emit("reflex", data);
        }
        return out.plan.behavior != BehaviorId.NONE;
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        ReflexEngine.Output out = lastOutput;
        if (out == null || out.plan.behavior == BehaviorId.NONE) {
            return new PathingCommand(null, PathingCommandType.DEFER);
        }
        return executor.execute(out.actions, true);
    }

    @Override
    public void onLostControl() {
        if (engine.active() != BehaviorId.NONE) {
            ReflexLog.record("[reflex] " + engine.active().describe + " stopped (lost control)");
            engine.abort();
            executor.cleanup();
        }
        lastOutput = null;
        ACTIVE_STATUS = "idle";
    }

    @Override
    public String displayName0() {
        return "reflexes (" + engine.active().lower() + ")";
    }

    @Override
    public double priority() {
        return 10; // above every normal process and the inventory pauser (5.1)
    }

    @Override
    public boolean isTemporary() {
        return true;
    }

    // ---------------------------------------------------------------- sampling

    private void refreshTuning() {
        Settings s = Baritone.settings();
        tuning.antiLava = s.reflexAntiLava.value;
        tuning.antiDrown = s.reflexAntiDrown.value;
        tuning.fleeCreepers = s.reflexFleeCreepers.value;
        tuning.fightBack = s.reflexFightBack.value;
        tuning.autoEat = s.reflexAutoEat.value;
        tuning.eatAtHunger = s.reflexEatAtHunger.value;
        tuning.creeperRadius = s.reflexCreeperRadius.value;
        tuning.combatRetreatHp = s.reflexCombatRetreatHealth.value.floatValue();
        tuning.retreatTargetHp = s.reflexRetreatTargetHealth.value.floatValue();
        tuning.swarmCount = s.reflexSwarmCount.value;
        tuning.pillarHeight = s.reflexPillarHeight.value;
        tuning.mlgFallTrigger = s.reflexMlgFallTrigger.value;
        tuning.fireWaterRadius = s.reflexFireWaterRadius.value;
        tuning.perceptionRadius = s.reflexPerceptionRadius.value;
        tuning.predictiveFleeBonus = s.reflexPredictiveRange.value;
        tuning.minMobDwellTicks = s.reflexMinDwellTicks.value;
        tuning.mobReleaseGraceTicks = s.reflexReleaseGraceTicks.value;
        tuning.proactiveEatHunger = s.reflexProactiveEatHunger.value;
        tuning.fightMaxMobs = s.reflexFightMaxMobs.value;
    }

    private WorldSnapshot snapshot(LocalPlayer player, long now) {
        WorldSnapshot s = new WorldSnapshot();
        s.gameTime = now;
        // vitals
        s.hp = player.getHealth();
        s.maxHp = player.getMaxHealth();
        s.food = player.getFoodData().getFoodLevel();
        s.air = player.getAirSupply();
        s.maxAir = player.getMaxAirSupply();
        s.onFire = player.isOnFire();
        s.inLava = player.isInLava();
        s.underWater = player.isUnderWater();
        s.poisoned = player.hasEffect(MobEffects.POISON) || player.hasEffect(MobEffects.WITHER);
        s.ticksSinceHurt = lastHurtAt == Long.MIN_VALUE
                ? Integer.MAX_VALUE : (int) Math.min(Integer.MAX_VALUE, now - lastHurtAt);
        s.working = lastWorkingAt != Long.MIN_VALUE && now - lastWorkingAt <= 40;
        // position & motion
        s.posX = player.getX();
        s.posY = player.getY();
        s.posZ = player.getZ();
        s.velY = player.getDeltaMovement().y;
        s.fallDistance = player.fallDistance;
        s.onGround = player.onGround();
        s.horizontalCollision = player.horizontalCollision;
        sampleBelow(player, s);
        s.headBlockedByGravity = ctx.world().getBlockState(player.blockPosition().above())
                .getBlock() instanceof FallingBlock;
        // look & UI
        s.yaw = ctx.playerRotations().getYaw();
        s.pitch = ctx.playerRotations().getPitch();
        s.screenOpen = ctx.minecraft().screen != null;
        s.attackStrengthScale = player.getAttackStrengthScale(0F);
        // inventory
        s.selectedSlot = player.getInventory().getSelectedSlot();
        sampleHotbar(player, s);
        s.hasShieldOffhand = player.getOffhandItem().is(Items.SHIELD);
        // world scans the pure core can't do itself
        if (s.inLava) {
            s.lavaEscape = findLavaEscape(player);
        }
        if (s.onFire && !s.inLava && !s.underWater) {
            s.nearestWater = findWaterNear(player);
        }
        sampleSurroundings(player, s);
        sampleAmbient(player, s);
        // mobs (perception radius reaches past the engage radius so the bot can react to a
        // threat that is closing fast while it is still far enough to do something about it)
        double scan = Math.max(Math.max(2D, tuning.creeperRadius) + 4D, tuning.perceptionRadius);
        long dtTicks = prevSnapshotTick == Long.MIN_VALUE ? 1 : Math.max(1, now - prevSnapshotTick);
        Map<Integer, Double> curMobDist = new HashMap<>();
        for (Monster e : ctx.world().getEntitiesOfClass(Monster.class,
                new AABB(player.blockPosition()).inflate(scan),
                m -> m.isAlive() && player.distanceTo(m) <= scan)) {
            MobInfo info = mobInfo(player, e, dtTicks);
            s.mobs.add(info);
            curMobDist.put(info.entityId, info.distance);
        }
        prevMobDist = curMobDist;
        prevSnapshotTick = now;
        return s;
    }

    private MobInfo mobInfo(LocalPlayer player, Monster e, long dtTicks) {
        MobInfo m = new MobInfo();
        m.entityId = e.getId();
        m.typeId = EntityType.getKey(e.getType()).getPath();
        m.x = e.getX();
        m.y = e.getY();
        m.z = e.getZ();
        m.aimY = e.getBoundingBox().getCenter().y;
        m.distance = player.distanceTo(e);
        m.lineOfSight = player.hasLineOfSight(e);
        m.creeper = e instanceof Creeper;
        m.skeleton = e instanceof AbstractSkeleton;
        m.hostile = true;
        m.ignited = e instanceof Creeper && ((Creeper) e).isIgnited();
        m.aggro = e instanceof Mob && ((Mob) e).getTarget() == player;
        Double prev = prevMobDist.get(m.entityId);
        m.approachingSpeed = prev == null ? 0D : (prev - m.distance) / dtTicks;
        return m;
    }

    /** Best melee weapon (slot+tier) and best safe food in the hotbar, plus blocks/buckets. */
    private void sampleHotbar(LocalPlayer player, WorldSnapshot s) {
        int bestRank = Integer.MAX_VALUE;
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            Item item = stack.getItem();
            for (int rank = 0; rank < MELEE_WEAPONS.length; rank++) {
                if (MELEE_WEAPONS[rank] == item) {
                    if (rank < bestRank) {
                        bestRank = rank;
                        s.bestWeaponSlot = slot;
                        s.bestWeaponTier = rank;
                    }
                    break;
                }
            }
            FoodProperties food = stack.get(DataComponents.FOOD);
            if (food != null && Detectors.isSafeFood(item.toString())
                    && food.nutrition() > s.bestFoodNutrition) {
                s.bestFoodNutrition = food.nutrition();
                s.bestFoodSlot = slot;
            }
            if (item == Items.WATER_BUCKET && s.waterBucketSlot < 0) {
                s.waterBucketSlot = slot;
            }
            if (item instanceof BlockItem) {
                s.blockCount += stack.getCount();
                if (s.blockSlot < 0) {
                    s.blockSlot = slot;
                }
            }
        }
    }

    /** Air gap straight down from the feet (for fall/void threats). */
    private void sampleBelow(LocalPlayer player, WorldSnapshot s) {
        BlockPos pos = player.blockPosition().below();
        int minY = ctx.world().getMinY();
        for (int i = 0; i < tuning.voidScanDepth; i++) {
            if (pos.getY() < minY) {
                s.voidBelow = true;
                return;
            }
            if (!ctx.world().getBlockState(pos).isAir()) {
                return;
            }
            s.gapBelow++;
            pos = pos.below();
        }
    }

    /**
     * Cheap look-ahead so behaviors never flee/sprint into lava or off a ledge. Fills the
     * per-octant safety flags plus the forward lava/drop flags relative to the current look yaw.
     */
    private void sampleSurroundings(LocalPlayer player, WorldSnapshot s) {
        BlockPos feet = player.blockPosition();
        for (int i = 0; i < ReflexMath.OCTANTS; i++) {
            BlockPos step = feet.offset(ReflexMath.OCTANT_DX[i], 0, ReflexMath.OCTANT_DZ[i]);
            s.octantSafe[i] = isStepSafe(step);
        }
        int forward = ReflexMath.nearestOctant(s.yaw);
        int dx = ReflexMath.OCTANT_DX[forward];
        int dz = ReflexMath.OCTANT_DZ[forward];
        for (int d = 1; d <= 3; d++) {
            BlockPos ahead = feet.offset(dx * d, 0, dz * d);
            if (isLavaAt(ahead) || isLavaAt(ahead.above())) {
                s.lavaAhead = true;
            }
            if (isKillingDrop(ahead)) {
                s.dropAhead = true;
            }
        }
    }

    /** A neighbor cell the bot could move into without lava, a wall, or a killing drop. */
    private boolean isStepSafe(BlockPos step) {
        if (isLavaAt(step) || isLavaAt(step.above())) {
            return false;
        }
        if (isSolid(step) && isSolid(step.above())) {
            return false; // a full wall — can't path through it on a panic sprint
        }
        return !isKillingDrop(step);
    }

    private boolean isSolid(BlockPos pos) {
        BlockState state = ctx.world().getBlockState(pos);
        return !state.isAir() && !state.canBeReplaced();
    }

    /** Open air below {@code step} deep enough that walking off it would hurt or kill. */
    private boolean isKillingDrop(BlockPos step) {
        BlockPos p = step.below();
        int minY = ctx.world().getMinY();
        int gap = 0;
        for (int i = 0; i < 6; i++) {
            if (p.getY() < minY) {
                return true;
            }
            if (!ctx.world().getBlockState(p).isAir()) {
                return false;
            }
            gap++;
            p = p.below();
        }
        return gap >= 4;
    }

    private void sampleAmbient(LocalPlayer player, WorldSnapshot s) {
        s.lightLevel = ctx.world().getBrightness(LightLayer.BLOCK, player.blockPosition());
        long dayTime = ctx.world().getDayTime() % 24000L;
        s.night = dayTime >= 13000L && dayTime <= 23000L;
    }

    /** Nearest column that isn't lava (feet, head and floor all clear), ring-searched outward. */
    private BlockPosSpec findLavaEscape(LocalPlayer player) {
        BlockPos feet = player.blockPosition();
        for (int radius = 2; radius <= 6; radius += 2) {
            for (int dir = 0; dir < 8; dir++) {
                double angle = dir * Math.PI / 4D;
                BlockPos candidate = feet.offset(
                        (int) Math.round(Math.cos(angle) * radius), 0,
                        (int) Math.round(Math.sin(angle) * radius));
                if (!isLavaAt(candidate) && !isLavaAt(candidate.above()) && !isLavaAt(candidate.below())) {
                    return new BlockPosSpec(candidate.getX(), candidate.getY(), candidate.getZ());
                }
            }
        }
        return null;
    }

    private boolean isLavaAt(BlockPos pos) {
        return ctx.world().getBlockState(pos).getFluidState().is(FluidTags.LAVA);
    }

    /** Nearest water cell in a flat ring scan (feet level ± 1) — for dousing fire. */
    private BlockPosSpec findWaterNear(LocalPlayer player) {
        BlockPos feet = player.blockPosition();
        for (int radius = 1; radius <= (int) tuning.fireWaterRadius; radius++) {
            for (int dir = 0; dir < 8; dir++) {
                double angle = dir * Math.PI / 4D;
                BlockPos candidate = feet.offset(
                        (int) Math.round(Math.cos(angle) * radius), 0,
                        (int) Math.round(Math.sin(angle) * radius));
                for (BlockPos p : new BlockPos[]{candidate, candidate.below(), candidate.above()}) {
                    if (ctx.world().getBlockState(p).getFluidState().is(FluidTags.WATER)) {
                        return new BlockPosSpec(p.getX(), p.getY(), p.getZ());
                    }
                }
            }
        }
        return null;
    }

    private boolean isWorking() {
        if (MistralAgent.ACTIVE.get() != null) {
            return true;
        }
        if (baritone.getPathingBehavior().isPathing()) {
            return true;
        }
        return baritone.getPathingControlManager().mostRecentInControl()
                .map(p -> p != this)
                .orElse(false);
    }

    // ---------------------------------------------------------------- telemetry

    private static void emitTelemetry(String phase, BehaviorId behavior,
                                      baritone.ai.reflex.Threat cause, int durationTicks) {
        Map<String, Object> data = new HashMap<>();
        data.put("phase", phase);
        data.put("behavior", behavior.name().toLowerCase(Locale.ROOT));
        if (cause != null) {
            data.put("threat", cause.type.name().toLowerCase(Locale.ROOT));
            data.put("severity", cause.severity);
        }
        if (durationTicks > 0) {
            data.put("duration_s", durationTicks / 20);
        }
        AgentTelemetry.emit("reflex", data);
    }
}
