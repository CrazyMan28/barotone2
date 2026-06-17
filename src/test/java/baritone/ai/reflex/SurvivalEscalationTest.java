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
 * The pure escalation/resolve policy for the cooperative survival agent. Tested without any Minecraft
 * or LLM: every gate (debounce, already-running, no-provider, disabled, cooldown) is a boolean.
 */
public class SurvivalEscalationTest {

    private static final int SUSTAIN = 60;
    private static final int CLEAR = 60;

    // ---------------------------------------------------------------- shouldEscalate

    @Test
    public void escalatesWhenEverythingLinesUp() {
        assertTrue(SurvivalEscalation.shouldEscalate(true, SUSTAIN, SUSTAIN,
                false, true, true, true));
    }

    @Test
    public void escalatesWhenSustainedWellPastTheWindow() {
        assertTrue(SurvivalEscalation.shouldEscalate(true, SUSTAIN * 3, SUSTAIN,
                false, true, true, true));
    }

    @Test
    public void noEscalationWithoutDistress() {
        assertFalse(SurvivalEscalation.shouldEscalate(false, SUSTAIN, SUSTAIN,
                false, true, true, true));
    }

    @Test
    public void noEscalationBeforeDebounceWindowElapses() {
        assertFalse(SurvivalEscalation.shouldEscalate(true, SUSTAIN - 1, SUSTAIN,
                false, true, true, true));
    }

    @Test
    public void noEscalationWhenOneIsAlreadyRunning() {
        assertFalse(SurvivalEscalation.shouldEscalate(true, SUSTAIN, SUSTAIN,
                true, true, true, true));
    }

    @Test
    public void noEscalationWithoutAProvider() {
        assertFalse(SurvivalEscalation.shouldEscalate(true, SUSTAIN, SUSTAIN,
                false, false, true, true));
    }

    @Test
    public void noEscalationWhenDisabled() {
        assertFalse(SurvivalEscalation.shouldEscalate(true, SUSTAIN, SUSTAIN,
                false, true, false, true));
    }

    @Test
    public void noEscalationDuringCooldown() {
        assertFalse(SurvivalEscalation.shouldEscalate(true, SUSTAIN, SUSTAIN,
                false, true, true, false));
    }

    @Test
    public void aSustainOfZeroIsTreatedAsOne() {
        // a misconfigured requiredSustainTicks <= 0 must not let a single distress tick instantly fire
        assertTrue(SurvivalEscalation.shouldEscalate(true, 1, 0,
                false, true, true, true));
        assertFalse(SurvivalEscalation.shouldEscalate(true, 0, 0,
                false, true, true, true));
    }

    // ---------------------------------------------------------------- isResolved

    @Test
    public void resolvedAfterEnoughCalmTicks() {
        assertTrue(SurvivalEscalation.isResolved(false, false, CLEAR, CLEAR));
    }

    @Test
    public void notResolvedWhileStillInDistress() {
        assertFalse(SurvivalEscalation.isResolved(true, false, CLEAR * 2, CLEAR));
    }

    @Test
    public void notResolvedWhileAHostileIsStillNear() {
        assertFalse(SurvivalEscalation.isResolved(false, true, CLEAR * 2, CLEAR));
    }

    @Test
    public void notResolvedBeforeTheCalmWindowFills() {
        assertFalse(SurvivalEscalation.isResolved(false, false, CLEAR - 1, CLEAR));
    }
}
