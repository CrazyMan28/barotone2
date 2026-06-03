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
import baritone.ai.GoalTracker;
import baritone.ai.MistralAgent;
import baritone.ai.ReflexLog;
import baritone.ai.ReflexPlanner;
import baritone.ai.ReflexPlanner.Conditions;
import baritone.ai.ReflexPlanner.Reflex;
import baritone.api.Settings;
import baritone.api.pathing.goals.GoalRunAway;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.VecUtils;
import baritone.api.utils.input.Input;
import baritone.utils.BaritoneProcessHelper;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.Locale;

/**
 * Survival reflexes: a deterministic, every-tick guardian that keeps the bot alive without waiting
 * on the AI model (whose round-trip can be minutes on a local model). Implemented as a temporary,
 * high-priority Baritone process so any interrupted mine/follow/goto process resumes automatically
 * when the danger has passed.
 *
 * Priority ladder (see {@link ReflexPlanner}): escape lava > anti-drown > flee creepers >
 * fight back > eat. Flee/fight only engage while the bot is actually working (pathing or on an AI
 * mission) so manual play is never hijacked; lava/drown/eat are pure lifesavers and always armed.
 */
public final class ReflexProcess extends BaritoneProcessHelper {

    private static final int EAT_RELEASE_FOOD_LEVEL = 18;
    private static final int FIGHT_DISENGAGE_TICKS = 100;
    private static final int EAT_TIMEOUT_TICKS = 400;

    private Reflex mode = Reflex.NONE;
    private int modeTicks;
    private int prevHotbarSlot = -1;
    private long lastHurtAt = Long.MIN_VALUE;
    private long lastWorkingAt = Long.MIN_VALUE;
    private LivingEntity fightTarget;
    private boolean loggedNoFood;

    public ReflexProcess(Baritone baritone) {
        super(baritone);
    }

