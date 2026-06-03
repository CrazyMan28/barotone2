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

package baritone.ai;

import baritone.ai.ReflexPlanner.Conditions;
import baritone.ai.ReflexPlanner.Reflex;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ReflexPlannerTest {

    @Test
    public void idleWhenNothingWrong() {
        assertEquals(Reflex.NONE, ReflexPlanner.pick(Reflex.NONE, new Conditions()));
    }

    @Test
    public void lavaBeatsEverythingEvenMidReflex() {
        Conditions c = new Conditions();
        c.inLava = true;
        c.drowning = true;
        c.creeperNear = true;
        c.working = true;
        assertEquals(Reflex.LAVA, ReflexPlanner.pick(Reflex.NONE, c));
        assertEquals(Reflex.LAVA, ReflexPlanner.pick(Reflex.EAT, c));
        assertEquals(Reflex.LAVA, ReflexPlanner.pick(Reflex.FIGHT, c));
    }

    @Test
    public void priorityOrderOnFreshEngagement() {
        Conditions c = new Conditions();
        c.working = true;
        c.drowning = true;
        c.creeperNear = true;
        c.hostileThreat = true;
        c.hungry = true;
        assertEquals(Reflex.DROWN, ReflexPlanner.pick(Reflex.NONE, c));
        c.drowning = false;
        assertEquals(Reflex.FLEE, ReflexPlanner.pick(Reflex.NONE, c));
        c.creeperNear = false;
        assertEquals(Reflex.FIGHT, ReflexPlanner.pick(Reflex.NONE, c));
        c.hostileThreat = false;
        assertEquals(Reflex.EAT, ReflexPlanner.pick(Reflex.NONE, c));
    }

    @Test
    public void fleeAndFightRequireWorkingButEatDoesNot() {
        Conditions c = new Conditions();
        c.working = false;
        c.creeperNear = true;
        c.hostileThreat = true;
        c.hungry = true;
        // manual play: never hijack movement for combat, but still keep the player fed
        assertEquals(Reflex.EAT, ReflexPlanner.pick(Reflex.NONE, c));
    }

    @Test
    public void runningReflexIsStickyUntilDone() {
        Conditions c = new Conditions();
        c.fleeDone = false;
        assertEquals(Reflex.FLEE, ReflexPlanner.pick(Reflex.FLEE, c));
        c.fleeDone = true;
        assertEquals(Reflex.NONE, ReflexPlanner.pick(Reflex.FLEE, c));
    }

    @Test
    public void fleeStaysEngagedEvenIfMissionEndsMidFlee() {
        Conditions c = new Conditions();
        c.working = false; // mission ended while running away
        c.fleeDone = false;
        assertEquals(Reflex.FLEE, ReflexPlanner.pick(Reflex.FLEE, c));
    }

    @Test
    public void fightEscalatesToFleeWhenCreeperArrives() {
        Conditions c = new Conditions();
        c.fightDone = false;
        c.creeperNear = true;
        assertEquals(Reflex.FLEE, ReflexPlanner.pick(Reflex.FIGHT, c));
    }

    @Test
    public void eatingIsInterruptedByDanger() {
        Conditions c = new Conditions();
        c.eatDone = false;
        c.working = true;
        c.hostileThreat = true;
        assertEquals(Reflex.FIGHT, ReflexPlanner.pick(Reflex.EAT, c));
        c.hostileThreat = false;
        c.drowning = true;
        assertEquals(Reflex.DROWN, ReflexPlanner.pick(Reflex.EAT, c));
    }

    @Test
    public void riskyFoodsAreRejected() {
        assertFalse(ReflexPlanner.isSafeFood("minecraft:rotten_flesh"));
        assertFalse(ReflexPlanner.isSafeFood("minecraft:pufferfish"));
        assertFalse(ReflexPlanner.isSafeFood("minecraft:enchanted_golden_apple"));
        assertFalse(ReflexPlanner.isSafeFood("chorus_fruit"));
        assertTrue(ReflexPlanner.isSafeFood("minecraft:bread"));
        assertTrue(ReflexPlanner.isSafeFood("cooked_beef"));
        assertTrue(ReflexPlanner.isSafeFood("minecraft:golden_apple"));
        assertFalse(ReflexPlanner.isSafeFood(null));
        assertFalse(ReflexPlanner.isSafeFood(""));
    }
}
