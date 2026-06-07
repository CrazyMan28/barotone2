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

import java.util.Locale;

/**
 * Pure tier ladders for "tier-or-better" verification. Minecraft-free on purpose
 * (same discipline as baritone.ai.reflex): the gotcha encoded here is that GOLDEN
 * tools harvest at WOOD level — a golden pickaxe cannot mine iron ore, so gold must
 * never satisfy a stone+ mining gate.
 */
public final class ToolTiers {

    private ToolTiers() {}

    private static final String[] TOOL_TYPES = {"pickaxe", "axe", "sword", "shovel", "hoe"};
    private static final String[] ARMOR_PIECES = {"helmet", "chestplate", "leggings", "boots"};

    /** Mining/harvest rank of a tool material. Unknown/null → -1 (below wooden). */
    public static int rank(String material) {
        if (material == null) {
            return -1;
        }
        switch (material.toLowerCase(Locale.ROOT)) {
            case "wooden":
            case "wood":
            case "golden": // gold harvests at wood level — the whole reason this class exists
            case "gold":
                return 0;
            case "stone":
                return 1;
            case "iron":
                return 2;
            case "diamond":
                return 3;
            case "netherite":
                return 4;
            default:
                return -1;
        }
    }

    /** Protection rank of an armor material. Unknown/null → -1. */
    public static int armorRank(String material) {
        if (material == null) {
            return -1;
        }
        switch (material.toLowerCase(Locale.ROOT)) {
            case "leather":
                return 0;
            case "golden":
            case "gold":
                return 1;
            case "chainmail":
                return 2;
            case "iron":
                return 3;
            case "diamond":
                return 4;
            case "netherite":
                return 5;
            default:
                return -1;
        }
    }

    /** "minecraft:iron_pickaxe" → "iron"; null when the id is not a tool. */
    public static String toolMaterial(String itemId) {
        String bare = strip(itemId);
        if (bare == null) {
            return null;
        }
        for (String type : TOOL_TYPES) {
            if (bare.endsWith("_" + type)) {
                return bare.substring(0, bare.length() - type.length() - 1);
            }
        }
        return null;
    }

    /** "minecraft:iron_pickaxe" → "pickaxe"; null when the id is not a tool. */
    public static String toolType(String itemId) {
        String bare = strip(itemId);
        if (bare == null) {
            return null;
        }
        for (String type : TOOL_TYPES) {
            if (bare.endsWith("_" + type)) {
                return type;
            }
        }
        return null;
    }

    /** "minecraft:diamond_chestplate" → "diamond"; null when the id is not an armor piece. */
    public static String armorMaterial(String itemId) {
        String bare = strip(itemId);
        if (bare == null) {
            return null;
        }
        for (String piece : ARMOR_PIECES) {
            if (bare.endsWith("_" + piece)) {
                return bare.substring(0, bare.length() - piece.length() - 1);
            }
        }
        return null;
    }

    /** "minecraft:diamond_chestplate" → "chest" (head/chest/legs/feet); null when not armor. */
    public static String armorSlot(String itemId) {
        String bare = strip(itemId);
        if (bare == null) {
            return null;
        }
        if (bare.endsWith("_helmet")) {
            return "head";
        }
        if (bare.endsWith("_chestplate")) {
            return "chest";
        }
        if (bare.endsWith("_leggings")) {
            return "legs";
        }
        if (bare.endsWith("_boots")) {
            return "feet";
        }
        return null;
    }

    /** Lowercase + strip a "minecraft:" namespace if present. */
    public static String strip(String id) {
        if (id == null) {
            return null;
        }
        String s = id.trim().toLowerCase(Locale.ROOT);
        int colon = s.indexOf(':');
        return colon >= 0 ? s.substring(colon + 1) : s;
    }
}
