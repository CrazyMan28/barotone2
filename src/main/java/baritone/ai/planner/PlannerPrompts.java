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

            CRITERIA ARE CHECKED AT THE END OF THE STEP, AFTER ALL CRAFTING — and crafting \
            CONSUMES its ingredients (1 coal + 1 stick -> 4 torches means the coal count DROPS; \
            logs -> planks -> tools means the LOG count drops to 0). Never pair a has_item count \
            on a raw material with crafting that consumes it: either require only the PRODUCT \
            (has_item torch >= 8, has_item wooden_pickaxe >= 1), or demand the LEFTOVER you \
            actually want and gather extra to cover what crafting eats. A "gather 3 logs" step \
            followed by a "craft tools" step that turns those logs into planks can NEVER verify \
            the log count — prefer ONE step "get logs and craft the tools" with the TOOLS as the \
            criteria, or set the gather step's criterion to the product (planks).

            SPECIES: the bot mines whatever trees/stone are nearby — it CANNOT target a species. \
            For materials that come in variants, use the GENERIC id: "log" (matches oak/spruce/ \
            birch/stem/wood — any), "planks" (any planks). NEVER write "spruce_log" or "oak_planks" \
            in a criterion — a species-specific id will never verify. Final tool/armor ids \
            (wooden_pickaxe, iron_ingot) are fine as-is.
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

                TRACE EVERY PREREQUISITE — work BACKWARD from the goal and include each dependency \
                as its own step. A target you can't make without first making something else MUST \
                have that something else as an earlier step. Worked example — "craft all stone \
                tools": stone tools need COBBLESTONE + STICKS + a crafting table; cobblestone must \
                be MINED, and mining it needs a WOODEN (or better) PICKAXE; the wooden pickaxe needs \
                PLANKS + STICKS; planks + sticks need LOGS. So the full chain is: get logs -> craft \
                a wooden pickaxe -> mine cobblestone (>= enough for all tools) -> craft the stone \
                tools (table + sticks + cobblestone). A plan that jumps from "gather logs" to "craft \
                stone tools" is WRONG — it skipped the pickaxe and the mining.

                COMPLETENESS SELF-CHECK before you submit: walk your steps in order and for EACH \
                one ask "are this step's inputs produced by an earlier step or already in the \
                current inventory, and is the tool needed to obtain them available by now?" If any \
                step needs cobblestone but nothing earlier yields a pickaxe to mine it, or needs \
                iron but nothing smelts it, your plan is INCOMPLETE — add the missing rungs and \
                check again. Do not submit a plan with a gap.

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
                trouble. Completed sub-goals stay done — do NOT include them again.

                CRITICAL: your new sub_goals must cover the COMPLETE remaining path from the current \
                state all the way to the MAIN GOAL — EVERY rung still needed, not just a retry of the \
                step that failed. The failed step is only where the bot is stuck on the ladder; the \
                goal still needs everything that came AFTER it too. Example: if "craft stone tools" \
                failed inside a "get iron tools" mission, the new plan is stone tools AND coal/torches \
                AND furnace AND smelt iron AND craft iron tools — do NOT omit the later rungs. \
                A plan that only fixes the failed step and drops the rest leaves the mission \
                permanently incomplete.

                Adapt to WHAT WENT WRONG:
                - failed with "Reached max iterations" / too much in one step -> SPLIT it into smaller \
                steps (e.g. "craft stone axe + shovel" then "craft stone sword + hoe"), each ~40 calls.
                - keeps dying -> add re-gear steps (food, armor, torches, safer route) BEFORE retrying \
                the step that killed it.

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
