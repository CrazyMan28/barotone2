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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** The user's death policy: first death — if the drops look reachable before the 5-minute
 *  despawn, go get them; once it has died MORE than maxDeaths times on one sub-goal, stop
 *  repeating the same mistake and replan/re-gear instead. */
public class DeathPolicyTest {

    private static final double WALK = 4.3;     // blocks/sec
    private static final double DESPAWN = 300;  // item despawn window

    @Test
    public void nearAndFreshDeathIsRecovered() {
        DeathPolicy.Verdict v = DeathPolicy.decide(40, 10, 1, 5, WALK, DESPAWN);
        assertEquals(DeathPolicy.Decision.RECOVER_THEN_CONTINUE, v.decision);
        assertTrue(v.recoverable);
        assertNotNull(v.reason);
    }

    @Test
    public void dropsTooFarAwayMeansRegear() {
        // 2000 blocks at 4.3 b/s is ~465s — items despawn long before arrival
        DeathPolicy.Verdict v = DeathPolicy.decide(2000, 10, 1, 5, WALK, DESPAWN);
        assertEquals(DeathPolicy.Decision.REPLAN_AND_REGEAR, v.decision);
        assertFalse(v.recoverable);
    }

    @Test
    public void staleDeathMeansRegear() {
        // most of the despawn window already burned
        DeathPolicy.Verdict v = DeathPolicy.decide(10, 290, 1, 5, WALK, DESPAWN);
        assertEquals(DeathPolicy.Decision.REPLAN_AND_REGEAR, v.decision);
        assertFalse(v.recoverable);
    }

    @Test
    public void moreThanMaxDeathsAlwaysRegearsEvenWhenRecoverable() {
        DeathPolicy.Verdict v = DeathPolicy.decide(5, 5, 6, 5, WALK, DESPAWN);
        assertEquals(DeathPolicy.Decision.REPLAN_AND_REGEAR, v.decision);
        assertTrue("drops were reachable — the POLICY said stop anyway", v.recoverable);
    }

    @Test
    public void exactlyMaxDeathsStillRecovers() {
        // "more then 5 time" — at exactly 5 it still tries
        DeathPolicy.Verdict v = DeathPolicy.decide(5, 5, 5, 5, WALK, DESPAWN);
        assertEquals(DeathPolicy.Decision.RECOVER_THEN_CONTINUE, v.decision);
    }

    @Test
    public void crossDimensionDeathIsNeverRecoverableEvenWhenNear() {
        // died in the Nether, respawned in the Overworld: the straight-line distance is tiny but the
        // drops are in another dimension — must re-gear, not walk into a wall where the portal isn't.
        DeathPolicy.Verdict v = DeathPolicy.decide(5, 5, 1, 5, WALK, DESPAWN, false);
        assertEquals(DeathPolicy.Decision.REPLAN_AND_REGEAR, v.decision);
        assertFalse("drops in another dimension are unreachable", v.recoverable);
        assertNotNull(v.reason);
    }

    @Test
    public void sameDimensionDeathStillFollowsTheNormalPolicy() {
        // the dimension-aware overload with sameDimension=true must match the plain overload exactly
        DeathPolicy.Verdict same = DeathPolicy.decide(40, 10, 1, 5, WALK, DESPAWN, true);
        DeathPolicy.Verdict plain = DeathPolicy.decide(40, 10, 1, 5, WALK, DESPAWN);
        assertEquals(plain.decision, same.decision);
        assertEquals(plain.recoverable, same.recoverable);
    }

    @Test
    public void despawnBoundaryUsesEtaPlusSafetyMargin() {
        // recoverable iff secondsSinceDeath + distance/walk + MARGIN < despawn
        double margin = DeathPolicy.SAFETY_MARGIN_SECONDS;
        double justInTime = (DESPAWN - margin - 10 - 1) * WALK;   // ~1s spare
        double tooLate = (DESPAWN - margin - 10 + 5) * WALK;      // ~5s past

        assertEquals(DeathPolicy.Decision.RECOVER_THEN_CONTINUE,
                DeathPolicy.decide(justInTime, 10, 1, 5, WALK, DESPAWN).decision);
        assertEquals(DeathPolicy.Decision.REPLAN_AND_REGEAR,
                DeathPolicy.decide(tooLate, 10, 1, 5, WALK, DESPAWN).decision);
    }
}
