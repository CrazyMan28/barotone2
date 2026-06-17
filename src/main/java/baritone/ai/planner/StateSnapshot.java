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

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The typed, Minecraft-free view of the player's situation that {@link CriteriaEvaluator}
 * verifies against. Filled directly on the client thread by BaritoneTools.snapshotForPlanner(),
 * or parsed from get_state JSON via {@link #fromStateJson} (tests / fallback).
 */
public final class StateSnapshot {

    /** Item id (namespace stripped, lowercase) → total count across the inventory. */
    public Map<String, Integer> inventoryTotals = new HashMap<>();

    /** Food level 0-20. */
    public int food;

    /** Player feet X (0 when unknown — only used for death-spot distance estimates). */
    public int x;

    /** Player feet Y. {@link Integer#MAX_VALUE} when unknown so a "dig to y<=N" gate never
     *  passes on missing data. */
    public int y = Integer.MAX_VALUE;

    /** Player feet Z (0 when unknown). */
    public int z;

    /** Current dimension id ("minecraft:overworld", "minecraft:the_nether", ...) or "unknown".
     *  Drives the cross-dimension recovery guard: a death whose drops are in another dimension is
     *  unreachable no matter how near the straight-line distance looks. */
    public String dimension = "unknown";

    /** Best pickaxe item id ("minecraft:iron_pickaxe") or "none" — same shape as get_state. */
    public String bestPickaxe = "none";

    /** Best axe item id or "none". */
    public String bestAxe = "none";

    /** Known station types ("crafting_table", "furnace", ...) from mission memory. */
    public Set<String> stationTypes = new HashSet<>();

    /** Equipped armor: slot ("head"/"chest"/"legs"/"feet") → item id. */
    public Map<String, String> armorEquipped = new HashMap<>();

    /** Parse a BaritoneTools get_state JSON object. Missing fields default safely. */
    public static StateSnapshot fromStateJson(JsonObject j) {
        StateSnapshot s = new StateSnapshot();
        if (j == null) {
            return s;
        }
        String position = asString(j, "position");
        if (position != null) {
            String[] parts = position.split(",");
            if (parts.length == 3) {
                try {
                    s.x = Integer.parseInt(parts[0].trim());
                    s.y = Integer.parseInt(parts[1].trim());
                    s.z = Integer.parseInt(parts[2].trim());
                } catch (NumberFormatException ignored) {}
            }
        }
        if (j.has("food") && j.get("food").isJsonPrimitive()) {
            try {
                s.food = j.get("food").getAsInt();
            } catch (RuntimeException ignored) {}
        }
        String dim = asString(j, "dimension");
        if (dim != null && !dim.isEmpty()) {
            s.dimension = dim;
        }
        String pick = asString(j, "best_pickaxe");
        if (pick != null && !pick.isEmpty()) {
            s.bestPickaxe = pick;
        }
        String axe = asString(j, "best_axe");
        if (axe != null && !axe.isEmpty()) {
            s.bestAxe = axe;
        }
        if (j.has("inventory_totals") && j.get("inventory_totals").isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : j.getAsJsonObject("inventory_totals").entrySet()) {
                try {
                    s.inventoryTotals.merge(ToolTiers.strip(e.getKey()), e.getValue().getAsInt(), Integer::sum);
                } catch (RuntimeException ignored) {}
            }
        }
        String stations = asString(j, "known_stations");
        if (stations != null && !stations.isEmpty()) {
            // MissionMemory.stationsForPrompt format: "crafting_table@10,64,20; furnace@11,64,20"
            for (String entry : stations.split(";")) {
                String type = entry.trim();
                int at = type.indexOf('@');
                if (at > 0) {
                    type = type.substring(0, at);
                }
                if (!type.isEmpty()) {
                    s.stationTypes.add(ToolTiers.strip(type));
                }
            }
        }
        if (j.has("armor_equipped") && j.get("armor_equipped").isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : j.getAsJsonObject("armor_equipped").entrySet()) {
                try {
                    s.armorEquipped.put(e.getKey(), e.getValue().getAsString());
                } catch (RuntimeException ignored) {}
            }
        }
        return s;
    }

    private static String asString(JsonObject j, String key) {
        if (!j.has(key) || !j.get(key).isJsonPrimitive()) {
            return null;
        }
        try {
            return j.get(key).getAsString();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
