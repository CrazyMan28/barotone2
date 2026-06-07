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
 * One machine-checkable success condition for a sub-goal. Produced by the planner LLM,
 * evaluated programmatically by {@link CriteriaEvaluator} against real game state —
 * the sub-agent's "done" claim is never taken on faith.
 *
 * Gson-serialized (LLM tool args + active-plan.json) — field names are protected by a
 * ProGuard -keep rule in scripts/proguard.pro.
 *
 * Known types: has_item, food_min, has_station, best_pickaxe_min, best_axe_min,
 * armor_equipped, reached_y_at_most. Unknown types are dropped at parse time.
 */
public final class SuccessCriterion {

    /** Criterion kind — see class doc for the closed set. */
    public String type;

    /** Item/station/material id. Tool/armor asks are tier-or-better ("stone_pickaxe" is
     *  satisfied by iron); for armor_equipped this is a material ("diamond") or full item id. */
    public String id;

    /** has_item: required count (0 → 1). food_min: required food level. reached_y_at_most: the y. */
    public int count;

    /** armor_equipped only: "head" | "chest" | "legs" | "feet". */
    public String slot;

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(type == null ? "?" : type);
        if (slot != null && !slot.isEmpty()) {
            sb.append(' ').append(slot);
        }
        if (id != null && !id.isEmpty()) {
            sb.append(' ').append(id);
        }
        if (count != 0) {
            sb.append(">=").append(count);
        }
        return sb.toString();
    }
}
