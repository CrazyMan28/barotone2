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

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tool stuck/timeout clocks must PAUSE while the reflexes own the bot: a shelter waiting out the
 * night (many minutes) must not read as "mining stuck" (30s) or burn the tool's wait budget —
 * otherwise the watchdog cancels the mine and relocates right out of the shelter.
 */
public class WatchdogClockTest {

    @Test
    public void normalTimeBurnsTheDeadline() {
        WatchdogClock c = new WatchdogClock(0, 10_000);
        c.onTick(false, 9_000);
        assertFalse(c.expired(9_000));
        c.onTick(false, 11_000);
        assertTrue(c.expired(11_000));
    }

    @Test
    public void reflexTimeExtendsTheDeadline() {
        WatchdogClock c = new WatchdogClock(0, 10_000);
        // the whole first 8s pass under reflex control (sheltering)
        for (long t = 1_000; t <= 8_000; t += 1_000) {
            c.onTick(true, t);
        }
        c.onTick(false, 11_000); // 3 more normal seconds
        assertFalse("8s of reflex time must not count", c.expired(11_000));
        c.onTick(false, 17_000);
        assertFalse(c.expired(17_000)); // 10s budget: 8s paused -> expires at 18s
        c.onTick(false, 19_000);
        assertTrue(c.expired(19_000));
    }

    @Test
    public void stuckWindowPausesUnderReflexControl() {
        WatchdogClock c = new WatchdogClock(0, Long.MAX_VALUE);
        c.onTick(false, 20_000);
        assertTrue("20s with no progress = stuck (30s window not yet)", !c.stuckFor(20_000, 30_000));
        c.onTick(false, 31_000);
        assertTrue(c.stuckFor(31_000, 30_000));

        WatchdogClock paused = new WatchdogClock(0, Long.MAX_VALUE);
        for (long t = 1_000; t <= 40_000; t += 1_000) {
            paused.onTick(true, t); // the whole time was reflex-owned
        }
        assertFalse("reflex time never counts as stuck", paused.stuckFor(40_000, 30_000));
    }

    @Test
    public void progressResetsTheStuckWindow() {
        WatchdogClock c = new WatchdogClock(0, Long.MAX_VALUE);
        c.onTick(false, 25_000);
        c.progress(25_000);
        c.onTick(false, 50_000);
        assertFalse(c.stuckFor(50_000, 30_000));
        c.onTick(false, 56_000);
        assertTrue(c.stuckFor(56_000, 30_000));
    }

    @Test
    public void alternatingIntervalsSumCorrectly() {
        WatchdogClock c = new WatchdogClock(0, 20_000);
        c.onTick(false, 5_000);  // 5s burned
        c.onTick(true, 15_000);  // 10s paused
        c.onTick(false, 25_000); // 10 more burned -> 15s total of a 20s budget
        assertFalse(c.expired(25_000));
        c.onTick(false, 31_000); // 21s burned
        assertTrue(c.expired(31_000));
    }
}
