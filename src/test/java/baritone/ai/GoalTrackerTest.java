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

package baritone.ai;

import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GoalTrackerTest {

    @Before
    public void reset() {
        GoalTracker.resetForTests();
    }

    @Test
    public void startShowsActiveVisibleGoal() {
        GoalTracker.start("get logs", true);

        GoalTracker.Snapshot snapshot = GoalTracker.snapshot();
        assertTrue(snapshot.visible);
        assertTrue(snapshot.active);
        assertTrue(snapshot.planMode);
        assertEquals("get logs", snapshot.goal);
        assertEquals("Planning...", snapshot.status);
        assertEquals("get logs", GoalTracker.lastGoal());
    }

    @Test
    public void setPlanPopulatesVisibleSteps() {
        GoalTracker.start("get logs", true);
        GoalTracker.setPlan(Arrays.asList("Find trees", "", "Mine logs"));

        GoalTracker.Snapshot snapshot = GoalTracker.snapshot();
        assertEquals("Plan ready", snapshot.status);
        assertEquals(2, snapshot.steps.size());
        assertEquals("Find trees", snapshot.steps.get(0).text);
        assertFalse(snapshot.steps.get(0).done);
        assertEquals("Mine logs", snapshot.steps.get(1).text);
    }

    @Test
    public void failPreservesGoalAndShowsStoppedStatus() {
        GoalTracker.start("get logs", true);
        GoalTracker.fail("Mistral API key is not set");

        GoalTracker.Snapshot snapshot = GoalTracker.snapshot();
        assertTrue(snapshot.visible);
        assertFalse(snapshot.active);
        assertEquals("get logs", snapshot.goal);
        assertEquals("Stopped: Mistral API key is not set", snapshot.status);
    }

    @Test
    public void togglingFreshTrackerShowsIdleStatus() {
        assertTrue(GoalTracker.toggleVisible());

        GoalTracker.Snapshot snapshot = GoalTracker.snapshot();
        assertTrue(snapshot.visible);
        assertFalse(snapshot.active);
        assertEquals("", snapshot.goal);
        assertEquals("No AI goal yet", snapshot.status);
        assertTrue(GoalTracker.describe().contains("No active AI goal"));
    }
}
