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

import java.util.List;

/**
 * Auto-eat: select the best safe food, look skyward (so {@code startUseItem} can never interact
 * with a block — it eats instead), and drive the real use key so vanilla actually consumes the
 * food. The executor saves/restores the previous hotbar slot and releases the key when done.
 */
public final class EatBehavior implements ReflexBehavior {

    @Override
    public BehaviorId id() {
        return BehaviorId.EAT;
    }

    @Override
    public void enter(WorldSnapshot s, ResponsePlan plan) {
    }

    @Override
    public List<ReflexAction> tick(WorldSnapshot s, ReflexTuning t, ResponsePlan plan) {
        if (s.bestFoodSlot < 0) {
            return List.of();
        }
        return List.of(
                ReflexAction.selectSlot(s.bestFoodSlot),
                ReflexAction.look(s.yaw, -75F),
                ReflexAction.useItem()
        );
    }

    @Override
    public void exit() {
    }
}
