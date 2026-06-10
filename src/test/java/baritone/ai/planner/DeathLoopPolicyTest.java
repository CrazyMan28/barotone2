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

package baritone.ai.planner;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * The anti-death-spiral policy. The live failure: a skeleton camped the bot's drop site, recovery
 * walked it back into the arrows, every death burned a replan, and the mission gave up after 5.
 * This policy keeps a death cheap (no replan), abandons a camped spot instead of returning, and
 * only gives up after the mission has genuinely bled out.
 */
public class DeathLoopPolicyTest {

    private static final int MAX = 12;

    @Test
    public void anIsolatedRecoverableDeathJustGoesBackForTheDrops() {
        assertEquals(DeathLoopPolicy.Action.CONTINUE_RECOVER,
                DeathLoopPolicy.decide(1, 1, MAX, true, false, false));
    }

    @Test
    public void aSecondDeathInTheSameAreaAbandonsTheTrap() {
        // died twice near the same spot: a mob is camping it — do NOT keep going back
        assertEquals(DeathLoopPolicy.Action.ESCAPE_TRAP,
                DeathLoopPolicy.decide(2, 2, MAX, true, false, false));
    }

    @Test
    public void dyingWhileRecoveringIsAlwaysATrap() {
        assertEquals(DeathLoopPolicy.Action.ESCAPE_TRAP,
                DeathLoopPolicy.decide(2, 1, MAX, true, false, true));
    }

    @Test
    public void destroyedDropsAreNeverRecovered() {
        assertEquals(DeathLoopPolicy.Action.CONTINUE_NO_RECOVER,
                DeathLoopPolicy.decide(1, 1, MAX, false, true, false));
    }

    @Test
    public void unreachableDropsAreNotChased() {
        assertEquals(DeathLoopPolicy.Action.CONTINUE_NO_RECOVER,
                DeathLoopPolicy.decide(1, 1, MAX, false, false, false));
    }

    @Test
    public void theMissionOnlyGivesUpAfterManyDeathsNotFive() {
        // five deaths must NOT end the mission (the old bug); only past the mission cap does it
        assertEquals(DeathLoopPolicy.Action.CONTINUE_RECOVER,
                DeathLoopPolicy.decide(5, 1, MAX, true, false, false));
        assertEquals(DeathLoopPolicy.Action.GIVE_UP,
                DeathLoopPolicy.decide(MAX + 1, 1, MAX, true, false, false));
    }
}
