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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MissionStatsTest {

    @Test
    public void countsCallsAndErrors() {
        MissionStats stats = new MissionStats(0L);
        stats.record("mine", false);
        stats.record("mine", false);
        stats.record("craft", true);

        assertEquals(3, stats.totalCalls());
        assertEquals(1, stats.totalErrors());
    }

    @Test
    public void reportListsTopToolsAndElapsedSeconds() {
        MissionStats stats = new MissionStats(1000L);
        stats.record("mine", false);
        stats.record("mine", false);
        stats.record("mine", false);
        stats.record("craft", false);
        stats.record("goto", true);

        String report = stats.report(1000L + 47_000L);

        assertTrue(report.contains("5 tool calls"));
        assertTrue(report.contains("1 error"));
        assertTrue(report.contains("mine x3"));
        assertTrue(report.contains("47s"));
    }

    @Test
    public void reportUsesSingularAndOmitsErrorsWhenNone() {
        MissionStats stats = new MissionStats(0L);
        stats.record("get_state", false);

        String report = stats.report(0L);

        assertTrue(report.contains("1 tool call"));
        assertFalse(report.contains("error"));
    }

    @Test
    public void unknownToolNameIsTolerated() {
        MissionStats stats = new MissionStats(0L);
        stats.record(null, true);
        stats.record("  ", false);

        assertEquals(2, stats.totalCalls());
        assertTrue(stats.report(0L).contains("unknown"));
    }
}
