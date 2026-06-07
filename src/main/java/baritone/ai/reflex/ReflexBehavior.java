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

package baritone.ai.reflex;

import java.util.List;

/**
 * One survival behavior as a small state machine. Behaviors only emit {@link ReflexAction}s —
 * engagement, release and escalation all live in the arbiter, so a behavior never decides its
 * own lifetime (the old design's sticky-mode bugs came from mixing the two).
 */
public interface ReflexBehavior {

    BehaviorId id();

    /** Called once when the arbiter engages this behavior. Reset internal FSM state here. */
    void enter(WorldSnapshot s, ResponsePlan plan);

    /** Called every tick while engaged. Return the actions to execute this tick. */
    List<ReflexAction> tick(WorldSnapshot s, ReflexTuning t, ResponsePlan plan);

    /** Called once when the arbiter releases this behavior. */
    void exit();
}
