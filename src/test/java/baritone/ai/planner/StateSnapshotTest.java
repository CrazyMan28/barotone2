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

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Parsing the get_state JSON into the typed view the evaluator consumes.
 *  Field names here mirror BaritoneTools.getStateOnClient exactly. */
public class StateSnapshotTest {

    @Test
    public void parsesCannedGetStateJson() {
        JsonObject j = JsonParser.parseString("{"
                + "\"position\":\"100,-58,200\","
                + "\"food\":14,"
                + "\"best_pickaxe\":\"minecraft:iron_pickaxe\","
                + "\"best_axe\":\"minecraft:stone_axe\","
                + "\"inventory_totals\":{\"minecraft:oak_log\":7,\"cobblestone\":32},"
                + "\"known_stations\":\"crafting_table@10,64,20; furnace@11,64,20\","
                + "\"armor_equipped\":{\"chest\":\"minecraft:iron_chestplate\",\"feet\":\"minecraft:leather_boots\"}"
                + "}").getAsJsonObject();

        StateSnapshot s = StateSnapshot.fromStateJson(j);

        assertEquals(100, s.x);
        assertEquals(-58, s.y);
        assertEquals(200, s.z);
        assertEquals(14, s.food);
        assertEquals("minecraft:iron_pickaxe", s.bestPickaxe);
        assertEquals("minecraft:stone_axe", s.bestAxe);
        // ids normalized (minecraft: stripped) so the evaluator has one key shape
        assertEquals(7, (int) s.inventoryTotals.get("oak_log"));
        assertEquals(32, (int) s.inventoryTotals.get("cobblestone"));
        assertTrue(s.stationTypes.contains("crafting_table"));
        assertTrue(s.stationTypes.contains("furnace"));
        assertEquals("minecraft:iron_chestplate", s.armorEquipped.get("chest"));
        assertEquals("minecraft:leather_boots", s.armorEquipped.get("feet"));
    }

    @Test
    public void missingFieldsDefaultSafely() {
        StateSnapshot s = StateSnapshot.fromStateJson(new JsonObject());
        assertEquals(0, s.food);
        assertEquals("none", s.bestPickaxe);
        assertEquals("none", s.bestAxe);
        assertTrue(s.inventoryTotals.isEmpty());
        assertTrue(s.stationTypes.isEmpty());
        assertTrue(s.armorEquipped.isEmpty());
        // unknown y must never satisfy a "dig down to y<=N" gate
        assertTrue(s.y == Integer.MAX_VALUE);
    }

    @Test
    public void emptyStationsStringYieldsNoStations() {
        JsonObject j = new JsonObject();
        j.addProperty("known_stations", "");
        assertTrue(StateSnapshot.fromStateJson(j).stationTypes.isEmpty());
    }
}
