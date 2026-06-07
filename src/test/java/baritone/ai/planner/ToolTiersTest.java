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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Tier ladders the verifier uses for "tier-or-better" checks. Gold is the trap:
 *  a golden pickaxe harvests at WOOD level in Minecraft, so it must never satisfy stone+. */
public class ToolTiersTest {

    @Test
    public void miningLadderIsOrdered() {
        assertTrue(ToolTiers.rank("wooden") < ToolTiers.rank("stone"));
        assertTrue(ToolTiers.rank("stone") < ToolTiers.rank("iron"));
        assertTrue(ToolTiers.rank("iron") < ToolTiers.rank("diamond"));
        assertTrue(ToolTiers.rank("diamond") < ToolTiers.rank("netherite"));
    }

    @Test
    public void goldenHarvestsAtWoodLevel() {
        // golden pickaxe cannot mine iron ore — it is NOT >= stone
        assertEquals(ToolTiers.rank("wooden"), ToolTiers.rank("golden"));
        assertTrue(ToolTiers.rank("golden") < ToolTiers.rank("stone"));
    }

    @Test
    public void unknownMaterialRanksBelowEverything() {
        assertTrue(ToolTiers.rank("plastic") < ToolTiers.rank("wooden"));
        assertTrue(ToolTiers.rank(null) < ToolTiers.rank("wooden"));
    }

    @Test
    public void extractsToolMaterialAndType() {
        assertEquals("iron", ToolTiers.toolMaterial("minecraft:iron_pickaxe"));
        assertEquals("stone", ToolTiers.toolMaterial("stone_axe"));
        assertEquals("pickaxe", ToolTiers.toolType("minecraft:iron_pickaxe"));
        assertEquals("axe", ToolTiers.toolType("stone_axe"));
        assertNull(ToolTiers.toolMaterial("minecraft:oak_log"));
        assertNull(ToolTiers.toolType("oak_log"));
        assertNull(ToolTiers.toolType(null));
    }

    @Test
    public void armorLadderIsOrdered() {
        assertTrue(ToolTiers.armorRank("leather") < ToolTiers.armorRank("golden"));
        assertTrue(ToolTiers.armorRank("golden") < ToolTiers.armorRank("chainmail"));
        assertTrue(ToolTiers.armorRank("chainmail") < ToolTiers.armorRank("iron"));
        assertTrue(ToolTiers.armorRank("iron") < ToolTiers.armorRank("diamond"));
        assertTrue(ToolTiers.armorRank("diamond") < ToolTiers.armorRank("netherite"));
        assertTrue(ToolTiers.armorRank("cardboard") < ToolTiers.armorRank("leather"));
    }

    @Test
    public void extractsArmorMaterialAndSlot() {
        assertEquals("diamond", ToolTiers.armorMaterial("minecraft:diamond_chestplate"));
        assertEquals("iron", ToolTiers.armorMaterial("iron_helmet"));
        assertEquals("chest", ToolTiers.armorSlot("minecraft:diamond_chestplate"));
        assertEquals("head", ToolTiers.armorSlot("iron_helmet"));
        assertEquals("legs", ToolTiers.armorSlot("diamond_leggings"));
        assertEquals("feet", ToolTiers.armorSlot("netherite_boots"));
        assertNull(ToolTiers.armorMaterial("minecraft:oak_log"));
        assertNull(ToolTiers.armorSlot("stick"));
    }
}
