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

import java.util.List;

/**
 * Prompt builders for the planner tier. Pure string assembly — unit-tested so the
 * load-bearing directives (think-first, criteria catalog, plan-tool ban for sub-agents)
 * can't silently disappear in a refactor.
 */
public final class PlannerPrompts {

    private PlannerPrompts() {}

    /** The criteria catalog shared by the decompose and replan system prompts. */
    private static final String CRITERIA_CATALOG = """
            Each sub-goal carries machine-checkable success criteria. The game VERIFIES these \
            against the real inventory after the executor claims done — pick objective, minimal \
            criteria. Available types (use EXACTLY these JSON shapes):
            - {"type":"has_item","id":"minecraft:iron_pickaxe","count":1} — inventory holds >= count. \
            Tools/armor are tier-or-better (asking stone_pickaxe is satisfied by iron). Gold counts as wood.
            - {"type":"food_min","count":8} — food bar at least count (max 20).
            - {"type":"has_station","id":"crafting_table"} — a crafting_table/furnace is placed and known.
            - {"type":"best_pickaxe_min","id":"iron"} — best pickaxe tier at least wooden|stone|iron|diamond|netherite.
            - {"type":"best_axe_min","id":"stone"} — same for axes.
            - {"type":"armor_equipped","slot":"chest","id":"diamond"} — armor WORN in slot \
            (head|chest|legs|feet) is at least material leather|golden|chainmail|iron|diamond|netherite.
            - {"type":"reached_y_at_most","count":-50} — the player has descended to y <= count.
            """;

    public static String decompositionSystemPrompt() {
        return """
                You are the mission PLANNER for a Minecraft survival bot. You do not act in the \
                world; you decompose the player's goal into ordered sub-goals that a smaller \
                executor agent will run one at a time with ~40 tool calls each.

                THINK FIRST. Fill the "reasoning" field BEFORE "sub_goals": reason step by step \
                about (1) what the goal actually requires, (2) what the CURRENT STATE message \
                ALREADY provides — read inventory_totals (the bot's WHOLE inventory), best_pickaxe, \
                best_axe, food, armor_equipped and known_stations before planning anything — \
                (3) only the MISSING rungs between current state and the goal, (4) the risks that \
                historically kill the bot and what insurance (food, torches, armor) the dangerous \
                steps need.

                THE PLAN MUST BE AS SMALL AS THE GOAL:
                - trivial gather goals ("get logs", "mine 20 cobblestone") = ONE sub-goal. \
                No ladder, no tools the goal doesn't need.
                - mid goals ("get an iron pickaxe") = only the rungs the inventory is missing.
                - deep goals ("full diamond armor") = the full REMAINING ladder plus insurance \
                before the dangerous steps.

                NEVER PLAN A STEP THE CURRENT STATE ALREADY SATISFIES. If inventory_totals shows \
                an iron pickaxe and food, do NOT plan wood/stone/iron steps — send the executor \
                straight at the target. If known_stations already lists a crafting_table or \
                furnace, never plan building another.

                THE TECH LADDER (for the MISSING rungs only — never skip a rung that is missing): \
                logs -> wooden pickaxe -> cobblestone -> stone tools -> coal + torches + FOOD -> \
                iron ore (needs stone pickaxe) -> smelt iron in a furnace -> iron pickaxe + iron \
                armor + bucket -> diamonds (need iron pickaxe, found at y ~ -58; bring food, \
                torches, and a water bucket for lava) -> diamond gear. Full diamond armor needs \
                24 diamonds. A golden pickaxe mines like wood — it cannot mine iron or diamonds.

                RULES:
                - 1 to 12 sub-goals, each sized for ~40 tool calls (one tier of the ladder, not three).
                - Insert survival insurance BEFORE risky steps only when the state shows it is \
                missing: food_min before long mining trips, iron armor before diamond hunting at depth.
                - Each sub-goal: a short "title" (<= 60 chars, shown as a checkbox), a focused \
                "instruction" for the executor, and 1-3 criteria.
                - Add "final_criteria" for the whole mission when the goal is concrete \
                (e.g. four armor_equipped diamond checks for "full diamond armor").
                - Respond ONLY by calling the submit_plan tool. No prose outside it.

                """ + CRITERIA_CATALOG;
    }

