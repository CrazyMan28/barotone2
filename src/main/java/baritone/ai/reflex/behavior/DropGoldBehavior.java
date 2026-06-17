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

package baritone.ai.reflex.behavior;

import baritone.ai.reflex.BehaviorId;
import baritone.ai.reflex.GoalSpec;
import baritone.ai.reflex.MobInfo;
import baritone.ai.reflex.ReflexAction;
import baritone.ai.reflex.ReflexBehavior;
import baritone.ai.reflex.ReflexMath;
import baritone.ai.reflex.ReflexTuning;
import baritone.ai.reflex.ResponsePlan;
import baritone.ai.reflex.WorldSnapshot;
import baritone.api.utils.input.Input;

import java.util.ArrayList;
import java.util.List;

/**
 * Piglin gold de-aggro: holding/wearing gold aggros the whole piglin pack (a vanilla trigger). The
 * root fix is to remove the trigger, not to out-fight the swarm gold summons. This behavior:
 * <ol>
 *   <li>drops the HELD gold stack (select the slot, then {@link ReflexAction#dropSlot}) — that alone
 *       reverts a piglin to neutral once it's no longer the gold-holding target;</li>
 *   <li>backs off from the nearest piglin the whole time, so even before/after the drop lands the bot
 *       is opening distance rather than standing in the pack.</li>
 * </ol>
 *
 * <p>Worn gold ARMOR cannot be un-equipped by a reflex (that needs the inventory GUI — the LLM); when
 * only armor triggers it, the behavior degrades to a pure back-off, which is the best a tick-level
 * reflex can do. The {@link ReflexAction.Kind#DROP_SLOT} executor wiring (vanilla {@code player.drop})
 * is the one part the survival sim cannot exercise — it is unit-tested at the action-emission level and
 * needs live verification in-game.
 */
public final class DropGoldBehavior implements ReflexBehavior {

    @Override
    public BehaviorId id() {
        return BehaviorId.DROP_GOLD;
    }

    @Override
    public void enter(WorldSnapshot s, ResponsePlan plan) {
    }

    @Override
    public List<ReflexAction> tick(WorldSnapshot s, ReflexTuning t, ResponsePlan plan) {
        List<ReflexAction> actions = new ArrayList<>(4);
        // 1. jettison the held gold (the aggro trigger). Select it first so the drop hits the right
        // stack; the executor restores the previous slot when the episode ends.
        if (s.goldSlot >= 0) {
            actions.add(ReflexAction.selectSlot(s.goldSlot));
            actions.add(ReflexAction.dropSlot(s.goldSlot));
        }
        // 2. back off from the piglins regardless — opening distance while (and after) the gold leaves
        // our hand. Sprint along a SAFE direction away from the nearest piglin (never into lava/a ledge).
        MobInfo piglin = nearestPiglin(s, t);
        if (piglin != null) {
            float awayYaw = ReflexMath.yawAway(s.posX, s.posZ, piglin.x, piglin.z);
            if (!Moves.boxedIn(s, awayYaw)) {
                actions.add(ReflexAction.look(Moves.safeFleeYaw(s, awayYaw), 5F));
                actions.add(ReflexAction.hold(Input.MOVE_FORWARD, true));
                actions.add(ReflexAction.hold(Input.SPRINT, true));
            } else {
                actions.add(ReflexAction.setGoal(GoalSpec.runAway(t.fleeGoalDistance,
                        ReflexMath.feetBlock(piglin))));
            }
        }
        return actions;
    }

    private static MobInfo nearestPiglin(WorldSnapshot s, ReflexTuning t) {
        MobInfo best = null;
        for (MobInfo m : s.mobs) {
            if (m.piglin && m.distance <= t.perceptionRadius
                    && (best == null || m.distance < best.distance)) {
                best = m;
            }
        }
        return best;
    }

    @Override
    public void exit() {
    }
}
