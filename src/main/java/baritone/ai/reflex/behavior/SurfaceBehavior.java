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
import baritone.ai.reflex.ReflexMath;
import baritone.ai.reflex.ReflexTuning;
import baritone.ai.reflex.ResponsePlan;
import baritone.ai.reflex.WorldSnapshot;
import baritone.api.utils.input.Input;

import java.util.ArrayList;
import java.util.List;

/**
 * Drowning: get to air the SAFE way, not just "hold JUMP and hope".
 * <ul>
 *   <li>A safe open column is known ({@link WorldSnapshot#surfaceEscape}) — swim toward it and bob
 *       up. The adapter only ever picks a column open to air and never capped by lava, and biases it
 *       away from a mob waiting at the surface, so we never breach straight into a hit or into fire.</li>
 *   <li>Sealed overhead ({@link WorldSnapshot#surfaceSealed}) with no escape — mine up while
 *       climbing, since bobbing into a solid ceiling just keeps drowning.</li>
 *   <li>Otherwise — hold JUMP to surface, swimming out from under an overhang if pinned.</li>
 * </ul>
 */
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
        List<ReflexAction> actions = new ArrayList<>(3);
        if (s.surfaceEscape != null) {
            // swim toward the known-safe open column and rise into it
            float yaw = ReflexMath.yawToward(s.posX, s.posZ,
                    s.surfaceEscape.x + 0.5D, s.surfaceEscape.z + 0.5D);
            actions.add(ReflexAction.look(yaw, 0F));
            actions.add(ReflexAction.hold(Input.JUMP, true));
            actions.add(ReflexAction.hold(Input.MOVE_FORWARD, true));
            return actions;
        }
        if (s.surfaceSealed) {
            // capped by solid block — mine up the shaft and climb it; bobbing here just drowns
            actions.add(ReflexAction.snapLook(s.yaw, -90F));
            actions.add(ReflexAction.hold(Input.CLICK_LEFT, true));
            actions.add(ReflexAction.hold(Input.JUMP, true));
            return actions;
        }
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
