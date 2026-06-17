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
import baritone.ai.SurvivalAgentCoordinator;
import baritone.ai.planner.DeathWatch;
import baritone.ai.ReflexLog;
import baritone.ai.reflex.SituationAssessment;
import baritone.ai.reflex.SurvivalEscalation;
import baritone.ai.reflex.BehaviorId;
import baritone.ai.reflex.BlockPosSpec;
import baritone.ai.reflex.Detectors;
import baritone.ai.reflex.EscapeColumns;
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
import net.minecraft.world.item.BedItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
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

    /**
     * A reflex behavior currently owns the bot. Tool watchdogs/deadlines (WatchdogClock) pause
     * while this is true — a shelter waiting out the night must not read as "mining stuck".
     */
    public static volatile boolean ENGAGED;

    /** Total milliseconds spent reflex-engaged since launch (50 per engaged tick) — pauses the mission clock. */
    public static final java.util.concurrent.atomic.AtomicLong ENGAGED_MILLIS =
            new java.util.concurrent.atomic.AtomicLong();

    /**
     * The brain's whole-picture read of the current tick (e.g. "endangered — 2 hostiles (1c/0r/1m),
     * can win"), for get_state / the HUD. The agent doesn't act on it, but it explains the situation.
     */
    public static volatile String SITUATION = "safe";

    /**
     * The report from the last survival episode the reflex resolved while the LLM was paused — e.g.
     * "fleeing danger handled creeper; moved 18 blocks; avoid ~(312,-44)". Surfaced once in get_state
     * so the agent resumes from an accurate view (it was moved) and doesn't path back into the trap.
     */
    public static volatile String LAST_REPORT;

    /**
     * The danger zone the last flee episode left, or null. {@code goto_coords} consults it so the LLM
     * can't immediately path the bot back to where it just nearly died (the flee → "avoid" hint →
     * forgotten → walk-back death loop). Cleared once the agent moves well clear of it.
     */
    public static volatile baritone.ai.AvoidZone LAST_AVOID_ZONE;

    /**
     * The rule ladder is exhausted and the bot is STILL endangered — the reflex is "having a bad
     * time". Set from {@code engine.inDistress()} each tick; the trigger for the cooperative LLM
     * survival agent. Read from the agent thread (get_state); the escalation policy debounces it.
     */
    public static volatile boolean DISTRESS;

    private final ReflexEngine engine = new ReflexEngine();
    private final ReflexTuning tuning = new ReflexTuning();
    private final ReflexExecutor executor;

    private long lastHurtAt = Long.MIN_VALUE;
    private long lastWorkingAt = Long.MIN_VALUE;
    private ReflexEngine.Output lastOutput;
    /** Death seq we've already cancelled Baritone work for (once per death). */
    private long lastCanceledDeathSeq;

    // ---- cooperative survival-agent escalation (the trigger + resolve bookkeeping)
    /** Consecutive ticks distress has held (debounce before summoning the LLM survival agent). */
    private int distressSustainedTicks;
    /** True while a distress episode is ongoing — so the {phase:"distress"} event fires once per onset. */
    private boolean distressEpisodeOpen;
    /** Game tick of the last escalation, for the cooldown. */
    private long lastEscalationTick = Long.MIN_VALUE;
    /** Consecutive calm ticks (no distress, no hostile near) once a survival agent is running — resolve window. */
    private int survivalClearTicks;

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
            // Capture the death CAUSE on the same edge: it drives the planner's recover-vs-regear
            // replan (a lava/fire/void death has no drops left to recover).
            boolean dead = player.isDeadOrDying();
            String cause = "unknown";
            String killer = "";
            boolean dropsDestroyed = false;
            if (dead) {
                try {
                    net.minecraft.world.damagesource.DamageSource src = player.getLastDamageSource();
                    if (src != null) {
                        cause = src.getMsgId();
                        dropsDestroyed = src.is(net.minecraft.tags.DamageTypeTags.IS_FIRE);
                    }
                    LivingEntity credit = player.getKillCredit();
                    if (credit != null) {
                        killer = EntityType.getKey(credit.getType()).getPath();
                    }
                    dropsDestroyed = dropsDestroyed || "lava".equals(cause) || "inFire".equals(cause)
                            || "onFire".equals(cause) || "outOfWorld".equals(cause)
                            || player.getY() < ctx.world().getMinY() - 2;
                } catch (RuntimeException ignored) {
                    // cause capture is best-effort; position + seq must never be lost to it
                }
            }
            DeathWatch.onClientTick(dead, player.getX(), player.getY(), player.getZ(), dim,
                    ctx.world().getGameTime(), cause, killer, dropsDestroyed);
            // Cancel in-flight Baritone work ONCE per death, so a mine/goto never resumes
            // bare-fisted after the auto-respawn while the agent is still inside a blocking tool.
            long seq = DeathWatch.currentSeq();
            if (seq > lastCanceledDeathSeq) {
                lastCanceledDeathSeq = seq;
                try {
                    baritone.getMineProcess().cancel();
                    baritone.getPathingBehavior().cancelEverything();
                } catch (RuntimeException ignored) {
                    // cancel is best-effort; the respawn below must still run
                }
            }
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
            ENGAGED = false;
            DISTRESS = false;
            distressSustainedTicks = 0;
            distressEpisodeOpen = false;
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
        ENGAGED = out.plan.behavior != BehaviorId.NONE;
        if (ENGAGED) {
            ENGAGED_MILLIS.addAndGet(50);
        }
        if (out.plan.behavior == BehaviorId.NONE) {
            ACTIVE_STATUS = "idle";
        } else if (out.plan.cause != null) {
            ACTIVE_STATUS = out.plan.behavior.describe + " (" +
                    out.plan.cause.type.name().toLowerCase(Locale.ROOT) + " sev " + out.plan.cause.severity + ")";
        } else {
            ACTIVE_STATUS = out.plan.behavior.describe;
        }
        baritone.ai.reflex.SituationAssessment sit = engine.situation();
        if (sit != null) {
            SITUATION = sit.describe();
        }
        handleDistressEscalation(now, sit);
        if (out.released) {
            executor.cleanup();
            ReflexLog.record("[reflex] " + out.previous.describe + " ended after " + (out.previousTicks / 20) + "s");
            baritone.ai.reflex.SurvivalReport report = engine.lastReport();
            if (report != null) {
                LAST_REPORT = report.summary;
                // remember the spot we fled so goto_coords can refuse to walk straight back into it
                LAST_AVOID_ZONE = report.hasAvoid
                        ? new baritone.ai.AvoidZone((int) Math.round(report.avoidX),
                                (int) Math.round(report.avoidZ))
                        : null;
                ReflexLog.record("[reflex] report: " + report.summary);
            }
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
        // A danger is being handled, but you can't move with a chest/crafting GUI open — if a blocking
        // craft/station tool left one up, close it so the reflex can actually flee/fight. (Eating is
        // exempt: its detector already skips while a screen is open, so this never interrupts a meal.)
        if (out.plan.behavior != BehaviorId.EAT) {
            net.minecraft.client.Minecraft mc = ctx.minecraft();
            if (mc != null && mc.screen != null && mc.player != null
                    && !(mc.player.containerMenu instanceof net.minecraft.world.inventory.InventoryMenu)) {
                mc.player.closeContainer();
                mc.setScreen(null);
            }
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
        ENGAGED = false;
        DISTRESS = false;
        distressSustainedTicks = 0;
        distressEpisodeOpen = false;
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
        tuning.defendIdle = s.aiReflexDefendIdle.value;
        tuning.gearAwareCombat = s.reflexGearAwareCombat.value;
        tuning.proactiveEngageRadius = s.reflexProactiveEngageRange.value;
        tuning.shelter = s.reflexShelter.value;
        tuning.shelterMaxTicks = Math.max(20, s.reflexShelterMaxSeconds.value * 20);
        tuning.distressTicks = Math.max(1, s.aiSurvivalDistressTicks.value);
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
        s.withered = player.hasEffect(MobEffects.WITHER);
        s.weakened = player.hasEffect(MobEffects.WEAKNESS);
        s.blinded = player.hasEffect(MobEffects.BLINDNESS) || player.hasEffect(MobEffects.DARKNESS);
        var slowEffect = player.getEffect(MobEffects.SLOWNESS);
        s.slownessLevel = slowEffect != null ? slowEffect.getAmplifier() + 1 : 0;
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
        BlockPos headPos = player.blockPosition().above();
        BlockState headState = ctx.world().getBlockState(headPos);
        s.headBlockedByGravity = headState.getBlock() instanceof FallingBlock;
        // suffocating inside ANY solid block (wall/cave-in/piston/bad teleport), not just gravity
        s.headInSolid = headState.isSuffocating(ctx.world(), headPos);
        // contact-damage block at the feet (cactus/sweet-berry we're inside, magma we stand on) — the
        // "fall-MLG landed on cactus and the bot stands there bleeding" death. Check both cells.
        s.contactHazardAtFeet = isContactHazard(ctx.world().getBlockState(player.blockPosition()))
                || isContactHazard(ctx.world().getBlockState(player.blockPosition().below()));
        // look & UI
        s.yaw = ctx.playerRotations().getYaw();
        s.pitch = ctx.playerRotations().getPitch();
        s.screenOpen = ctx.minecraft().screen != null;
        s.attackStrengthScale = player.getAttackStrengthScale(0F);
        // inventory
        s.selectedSlot = player.getInventory().getSelectedSlot();
        sampleHotbar(player, s);
        s.hasShieldOffhand = player.getOffhandItem().is(Items.SHIELD);
        s.armorValue = player.getArmorValue();
        sampleSurroundings(player, s);
        sampleAmbient(player, s);
        // mobs FIRST: the lava/surface escape picks must be mob-aware (climbing out next to a mob,
        // or surfacing into one waiting at the top, is a death the precomputed column avoids).
        // (perception radius reaches past the engage radius so the bot can react to a threat that is
        // closing fast while it is still far enough to do something about it)
        double scan = Math.max(Math.max(2D, tuning.creeperRadius) + 4D, tuning.perceptionRadius);
        // ghasts lob fireballs from far past normal perception — scan out to the ranged radius, but
        // keep a far mob ONLY if it's a ghast-class long-range shooter (everything else uses `scan`).
        double wideScan = Math.max(scan, tuning.rangedPerceptionRadius);
        long dtTicks = prevSnapshotTick == Long.MIN_VALUE ? 1 : Math.max(1, now - prevSnapshotTick);
        Map<Integer, Double> curMobDist = new HashMap<>();
        for (Monster e : ctx.world().getEntitiesOfClass(Monster.class,
                new AABB(player.blockPosition()).inflate(wideScan),
                m -> m.isAlive() && player.distanceTo(m) <= wideScan)) {
            double d = player.distanceTo(e);
            boolean ghastClass = e.getType() == EntityType.GHAST;
            if (d > scan && !ghastClass) {
                continue; // a far non-ghast is out of our reaction range — ignore until it closes
            }
            MobInfo info = mobInfo(player, e, dtTicks);
            s.mobs.add(info);
            curMobDist.put(info.entityId, info.distance);
        }
        prevMobDist = curMobDist;
        prevSnapshotTick = now;
        // knockback-toward-hazard: a melee hostile in striking range whose knockback (it shoves us away
        // from itself) would push us toward an unsafe octant (a killing drop / lava). The octant scan in
        // sampleSurroundings already flagged the unsafe directions; here we check whether the away-from-mob
        // bearing lands on one. (after mobs + octantSafe are both filled.)
        s.horizontalSpeed = Math.hypot(player.getDeltaMovement().x, player.getDeltaMovement().z);
        s.knockbackTowardUnsafe = computeKnockbackTowardUnsafe(s);
        // world scans the pure core can't do itself (after mobs, so they can be mob-aware)
        if (s.inLava) {
            s.lavaEscape = findLavaEscape(player, s.mobs);
        }
        if (s.onFire && !s.inLava && !s.underWater) {
            s.nearestWater = findWaterNear(player);
        }
        if (s.underWater) {
            sampleSurfaceEscape(player, s);
        }
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
        // unwinnable: a Warden one-shots geared players and tunnels through walls — flee, never fight.
        m.unkillable = e.getType() == EntityType.WARDEN;
        // ranged non-skeletons (blaze/ghast fireballs, a trident-armed drowned): out-trade a melee
        // charge just like a skeleton, so answer with cover/shelter — never chase into their fire.
        // A witch (throws potions, heals through our hits) and a phantom (flies out of melee reach) are
        // likewise "no melee wins this" — flag them ranged so they get cover/flee regardless of gear.
        m.ranged = e.getType() == EntityType.BLAZE || e.getType() == EntityType.GHAST
                || e.getType() == EntityType.WITCH || e.getType() == EntityType.PHANTOM
                || (e.getType() == EntityType.DROWNED && e.getMainHandItem().is(Items.TRIDENT));
        // a ghast shoots from far past normal perception — perceive it out to rangedPerceptionRadius
        // so we take cover before the first fireball, not after it has been shelling us from afar.
        m.longRange = e.getType() == EntityType.GHAST;
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
                        // remaining durability % so the power score discounts a near-broken sword
                        // (it snaps mid-fight and then deals fist damage — never "win" on it)
                        int maxDmg = stack.getMaxDamage();
                        s.bestWeaponDurabilityPercent = (maxDmg <= 0 || !stack.isDamageableItem())
                                ? -1 : (maxDmg - stack.getDamageValue()) * 100 / maxDmg;
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
            if (item instanceof BedItem && s.bedSlot < 0) {
                s.bedSlot = slot;
            }
            if (item instanceof BlockItem) {
                s.blockCount += stack.getCount();
                if (s.blockSlot < 0) {
                    s.blockSlot = slot;
                }
            }
        }
    }

    /** A block that ticks contact damage while we touch it (not fire/lava — those have own handlers). */
    private static boolean isContactHazard(BlockState st) {
        Block b = st.getBlock();
        return b == Blocks.CACTUS || b == Blocks.MAGMA_BLOCK || b == Blocks.SWEET_BERRY_BUSH
                || b == Blocks.WITHER_ROSE || b == Blocks.POWDER_SNOW;
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
            // a 2-high solid column within a few blocks that way = cover that breaks arrow LOS
            for (int d = 1; d <= 4 && !s.octantCover[i]; d++) {
                BlockPos col = feet.offset(ReflexMath.OCTANT_DX[i] * d, 0, ReflexMath.OCTANT_DZ[i] * d);
                if (isSolid(col) && isSolid(col.above())) {
                    s.octantCover[i] = true;
                }
            }
        }
        sampleShelterGround(player, s);
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

    /** Turtle-hole safety: solid non-gravity dry ground under the feet, sealed-state overhead. */
    private void sampleShelterGround(LocalPlayer player, WorldSnapshot s) {
        BlockPos feet = player.blockPosition();
        boolean safe = true;
        for (int i = 1; i <= 3 && safe; i++) {
            BlockPos p = feet.below(i);
            BlockState st = ctx.world().getBlockState(p);
            if (st.isAir() || st.getBlock() instanceof FallingBlock || !st.getFluidState().isEmpty()) {
                safe = false; // a cave, sand/gravel, or fluid underfoot — never dig into that
            }
        }
        if (safe && isLavaAt(feet.below(4))) {
            safe = false;
        }
        s.digDownSafe = safe;
        s.sealedOverhead = isSolid(feet.above(2));
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
        s.dayTime = dayTime;
        s.night = dayTime >= 13000L && dayTime <= 23000L;
    }

    /**
     * Columns that aren't lava (feet, head and floor all clear), ring-searched outward, then picked
     * mob-aware: the one farthest from any hostile (never one a mob is parked on). Climbing out RIGHT
     * next to a waiting mob — or cooking because a mob blocked the only near column — is a real death.
     */
    private BlockPosSpec findLavaEscape(LocalPlayer player, List<MobInfo> mobs) {
        BlockPos feet = player.blockPosition();
        java.util.List<BlockPosSpec> candidates = new java.util.ArrayList<>();
        for (int radius = 2; radius <= 6; radius += 2) {
            for (int dir = 0; dir < 8; dir++) {
                double angle = dir * Math.PI / 4D;
                BlockPos candidate = feet.offset(
                        (int) Math.round(Math.cos(angle) * radius), 0,
                        (int) Math.round(Math.sin(angle) * radius));
                if (!isLavaAt(candidate) && !isLavaAt(candidate.above()) && !isLavaAt(candidate.below())) {
                    candidates.add(new BlockPosSpec(candidate.getX(), candidate.getY(), candidate.getZ()));
                }
            }
            // a clear nearer ring is preferred over a farther one — only widen if nothing close is
            // mob-clear yet (so we don't climb out next to a mob when a clear column is one ring out)
            if (hasClearColumn(candidates, mobs)) {
                return EscapeColumns.best(candidates, mobs);
            }
        }
        return EscapeColumns.best(candidates, mobs);
    }

    /**
     * True if a melee hostile is close enough to land a hit AND the direction its knockback would shove
     * us (directly away from the mob) lands on an unsafe octant (a killing drop / lava) — i.e. a single
     * punch would launch us off a ledge or into lava. Pure read of the already-built snapshot.
     */
    private static boolean computeKnockbackTowardUnsafe(WorldSnapshot s) {
        for (MobInfo m : s.mobs) {
            if (m.creeper || m.skeleton || m.ranged || !m.hostile) {
                continue; // only melee shovers (a creeper explodes, a shooter doesn't melee-knockback)
            }
            if (m.distance > ReflexMath.EYE_HEIGHT + 3D) {
                continue; // out of striking range — it can't knock us anywhere yet
            }
            // knockback pushes us AWAY from the mob: the octant pointing from the mob toward us
            float awayYaw = ReflexMath.yawAway(s.posX, s.posZ, m.x, m.z);
            int octant = ReflexMath.nearestOctant(awayYaw);
            if (octant >= 0 && octant < s.octantSafe.length && !s.octantSafe[octant]) {
                return true; // the shove direction is a ledge/lava — a hit launches us off it
            }
        }
        return false;
    }

    private static boolean hasClearColumn(List<BlockPosSpec> candidates, List<MobInfo> mobs) {
        for (BlockPosSpec c : candidates) {
            double nearest = Double.MAX_VALUE;
            for (MobInfo m : mobs) {
                nearest = Math.min(nearest, Math.hypot((c.x + 0.5D) - m.x, (c.z + 0.5D) - m.z));
            }
            if (nearest >= EscapeColumns.MOB_BLOCK_RADIUS) {
                return true;
            }
        }
        return false;
    }

    /**
     * Drowning escape: the nearest open-air column to surface into that is NOT capped by a solid
     * ceiling or by lava, biased away from a mob waiting at the top, plus whether we're sealed
     * directly overhead (must dig up instead of bobbing). Surfacing into lava or into a waiting hit
     * was the drowning death the bot kept finding.
     */
    private void sampleSurfaceEscape(LocalPlayer player, WorldSnapshot s) {
        BlockPos feet = player.blockPosition();
        // sealed overhead: a solid (non-water, non-air) block directly above the head
        BlockState above = ctx.world().getBlockState(feet.above(2));
        s.surfaceSealed = isSolid(feet.above(2)) && above.getFluidState().isEmpty();
        java.util.List<BlockPosSpec> candidates = new java.util.ArrayList<>();
        for (int radius = 0; radius <= 4; radius++) {
            for (int dir = 0; dir < 8 && (radius > 0 || dir == 0); dir++) {
                double angle = dir * Math.PI / 4D;
                BlockPos col = feet.offset(
                        (int) Math.round(Math.cos(angle) * radius), 0,
                        (int) Math.round(Math.sin(angle) * radius));
                if (surfaceColumnSafe(col)) {
                    candidates.add(new BlockPosSpec(col.getX(), col.getY(), col.getZ()));
                }
            }
            BlockPosSpec best = EscapeColumns.best(candidates, s.mobs);
            if (best != null && hasClearColumn(candidates, s.mobs)) {
                s.surfaceEscape = best;
                return;
            }
        }
        s.surfaceEscape = EscapeColumns.best(candidates, s.mobs);
    }

    /** A column we can surface into: open to air above the head and never capped by lava. */
    private boolean surfaceColumnSafe(BlockPos col) {
        // scan up from head height to the first non-water block; it must be air (open sky/cave), not
        // solid (sealed) and never lava (surfacing into fire is death)
        BlockPos p = col.above();
        for (int i = 0; i < 12; i++) {
            if (isLavaAt(p)) {
                return false;
            }
            BlockState st = ctx.world().getBlockState(p);
            if (st.getFluidState().is(FluidTags.WATER)) {
                p = p.above();
                continue;
            }
            return st.isAir(); // first non-water block: open air = good, solid = sealed
        }
        return false;
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
        if (MistralAgent.ACTIVE.get() != null || SurvivalAgentCoordinator.isRunning()) {
            return true;
        }
        if (baritone.getPathingBehavior().isPathing()) {
            return true;
        }
        return baritone.getPathingControlManager().mostRecentInControl()
                .map(p -> p != this)
                .orElse(false);
    }

    // ---------------------------------------------------------------- survival escalation

    /**
     * The cooperative-survival-agent escalation policy, applied every tick off the pure
     * {@link ReflexEngine#inDistress()} read. When distress holds continuously for a debounce window
     * and the {@link baritone.ai.reflex.SurvivalEscalation} policy is satisfied (enabled, provider set,
     * no survival agent already running, cooldown elapsed) we spin one up — pausing+requeuing any
     * running mission. When the danger has resolved (distress clear + no hostile near for K ticks) and
     * a survival agent is still grinding, we stand it down so the original mission resumes.
     *
     * <p>The decision is the pure policy; only the start/stop is done here, and the survival agent has
     * NO path to override the reflex (it goes through the same tool/pathing layer — the reflex stays
     * priority-10 and always wins tick-level control). That invariant lives in
     * {@link baritone.ai.SurvivalAgentCoordinator}.
     */
    private void handleDistressEscalation(long now, SituationAssessment sit) {
        boolean distress = engine.inDistress();
        DISTRESS = distress;

        // debounce: count consecutive distress ticks; emit the {phase:"distress"} event once per onset
        if (distress) {
            distressSustainedTicks++;
            if (!distressEpisodeOpen) {
                distressEpisodeOpen = true;
                Map<String, Object> data = new HashMap<>();
                data.put("phase", "distress");
                if (sit != null) {
                    data.put("situation", sit.describe());
                }
                AgentTelemetry.emit("reflex", data);
                ReflexLog.record("[reflex] DISTRESS — rules exhausted, still endangered");
            }
        } else {
            distressSustainedTicks = 0;
            distressEpisodeOpen = false;
        }

        Settings s = Baritone.settings();
        boolean enabled = s.aiSurvivalEscalation.value;
        int sustainRequired = Math.max(1, s.aiSurvivalDistressTicks.value);
        int cooldownTicks = Math.max(0, s.aiSurvivalEscalationCooldownTicks.value);
        boolean cooldownOver = lastEscalationTick == Long.MIN_VALUE
                || now - lastEscalationTick >= cooldownTicks;
        boolean running = SurvivalAgentCoordinator.isRunning();

        if (SurvivalEscalation.shouldEscalate(distress, distressSustainedTicks,
                sustainRequired, running, SurvivalAgentCoordinator.providerConfigured(),
                enabled, cooldownOver)) {
            lastEscalationTick = now;
            survivalClearTicks = 0;
            // Off the game thread: launching the agent must never block the tick.
            final baritone.api.IBaritone b = baritone;
            new Thread(() -> SurvivalAgentCoordinator.escalate(b),
                    "baritone-survival-escalate").start();
            return;
        }

        // Resolve: while a survival agent runs, count calm ticks; once the danger has been gone long
        // enough, stand the agent down so the original mission resumes (its own done is the other path).
        if (running) {
            boolean hostilesNear = sit != null && sit.hostilesNear > 0;
            if (!distress && !hostilesNear) {
                survivalClearTicks++;
            } else {
                survivalClearTicks = 0;
            }
            int requiredClear = Math.max(1, s.aiSurvivalDistressTicks.value);
            if (SurvivalEscalation.isResolved(distress, hostilesNear,
                    survivalClearTicks, requiredClear)) {
                survivalClearTicks = 0;
                SurvivalAgentCoordinator.resolve();
            }
        } else {
            survivalClearTicks = 0;
        }
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
