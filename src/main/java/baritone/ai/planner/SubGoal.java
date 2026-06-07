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

import java.util.ArrayList;
import java.util.List;

/**
 * One rung of the decomposed mission ("Get a stone pickaxe"). A fresh sub-agent conversation
 * executes {@link #instruction}; the planner only advances when every {@link #criteria} entry
 * holds against real game state.
 *
 * Gson-serialized (LLM tool args + active-plan.json) — ProGuard -keep rule required.
 */
public final class SubGoal {

    /** Short HUD/checkbox label, <= ~60 chars. */
    public String title;

    /** The focused prompt body the sub-agent runs with. */
    public String instruction;

    /** ALL must hold for the sub-goal to count as done. */
    public List<SuccessCriterion> criteria = new ArrayList<>();

    /** Verified-complete (set by the planner after criteria pass, persisted for resume). */
    public boolean complete;

    /** "You claimed done but X is missing" bounces spent on this sub-goal. */
    public int verifyBounces;

    /** Deaths while pursuing this sub-goal — drives the recover-vs-replan policy. */
    public int deaths;

    /** Sub-agent runs spent on this sub-goal. */
    public int attempts;
}
