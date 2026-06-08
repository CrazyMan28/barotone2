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

import baritone.ai.OpenAiChatClient;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The planner↔LLM seam: the forced submit_plan tool schema and the answer extraction. */
public class PlannerAgentStaticsTest {

    @Test
    public void toolDefsAreValidAndDescribeSubmitPlan() {
        JsonArray defs = PlannerAgent.submitPlanToolDefs();
        assertEquals(1, defs.size());
        JsonObject fn = defs.get(0).getAsJsonObject().getAsJsonObject("function");
        assertEquals("submit_plan", fn.get("name").getAsString());
        JsonObject params = fn.getAsJsonObject("parameters");
        assertTrue(params.getAsJsonObject("properties").has("reasoning"));
        assertTrue(params.getAsJsonObject("properties").has("sub_goals"));
        // reasoning is REQUIRED — that's the "think first" enforcement
        assertTrue(params.getAsJsonArray("required").toString().contains("reasoning"));
    }

    @Test
    public void extractsArgumentsFromASubmitPlanToolCall() {
        OpenAiChatClient.AssistantMessage am = new OpenAiChatClient.AssistantMessage();
        am.toolCalls = JsonParser.parseString("[{\"id\":\"c1\",\"function\":{\"name\":\"submit_plan\","
                + "\"arguments\":\"{\\\"reasoning\\\":\\\"ladder\\\",\\\"sub_goals\\\":[]}\"}}]").getAsJsonArray();
        JsonObject args = PlannerAgent.extractSubmitPlanArgs(am);
        assertNotNull(args);
        assertEquals("ladder", args.get("reasoning").getAsString());
    }

    @Test
    public void plannerPrefersItsOwnLargeModelOverTheExecutorModel() {
        // the executor may run a cheap/fast model, but planning must use the strong planner model
        assertEquals("mistral-large-latest",
                PlannerAgent.effectivePlannerModel("mistral-large-latest", "mistral-small-latest"));
        // blank planner model -> fall back to the mission model
        assertEquals("mistral-small-latest",
                PlannerAgent.effectivePlannerModel("", "mistral-small-latest"));
        assertEquals("mistral-small-latest",
                PlannerAgent.effectivePlannerModel(null, "mistral-small-latest"));
        assertEquals("mistral-large-latest",
                PlannerAgent.effectivePlannerModel("  mistral-large-latest  ", "x"));
    }

    @Test
    public void collectsDeliverableToolAndArmorIdsForTheHotbar() {
        // a finished mission should move the goods it made into the hotbar so the player sees them
        PlanDocument d = new PlanDocument();
        SuccessCriterion pick = new SuccessCriterion(); pick.type = "has_item"; pick.id = "minecraft:iron_pickaxe"; pick.count = 1;
        SuccessCriterion logs = new SuccessCriterion(); logs.type = "has_item"; logs.id = "log"; logs.count = 5;          // raw material — skip
        SuccessCriterion food = new SuccessCriterion(); food.type = "food_min"; food.count = 8;                           // not an item — skip
        SuccessCriterion chest = new SuccessCriterion(); chest.type = "armor_equipped"; chest.slot = "chest"; chest.id = "diamond_chestplate";
        d.finalCriteria = new java.util.ArrayList<>(java.util.Arrays.asList(pick, logs, food, chest));

        java.util.List<String> ids = PlannerAgent.deliverableItemIds(d);
        assertTrue("keeps the iron pickaxe (a tool)", ids.contains("minecraft:iron_pickaxe"));
        assertTrue("keeps equipped armor", ids.contains("diamond_chestplate"));
        assertFalse("drops raw materials like logs", ids.contains("log"));
        assertFalse("ignores non-item criteria", ids.stream().anyMatch(s -> s.contains("food")));
    }

    @Test
    public void deliverableIdsFallBackToLastStepWhenNoFinalCriteria() {
        PlanDocument d = new PlanDocument();
        SubGoal s1 = new SubGoal();
        SubGoal s2 = new SubGoal();
        SuccessCriterion sword = new SuccessCriterion(); sword.type = "has_item"; sword.id = "stone_sword"; sword.count = 1;
        s2.criteria = new java.util.ArrayList<>(java.util.Collections.singletonList(sword));
        d.subGoals = new java.util.ArrayList<>(java.util.Arrays.asList(s1, s2));
        d.finalCriteria = new java.util.ArrayList<>();

        java.util.List<String> ids = PlannerAgent.deliverableItemIds(d);
        assertTrue(ids.contains("stone_sword"));
    }

    @Test
    public void plannerMessagesNeverCarryAnUnansweredToolCall() {
        // Mistral 422 "Not the same number of function calls and responses" happens when an
        // assistant tool_call has no matching tool response. The planner request must therefore
        // only ever contain system/user roles — never assistant or tool.
        for (int attempt = 0; attempt < 3; attempt++) {
            JsonArray msgs = PlannerAgent.plannerMessages("sys", "usr", attempt);
            for (com.google.gson.JsonElement el : msgs) {
                String role = el.getAsJsonObject().get("role").getAsString();
                assertTrue("only system/user roles allowed, got " + role,
                        role.equals("system") || role.equals("user"));
            }
        }
        // first attempt is just system + user; retries add a corrective user nudge
        assertEquals(2, PlannerAgent.plannerMessages("sys", "usr", 0).size());
        assertEquals(3, PlannerAgent.plannerMessages("sys", "usr", 1).size());
    }

    @Test
    public void ignoresOtherToolCallsAndProse() {
        OpenAiChatClient.AssistantMessage prose = new OpenAiChatClient.AssistantMessage();
        prose.content = "here is my plan: ...";
        assertNull(PlannerAgent.extractSubmitPlanArgs(prose));

        OpenAiChatClient.AssistantMessage wrongTool = new OpenAiChatClient.AssistantMessage();
        wrongTool.toolCalls = JsonParser.parseString(
                "[{\"id\":\"c1\",\"function\":{\"name\":\"mine\",\"arguments\":\"{}\"}}]").getAsJsonArray();
        assertNull(PlannerAgent.extractSubmitPlanArgs(wrongTool));

        assertNull(PlannerAgent.extractSubmitPlanArgs(null));
    }
}
