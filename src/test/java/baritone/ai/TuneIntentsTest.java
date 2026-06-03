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

import baritone.ai.TuneIntents.Intent;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TuneIntentsTest {

    @Test
    public void headNotTurningIsTheFixIntent() {
        List<Intent> intents = TuneIntents.match("my head isn't turning and it won't break blocks");
        assertTrue(intents.contains(Intent.FIX_AIM));
        assertFalse(intents.contains(Intent.NO_BREAK));
    }

    @Test
    public void cantBreakComplaintIsFix() {
        List<Intent> intents = TuneIntents.match("it cant break blocks when it pathfinds, fix it");
        assertTrue(intents.contains(Intent.FIX_AIM));
        assertFalse(intents.contains(Intent.NO_BREAK));
    }

    @Test
    public void sneakyIsStealth() {
        assertEquals(List.of(Intent.STEALTH), TuneIntents.match("be sneaky on this server"));
        assertEquals(List.of(Intent.STEALTH), TuneIntents.match("go UNDERCOVER"));
    }

    @Test
    public void breakFasterIsOnlyBreakFaster() {
        assertEquals(List.of(Intent.BREAK_FASTER), TuneIntents.match("break faster please"));
    }

    @Test
    public void stopBreakingIsNoBreakNotFix() {
        List<Intent> intents = TuneIntents.match("stop breaking my house");
        assertEquals(List.of(Intent.NO_BREAK), intents);
        assertEquals(List.of(Intent.NO_BREAK), TuneIntents.match("don't break anything"));
    }

    @Test
    public void allowBreakingAgain() {
        assertEquals(List.of(Intent.ALLOW_BREAK), TuneIntents.match("allow breaking again"));
    }

    @Test
    public void smootherAimResetsThenSmooths() {
        // "aim" matches the fix intent and "smoother" the smooth style; canonical order applies the
        // reset first, then the light smoothing on top.
        assertEquals(List.of(Intent.FIX_AIM, Intent.SMOOTH), TuneIntents.match("smoother aim please"));
    }

    @Test
    public void stealthPlusBreakFasterCombine() {
        assertEquals(List.of(Intent.STEALTH, Intent.BREAK_FASTER),
                TuneIntents.match("be sneaky but break faster"));
    }

    @Test
    public void smoothBeatsSnappyWhenBothMentioned() {
        assertEquals(List.of(Intent.SMOOTH), TuneIntents.match("smooth but fast turn"));
    }

    @Test
    public void carefulTurnsReflexesOn() {
        assertEquals(List.of(Intent.REFLEX_ON), TuneIntents.match("be careful out there"));
        assertEquals(List.of(Intent.REFLEX_ON), TuneIntents.match("protect yourself"));
    }

    @Test
    public void ignoreMobsTurnsReflexesOff() {
        assertEquals(List.of(Intent.REFLEX_OFF), TuneIntents.match("ignore mobs and keep mining"));
        assertEquals(List.of(Intent.REFLEX_OFF), TuneIntents.match("reflexes off"));
    }

    @Test
    public void unknownRequestMatchesNothing() {
        assertTrue(TuneIntents.match("make me a sandwich").isEmpty());
        assertTrue(TuneIntents.match("").isEmpty());
        assertTrue(TuneIntents.match(null).isEmpty());
    }

    @Test
    public void helpMentionsTheFallbackTools() {
        assertTrue(TuneIntents.help().contains("set_setting"));
        assertTrue(TuneIntents.help().contains("list_settings"));
    }
}
