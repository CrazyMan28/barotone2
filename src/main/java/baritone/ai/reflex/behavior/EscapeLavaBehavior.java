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
 * In lava: float up (hold JUMP) and push toward the chosen non-lava column. The adapter precomputes
 * that column into {@link WorldSnapshot#lavaEscape} mob-aware (the core can't scan blocks). When no
 * clear column exists (every side is lava/blocked, or a mob is parked on the only one), fall back to
 * the first safe octant out of the lava — any direction off the fire beats cooking in place.
 */
public final class EscapeLavaBehavior implements ReflexBehavior {

    @Override
    public BehaviorId id() {
        return BehaviorId.ESCAPE_LAVA;
    }

    @Override
    public void enter(WorldSnapshot s, ResponsePlan plan) {
    }

    @Override
    public List<ReflexAction> tick(WorldSnapshot s, ReflexTuning t, ResponsePlan plan) {
        List<ReflexAction> actions = new ArrayList<>(3);
        actions.add(ReflexAction.hold(Input.JUMP, true));
        if (s.lavaEscape != null) {
            float yaw = ReflexMath.yawToward(s.posX, s.posZ, s.lavaEscape.x + 0.5D, s.lavaEscape.z + 0.5D);
            actions.add(ReflexAction.look(yaw, 0F));
            actions.add(ReflexAction.hold(Input.MOVE_FORWARD, true));
            return actions;
        }
        // no precomputed column: head out along any safe octant rather than holding still in the lava
        int octant = firstSafeOctant(s);
        if (octant >= 0) {
            actions.add(ReflexAction.look(ReflexMath.octantYaw(octant), 0F));
            actions.add(ReflexAction.hold(Input.MOVE_FORWARD, true));
        }
        return actions;
    }

    private static int firstSafeOctant(WorldSnapshot s) {
        for (int i = 0; i < s.octantSafe.length; i++) {
            if (s.octantSafe[i]) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public void exit() {
    }
}
