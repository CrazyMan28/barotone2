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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class MissionQueueTest {

    @Before
    public void reset() {
        MissionQueue.resetForTests();
    }

    @Test
    public void enqueuePreservesOrderAndDescription() {
        MissionQueue.Mission first = MissionQueue.enqueue("get logs", true, "goal");
        MissionQueue.Mission second = MissionQueue.enqueue("craft pickaxe", true, "goal");

        MissionQueue.Snapshot snapshot = MissionQueue.snapshot();
        assertFalse(snapshot.paused);
        assertEquals(2, snapshot.pending.size());
        assertEquals(first.id, snapshot.pending.get(0).id);
        assertEquals(second.id, snapshot.pending.get(1).id);
        assertTrue(MissionQueue.describe().contains("2 pending"));
    }

    @Test
    public void pollNextMarksActiveAndFinishTracksLast() {
        MissionQueue.enqueue("get logs", true, "goal");

        MissionQueue.Mission running = MissionQueue.pollNext();
        assertNotNull(running);
        assertEquals(1, running.attempts);
        assertEquals(running.id, MissionQueue.snapshot().active.id);

        MissionQueue.finishActive(running.id, "Done: got logs");

        MissionQueue.Snapshot snapshot = MissionQueue.snapshot();
        assertNull(snapshot.active);
        assertNotNull(snapshot.lastFinished);
        assertEquals(running.id, snapshot.lastFinished.id);
        assertEquals("Done: got logs", snapshot.lastFinished.status);
    }

    @Test
    public void pauseActiveRequeuesFrontUntilResume() {
        MissionQueue.Mission created = MissionQueue.create("get diamonds", true, "goal");
        MissionQueue.Mission running = MissionQueue.markActive(created);

        assertEquals(running.id, MissionQueue.pauseAndRequeueActive().id);
        assertTrue(MissionQueue.finishActive(running.id, "Stopped: Cancelled"));

        MissionQueue.Snapshot paused = MissionQueue.snapshot();
        assertTrue(paused.paused);
        assertNull(paused.active);
        assertEquals(1, paused.pending.size());
        assertEquals(running.id, paused.pending.get(0).id);
        assertEquals(1, paused.pending.get(0).attempts);
        assertNull(MissionQueue.pollNext());

        MissionQueue.resume();
        MissionQueue.Mission resumed = MissionQueue.pollNext();
        assertNotNull(resumed);
        assertEquals(running.id, resumed.id);
        assertEquals(2, resumed.attempts);
    }

    @Test
    public void retryLastCreatesNewQueuedRetry() {
        MissionQueue.Mission running = MissionQueue.markActive(MissionQueue.create("farm wheat", true, "goal"));
        MissionQueue.finishActive(running.id, "Done");

        MissionQueue.Mission retry = MissionQueue.retryLast();

        assertNotNull(retry);
        assertNotEquals(running.id, retry.id);
        assertEquals("farm wheat", retry.goal);
        assertEquals(1, retry.attempts);
        assertEquals(retry.id, MissionQueue.snapshot().pending.get(0).id);
    }

    @Test
    public void clearPendingLeavesActiveMissionAlone() {
        MissionQueue.Mission running = MissionQueue.markActive(MissionQueue.create("active", true, "goal"));
        MissionQueue.enqueue("queued", true, "goal");

        assertEquals(1, MissionQueue.clearPending());

        MissionQueue.Snapshot snapshot = MissionQueue.snapshot();
        assertEquals(running.id, snapshot.active.id);
        assertTrue(snapshot.pending.isEmpty());
    }
}
