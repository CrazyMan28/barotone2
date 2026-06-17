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

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The avoid-zone guard that stops the agent walking straight back into a spot the survival reflex
 * just fled (the flee → "avoid (x,z)" hint → LLM forgets → goto_coords(x,y,z) → die loop). Pure 2D
 * geometry — {@code goto_coords} warns when a target lands inside the zone.
 */
public class AvoidZoneTest {

    @Test
    public void aTargetAtTheCentreIsBlocked() {
        AvoidZone z = new AvoidZone(100, 200);
        assertTrue(z.blocks(100, 200));
    }

    @Test
    public void aTargetJustInsideTheRadiusIsBlocked() {
        AvoidZone z = new AvoidZone(100, 200, 12);
        assertTrue("10 blocks away is within the 12-block danger radius", z.blocks(110, 200));
        assertTrue("diagonal inside the radius", z.blocks(108, 208)); // ~11.3 < 12
    }

    @Test
    public void aTargetBeyondTheRadiusIsAllowed() {
        AvoidZone z = new AvoidZone(100, 200, 12);
        assertFalse("20 blocks away is clear", z.blocks(120, 200));
        assertFalse("diagonal beyond the radius", z.blocks(110, 210)); // ~14.1 > 12
    }

    @Test
    public void theCheckIsHorizontalOnlyAndIgnoresHeight() {
        // the zone has no Y — the threat is positional in the world; approaching from any height is
        // still inside the same XZ danger circle
        AvoidZone z = new AvoidZone(0, 0, 8);
        assertTrue(z.blocks(5, 5)); // ~7.07 < 8
    }

    @Test
    public void radiusIsClampedToAtLeastOne() {
        AvoidZone z = new AvoidZone(0, 0, 0);
        assertTrue("a zero/negative radius is forced to 1 so the centre is always blocked",
                z.blocks(0, 0));
        assertFalse(z.blocks(5, 5));
    }

    @Test
    public void distanceToIsTheHorizontalEuclideanDistance() {
        AvoidZone z = new AvoidZone(0, 0, 8);
        assertEquals(5.0, z.distanceTo(3, 4), 1e-9);
    }

    @Test
    public void defaultRadiusIsTheMobEngageDistance() {
        AvoidZone z = new AvoidZone(0, 0);
        assertEquals(AvoidZone.DEFAULT_RADIUS, z.radius);
    }
}
