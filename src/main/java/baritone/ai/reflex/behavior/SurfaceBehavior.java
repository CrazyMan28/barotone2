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
import baritone.ai.reflex.ReflexAction;
import baritone.ai.reflex.ReflexBehavior;
import baritone.ai.reflex.ReflexTuning;
import baritone.ai.reflex.ResponsePlan;
import baritone.ai.reflex.WorldSnapshot;
import baritone.api.utils.input.Input;

import java.util.ArrayList;
import java.util.List;

/** Drowning: hold JUMP to bob up until air refills. Swim forward if pinned under an overhang. */
public final class SurfaceBehavior implements ReflexBehavior {

    @Override
    public BehaviorId id() {
        return BehaviorId.SURFACE;
    }

    @Override
    public void enter(WorldSnapshot s, ResponsePlan plan) {
    }

    @Override
    public List<ReflexAction> tick(WorldSnapshot s, ReflexTuning t, ResponsePlan plan) {
        List<ReflexAction> actions = new ArrayList<>(2);
        actions.add(ReflexAction.hold(Input.JUMP, true));
        if (s.horizontalCollision) {
            // pinned against a wall under an overhang: swim out from under it
            actions.add(ReflexAction.hold(Input.MOVE_FORWARD, true));
        }
        return actions;
    }

    @Override
    public void exit() {
    }
}
