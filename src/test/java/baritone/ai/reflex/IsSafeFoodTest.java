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

package baritone.ai.reflex;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Ported from ReflexPlannerTest#riskyFoodsAreRejected — the auto-eat food blacklist. */
public class IsSafeFoodTest {

    @Test
    public void riskyFoodsAreRejected() {
        assertFalse(Detectors.isSafeFood("minecraft:rotten_flesh"));
        assertFalse(Detectors.isSafeFood("minecraft:pufferfish"));
        assertFalse(Detectors.isSafeFood("minecraft:enchanted_golden_apple"));
        assertFalse(Detectors.isSafeFood("chorus_fruit"));
        assertTrue(Detectors.isSafeFood("minecraft:bread"));
        assertTrue(Detectors.isSafeFood("cooked_beef"));
        assertTrue(Detectors.isSafeFood("minecraft:golden_apple"));
        assertFalse(Detectors.isSafeFood(null));
        assertFalse(Detectors.isSafeFood(""));
    }
}