    @Override
    public boolean isActive() {
        LocalPlayer player = ctx.player();
        if (player == null || ctx.world() == null || !Baritone.settings().reflexesEnabled.value) {
            if (mode != Reflex.NONE) {
                endReflex("reflexes disabled");
            }
            return false;
        }
        long now = ctx.world().getGameTime();
        if (player.hurtTime > 0) {
            lastHurtAt = now;
        }
        if (isWorking()) {
            lastWorkingAt = now;
        }

        Conditions c = sample(player, now);
        Reflex next = ReflexPlanner.pick(mode, c);
        if (next != mode) {
            transition(next);
        }
        return mode != Reflex.NONE;
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        LocalPlayer player = ctx.player();
        if (player == null || mode == Reflex.NONE) {
            return new PathingCommand(null, PathingCommandType.DEFER);
        }
        modeTicks++;
        switch (mode) {
            case LAVA:
                return tickLava(player);
            case DROWN:
                baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, true);
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            case FLEE:
                return tickFlee(player);
            case FIGHT:
                return tickFight(player);
            case EAT:
                return tickEat(player);
            default:
                return new PathingCommand(null, PathingCommandType.DEFER);
        }
    }

    // ---------------------------------------------------------------- conditions

    private Conditions sample(LocalPlayer player, long now) {
        Settings s = Baritone.settings();
        Conditions c = new Conditions();
        c.working = lastWorkingAt != Long.MIN_VALUE && now - lastWorkingAt <= 40;

        c.inLava = s.reflexAntiLava.value && player.isInLava();

        int air = player.getAirSupply();
        c.drowning = s.reflexAntiDrown.value && player.isUnderWater() && air < 90;
        c.drownDone = !player.isUnderWater() || air >= 250;

        double engageRadius = Math.max(2D, s.reflexCreeperRadius.value);
        boolean creeperWithin = s.reflexFleeCreepers.value && !nearbyCreepers(player, engageRadius).isEmpty();
        c.creeperNear = creeperWithin;
        c.fleeDone = nearbyCreepers(player, engageRadius + 4D).isEmpty();

        boolean recentlyHurt = lastHurtAt != Long.MIN_VALUE && now - lastHurtAt <= FIGHT_DISENGAGE_TICKS;
        LivingEntity threat = s.reflexFightBack.value && recentlyHurt ? nearestHostile(player, 4.5D) : null;
        c.hostileThreat = threat != null;
        if (threat != null) {
            fightTarget = threat;
        }
        c.fightDone = !recentlyHurt
                || fightTarget == null
                || !fightTarget.isAlive()
                || fightTarget.distanceTo(player) > 6.5D;

        boolean screenOpen = ctx.minecraft().screen != null;
        int foodSlot = findSafeFoodSlot(player);
        c.hungry = s.reflexAutoEat.value
                && player.getFoodData().getFoodLevel() <= s.reflexEatAtHunger.value
                && !screenOpen
                && foodSlot >= 0;
        c.eatDone = player.getFoodData().getFoodLevel() >= EAT_RELEASE_FOOD_LEVEL
                || screenOpen
                || foodSlot < 0
                || modeTicks > EAT_TIMEOUT_TICKS;
        return c;
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

    private List<Creeper> nearbyCreepers(LocalPlayer player, double radius) {
        return ctx.world().getEntitiesOfClass(Creeper.class,
                new AABB(player.blockPosition()).inflate(radius),
                e -> e.isAlive() && player.distanceTo(e) <= radius);
    }

    private LivingEntity nearestHostile(LocalPlayer player, double radius) {
        List<Monster> monsters = ctx.world().getEntitiesOfClass(Monster.class,
                new AABB(player.blockPosition()).inflate(radius),
                e -> e.isAlive() && !(e instanceof Creeper) && player.distanceTo(e) <= radius);
        Monster best = null;
        double bestDist = Double.MAX_VALUE;
        for (Monster m : monsters) {
            double d = player.distanceTo(m);
            if (d < bestDist) {
                bestDist = d;
                best = m;
            }
        }
        return best;
    }

    private int findSafeFoodSlot(LocalPlayer player) {
        int best = -1;
        int bestNutrition = -1;
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            FoodProperties food = stack.get(DataComponents.FOOD);
            if (food == null || !ReflexPlanner.isSafeFood(stack.getItem().toString())) {
                continue;
            }
            if (food.nutrition() > bestNutrition) {
                bestNutrition = food.nutrition();
                best = slot;
            }
        }
        return best;
    }

    // ---------------------------------------------------------------- behaviors

    private PathingCommand tickLava(LocalPlayer player) {
        // Float up, and push toward the nearest non-lava column if one is in reach.
        baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, true);
        BlockPos escape = findLavaEscape(player);
        if (escape != null) {
            Rotation rot = RotationUtils.calcRotationFromVec3d(ctx.playerHead(),
                    VecUtils.getBlockPosCenter(escape), ctx.playerRotations());
            baritone.getLookBehavior().updateTarget(new Rotation(rot.getYaw(), 0F), true);
            baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);
        }
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    private BlockPos findLavaEscape(LocalPlayer player) {
        BlockPos feet = player.blockPosition();
        for (int radius = 2; radius <= 6; radius += 2) {
            for (int dir = 0; dir < 8; dir++) {
                double angle = dir * Math.PI / 4D;
                BlockPos candidate = feet.offset(
                        (int) Math.round(Math.cos(angle) * radius), 0,
                        (int) Math.round(Math.sin(angle) * radius));
                if (!isLavaAt(candidate) && !isLavaAt(candidate.above()) && !isLavaAt(candidate.below())) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private boolean isLavaAt(BlockPos pos) {
        return ctx.world().getBlockState(pos).getFluidState().is(FluidTags.LAVA);
    }

    private PathingCommand tickFlee(LocalPlayer player) {
        List<Creeper> creepers = nearbyCreepers(player, Math.max(2D, Baritone.settings().reflexCreeperRadius.value) + 4D);
        if (creepers.isEmpty()) {
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        BlockPos[] from = creepers.stream().map(LivingEntity::blockPosition).toArray(BlockPos[]::new);
        return new PathingCommand(new GoalRunAway(16, from), PathingCommandType.FORCE_REVALIDATE_GOAL_AND_PATH);
    }

    private PathingCommand tickFight(LocalPlayer player) {
        baritone.getInputOverrideHandler().clearAllKeys();
        LivingEntity target = fightTarget;
        if (target == null || !target.isAlive()) {
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        Rotation rot = RotationUtils.calcRotationFromVec3d(ctx.playerHead(),
                target.position().add(0, target.getBbHeight() * 0.6D, 0), ctx.playerRotations());
        baritone.getLookBehavior().updateTarget(rot, true);
        if (player.distanceTo(target) <= 3.5D && player.getAttackStrengthScale(0F) >= 0.95F) {
            ctx.minecraft().gameMode.attack(player, target);
            player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        }
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    private PathingCommand tickEat(LocalPlayer player) {
        int foodSlot = findSafeFoodSlot(player);
        if (foodSlot < 0) {
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        if (prevHotbarSlot < 0) {
            prevHotbarSlot = player.getInventory().getSelectedSlot();
        }
        if (player.getInventory().getSelectedSlot() != foodSlot) {
            player.getInventory().setSelectedSlot(foodSlot);
        }
        // Look up at the sky so the use-click can never open a chest/door in front of us.
        baritone.getLookBehavior().updateTarget(
                new Rotation(ctx.playerRotations().getYaw(), -75F), true);
        baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    // ---------------------------------------------------------------- lifecycle

    private void transition(Reflex next) {
        if (mode != Reflex.NONE) {
            cleanupMode();
            String note = "[reflex] " + describe(mode) + " ended after " + (modeTicks / 20) + "s";
            ReflexLog.record(note);
        }
        mode = next;
        modeTicks = 0;
        loggedNoFood = false;
        if (next != Reflex.NONE) {
            String note = "[reflex] " + describe(next);
            ReflexLog.record(note);
            logDirect(note, ChatFormatting.GOLD);
            GoalTracker.setStatus(note);
        }
    }

    private void endReflex(String reason) {
        cleanupMode();
        ReflexLog.record("[reflex] " + describe(mode) + " stopped (" + reason + ")");
        mode = Reflex.NONE;
        modeTicks = 0;
    }

    private void cleanupMode() {
        baritone.getInputOverrideHandler().clearAllKeys();
        if (prevHotbarSlot >= 0 && ctx.player() != null) {
            ctx.player().getInventory().setSelectedSlot(prevHotbarSlot);
        }
        prevHotbarSlot = -1;
        fightTarget = null;
    }

    private static String describe(Reflex reflex) {
        switch (reflex) {
            case LAVA:
                return "escaping lava";
            case DROWN:
                return "surfacing for air";
            case FLEE:
                return "fleeing creeper";
            case FIGHT:
                return "fighting back";
            case EAT:
                return "eating";
            default:
                return "idle";
        }
    }

    @Override
    public void onLostControl() {
        if (mode != Reflex.NONE) {
            endReflex("lost control");
        }
    }

    @Override
    public String displayName0() {
        return "reflexes (" + describe(mode).toLowerCase(Locale.ROOT) + ")";
    }

    @Override
    public double priority() {
        return 10; // above every normal process and the inventory pauser (5.1)
    }

    @Override
    public boolean isTemporary() {
        return true;
    }
}