    public static String decompositionUserPrompt(String mainGoal, String memoryContext, String stateJson) {
        return "MAIN GOAL: " + mainGoal + "\n\n"
                + "CURRENT STATE (live get_state):\n" + safe(stateJson) + "\n\n"
                + "MISSION MEMORY (what the bot already knows about this world):\n" + safe(memoryContext) + "\n\n"
                + "Think in \"reasoning\" first, then call submit_plan with the ordered sub_goals.";
    }

    public static String replanSystemPrompt() {
        return """
                You are the mission PLANNER for a Minecraft survival bot, REVISING a plan that hit \
                trouble. Completed sub-goals stay done — do NOT include them again. Produce a fresh \
                ordered list covering ONLY the remaining work, adapted to what went wrong (if the \
                bot keeps dying: add re-gear steps — food, armor, torches, safer route — before \
                retrying the step that killed it).

                Read the fresh CURRENT STATE message first: inventory_totals is the bot's WHOLE \
                inventory. NEVER include a step the state ALREADY satisfies — tools, food, armor \
                or stations the bot still has do not need re-earning.

                THINK FIRST in "reasoning", then respond ONLY by calling submit_plan.

                """ + CRITERIA_CATALOG;
    }

    public static String replanUserPrompt(String mainGoal, PlanDocument plan, String reasonForReplan, String stateJson) {
        StringBuilder sb = new StringBuilder();
        sb.append("MAIN GOAL: ").append(mainGoal).append('\n');
        sb.append("WHAT WENT WRONG: ").append(safe(reasonForReplan)).append("\n\n");
        sb.append("PLAN SO FAR:\n");
        if (plan != null && plan.subGoals != null) {
            for (int i = 0; i < plan.subGoals.size(); i++) {
                SubGoal g = plan.subGoals.get(i);
                sb.append(g.complete ? "  [x] " : (i == plan.cursor ? "  [>] " : "  [ ] "))
                        .append(g.title);
                if (i == plan.cursor && g.deaths > 0) {
                    sb.append(" (deaths: ").append(g.deaths).append(')');
                }
                sb.append('\n');
            }
        }
        sb.append("\nCURRENT STATE (live get_state):\n").append(safe(stateJson)).append('\n');
        sb.append("\nCall submit_plan with the REMAINING work only (the [x] steps are done for good).");
        return sb.toString();
    }

    /** System preamble for a sub-agent executing one sub-goal. */
    public static String subGoalPreamble(String mainGoal, List<String> completedTitles, SubGoal current) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are executing ONE step of a larger mission. Main mission: ").append(mainGoal).append('\n');
        if (completedTitles != null && !completedTitles.isEmpty()) {
            sb.append("Already completed by earlier steps: ").append(String.join("; ", completedTitles)).append('\n');
        }
        sb.append("YOUR ONLY JOB RIGHT NOW: ").append(current.instruction).append('\n');
        if (current.criteria != null && !current.criteria.isEmpty()) {
            sb.append("Call done() the MOMENT all of these hold (the game verifies them — claiming done early just bounces you back):\n");
            for (SuccessCriterion c : current.criteria) {
                sb.append("  - ").append(c).append('\n');
            }
        } else {
            sb.append("Call done() when this step is finished.\n");
        }
        sb.append("LONG ACTIONS: start ONE action (mine/goto/explore), then call wait_until_idle and ")
                .append("check get_state. NEVER re-issue the same mine/goto call while it is still running — ")
                .append("re-issuing RESTARTS the process and wastes the whole run. When a criterion is ")
                .append("has_item X >= N, pass N as the mine quantity so mining stops by itself.\n");
        sb.append("Do NOT work ahead on later steps. ");
        sb.append("Do NOT call set_goal_plan, update_goal_status or complete_goal_step — the planner owns the plan display.");
        return sb.toString();
    }

    /** Preamble for the death-recovery interlude: sprint to the drops before they despawn. */
    public static String recoveryPreamble(int x, int y, int z, int secondsLeft) {
        return "EMERGENCY: you just died and respawned. ALL your items dropped at "
                + x + "," + y + "," + z + " and will DESPAWN in about " + secondsLeft + " seconds.\n"
                + "goto_coords there NOW (fastest route, ignore everything else), pick up every item, "
                + "then call done(). If the spot is unreachable or the items are already gone, call done() "
                + "and say so — do not wander.";
    }

    private static String safe(String s) {
        return s == null || s.isEmpty() ? "(none)" : s;
    }
}
