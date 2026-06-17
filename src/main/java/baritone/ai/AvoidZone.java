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

/**
 * The horizontal spot a survival episode just fled — the danger zone the agent must NOT path straight
 * back into. The reflex leaves it after a flee that moved us clear (a creeper/lava/mob position); the
 * {@code goto_coords} tool consults it so the LLM can't immediately walk the bot back to where it just
 * nearly died (the death-loop: flee → "avoid (x,z)" hint → LLM forgets → goto_coords(x,y,z) → die).
 *
 * <p>Pure, Minecraft-free and unit-tested. The radius is the typical mob engagement distance: a goal
 * inside it would re-enter the threat; one outside is fine. The check is 2D (XZ) because the threat is
 * positional in the world, not the height we approach from.
 */
public final class AvoidZone {

    /** Default danger radius (blocks): roughly mob engage range — inside this, you're back in it. */
    public static final int DEFAULT_RADIUS = 12;

    public final int x;
    public final int z;
    public final int radius;

    public AvoidZone(int x, int z, int radius) {
        this.x = x;
        this.z = z;
        this.radius = Math.max(1, radius);
    }

    public AvoidZone(int x, int z) {
        this(x, z, DEFAULT_RADIUS);
    }

    /** True when (gx,gz) lies within this zone's radius — pathing there re-enters the danger. */
    public boolean blocks(int gx, int gz) {
        long dx = gx - x;
        long dz = gz - z;
        return dx * dx + dz * dz <= (long) radius * radius;
    }

    /** Horizontal distance from this zone's centre to (gx,gz). */
    public double distanceTo(int gx, int gz) {
        double dx = gx - x;
        double dz = gz - z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    @Override
    public String toString() {
        return "avoid ~(" + x + "," + z + ") r=" + radius;
    }
}
