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

import com.google.gson.Gson;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The persisted plan: cursor bookkeeping + gson round-trip (it lives in active-plan.json
 *  across relaunches, so serialization fidelity is load-bearing for `ai recover`). */
public class PlanDocumentTest {

    private static PlanDocument plan(int subGoals) {
        PlanDocument d = new PlanDocument();
        d.mainGoal = "get full diamond armor";
        d.reasoning = "tech ladder first";
        d.subGoals = new ArrayList<>();
        for (int i = 0; i < subGoals; i++) {
            SubGoal g = new SubGoal();
            g.title = "step " + (i + 1);
            g.instruction = "do step " + (i + 1);
            SuccessCriterion c = new SuccessCriterion();
            c.type = "has_item";
            c.id = "oak_log";
            c.count = i + 1;
            g.criteria = new ArrayList<>(Collections.singletonList(c));
            d.subGoals.add(g);
        }
        return d;
    }

    @Test
    public void cursorWalksTheSubGoals() {
        PlanDocument d = plan(3);
        assertEquals("step 1", d.currentSubGoal().title);
        assertFalse(d.isComplete());

        d.cursor = 2;
        assertEquals("step 3", d.currentSubGoal().title);
        assertEquals(1, d.remainingSubGoals().size());

        d.cursor = 3;
        assertNull(d.currentSubGoal());
        assertTrue(d.isComplete());
        assertTrue(d.remainingSubGoals().isEmpty());
    }

    @Test
    public void emptyPlanIsComplete() {
        PlanDocument d = plan(0);
        assertTrue(d.isComplete());
        assertNull(d.currentSubGoal());
    }

    @Test
    public void gsonRoundTripKeepsEverything() {
        PlanDocument d = plan(2);
        d.cursor = 1;
        d.replans = 3;
        d.subGoals.get(0).complete = true;
        d.subGoals.get(0).deaths = 2;
        d.subGoals.get(0).verifyBounces = 1;

        Gson gson = new Gson();
        PlanDocument back = gson.fromJson(gson.toJson(d), PlanDocument.class);

        assertEquals(d.mainGoal, back.mainGoal);
        assertEquals(d.reasoning, back.reasoning);
        assertEquals(d.cursor, back.cursor);
        assertEquals(d.replans, back.replans);
        assertEquals(2, back.subGoals.size());
        assertTrue(back.subGoals.get(0).complete);
        assertEquals(2, back.subGoals.get(0).deaths);
        assertEquals(1, back.subGoals.get(0).verifyBounces);
        assertEquals("has_item", back.subGoals.get(0).criteria.get(0).type);
        assertEquals("oak_log", back.subGoals.get(0).criteria.get(0).id);
        assertEquals(1, back.subGoals.get(0).criteria.get(0).count);
    }
}
