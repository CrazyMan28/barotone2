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
import java.util.Collections;
import java.util.List;

/**
 * The whole decomposed mission: ordered {@link SubGoal}s plus the cursor of where we are.
 * Persisted to &lt;gameDir&gt;/baritone/active-plan.json by {@link PlannerStore} so
 * `ai recover` can resume mid-plan after a crash/relaunch.
 *
 * Gson-serialized — ProGuard -keep rule required.
 */
public final class PlanDocument {

    public int version = 1;

    /** The user's original goal ("get full diamond armor"). */
    public String mainGoal;

    /** The planner LLM's think-first rationale (kept for replans + diagnostics). */
    public String reasoning;

    public List<SubGoal> subGoals = new ArrayList<>();

    /** Optional whole-mission gate checked after the last sub-goal. */
    public List<SuccessCriterion> finalCriteria = new ArrayList<>();

    /** Index of the sub-goal in progress (== subGoals.size() when done). */
    public int cursor;

    /** Replans spent (capped by aiPlannerMaxReplans). */
    public int replans;

    public long createdAt;
    public long updatedAt;

    /** The sub-goal in progress, or null when the plan is exhausted. */
    public SubGoal currentSubGoal() {
        if (subGoals == null || cursor < 0 || cursor >= subGoals.size()) {
            return null;
        }
        return subGoals.get(cursor);
    }

    /** The work left to do: the current sub-goal (if unfinished) and everything after it. */
    public List<SubGoal> remainingSubGoals() {
        if (subGoals == null || cursor >= subGoals.size()) {
            return Collections.emptyList();
        }
        return subGoals.subList(Math.max(0, cursor), subGoals.size());
    }

    /** True when every sub-goal has been walked past. */
    public boolean isComplete() {
        return subGoals == null || cursor >= subGoals.size();
    }
}
