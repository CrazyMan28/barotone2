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

/**
 * The water-bucket MLG, minimal edition: while falling, equip the bucket and aim straight down;
 * once the ground is within placement reach, hold use so the water lands under us. Degrades to a
 * no-op without a bucket (the detector doesn't even engage then). Picking the water back up is
 * a nice-to-have for later.
 */
public final class AntiFallBehavior implements ReflexBehavior {

    /** Use the bucket once the floor is within this many air blocks (vanilla reach ~4.5). */
    private static final int DEPLOY_GAP = 4;

    @Override
    public BehaviorId id() {
        return BehaviorId.ANTI_FALL;
    }

    @Override
    public void enter(WorldSnapshot s, ResponsePlan plan) {
    }

    @Override
    public List<ReflexAction> tick(WorldSnapshot s, ReflexTuning t, ResponsePlan plan) {
        if (s.waterBucketSlot < 0) {
            return List.of(); // nothing the reflex can do (void drop with no bucket)
        }
        List<ReflexAction> actions = new ArrayList<>(3);
        actions.add(ReflexAction.selectSlot(s.waterBucketSlot));
        actions.add(ReflexAction.snapLook(s.yaw, 90F));
        if (s.gapBelow <= DEPLOY_GAP) {
            actions.add(ReflexAction.hold(Input.CLICK_RIGHT, true));
        }
        return actions;
    }

    @Override
    public void exit() {
    }
}
