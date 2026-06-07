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

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The shared per-behavior progress watchdog: a behavior that neither moves the bot nor improves
 * its metric (air, target hp, own hp...) over a full window is "stuck" and the arbiter escalates.
 */
public class BehaviorWatchdogTest {

    /** window=40 ticks, must move >=2 blocks OR gain >=1 metric to count as progress. */
    private static BehaviorWatchdog watchdog() {
        return new BehaviorWatchdog(40, 2.0, 1.0);
    }

    @Test
    public void neverStuckWhileMoving() {
        BehaviorWatchdog w = watchdog();
        for (long t = 0; t < 400; t++) {
            assertFalse("moving 1 block/tick is progress", w.stuck(t, t * 1.0, 0, 0));
        }
    }

    @Test
    public void stuckWhenStationaryAndNoMetricGain() {
        BehaviorWatchdog w = watchdog();
        boolean stuck = false;
        for (long t = 0; t <= 80 && !stuck; t++) {
            stuck = w.stuck(t, 5.0, 5.0, 0);
        }
        assertTrue("stationary with flat metric must trip within two windows", stuck);
    }

    @Test
    public void notStuckBeforeAFullWindowElapses() {
        BehaviorWatchdog w = watchdog();
        for (long t = 0; t < 40; t++) {
            assertFalse("a window must fully elapse before stuck can fire", w.stuck(t, 5.0, 5.0, 0));
        }
    }

    @Test
    public void metricProgressPreventsStuck() {
        BehaviorWatchdog w = watchdog();
        for (long t = 0; t < 400; t++) {
            // stationary but the metric climbs (e.g. air refilling while surfaced in a 1x1 pool)
            assertFalse("metric gain is progress even when stationary", w.stuck(t, 5.0, 5.0, t * 0.5));
        }
    }

    @Test
    public void resetStartsAFreshWindow() {
        BehaviorWatchdog w = watchdog();
        for (long t = 0; t <= 80; t++) {
            w.stuck(t, 5.0, 5.0, 0);
        }
        w.reset();
        for (long t = 81; t < 120; t++) {
            assertFalse("post-reset, a full fresh window must elapse first", w.stuck(t, 5.0, 5.0, 0));
        }
    }

    @Test
    public void stoppingAfterProgressTripsWithinTwoWindows() {
        BehaviorWatchdog w = watchdog();
        for (long t = 0; t < 100; t++) {
            assertFalse(w.stuck(t, t * 1.0, 0, 0)); // running
        }
        boolean stuck = false;
        for (long t = 100; t <= 180 && !stuck; t++) {
            stuck = w.stuck(t, 100.0, 0, 0);        // pinned against a wall
        }
        assertTrue("going still after progress must trip within two windows", stuck);
    }
}
