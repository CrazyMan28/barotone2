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
import baritone.ai.reflex.BlockPosSpec;
import baritone.ai.reflex.GoalSpec;
import baritone.ai.reflex.ReflexAction;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.pathing.goals.GoalRunAway;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.Rotation;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import java.util.List;

/**
 * Turns the pure {@link ReflexAction}s the engine emits into real inputs: key overrides, look
 * updates, hotbar switches, attacks, pathing goals. The one place (besides the snapshot builder
 * in {@link ReflexProcess}) where the reflex redesign touches Minecraft.
 */
final class ReflexExecutor {

    private final Baritone baritone;
    private final IPlayerContext ctx;
    /** Hotbar slot to restore when the current reflex episode ends (-1 = nothing to restore). */
    private int prevHotbarSlot = -1;

    ReflexExecutor(Baritone baritone, IPlayerContext ctx) {
        this.baritone = baritone;
        this.ctx = ctx;
    }

    /**
     * Execute one tick's actions.
     *
     * @param behaviorActive whether a behavior is engaged (an active behavior with no explicit
     *                       goal pauses pathing, exactly like the old tick methods)
     * @return the PathingCommand for {@code onTick}
     */
    PathingCommand execute(List<ReflexAction> actions, boolean behaviorActive) {
        LocalPlayer player = ctx.player();
        if (player == null) {
            return new PathingCommand(null, PathingCommandType.DEFER);
        }
        Goal goal = null;
        for (ReflexAction a : actions) {
            switch (a.kind) {
                case HOLD_INPUT:
                    baritone.getInputOverrideHandler().setInputForceState(a.input, a.pressed);
                    break;
                case RELEASE_ALL_INPUTS:
                    baritone.getInputOverrideHandler().clearAllKeys();
                    break;
                case LOOK:
                    baritone.getLookBehavior().updateTarget(new Rotation(a.yaw, a.pitch), true);
                    break;
                case SNAP_LOOK:
                    // Set the rotation directly THIS tick. A smoothed turn hasn't landed by the
                    // time we swing — that's how the old bot "attacked while facing the wrong way".
                    player.setYRot(a.yaw);
                    player.setXRot(a.pitch);
                    player.yBodyRot = a.yaw;
                    player.yHeadRot = a.yaw;
                    baritone.getLookBehavior().updateTarget(new Rotation(a.yaw, a.pitch), true);
                    break;
                case SELECT_SLOT:
                    if (prevHotbarSlot < 0) {
                        prevHotbarSlot = player.getInventory().getSelectedSlot();
                    }
                    if (player.getInventory().getSelectedSlot() != a.slot) {
                        player.getInventory().setSelectedSlot(a.slot);
                    }
                    break;
                case ATTACK: {
                    Entity e = ctx.world().getEntity(a.entityId);
                    if (e instanceof LivingEntity && e.isAlive()) {
                        ctx.minecraft().gameMode.attack(player, e);
                        player.swing(InteractionHand.MAIN_HAND);
                    }
                    break;
                }
                case PLACE_BLOCK:
                    // arrives with the flee-resolution step (pillar/wall)
                    break;
                case SET_GOAL:
                    goal = toGoal(a.goal);
                    break;
                default:
                    break;
            }
        }
        if (goal != null) {
            return new PathingCommand(goal, PathingCommandType.FORCE_REVALIDATE_GOAL_AND_PATH);
        }
        return new PathingCommand(null,
                behaviorActive ? PathingCommandType.REQUEST_PAUSE : PathingCommandType.DEFER);
    }

    /** End-of-episode cleanup: release every forced key and restore the hotbar slot. */
    void cleanup() {
        baritone.getInputOverrideHandler().clearAllKeys();
        if (prevHotbarSlot >= 0 && ctx.player() != null) {
            ctx.player().getInventory().setSelectedSlot(prevHotbarSlot);
        }
        prevHotbarSlot = -1;
    }

    private static Goal toGoal(GoalSpec spec) {
        switch (spec.kind) {
            case RUN_AWAY: {
                BlockPos[] from = new BlockPos[spec.from.length];
                for (int i = 0; i < from.length; i++) {
                    from[i] = toPos(spec.from[i]);
                }
                return new GoalRunAway(spec.distance, from);
            }
            case NEAR:
                return new GoalNear(toPos(spec.target), spec.distance);
            case XZ:
                return new GoalXZ(spec.target.x, spec.target.z);
            default:
                return null;
        }
    }

    private static BlockPos toPos(BlockPosSpec spec) {
        return new BlockPos(spec.x, spec.y, spec.z);
    }
}
