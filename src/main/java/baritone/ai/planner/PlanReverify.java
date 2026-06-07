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

/**
 * Re-verifies a plan's already-completed steps against the CURRENT inventory after a death.
 * Death drops everything the bot was carrying, so a step the planner checked off ("made a stone
 * pickaxe") can silently become false. Without this, the bot proceeds to "mine iron" holding the
 * wooden pickaxe (or fist) it just lost. Pure, Minecraft-free, unit-tested.
 */
public final class PlanReverify {

    private PlanReverify() {}

    /**
     * Un-check every completed sub-goal whose criteria no longer hold against {@code snap}, then
     * rewind the cursor to the earliest now-unfinished step (never forward). Mutates {@code plan}.
     *
     * @return how many steps were un-checked
     */
    public static int afterDeath(PlanDocument plan, StateSnapshot snap) {
        if (plan == null || plan.subGoals == null) {
            return 0;
        }
        int unchecked = 0;
        for (SubGoal g : plan.subGoals) {
            if (g.complete && !CriteriaEvaluator.evaluate(g.criteria, snap).met) {
                g.complete = false;
                unchecked++;
            }
        }
        // cursor moves back to the first unfinished step, but never skips ahead of where we were
        int firstUnfinished = 0;
        while (firstUnfinished < plan.subGoals.size() && plan.subGoals.get(firstUnfinished).complete) {
            firstUnfinished++;
        }
        if (firstUnfinished < plan.cursor) {
            plan.cursor = firstUnfinished;
        }
        return unchecked;
    }
}
