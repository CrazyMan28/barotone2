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
 * The flee-episode timing core, ported verbatim from {@code ReflexPlanner.FleeWatchdog} (these
 * are the original 6 tests). The redesign keeps the proven episode/cooldown clock but uses
 * "unresolved" as the trigger for tactical resolution (fight/pillar/wall) instead of plain
 * suppression.
 *
 * <p>(maxFleeTicks=200, cooldownTicks=120, episodeGapTicks=100 — the live values.)
 */
public class FleeEscalationTest {

    private static FleeEscalation escalation() {
        return new FleeEscalation(200, 120, 100);
    }

    @Test
    public void neverSuppressesWhenNoMob() {
        FleeEscalation w = escalation();
        for (long t = 0; t < 1000; t++) {
            assertFalse("no mob -> never suppress", w.suppressed(t, false));
        }
    }

    @Test
    public void letsShortFleesRun() {
        FleeEscalation w = escalation();
        for (long t = 0; t <= 200; t++) {
            assertFalse("within maxFleeTicks -> keep fleeing", w.suppressed(t, true));
        }
    }

    @Test
    public void cutsOffAStuckFlee() {
        FleeEscalation w = escalation();
        for (long t = 0; t <= 200; t++) {
            assertFalse(w.suppressed(t, true));
        }
        assertTrue("episode > maxFleeTicks -> suppress", w.suppressed(201, true));
    }

    @Test
    public void cooldownThenReEngages() {
        FleeEscalation w = escalation();
        for (long t = 0; t <= 201; t++) {
            w.suppressed(t, true); // trips a cooldown at tick 201 (lasts 120 ticks -> through 320)
        }
        assertTrue("mid-cooldown still suppressed", w.suppressed(250, true));
        assertTrue("last cooldown tick still suppressed", w.suppressed(320, true));
        assertFalse("cooldown expired -> flee again", w.suppressed(321, true));
        for (long t = 322; t <= 521; t++) {
            assertFalse("fresh window keeps fleeing", w.suppressed(t, true));
        }
        assertTrue("second episode also gets cut off", w.suppressed(522, true));
    }

    @Test
    public void oscillatingInAndOutStillCountsAsOneEpisode() {
        FleeEscalation w = escalation();
        boolean suppressedEver = false;
        for (long t = 0; t <= 400; t++) {
            boolean mobNear = (t % 20) < 12; // present 12 ticks, gone 8 (gap of 8 << 100)
            suppressedEver |= w.suppressed(t, mobNear);
        }
        assertTrue("oscillating episode eventually gets cut off", suppressedEver);
    }

    @Test
    public void longLullResetsEpisode() {
        FleeEscalation w = escalation();
        for (long t = 0; t < 150; t++) {
            assertFalse(w.suppressed(t, true));
        }
        for (long t = 150; t < 450; t++) {
            assertFalse("no mob -> never suppress", w.suppressed(t, false));
        }
        for (long t = 450; t <= 650; t++) {
            assertFalse("fresh episode gets a full flee window", w.suppressed(t, true));
        }
        assertTrue(w.suppressed(651, true));
    }
}
