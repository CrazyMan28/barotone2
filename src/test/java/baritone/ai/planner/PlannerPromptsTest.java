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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The planner's prompts carry the directives that make decomposition work; if a refactor
 *  drops one of these key lines the bot silently regresses to YOLO behavior. */
public class PlannerPromptsTest {

    private static SubGoal sub() {
        SubGoal g = new SubGoal();
        g.title = "Get a stone pickaxe";
        g.instruction = "Mine 3 cobblestone and craft a stone pickaxe at the crafting table.";
        SuccessCriterion c = new SuccessCriterion();
        c.type = "best_pickaxe_min";
        c.id = "stone";
        g.criteria = new ArrayList<>(Collections.singletonList(c));
        return g;
    }

    @Test
    public void decompositionSystemPromptHasTheLoadBearingDirectives() {
        String p = PlannerPrompts.decompositionSystemPrompt();
        assertTrue("must force think-first", p.contains("reasoning"));
        assertTrue("must name the tool to call", p.contains("submit_plan"));
        // the criteria catalog the LLM may use
        for (String type : new String[]{"has_item", "food_min", "has_station",
                "best_pickaxe_min", "best_axe_min", "armor_equipped", "reached_y_at_most"}) {
            assertTrue("criteria catalog must list " + type, p.contains(type));
        }
        assertTrue("must teach the tech ladder", p.toLowerCase().contains("iron"));
        assertFalse(p.isEmpty());
    }

    @Test
    public void decompositionSystemPromptScalesThePlanToTheGoal() {
        // NOT hard-coded ladders: "get logs" must be one step; "get diamonds" with iron+food
        // already in the inventory must skip straight to mining
        String p = PlannerPrompts.decompositionSystemPrompt();
        assertTrue("plans may be a single sub-goal", p.contains("1 to 12"));
        assertTrue("must call out trivial goals = one step", p.toLowerCase().contains("trivial"));
        assertTrue("must tell the model to read the WHOLE inventory first",
                p.contains("inventory_totals"));
        assertTrue("must forbid planning steps the inventory already satisfies",
                p.contains("ALREADY"));
    }

    @Test
    public void replanSystemPromptAlsoSkipsWhatTheInventoryAlreadyHas() {
        String p = PlannerPrompts.replanSystemPrompt();
        assertTrue(p.contains("ALREADY"));
        assertTrue(p.contains("submit_plan"));
    }

    @Test
    public void replanPromptDemandsTheWholeRemainingPathNotJustTheFailedStep() {
        // live bug: a 6-step iron-tools plan replanned down to 2 because the LLM only re-planned
        // the step that failed and dropped the entire iron ladder after it
        String p = PlannerPrompts.replanSystemPrompt();
        assertTrue("must demand the full path to the MAIN GOAL",
                p.contains("MAIN GOAL") && p.toLowerCase().contains("every"));
        assertTrue("must warn against dropping later rungs",
                p.toLowerCase().contains("do not") && p.toLowerCase().contains("omit"));
        assertTrue("must tell it to split a step that failed for being too big",
                p.toLowerCase().contains("split"));
    }

    @Test
    public void promptsWarnThatCraftingConsumesGatheredMaterials() {
        // seen in the wild: step criteria were coal>=8 AND torches crafted FROM that coal —
        // after crafting only 7 coal remained, so verification could never pass and the
        // planner bounced the sub-agent in an endless fight
        assertTrue("decompose prompt must warn about ingredient consumption",
                PlannerPrompts.decompositionSystemPrompt().contains("CONSUMES"));
        assertTrue("replan prompt must warn about ingredient consumption",
                PlannerPrompts.replanSystemPrompt().contains("CONSUMES"));
    }

    @Test
    public void decompositionUserPromptEmbedsGoalStateAndMemory() {
        String p = PlannerPrompts.decompositionUserPrompt("get full diamond armor",
                "base=10,64,20", "{\"food\":20}");
        assertTrue(p.contains("get full diamond armor"));
        assertTrue(p.contains("base=10,64,20"));
        assertTrue(p.contains("{\"food\":20}"));
    }

    @Test
    public void replanUserPromptCarriesHistoryAndFailure() {
        PlanDocument d = new PlanDocument();
        d.mainGoal = "get full diamond armor";
        d.subGoals = new ArrayList<>(Arrays.asList(sub(), sub()));
        d.subGoals.get(0).complete = true;
        d.subGoals.get(0).title = "Wooden pickaxe";
        d.cursor = 1;

        String p = PlannerPrompts.replanUserPrompt("get full diamond armor", d,
                "died 6 times to lava at y=-58", "{\"food\":3}");
        assertTrue(p.contains("get full diamond armor"));
        assertTrue("completed steps shown", p.contains("Wooden pickaxe"));
        assertTrue("failure reason shown", p.contains("died 6 times to lava"));
        assertTrue("fresh state shown", p.contains("{\"food\":3}"));
        assertTrue("must ask for remaining work only", p.toLowerCase().contains("remaining"));
    }

    @Test
    public void subGoalPreambleFocusesAndForbidsPlanTools() {
        String p = PlannerPrompts.subGoalPreamble("get full diamond armor",
                Arrays.asList("Wooden pickaxe", "Stone tools"), sub());
        assertTrue(p.contains("get full diamond armor"));
        assertTrue(p.contains("Wooden pickaxe"));
        assertTrue(p.contains(sub().instruction));
        assertTrue("criteria rendered for the sub-agent", p.contains("stone"));
        assertTrue("must forbid plan-display tools", p.contains("set_goal_plan"));
        assertTrue("done() is the contract", p.contains("done"));
    }

    @Test
    public void subGoalPreambleTeachesLongActionDiscipline() {
        // seen in the wild: the sub-agent re-issued the same mine() call dozens of times,
        // restarting the mine process each time and blowing past the needed count
        String p = PlannerPrompts.subGoalPreamble("get full diamond armor",
                java.util.Collections.emptyList(), sub());
        assertTrue("must teach mine -> wait_until_idle", p.contains("wait_until_idle"));
        assertTrue("must forbid re-issuing a running action", p.toLowerCase().contains("re-issue"));
        assertTrue("must teach passing the criterion count as mine quantity",
                p.toLowerCase().contains("quantity"));
    }

    @Test
    public void recoveryPreambleSendsTheBotToTheDrops() {
        String p = PlannerPrompts.recoveryPreamble(123, -58, 456, 140);
        assertTrue(p.contains("123"));
        assertTrue(p.contains("-58"));
        assertTrue(p.contains("456"));
        assertTrue("urgency: seconds left", p.contains("140"));
        assertTrue(p.toLowerCase().contains("goto_coords"));
    }
}
