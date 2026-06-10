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
import static org.junit.Assert.assertTrue;

/**
 * Death tracking lives on the PLAN, not the (ephemeral, replan-rebuilt) sub-goal — the live bug was
 * every death reporting "deaths: 1" because the counter reset whenever the sub-goals were rebuilt.
 */
public class PlanDocumentDeathTest {

    private static final double KZ = 24D;

    @Test
    public void deathCountSurvivesAcrossSubGoalRebuilds() {
        PlanDocument plan = new PlanDocument();
        plan.recordDeath(0, 70, 0, KZ);
        plan.subGoals.clear(); // simulate a replan wiping the sub-goals
        plan.recordDeath(500, 70, 500, KZ);
        assertEquals("the plan remembers both deaths", 2, plan.deathsTotal);
    }

    @Test
    public void twoDeathsInTheSameAreaCountAsNear() {
        PlanDocument plan = new PlanDocument();
        assertFalse("first death has nothing to compare to", plan.recordDeath(2, 79, 3, KZ));
        assertTrue("died 9 blocks away — same camped area", plan.recordDeath(9, 77, -3, KZ));
        assertEquals(2, plan.deathsNearLast);
    }

    @Test
    public void aDeathFarAwayResetsTheNearCounter() {
        PlanDocument plan = new PlanDocument();
        plan.recordDeath(2, 79, 3, KZ);
        plan.recordDeath(9, 77, -3, KZ);
        assertEquals(2, plan.deathsNearLast);
        assertFalse("400 blocks away is a new place", plan.recordDeath(400, 70, 400, KZ));
        assertEquals("near-counter restarts", 1, plan.deathsNearLast);
        assertEquals("but the total keeps climbing", 3, plan.deathsTotal);
    }
}
