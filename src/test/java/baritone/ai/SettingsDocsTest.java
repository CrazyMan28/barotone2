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

import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SettingsDocsTest {

    @Test
    public void resourceLoadsAndCoversTheSettings() {
        assertTrue("expected 200+ documented settings, got " + SettingsDocs.load().size(),
                SettingsDocs.load().size() >= 200);
    }

    @Test
    public void knownSettingsAreDocumented() {
        assertTrue(SettingsDocs.describe("allowBreak").toLowerCase(Locale.ROOT).contains("break"));
        assertFalse(SettingsDocs.describe("smoothLook").isEmpty());
        assertFalse(SettingsDocs.describe("strictVisibleBlockInteractions").isEmpty());
    }

    @Test
    public void lookupIsCaseInsensitive() {
        assertEquals(SettingsDocs.describe("allowBreak"), SettingsDocs.describe("ALLOWBREAK"));
    }

    @Test
    public void matchesSearchesDocsNotJustNames() {
        // "ticks between breaking" appears only in blockBreakSpeed's documentation, not its name
        assertTrue(SettingsDocs.matches("blockBreakSpeed", "ticks between breaking"));
        assertTrue(SettingsDocs.matches("blockBreakSpeed", "blockbreak"));
        assertFalse(SettingsDocs.matches("blockBreakSpeed", "zombie pigman"));
    }

    @Test
    public void nullsAreSafe() {
        assertEquals("", SettingsDocs.describe(null));
        assertFalse(SettingsDocs.matches(null, "break"));
        assertFalse(SettingsDocs.matches("allowBreak", null));
    }
}
