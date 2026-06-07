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
import baritone.ai.reflex.ReflexAction;
import baritone.ai.reflex.ReflexBehavior;
import baritone.ai.reflex.ReflexTuning;
import baritone.ai.reflex.ResponsePlan;
import baritone.ai.reflex.WorldSnapshot;

import java.util.List;

/**
 * On fire: walk into water if any is in reach (the adapter precomputes
 * {@link WorldSnapshot#nearestWater}), else run off the burning ground (fire blocks, magma,
 * campfires keep re-igniting whoever stands still on them).
 */
public final class ExtinguishFireBehavior implements ReflexBehavior {

    @Override
    public BehaviorId id() {
        return BehaviorId.EXTINGUISH_FIRE;
    }

    @Override
    public void enter(WorldSnapshot s, ResponsePlan plan) {
    }

    @Override
    public List<ReflexAction> tick(WorldSnapshot s, ReflexTuning t, ResponsePlan plan) {
        if (s.nearestWater != null) {
            return List.of(
                    ReflexAction.releaseAll(),
                    ReflexAction.setGoal(GoalSpec.near(s.nearestWater, 1))
            );
        }
        BlockPosSpec feet = new BlockPosSpec(
                (int) Math.floor(s.posX), (int) Math.floor(s.posY), (int) Math.floor(s.posZ));
        return List.of(
                ReflexAction.releaseAll(),
                ReflexAction.setGoal(GoalSpec.runAway(8, feet))
        );
    }

    @Override
    public void exit() {
    }
}
