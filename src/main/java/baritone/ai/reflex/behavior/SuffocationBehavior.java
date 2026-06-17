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

import java.util.List;

/**
 * Suffocating: look straight up and mine out before it kills us.
 * <ul>
 *   <li>Buried by falling sand/gravel — mine up, but do NOT move: wiggling just collects more of the
 *       gravity blocks still falling above.</li>
 *   <li>Encased in solid (cave-in, piston, a closing gap, a bad spawn/teleport) — mine up AND jump, to
 *       climb the shaft we're carving and break back out to air.</li>
 * </ul>
 */
public final class SuffocationBehavior implements ReflexBehavior {

    @Override
    public BehaviorId id() {
        return BehaviorId.DIG_OUT;
    }

    @Override
    public void enter(WorldSnapshot s, ResponsePlan plan) {
    }

    @Override
    public List<ReflexAction> tick(WorldSnapshot s, ReflexTuning t, ResponsePlan plan) {
        // encased in solid (not merely buried by gravity) -> climb the shaft we're mining
        boolean encased = s.headInSolid && !s.headBlockedByGravity;
        if (encased) {
            return List.of(
                    ReflexAction.snapLook(s.yaw, -90F),
                    ReflexAction.hold(Input.CLICK_LEFT, true),
                    ReflexAction.hold(Input.JUMP, true)
            );
        }
        return List.of(
                ReflexAction.snapLook(s.yaw, -90F),
                ReflexAction.hold(Input.CLICK_LEFT, true)
        );
    }

    @Override
    public void exit() {
    }
}
