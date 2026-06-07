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
import baritone.ai.reflex.BlockPosSpec;
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
 * Lick your wounds: BREAK CONTACT (run until no hostile is close), then EAT back to full
 * hunger so regen kicks in, then WAIT for hp to recover. The arbiter releases us at
 * {@code retreatTargetHp} and the interrupted mission resumes.
 */
public final class RetreatAndHealBehavior implements ReflexBehavior {

    @Override
    public BehaviorId id() {
        return BehaviorId.RETREAT_HEAL;
    }

    @Override
    public void enter(WorldSnapshot s, ResponsePlan plan) {
    }

    @Override
    public List<ReflexAction> tick(WorldSnapshot s, ReflexTuning t, ResponsePlan plan) {
        // BREAK_CONTACT: any hostile in pressure range -> keep running
        List<MobInfo> chasing = new ArrayList<>();
        for (MobInfo m : s.mobs) {
            if (m.distance <= t.retreatSafeDistance && (m.hostile || m.creeper || m.skeleton)) {
                chasing.add(m);
            }
        }
        if (!chasing.isEmpty()) {
            BlockPosSpec[] from = new BlockPosSpec[chasing.size()];
            for (int i = 0; i < chasing.size(); i++) {
                from[i] = ReflexMath.feetBlock(chasing.get(i));
            }
            return List.of(
                    ReflexAction.releaseAll(),
                    ReflexAction.setGoal(GoalSpec.runAway(t.fleeGoalDistance, from))
            );
        }
        // HEAL: top the hunger bar up so natural regen runs
        if (s.food < t.eatReleaseFood && s.bestFoodSlot >= 0) {
            return List.of(
                    ReflexAction.selectSlot(s.bestFoodSlot),
                    ReflexAction.look(s.yaw, -75F),
                    ReflexAction.hold(Input.CLICK_RIGHT, true)
            );
        }
        // WAIT: hold position and regen (pathing stays paused while we're engaged)
        return List.of(ReflexAction.releaseAll());
    }

    @Override
    public void exit() {
    }
}
