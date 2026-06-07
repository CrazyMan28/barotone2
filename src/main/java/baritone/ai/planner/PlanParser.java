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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Defensive parser for the planner LLM's submit_plan arguments. A malformed response must
 * never block a mission: unknown criterion types are dropped, oversized plans are clamped,
 * and total garbage falls back to a single sub-goal that is just the whole main goal
 * (i.e. exactly today's non-hierarchical behavior).
 */
public final class PlanParser {

    private PlanParser() {}

    /** The closed criteria set {@link CriteriaEvaluator} understands. */
    static final Set<String> KNOWN_CRITERIA = new HashSet<>(Arrays.asList(
            "has_item", "food_min", "has_station", "best_pickaxe_min",
            "best_axe_min", "armor_equipped", "reached_y_at_most"));

    /** Hard cap on sub-goals per plan (HUD + sanity). */
    public static final int MAX_SUB_GOALS = 12;

    /** HUD checkbox labels stay short. */
    static final int MAX_TITLE_CHARS = 64;

    public static PlanDocument parse(String mainGoal, JsonObject args, long nowMillis) {
        PlanDocument doc = new PlanDocument();
        doc.mainGoal = mainGoal;
        doc.createdAt = nowMillis;
        doc.updatedAt = nowMillis;

        if (args != null) {
            doc.reasoning = optString(args, "reasoning");
            if (args.has("sub_goals") && args.get("sub_goals").isJsonArray()) {
                JsonArray arr = args.getAsJsonArray("sub_goals");
                for (JsonElement el : arr) {
                    if (doc.subGoals.size() >= MAX_SUB_GOALS) {
                        break;
                    }
                    SubGoal g = parseSubGoal(el);
                    if (g != null) {
                        doc.subGoals.add(g);
                    }
                }
            }
            if (args.has("final_criteria") && args.get("final_criteria").isJsonArray()) {
                for (JsonElement el : args.getAsJsonArray("final_criteria")) {
                    SuccessCriterion c = parseCriterion(el);
                    if (c != null) {
                        doc.finalCriteria.add(c);
                    }
                }
            }
        }

        if (doc.subGoals.isEmpty()) {
            // broken planner output → degrade gracefully to the old single-mission behavior
            SubGoal whole = new SubGoal();
            whole.title = truncate(mainGoal);
            whole.instruction = mainGoal;
            doc.subGoals.add(whole);
        }
        return doc;
    }

    private static SubGoal parseSubGoal(JsonElement el) {
        if (el == null || !el.isJsonObject()) {
            return null;
        }
        JsonObject o = el.getAsJsonObject();
        String title = optString(o, "title");
        String instruction = optString(o, "instruction");
        boolean noTitle = title == null || title.trim().isEmpty();
        boolean noInstruction = instruction == null || instruction.trim().isEmpty();
        if (noTitle && noInstruction) {
            return null;
        }
        SubGoal g = new SubGoal();
        g.instruction = noInstruction ? title : instruction;
        g.title = truncate(noTitle ? instruction : title);
        if (o.has("criteria") && o.get("criteria").isJsonArray()) {
            for (JsonElement ce : o.getAsJsonArray("criteria")) {
                SuccessCriterion c = parseCriterion(ce);
                if (c != null) {
                    g.criteria.add(c);
                }
            }
        }
        return g;
    }

    private static SuccessCriterion parseCriterion(JsonElement el) {
        if (el == null || !el.isJsonObject()) {
            return null;
        }
        JsonObject o = el.getAsJsonObject();
        String type = optString(o, "type");
        if (type == null || !KNOWN_CRITERIA.contains(type)) {
            return null; // unknown types are dropped, never fatal
        }
        SuccessCriterion c = new SuccessCriterion();
        c.type = type;
        c.id = optString(o, "id");
        c.slot = optString(o, "slot");
        if (o.has("count") && o.get("count").isJsonPrimitive()) {
            try {
                c.count = o.get("count").getAsInt();
            } catch (RuntimeException ignored) {}
        }
        return c;
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        String t = s.trim();
        return t.length() <= MAX_TITLE_CHARS ? t : t.substring(0, MAX_TITLE_CHARS - 3) + "...";
    }

    private static String optString(JsonObject o, String key) {
        if (!o.has(key) || !o.get(key).isJsonPrimitive()) {
            return null;
        }
        try {
            return o.get(key).getAsString();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
