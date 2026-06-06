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
import static org.junit.Assert.assertTrue;

public class ScreenshotHelperTest {

    @Test
    public void defaultsBlankReasonToManual() {
        assertEquals("manual", ScreenshotHelper.sanitizeReason(null));
        assertEquals("manual", ScreenshotHelper.sanitizeReason(""));
    }

    @Test
    public void lowercasesAndStripsUnsafeChars() {
        assertEquals("mission_done", ScreenshotHelper.sanitizeReason("mission_done"));
        assertEquals("mission_fail", ScreenshotHelper.sanitizeReason("Mission_Fail"));
        // spaces / punctuation -> underscores so it's a safe filename fragment
        assertEquals("get_iron_axe_", ScreenshotHelper.sanitizeReason("get iron axe!"));
    }

    @Test
    public void capsLength() {
        String s = ScreenshotHelper.sanitizeReason("a".repeat(100));
        assertTrue("reason should be length-capped", s.length() <= 24);
    }

    @Test
    public void captureNeverThrowsHeadless() {
        // No Minecraft instance in the test JVM -> must return null, never throw.
        assertEquals(null, ScreenshotHelper.capture("manual"));
    }
}
