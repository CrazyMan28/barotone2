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

import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The escape-the-trap step and the recovery-step purge that break the camped-spot death loop. */
public class EscapeTrapTest {

    private static DeathEvent death() {
        return new DeathEvent(2, 79, 3, "minecraft:overworld", 4000, "arrow", "skeleton", false);
    }

    private static SubGoal recovery(String title) {
        SubGoal g = new SubGoal();
        g.title = title;
        return g;
    }

    @Test
    public void escapeStepFleesAwayAndForbidsReturning() {
        SubGoal step = PlannerAgent.escapeTrapSubGoal(death(), 200, 70, -150);
        String lower = step.instruction.toLowerCase(Locale.ROOT);
        assertTrue("empty criteria so it completes when the sub-agent returns", step.criteria.isEmpty());
        assertTrue("names the flee destination", step.instruction.contains("200") && step.instruction.contains("-150"));
        assertTrue("forbids going back for the drops", lower.contains("do not") || lower.contains("don't"));
        assertTrue("waits out the night if needed", lower.contains("wait_for_dawn"));
    }

    @Test
    public void purgePullsPendingRecoveryStepsButKeepsRealWork() {
        PlanDocument plan = new PlanDocument();
        plan.subGoals.add(recovery("Recover drops at 2, 79, 3"));   // 0 - pending recovery
        plan.subGoals.add(recovery("Craft a furnace"));             // 1 - real work
        plan.subGoals.add(recovery("Recover drops at 9, 77, -3"));  // 2 - another pending recovery
        plan.cursor = 0;
        int removed = PlannerAgent.removeIncompleteRecoverySteps(plan);
        assertEquals(2, removed);
        assertEquals(1, plan.subGoals.size());
        assertEquals("Craft a furnace", plan.subGoals.get(0).title);
        assertEquals("cursor clamped to the surviving work", 0, plan.cursor);
    }

    @Test
    public void purgeKeepsACompletedRecoveryStep() {
        PlanDocument plan = new PlanDocument();
        SubGoal doneRecovery = recovery("Recover drops at 2, 79, 3");
        doneRecovery.complete = true; // already got those — it stays as history
        plan.subGoals.add(doneRecovery);
        plan.subGoals.add(recovery("Craft a furnace"));
        plan.cursor = 1;
        int removed = PlannerAgent.removeIncompleteRecoverySteps(plan);
        assertEquals(0, removed);
        assertEquals(2, plan.subGoals.size());
    }

    @Test
    public void isRecoveryStepRecognizesTheTitle() {
        assertTrue(PlannerAgent.isRecoveryStep(recovery("Recover drops at 1, 2, 3")));
        assertFalse(PlannerAgent.isRecoveryStep(recovery("Craft a furnace")));
        assertFalse(PlannerAgent.isRecoveryStep(new SubGoal()));
    }
}
