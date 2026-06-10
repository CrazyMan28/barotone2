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

/**
 * Every death now goes through an LLM replan that explicitly decides "recover the drops" vs
 * "re-gear and update the goals" (the user's death policy). These cover the pure pieces: the
 * DEATH REPORT the LLM reads, the synthetic recovery step used as the no-LLM fallback, and the
 * splice that turns recovery into a first-class plan step.
 */
public class DeathReplanTest {

    private static DeathEvent death(String cause, String killer, boolean destroyed) {
        return new DeathEvent(100.2, 12.7, -40.9, "minecraft:overworld", 4000, cause, killer, destroyed);
    }

    private static DeathPolicy.Verdict verdict(double distance, int deaths) {
        return DeathPolicy.decide(distance, 10, deaths, 5, 4.3, 300);
    }

    @Test
    public void deathContextReportsEverythingTheLlmNeeds() {
        String ctx = PlannerAgent.deathContext(death("arrow", "skeleton", false),
                verdict(50, 1), 50, 240, 1, 5);
        String lower = ctx.toLowerCase(Locale.ROOT);
        assertTrue(ctx.contains("DEATH REPORT"));
        assertTrue("cause", lower.contains("arrow"));
        assertTrue("killer", lower.contains("skeleton"));
        assertTrue("coordinates", ctx.contains("100") && ctx.contains("12") && ctx.contains("-40"));
        assertTrue("despawn countdown", ctx.contains("240"));
        assertTrue("death budget", ctx.contains("1") && ctx.contains("5"));
        assertTrue("drops still there", lower.contains("drops likely destroyed: no"));
    }

    @Test
    public void deathContextFlagsDestroyedDrops() {
        String ctx = PlannerAgent.deathContext(death("lava", "", true),
                verdict(30, 1), 30, 250, 1, 5);
        assertTrue(ctx.toLowerCase(Locale.ROOT).contains("drops likely destroyed: yes"));
    }

    @Test
    public void deathContextDemandsAStrategyChangeOverBudget() {
        String ctx = PlannerAgent.deathContext(death("mob", "zombie", false),
                verdict(30, 6), 30, 250, 6, 5);
        assertTrue(ctx.contains("MANDATORY"));
    }

    @Test
    public void underBudgetThereIsNoMandatoryLine() {
        String ctx = PlannerAgent.deathContext(death("mob", "zombie", false),
                verdict(30, 1), 30, 250, 1, 5);
        assertFalse(ctx.contains("MANDATORY"));
    }

    @Test
    public void recoveryStepVerifiesTriviallyAndSaysWhereToGo() {
        SubGoal step = PlannerAgent.recoverySubGoal(death("mob", "zombie", false), 240);
        assertTrue(step.title.toLowerCase(Locale.ROOT).contains("recover"));
        assertTrue("empty criteria = completes when the sub-agent returns",
                step.criteria.isEmpty());
        assertTrue(step.instruction.contains("100") && step.instruction.contains("-40"));
        assertTrue(step.instruction.contains("240"));
    }

    @Test
    public void spliceInsertsTheRecoveryStepAtTheCursor() {
        PlanDocument plan = new PlanDocument();
        plan.mainGoal = "get iron tools";
        for (String title : new String[]{"logs", "stone tools", "iron"}) {
            SubGoal g = new SubGoal();
            g.title = title;
            plan.subGoals.add(g);
        }
        plan.subGoals.get(0).complete = true;
        plan.cursor = 1;
        PlannerAgent.spliceRecoveryStep(plan, PlannerAgent.recoverySubGoal(
                death("mob", "zombie", false), 200));
        assertEquals(4, plan.subGoals.size());
        assertTrue(plan.subGoals.get(1).title.toLowerCase(Locale.ROOT).contains("recover"));
        assertEquals("cursor points at the recovery step", 1, plan.cursor);
        assertEquals("later steps untouched", "iron", plan.subGoals.get(3).title);
    }

    @Test
    public void deathReplanDirectivesCoverBothArmsOfThePolicy() {
        String d = PlannerPrompts.deathReplanDirectives();
        String lower = d.toLowerCase(Locale.ROOT);
        assertTrue("recover-the-drops arm", lower.contains("recover"));
        assertTrue("re-gear arm", lower.contains("re-gear"));
        assertTrue("burned drops are written off", lower.contains("destroyed"));
        assertTrue("night safety sequencing", lower.contains("wait_for_dawn"));
        assertTrue("never repeat the killing approach", lower.contains("never repeat"));
    }
}
