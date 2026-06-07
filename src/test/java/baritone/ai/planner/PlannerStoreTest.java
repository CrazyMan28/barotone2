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

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** active-plan.json survives crashes/relaunches so `ai recover` can resume mid-plan. */
public class PlannerStoreTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private Path planFile;

    @Before
    public void setUp() {
        planFile = temporaryFolder.getRoot().toPath().resolve("active-plan.json");
        PlannerStore.setFileForTests(planFile);
    }

    @After
    public void tearDown() {
        PlannerStore.clearFileForTests();
    }

    private static PlanDocument plan() {
        PlanDocument d = new PlanDocument();
        d.mainGoal = "get full diamond armor";
        d.cursor = 1;
        SubGoal g = new SubGoal();
        g.title = "Wooden pickaxe";
        g.complete = true;
        SuccessCriterion c = new SuccessCriterion();
        c.type = "has_item";
        c.id = "wooden_pickaxe";
        c.count = 1;
        g.criteria = new ArrayList<>(Collections.singletonList(c));
        d.subGoals.add(g);
        return d;
    }

    @Test
    public void saveThenLoadRoundTrips() {
        PlannerStore.save(plan());
        assertTrue(Files.exists(planFile));

        PlanDocument back = PlannerStore.load();
        assertEquals("get full diamond armor", back.mainGoal);
        assertEquals(1, back.cursor);
        assertTrue(back.subGoals.get(0).complete);
        assertEquals("has_item", back.subGoals.get(0).criteria.get(0).type);
    }

    @Test
    public void loadWithoutFileIsNull() {
        assertNull(PlannerStore.load());
    }

    @Test
    public void clearRemovesThePlan() {
        PlannerStore.save(plan());
        PlannerStore.clear();
        assertFalse(Files.exists(planFile));
        assertNull(PlannerStore.load());
    }

    @Test
    public void corruptFileLoadsAsNullNotThrow() throws Exception {
        Files.write(planFile, "{not json!!".getBytes(StandardCharsets.UTF_8));
        assertNull(PlannerStore.load());
    }
}
