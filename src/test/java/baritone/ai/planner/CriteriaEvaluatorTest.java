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

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The verification gate: a sub-agent's "done" claim is only believed if these checks pass
 *  against the real inventory/state. This is what stops wooden-pick → diamond YOLO runs. */
public class CriteriaEvaluatorTest {

    private static StateSnapshot snap() {
        StateSnapshot s = new StateSnapshot();
        s.food = 20;
        s.y = 64;
        return s;
    }

    private static SuccessCriterion c(String type, String id, int count) {
        SuccessCriterion sc = new SuccessCriterion();
        sc.type = type;
        sc.id = id;
        sc.count = count;
        return sc;
    }

    private static List<SuccessCriterion> one(SuccessCriterion sc) {
        return Collections.singletonList(sc);
    }

    @Test
    public void emptyCriteriaAreMet() {
        assertTrue(CriteriaEvaluator.evaluate(Collections.emptyList(), snap()).met);
        assertTrue(CriteriaEvaluator.evaluate(null, snap()).met);
    }

    @Test
    public void plainItemCountsExactly() {
        StateSnapshot s = snap();
        s.inventoryTotals.put("oak_log", 2);
        CriteriaEvaluator.Result r = CriteriaEvaluator.evaluate(one(c("has_item", "minecraft:oak_log", 3)), s);
        assertFalse(r.met);
        assertTrue("unmet should say how many we have: " + r.unmet, r.unmet.get(0).contains("have 2"));

        s.inventoryTotals.put("oak_log", 3);
        assertTrue(CriteriaEvaluator.evaluate(one(c("has_item", "minecraft:oak_log", 3)), s).met);
    }

    @Test
    public void prefixIsNormalizedBothWays() {
        StateSnapshot s = snap();
        s.inventoryTotals.put("minecraft:cobblestone", 10);
        assertTrue(CriteriaEvaluator.evaluate(one(c("has_item", "cobblestone", 5)), s).met);

        StateSnapshot s2 = snap();
        s2.inventoryTotals.put("cobblestone", 10);
        assertTrue(CriteriaEvaluator.evaluate(one(c("has_item", "minecraft:cobblestone", 5)), s2).met);
    }

    @Test
    public void betterToolSatisfiesLowerTierAsk() {
        StateSnapshot s = snap();
        s.inventoryTotals.put("iron_pickaxe", 1);
        assertTrue(CriteriaEvaluator.evaluate(one(c("has_item", "minecraft:stone_pickaxe", 1)), s).met);
    }

    @Test
    public void goldenToolDoesNotSatisfyIronAsk() {
        StateSnapshot s = snap();
        s.inventoryTotals.put("golden_pickaxe", 1);
        CriteriaEvaluator.Result r = CriteriaEvaluator.evaluate(one(c("has_item", "iron_pickaxe", 1)), s);
        assertFalse("golden pickaxe harvests at wood level — must not pass an iron gate", r.met);
    }

    @Test
    public void betterArmorPieceSatisfiesLowerAsk() {
        StateSnapshot s = snap();
        s.inventoryTotals.put("diamond_chestplate", 1);
        assertTrue(CriteriaEvaluator.evaluate(one(c("has_item", "iron_chestplate", 1)), s).met);
    }

    @Test
    public void foodMin() {
        StateSnapshot s = snap();
        s.food = 6;
        assertFalse(CriteriaEvaluator.evaluate(one(c("food_min", null, 8)), s).met);
        s.food = 8;
        assertTrue(CriteriaEvaluator.evaluate(one(c("food_min", null, 8)), s).met);
    }

    @Test
    public void hasStation() {
        StateSnapshot s = snap();
        s.stationTypes.add("crafting_table");
        assertTrue(CriteriaEvaluator.evaluate(one(c("has_station", "crafting_table", 0)), s).met);
        assertFalse(CriteriaEvaluator.evaluate(one(c("has_station", "furnace", 0)), s).met);
    }

