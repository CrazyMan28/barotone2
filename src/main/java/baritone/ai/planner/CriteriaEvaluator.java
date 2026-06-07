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
import java.util.Map;

/**
 * The verification gate. A sub-agent's "done" claim only counts when every criterion holds
 * against the real {@link StateSnapshot} — this (not prompt guidance) is what stops the
 * wooden-pickaxe → straight-to-diamonds runs. Pure Java, unit-tested, no Minecraft.
 *
 * Tool and armor asks are tier-or-better: requesting a stone_pickaxe is satisfied by iron,
 * but never by gold (wood-level harvest — see {@link ToolTiers}).
 */
public final class CriteriaEvaluator {

    private CriteriaEvaluator() {}

    public static final class Result {
        public final boolean met;
        public final List<String> unmet;

        Result(List<String> unmet) {
            this.unmet = unmet;
            this.met = unmet.isEmpty();
        }
    }

    public static Result evaluate(List<SuccessCriterion> criteria, StateSnapshot s) {
        List<String> unmet = new ArrayList<>();
        if (criteria == null || criteria.isEmpty()) {
            return new Result(unmet);
        }
        for (SuccessCriterion c : criteria) {
            if (c == null || c.type == null) {
                continue;
            }
            switch (c.type) {
                case "has_item": {
                    int want = Math.max(1, c.count);
                    int have = countTierOrBetter(c.id, s);
                    if (have < want) {
                        unmet.add("has_item " + ToolTiers.strip(c.id) + ">=" + want + " (have " + have + ")");
                    }
                    break;
                }
                case "food_min": {
                    if (s.food < c.count) {
                        unmet.add("food>=" + c.count + " (have " + s.food + ")");
                    }
                    break;
                }
                case "has_station": {
                    if (!s.stationTypes.contains(ToolTiers.strip(c.id))) {
                        unmet.add("has_station " + ToolTiers.strip(c.id) + " (none known)");
                    }
                    break;
                }
                case "best_pickaxe_min": {
                    if (!toolTierAtLeast(s.bestPickaxe, c.id)) {
                        unmet.add("best_pickaxe>=" + ToolTiers.strip(c.id) + " (have " + s.bestPickaxe + ")");
                    }
                    break;
                }
                case "best_axe_min": {
                    if (!toolTierAtLeast(s.bestAxe, c.id)) {
                        unmet.add("best_axe>=" + ToolTiers.strip(c.id) + " (have " + s.bestAxe + ")");
                    }
                    break;
                }
                case "armor_equipped": {
                    String slot = c.slot == null ? "" : c.slot;
                    String equipped = s.armorEquipped.get(slot);
                    String askMaterial = ToolTiers.armorMaterial(c.id) != null
                            ? ToolTiers.armorMaterial(c.id)
                            : ToolTiers.strip(c.id);
                    int haveRank = ToolTiers.armorRank(ToolTiers.armorMaterial(equipped));
                    if (haveRank < ToolTiers.armorRank(askMaterial)) {
                        unmet.add("armor_equipped " + slot + ">=" + askMaterial
                                + " (have " + (equipped == null ? "nothing" : ToolTiers.strip(equipped)) + ")");
                    }
                    break;
                }
                case "reached_y_at_most": {
                    if (s.y > c.count) {
                        unmet.add("y<=" + c.count + " (at y=" + (s.y == Integer.MAX_VALUE ? "unknown" : s.y) + ")");
                    }
                    break;
                }
                default:
                    // unknown types are dropped at parse time; never block on a straggler
                    break;
            }
        }
        return new Result(unmet);
    }

    /** Inventory count of `id`, where tool/armor asks accept any same-type piece of >= tier. */
    private static int countTierOrBetter(String id, StateSnapshot s) {
        String askType = ToolTiers.toolType(id);
        if (askType != null) {
            int askRank = ToolTiers.rank(ToolTiers.toolMaterial(id));
            int have = 0;
            for (Map.Entry<String, Integer> e : s.inventoryTotals.entrySet()) {
                if (askType.equals(ToolTiers.toolType(e.getKey()))
                        && ToolTiers.rank(ToolTiers.toolMaterial(e.getKey())) >= askRank) {
                    have += e.getValue();
                }
            }
            return have;
        }
        String askSlot = ToolTiers.armorSlot(id);
        if (askSlot != null) {
            int askRank = ToolTiers.armorRank(ToolTiers.armorMaterial(id));
            int have = 0;
            for (Map.Entry<String, Integer> e : s.inventoryTotals.entrySet()) {
                if (askSlot.equals(ToolTiers.armorSlot(e.getKey()))
                        && ToolTiers.armorRank(ToolTiers.armorMaterial(e.getKey())) >= askRank) {
                    have += e.getValue();
                }
            }
            return have;
        }
        // tolerate either key shape ("cobblestone" / "minecraft:cobblestone") in the map
        String want = ToolTiers.strip(id);
        int have = 0;
        for (Map.Entry<String, Integer> e : s.inventoryTotals.entrySet()) {
            if (want.equals(ToolTiers.strip(e.getKey()))) {
                have += e.getValue();
            }
        }
        return have;
    }

    /** Does the held best tool ("minecraft:iron_pickaxe" / "none") meet a material ask ("stone")? */
    private static boolean toolTierAtLeast(String bestToolId, String askId) {
        String askMaterial = ToolTiers.toolMaterial(askId) != null
                ? ToolTiers.toolMaterial(askId)
                : ToolTiers.strip(askId);
        return ToolTiers.rank(ToolTiers.toolMaterial(bestToolId)) >= ToolTiers.rank(askMaterial);
    }
}
