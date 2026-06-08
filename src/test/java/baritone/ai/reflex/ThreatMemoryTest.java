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

/** The anti-flap release debounce: min dwell, release grace, and reset-on-threat-return. */
public class ThreatMemoryTest {

    private static final int DWELL = 12;
    private static final int GRACE = 16;

    @Test
    public void holdsThroughTheDwellWindowEvenIfThreatGoneImmediately() {
        ThreatMemory m = new ThreatMemory();
        m.onEngage(0);
        for (long now = 1; now < DWELL; now++) {
            assertFalse("inside dwell @" + now, m.shouldRelease(now, true, DWELL, GRACE));
        }
    }

    @Test
    public void releasesOnlyAfterGraceElapsesPastDwell() {
        ThreatMemory m = new ThreatMemory();
        m.onEngage(0);
        // threat reported gone from tick 1 onward; release allowed once both dwell and grace pass
        assertFalse(m.shouldRelease(1, true, DWELL, GRACE));   // grace clock starts at 1
        assertFalse(m.shouldRelease(15, true, DWELL, GRACE));  // 15-1=14 < grace
        assertTrue(m.shouldRelease(17, true, DWELL, GRACE));   // 17-1=16 >= grace, dwell long past
    }

    @Test
    public void aThreatComingBackResetsTheReleaseClock() {
        ThreatMemory m = new ThreatMemory();
        m.onEngage(0);
        assertFalse(m.shouldRelease(20, true, DWELL, GRACE));  // start grace at 20
        assertFalse(m.shouldRelease(25, false, DWELL, GRACE)); // threat back: cancel pending release
        // grace must start over from the next "gone" tick (36); a stale clock would have released
        // at 36 already (20 + 16) — it must not.
        assertFalse(m.shouldRelease(36, true, DWELL, GRACE));  // restart grace at 36
        assertFalse(m.shouldRelease(51, true, DWELL, GRACE));  // 51-36=15 < grace
        assertTrue(m.shouldRelease(52, true, DWELL, GRACE));   // 52-36=16 >= grace
    }

    @Test
    public void neverReleasesWhileThreatPresent() {
        ThreatMemory m = new ThreatMemory();
        m.onEngage(0);
        for (long now = 1; now < 200; now++) {
            assertFalse(m.shouldRelease(now, false, DWELL, GRACE));
        }
    }
}
