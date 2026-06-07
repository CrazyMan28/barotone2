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

import java.util.ArrayList;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * After a death, gear DROPS — so steps the planner already checked off ("made a stone pickaxe")
 * may no longer hold. This re-verifies completed steps against the real post-death inventory and
 * UN-checks the ones that are no longer satisfied, rewinding the cursor so the bot re-earns them
 * instead of trying to mine iron with the wooden pickaxe it lost.
 */
public class PlanReverifyTest {

    private static SubGoal step(String title, boolean complete, SuccessCriterion... criteria) {
        SubGoal g = new SubGoal();
        g.title = title;
        g.instruction = title;
        g.complete = complete;
        g.criteria = new ArrayList<>(java.util.Arrays.asList(criteria));
        return g;
    }

    private static SuccessCriterion crit(String type, String id) {
        SuccessCriterion c = new SuccessCriterion();
        c.type = type;
        c.id = id;
        return c;
    }

    private static PlanDocument plan(SubGoal... steps) {
        PlanDocument d = new PlanDocument();
        d.mainGoal = "get full diamond armor";
        d.subGoals = new ArrayList<>(java.util.Arrays.asList(steps));
        return d;
    }

    @Test
    public void unchecksAStepWhoseGearWasLostOnDeath() {
        PlanDocument d = plan(
                step("Wooden pickaxe", true, crit("has_item", "wooden_pickaxe")),
                step("Stone pickaxe", true, crit("best_pickaxe_min", "stone")),
                step("Mine iron", false, crit("has_item", "raw_iron")));
        d.cursor = 2;

        // post-death: lost everything — empty inventory snapshot
        StateSnapshot empty = new StateSnapshot();

        int unchecked = PlanReverify.afterDeath(d, empty);

        assertEquals(2, unchecked);
        assertFalse("lost wooden pickaxe -> unchecked", d.subGoals.get(0).complete);
        assertFalse("lost stone pickaxe -> unchecked", d.subGoals.get(1).complete);
        assertEquals("cursor rewinds to the earliest unfinished step", 0, d.cursor);
    }

    @Test
    public void keepsStepsStillSatisfiedAfterRecoveringDrops() {
        PlanDocument d = plan(
                step("Stone pickaxe", true, crit("best_pickaxe_min", "stone")),
                step("Food", true, crit("food_min", null)),
                step("Mine iron", false, crit("has_item", "raw_iron")));
        d.subGoals.get(1).criteria.get(0).count = 8;
        d.cursor = 2;

        // drops were recovered: still has the iron pickaxe (better than stone) and full food
        StateSnapshot ok = new StateSnapshot();
        ok.bestPickaxe = "minecraft:iron_pickaxe";
        ok.food = 20;

        int unchecked = PlanReverify.afterDeath(d, ok);

        assertEquals(0, unchecked);
        assertTrue(d.subGoals.get(0).complete);
        assertTrue(d.subGoals.get(1).complete);
        assertEquals("nothing lost -> cursor unchanged", 2, d.cursor);
    }

    @Test
    public void cursorOnlyMovesBackwardNeverForward() {
        PlanDocument d = plan(
                step("Wooden pickaxe", true, crit("has_item", "wooden_pickaxe")),
                step("Mine logs", false, crit("has_item", "oak_log")));
        d.cursor = 0; // mid-way through step 0 region; a death here must not skip ahead

        StateSnapshot stillHasPick = new StateSnapshot();
        stillHasPick.inventoryTotals.put("wooden_pickaxe", 1);

        PlanReverify.afterDeath(d, stillHasPick);
        assertEquals(0, d.cursor);
    }

    @Test
    public void emptyCriteriaStepStaysComplete() {
        PlanDocument d = plan(step("Scout area", true)); // no criteria
        d.cursor = 1;
        assertEquals(0, PlanReverify.afterDeath(d, new StateSnapshot()));
        assertTrue(d.subGoals.get(0).complete);
        assertEquals(1, d.cursor);
    }

    @Test
    public void partialLossRewindsToTheFirstLostStep() {
        PlanDocument d = plan(
                step("Crafting table", true, crit("has_station", "crafting_table")),
                step("Stone pickaxe", true, crit("best_pickaxe_min", "stone")),
                step("Iron pickaxe", true, crit("best_pickaxe_min", "iron")),
                step("Mine diamonds", false, crit("has_item", "diamond")));
        d.cursor = 3;

        // table survives (placed in world), but all tools dropped
        StateSnapshot s = new StateSnapshot();
        s.stationTypes.add("crafting_table");

        int unchecked = PlanReverify.afterDeath(d, s);
        assertEquals(2, unchecked);
        assertTrue("placed station is not lost on death", d.subGoals.get(0).complete);
        assertFalse(d.subGoals.get(1).complete);
        assertFalse(d.subGoals.get(2).complete);
        assertEquals("rewind to the first lost step (stone pickaxe), not step 0", 1, d.cursor);
    }
}
