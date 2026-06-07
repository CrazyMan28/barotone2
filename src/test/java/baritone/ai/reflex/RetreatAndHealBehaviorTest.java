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

import baritone.ai.reflex.behavior.RetreatAndHealBehavior;
import baritone.api.utils.input.Input;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The lick-your-wounds behavior: break contact, then eat, then wait for regen. */
public class RetreatAndHealBehaviorTest {

    private final ReflexTuning t = new ReflexTuning();
    private final RetreatAndHealBehavior b = new RetreatAndHealBehavior();
    private final ResponsePlan plan = new ResponsePlan(BehaviorId.RETREAT_HEAL,
            new Threat(ThreatType.POISON, 50));

    private static WorldSnapshot hurt() {
        WorldSnapshot s = new WorldSnapshot();
        s.working = true;
        s.hp = 6;
        return s;
    }

    private static ReflexAction find(List<ReflexAction> actions, ReflexAction.Kind kind) {
        return actions.stream().filter(a -> a.kind == kind).findFirst().orElse(null);
    }

    @Test
    public void breaksContactWhileHostilesAreClose() {
        WorldSnapshot s = hurt();
        MobInfo z = new MobInfo();
        z.entityId = 1;
        z.hostile = true;
        z.distance = 6;
        z.x = 6;
        s.mobs.add(z);
        b.enter(s, plan);
        List<ReflexAction> actions = b.tick(s, t, plan);
        ReflexAction goal = find(actions, ReflexAction.Kind.SET_GOAL);
        assertNotNull("must run from the mob before eating", goal);
        assertEquals(GoalSpec.Kind.RUN_AWAY, goal.goal.kind);
        assertNull("no eating while being chased", find(actions, ReflexAction.Kind.SELECT_SLOT));
    }

    @Test
    public void eatsOnceClear() {
        WorldSnapshot s = hurt();
        s.food = 12;
        s.bestFoodSlot = 2;
        b.enter(s, plan);
        List<ReflexAction> actions = b.tick(s, t, plan);
        ReflexAction slot = find(actions, ReflexAction.Kind.SELECT_SLOT);
        assertNotNull("clear of mobs: eat back to full hunger for regen", slot);
        assertEquals(2, slot.slot);
        assertTrue(actions.stream().anyMatch(a ->
                a.kind == ReflexAction.Kind.HOLD_INPUT && a.input == Input.CLICK_RIGHT && a.pressed));
    }

    @Test
    public void waitsForRegenWhenFed() {
        WorldSnapshot s = hurt();
        s.food = 20;
        s.bestFoodSlot = 2;
        b.enter(s, plan);
        List<ReflexAction> actions = b.tick(s, t, plan);
        assertNull("fed and clear: just hold position and regen",
                find(actions, ReflexAction.Kind.SET_GOAL));
        assertNull(find(actions, ReflexAction.Kind.SELECT_SLOT));
    }
}
