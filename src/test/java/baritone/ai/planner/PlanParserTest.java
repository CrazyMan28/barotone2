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

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Parsing the planner LLM's submit_plan arguments. A broken planner response must never
 *  block the mission — worst case we fall back to a single sub-goal = the whole goal. */
public class PlanParserTest {

    private static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    @Test
    public void parsesWellFormedPlan() {
        JsonObject args = json("{"
                + "\"reasoning\":\"ladder: wood->stone->iron->diamond, with food insurance\","
                + "\"sub_goals\":["
                + "  {\"title\":\"Get logs + wooden pickaxe\",\"instruction\":\"chop 5 logs, craft wooden pickaxe\","
                + "   \"criteria\":[{\"type\":\"has_item\",\"id\":\"wooden_pickaxe\",\"count\":1}]},"
                + "  {\"title\":\"Stone tools\",\"instruction\":\"mine cobble, craft stone pickaxe\","
                + "   \"criteria\":[{\"type\":\"best_pickaxe_min\",\"id\":\"stone\"}]}"
                + "],"
                + "\"final_criteria\":[{\"type\":\"armor_equipped\",\"id\":\"diamond\",\"slot\":\"chest\"}]"
                + "}");

        PlanDocument d = PlanParser.parse("get full diamond armor", args, 1234L);

        assertEquals("get full diamond armor", d.mainGoal);
        assertEquals("ladder: wood->stone->iron->diamond, with food insurance", d.reasoning);
        assertEquals(2, d.subGoals.size());
        assertEquals(0, d.cursor);
        assertEquals(1234L, d.createdAt);
        assertEquals("Get logs + wooden pickaxe", d.subGoals.get(0).title);
        assertEquals(1, d.subGoals.get(0).criteria.size());
        assertEquals("has_item", d.subGoals.get(0).criteria.get(0).type);
        assertEquals(1, d.finalCriteria.size());
        assertEquals("chest", d.finalCriteria.get(0).slot);
    }

    @Test
    public void dropsUnknownCriterionTypes() {
        JsonObject args = json("{\"sub_goals\":[{\"title\":\"t\",\"instruction\":\"i\",\"criteria\":["
                + "{\"type\":\"has_item\",\"id\":\"oak_log\",\"count\":3},"
                + "{\"type\":\"vibe_check\",\"id\":\"vibes\"}"
                + "]}]}");
        PlanDocument d = PlanParser.parse("g", args, 0L);
        assertEquals(1, d.subGoals.get(0).criteria.size());
        assertEquals("has_item", d.subGoals.get(0).criteria.get(0).type);
    }

    @Test
    public void clampsToTwelveSubGoals() {
        StringBuilder sb = new StringBuilder("{\"sub_goals\":[");
        for (int i = 0; i < 15; i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"title\":\"t").append(i).append("\",\"instruction\":\"i").append(i).append("\"}");
        }
        sb.append("]}");
        PlanDocument d = PlanParser.parse("g", json(sb.toString()), 0L);
        assertEquals(12, d.subGoals.size());
    }

    @Test
    public void missingInstructionFallsBackToTitle() {
        PlanDocument d = PlanParser.parse("g",
                json("{\"sub_goals\":[{\"title\":\"Get a stone pickaxe\"}]}"), 0L);
        assertEquals("Get a stone pickaxe", d.subGoals.get(0).instruction);
    }

    @Test
    public void missingTitleFallsBackToInstruction() {
        PlanDocument d = PlanParser.parse("g",
                json("{\"sub_goals\":[{\"instruction\":\"mine 3 iron ore and smelt them\"}]}"), 0L);
        assertTrue(d.subGoals.get(0).title.startsWith("mine 3 iron ore"));
    }

    @Test
    public void uselessEntriesAreSkipped() {
        PlanDocument d = PlanParser.parse("g",
                json("{\"sub_goals\":[{\"criteria\":[]},{\"title\":\"real\",\"instruction\":\"work\"}]}"), 0L);
        assertEquals(1, d.subGoals.size());
        assertEquals("real", d.subGoals.get(0).title);
    }

    @Test
    public void nullArgsFallBackToSingleSubGoalPlan() {
        PlanDocument d = PlanParser.parse("get full diamond armor", null, 9L);
        assertEquals(1, d.subGoals.size());
        assertEquals("get full diamond armor", d.subGoals.get(0).instruction);
        assertTrue(d.subGoals.get(0).criteria.isEmpty());
        assertEquals(9L, d.createdAt);
    }

    @Test
    public void emptySubGoalsFallBackToSingleSubGoalPlan() {
        PlanDocument d = PlanParser.parse("build a house", json("{\"sub_goals\":[]}"), 0L);
        assertEquals(1, d.subGoals.size());
        assertEquals("build a house", d.subGoals.get(0).instruction);
    }

    @Test
    public void longTitleIsTruncatedForTheHud() {
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 30; i++) big.append("very ");
        PlanDocument d = PlanParser.parse("g",
                json("{\"sub_goals\":[{\"title\":\"" + big + "long\",\"instruction\":\"i\"}]}"), 0L);
        assertTrue("HUD titles stay short", d.subGoals.get(0).title.length() <= 64);
    }
}
