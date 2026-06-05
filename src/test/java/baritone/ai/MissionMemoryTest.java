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
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class MissionMemoryTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private Path memoryFile;

    @Before
    public void setUp() {
        memoryFile = temporaryFolder.getRoot().toPath().resolve("mission-memory.json");
        MissionMemory.setFileForTests(memoryFile);
    }

    @After
    public void tearDown() {
        MissionMemory.clearFileForTests();
    }

    @Test
    public void rememberLocationPersistsAcrossReload() {
        MissionMemory.rememberLocation("base", "Starter base", "location",
                "minecraft:overworld", 10, 64, -20, "test");

        assertTrue(Files.exists(memoryFile));

        MissionMemory.resetForTests();
        MissionMemory.Snapshot snapshot = MissionMemory.snapshot();

        assertEquals(1, snapshot.memories.size());
        MissionMemory.MemoryRecord memory = snapshot.memories.get(0);
        assertEquals("base", memory.key);
        assertEquals("location", memory.category);
        assertTrue(memory.hasPosition);
        assertEquals("minecraft:overworld", memory.dimension);
        assertEquals(10, memory.x);
        assertEquals(64, memory.y);
        assertEquals(-20, memory.z);
    }

    @Test
    public void recallFiltersByQueryAndCategory() {
        MissionMemory.remember("base", "Starter base near spawn", "location", "test", null);
        MissionMemory.remember("no_explore", "Player prefers no wandering", "preference", "test", null);

        String locationRecall = MissionMemory.recall("spawn", "location", false);
        String preferenceRecall = MissionMemory.recall("", "preference", false);

        assertTrue(locationRecall.contains("base"));
        assertFalse(locationRecall.contains("no_explore"));
        assertTrue(preferenceRecall.contains("no_explore"));
    }

    @Test
    public void forgetRemovesMemory() {
        MissionMemory.remember("wood_chest", "Logs and planks", "storage", "test", null);

        assertTrue(MissionMemory.forget("wood chest"));

        assertTrue(MissionMemory.snapshot().memories.isEmpty());
        assertFalse(MissionMemory.forget("wood_chest"));
    }

    @Test
    public void checkpointsAreBoundedNewestFirst() {
        for (int i = 0; i < MissionMemory.MAX_CHECKPOINTS + 5; i++) {
            MissionMemory.recordCheckpoint("goal " + i, "step" + i, "detail" + i, "ok");
        }

        MissionMemory.Snapshot snapshot = MissionMemory.snapshot();

        assertEquals(MissionMemory.MAX_CHECKPOINTS, snapshot.checkpoints.size());
        assertEquals(MissionMemory.MAX_CHECKPOINTS + 5, snapshot.checkpoints.get(0).id);
        assertEquals("goal " + (MissionMemory.MAX_CHECKPOINTS + 4), snapshot.checkpoints.get(0).missionGoal);
    }

    @Test
    public void summaryIncludesMemoryAndLastCheckpoint() {
        MissionMemory.remember("base", "Starter base", "location", "test", null);
        MissionMemory.recordCheckpoint("get logs", "mine", "Collected logs", "ok");

        String summary = MissionMemory.summaryForPrompt();

        assertTrue(summary.contains("base=Starter base"));
        assertTrue(summary.contains("last_checkpoint=mine:Collected logs"));
    }

    @Test
    public void goalHistoryPersistsDedupedAcrossReload() {
        MissionMemory.saveGoalHistory(Arrays.asList("mine diamonds", "build house", "mine diamonds"));

        MissionMemory.resetForTests();
        List<String> loaded = MissionMemory.loadGoalHistory();

        assertEquals(2, loaded.size());
        assertEquals("mine diamonds", loaded.get(0));
        assertEquals("build house", loaded.get(1));
    }

    @Test
    public void inFlightMissionRoundTripsAndClears() {
        MissionMemory.recordInFlightMission("get 10 jungle logs", true);

        MissionMemory.resetForTests();
        MissionMemory.InFlightMission inflight = MissionMemory.getInFlightMission();

        assertNotNull(inflight);
        assertEquals("get 10 jungle logs", inflight.goal);
        assertTrue(inflight.planMode);

        MissionMemory.clearInFlightMission();
        assertNull(MissionMemory.getInFlightMission());
    }

    @Test
    public void contextForGoalSurfacesRelevantMemoriesOnly() {
        MissionMemory.rememberLocation("diamond_spot", "Lots of diamonds here", "resource",
                "minecraft:overworld", 100, 12, 200, "test");
        MissionMemory.remember("wheat_farm", "Auto wheat farm by river", "farm", "test", null);

        String context = MissionMemory.contextForGoal("go find and mine diamonds", 6);

        assertTrue(context.contains("diamond_spot"));
        assertFalse(context.contains("wheat_farm"));
    }

    @Test
    public void contextForGoalEmptyWhenNoMemories() {
        assertEquals("", MissionMemory.contextForGoal("mine diamonds", 6));
    }

    @Test
    public void runtimeStorageDoesNotOverrideTestFile() {
        Path runtimeFile = temporaryFolder.getRoot().toPath().resolve("runtime-memory.json");

        MissionMemory.useStorageFile(runtimeFile);
        MissionMemory.remember("base", "Starter base", "location", "test", null);

        assertTrue(Files.exists(memoryFile));
        assertFalse(Files.exists(runtimeFile));
    }

    // --- Homestead station registry ---

    @Test
    public void rememberStationDedupesByTuple() {
        MissionMemory.rememberStation("crafting_table", "minecraft:overworld", 10, 64, -20);
        MissionMemory.rememberStation("crafting_table", "minecraft:overworld", 10, 64, -20); // same tuple
        List<MissionMemory.StationRecord> found =
                MissionMemory.findStations("crafting_table", "minecraft:overworld");
        assertEquals("same (type,dim,x,y,z) must not duplicate", 1, found.size());
        assertEquals(10, found.get(0).x);
        assertEquals(64, found.get(0).y);
        assertEquals(-20, found.get(0).z);
    }

    @Test
    public void findStationsFiltersByTypeAndDimension() {
        MissionMemory.rememberStation("crafting_table", "minecraft:overworld", 1, 64, 1);
        MissionMemory.rememberStation("furnace", "minecraft:overworld", 2, 64, 2);
        MissionMemory.rememberStation("crafting_table", "minecraft:the_nether", 3, 64, 3);
        assertEquals(1, MissionMemory.findStations("crafting_table", "minecraft:overworld").size());
        assertEquals(1, MissionMemory.findStations("furnace", "minecraft:overworld").size());
        // a Nether table must NOT be returned for an Overworld query
        assertEquals(1, MissionMemory.findStations("crafting_table", "minecraft:the_nether").size());
        assertEquals(0, MissionMemory.findStations("furnace", "minecraft:the_nether").size());
    }

    @Test
    public void forgetStationRemoves() {
        MissionMemory.rememberStation("furnace", "minecraft:overworld", 5, 70, 5);
        assertTrue(MissionMemory.forgetStation("furnace", "minecraft:overworld", 5, 70, 5));
        assertEquals(0, MissionMemory.findStations("furnace", "minecraft:overworld").size());
        assertFalse(MissionMemory.forgetStation("furnace", "minecraft:overworld", 5, 70, 5));
    }

    @Test
    public void stationsSurviveReload() {
        MissionMemory.rememberStation("crafting_table", "minecraft:overworld", 7, 63, 8);
        MissionMemory.rememberStation("furnace", "minecraft:overworld", 9, 63, 8);
        assertTrue(Files.exists(memoryFile));

        MissionMemory.resetForTests(); // drops in-memory state; next access reloads from disk
        List<MissionMemory.StationRecord> tables =
                MissionMemory.findStations("crafting_table", "minecraft:overworld");
        List<MissionMemory.StationRecord> furnaces =
                MissionMemory.findStations("furnace", "minecraft:overworld");
        assertEquals("stations must survive save/load (gson field-name check)", 1, tables.size());
        assertEquals(1, furnaces.size());
        assertEquals(7, tables.get(0).x);
    }
}
