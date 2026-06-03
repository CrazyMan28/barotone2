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

import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ReflexLogTest {

    @Before
    public void reset() {
        ReflexLog.resetForTests();
    }

    @Test
    public void newestFirstAndCapped() {
        for (int i = 1; i <= 12; i++) {
            ReflexLog.record("event " + i);
        }
        List<String> recent = ReflexLog.recent(20);
        assertEquals(8, recent.size());
        assertTrue(recent.get(0).startsWith("event 12"));
        assertTrue(recent.get(7).startsWith("event 5"));
    }

    @Test
    public void recentRespectsMaxAndIgnoresBlanks() {
        ReflexLog.record("one");
        ReflexLog.record(" ");
        ReflexLog.record(null);
        ReflexLog.record("two");
        List<String> recent = ReflexLog.recent(1);
        assertEquals(1, recent.size());
        assertTrue(recent.get(0).startsWith("two"));
        assertTrue(recent.get(0).contains("s ago)"));
    }
}
