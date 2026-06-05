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

    // --- FleeWatchdog: the "stuck fleeing creeper forever" breaker ---
    // (maxFleeTicks=200, cooldownTicks=120, episodeGapTicks=100 — the live ReflexProcess values.)

    private static ReflexPlanner.FleeWatchdog watchdog() {
        return new ReflexPlanner.FleeWatchdog(200, 120, 100);
    }

    @Test
    public void watchdogNeverSuppressesWhenNoMob() {
        ReflexPlanner.FleeWatchdog w = watchdog();
        for (long t = 0; t < 1000; t++) {
            assertFalse("no mob -> never suppress", w.suppressed(t, false));
        }
    }

    @Test
    public void watchdogLetsShortFleesRun() {
        ReflexPlanner.FleeWatchdog w = watchdog();
        // A mob present for up to maxFleeTicks must keep fleeing (never suppressed).
        for (long t = 0; t <= 200; t++) {
            assertFalse("within maxFleeTicks -> keep fleeing", w.suppressed(t, true));
        }
    }

    @Test
    public void watchdogCutsOffAStuckFlee() {
        ReflexPlanner.FleeWatchdog w = watchdog();
        for (long t = 0; t <= 200; t++) {
            assertFalse(w.suppressed(t, true));
        }
        // Once the episode exceeds maxFleeTicks the watchdog forces a cooldown so the mission resumes.
        assertTrue("episode > maxFleeTicks -> suppress", w.suppressed(201, true));
    }

    @Test
    public void watchdogCooldownThenReEngages() {
        ReflexPlanner.FleeWatchdog w = watchdog();
        for (long t = 0; t <= 201; t++) {
            w.suppressed(t, true); // trips a cooldown at tick 201 (lasts 120 ticks -> through 320)
        }
        assertTrue("mid-cooldown still suppressed", w.suppressed(250, true));
        assertTrue("last cooldown tick still suppressed", w.suppressed(320, true));
        // Cooldown expired and the mob is still here: a FRESH flee window opens (not stuck off).
        assertFalse("cooldown expired -> flee again", w.suppressed(321, true));
        // ...and that fresh window runs its own full maxFleeTicks before the next cutoff.
        for (long t = 322; t <= 521; t++) {
            assertFalse("fresh window keeps fleeing", w.suppressed(t, true));
        }
        assertTrue("second episode also gets cut off", w.suppressed(522, true));
    }

    @Test
    public void watchdogOscillatingInAndOutStillCountsAsOneEpisode() {
        ReflexPlanner.FleeWatchdog w = watchdog();
        // Bot bobs in/out of engage range (short gaps << episodeGapTicks): the episode must NOT reset,
        // so a creeper that keeps re-appearing still trips the cutoff instead of looping forever.
        boolean suppressedEver = false;
        for (long t = 0; t <= 400; t++) {
            boolean mobNear = (t % 20) < 12; // present 12 ticks, gone 8 (gap of 8 << 100)
            suppressedEver |= w.suppressed(t, mobNear);
        }
        assertTrue("oscillating episode eventually gets cut off", suppressedEver);
    }

    @Test
    public void watchdogLongLullResetsEpisode() {
        ReflexPlanner.FleeWatchdog w = watchdog();
        // A short encounter (never trips), then a long lull (> episodeGapTicks) before the mob returns.
        for (long t = 0; t < 150; t++) {
            assertFalse(w.suppressed(t, true));
        }
        for (long t = 150; t < 450; t++) {
            assertFalse("no mob -> never suppress", w.suppressed(t, false));
        }
        // Because the lull reset the episode, the return encounter gets a full fresh flee window
        // rather than being penalised for the earlier one.
        for (long t = 450; t <= 650; t++) {
            assertFalse("fresh episode gets a full flee window", w.suppressed(t, true));
        }
        assertTrue(w.suppressed(651, true));
    }
}
