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

import baritone.ai.BlockingToolGuard.Bail;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Every blocking tool wait-loop (open_station, hunt, furnace_smelt, brewing_brew) must yield
 * control PROMPTLY rather than spin to its own deadline when the agent is cancelled, dies, or the
 * survival reflex takes over (it closes the container GUI). This is the priority logic those loops
 * share — the cause of "agent stuck up to 90s while the reflex fights" before the fix.
 */
public class BlockingToolGuardTest {

    @Test
    public void quietPollDoesNotBail() {
        assertEquals(Bail.NONE, BlockingToolGuard.evaluate(false, false, false, false));
    }

    @Test
    public void reflexEngagementBailsImmediately() {
        // the reflex closed the GUI; the loop could never make progress — bail now, not at timeout
        assertEquals(Bail.REFLEX, BlockingToolGuard.evaluate(false, false, true, false));
    }

    @Test
    public void deathBailsImmediately() {
        // a mid-craft death must hand back to the planner's death-replan, not hang until timeout
        assertEquals(Bail.DIED, BlockingToolGuard.evaluate(false, true, false, false));
    }

    @Test
    public void cancelBails() {
        assertEquals(Bail.CANCELLED, BlockingToolGuard.evaluate(true, false, false, false));
    }

    @Test
    public void timeoutOnlyAfterTheReflexPausedDeadlineElapses() {
        assertEquals(Bail.TIMEOUT, BlockingToolGuard.evaluate(false, false, false, true));
    }

    @Test
    public void deathOutranksAReflexThatIsAlsoEngaged() {
        // when the bot dies WHILE a threat is present, the death handoff wins: gear is gone, the
        // planner must replan rather than the tool reporting "retry after danger".
        assertEquals(Bail.DIED, BlockingToolGuard.evaluate(false, true, true, false));
    }

    @Test
    public void cancelOutranksEverything() {
        assertEquals(Bail.CANCELLED, BlockingToolGuard.evaluate(true, true, true, true));
    }

    @Test
    public void reflexOutranksAnExpiredDeadline() {
        // a deadline that elapsed only because we DIDN'T pause it would be wrong — but even with a
        // truly expired (reflex-paused) clock, an active threat is the more useful message to return
        assertEquals(Bail.REFLEX, BlockingToolGuard.evaluate(false, false, true, true));
    }

    @Test
    public void deathOutranksTimeout() {
        assertEquals(Bail.DIED, BlockingToolGuard.evaluate(false, true, false, true));
    }
}