    @Test
    public void bestPickaxeMin() {
        StateSnapshot s = snap();
        s.bestPickaxe = "minecraft:iron_pickaxe";
        assertTrue(CriteriaEvaluator.evaluate(one(c("best_pickaxe_min", "stone", 0)), s).met);
        assertTrue(CriteriaEvaluator.evaluate(one(c("best_pickaxe_min", "iron", 0)), s).met);
        assertFalse(CriteriaEvaluator.evaluate(one(c("best_pickaxe_min", "diamond", 0)), s).met);

        s.bestPickaxe = "none";
        assertFalse(CriteriaEvaluator.evaluate(one(c("best_pickaxe_min", "stone", 0)), s).met);

        s.bestPickaxe = "minecraft:golden_pickaxe";
        assertFalse("gold is wood-level", CriteriaEvaluator.evaluate(one(c("best_pickaxe_min", "iron", 0)), s).met);
    }

    @Test
    public void bestAxeMin() {
        StateSnapshot s = snap();
        s.bestAxe = "minecraft:stone_axe";
        assertTrue(CriteriaEvaluator.evaluate(one(c("best_axe_min", "wooden", 0)), s).met);
        assertFalse(CriteriaEvaluator.evaluate(one(c("best_axe_min", "iron", 0)), s).met);
    }

    @Test
    public void armorEquippedTierOrBetter() {
        StateSnapshot s = snap();
        s.armorEquipped.put("chest", "minecraft:diamond_chestplate");

        SuccessCriterion ask = c("armor_equipped", "iron", 0);
        ask.slot = "chest";
        assertTrue("diamond on chest satisfies an iron ask", CriteriaEvaluator.evaluate(one(ask), s).met);

        SuccessCriterion askDiamond = c("armor_equipped", "diamond", 0);
        askDiamond.slot = "chest";
        assertTrue(CriteriaEvaluator.evaluate(one(askDiamond), s).met);

        s.armorEquipped.put("chest", "minecraft:iron_chestplate");
        assertFalse("iron on chest fails a diamond ask", CriteriaEvaluator.evaluate(one(askDiamond), s).met);

        SuccessCriterion askHead = c("armor_equipped", "iron", 0);
        askHead.slot = "head";
        assertFalse("empty slot fails", CriteriaEvaluator.evaluate(one(askHead), s).met);
    }

    @Test
    public void armorEquippedAcceptsFullItemIdAsk() {
        StateSnapshot s = snap();
        s.armorEquipped.put("feet", "minecraft:diamond_boots");
        SuccessCriterion ask = c("armor_equipped", "minecraft:diamond_boots", 0);
        ask.slot = "feet";
        assertTrue(CriteriaEvaluator.evaluate(one(ask), s).met);
    }

    @Test
    public void reachedYAtMost() {
        StateSnapshot s = snap();
        s.y = -58;
        assertTrue(CriteriaEvaluator.evaluate(one(c("reached_y_at_most", null, -50)), s).met);
        s.y = 12;
        assertFalse(CriteriaEvaluator.evaluate(one(c("reached_y_at_most", null, -50)), s).met);
    }

    @Test
    public void allUnmetCriteriaAreListed() {
        StateSnapshot s = snap();
        s.food = 0;
        CriteriaEvaluator.Result r = CriteriaEvaluator.evaluate(Arrays.asList(
                c("has_item", "iron_ingot", 3),
                c("food_min", null, 8)
        ), s);
        assertFalse(r.met);
        assertEquals(2, r.unmet.size());
    }

    @Test
    public void unknownCriterionTypeIsIgnored() {
        // parse-time already drops unknown types; the evaluator must not block on stragglers
        assertTrue(CriteriaEvaluator.evaluate(one(c("fly_to_the_moon", "moon", 1)), snap()).met);
    }

    @Test
    public void hasItemDefaultsCountToOne() {
        StateSnapshot s = snap();
        s.inventoryTotals.put("furnace", 1);
        assertTrue(CriteriaEvaluator.evaluate(one(c("has_item", "furnace", 0)), s).met);
    }
}
