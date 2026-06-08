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

package baritone.ai.reflex.behavior;

import baritone.ai.reflex.MobInfo;
import baritone.ai.reflex.ReflexMath;
import baritone.ai.reflex.WorldSnapshot;

/**
 * Shared movement helpers for the mob behaviors, built on the snapshot's forward perception
 * ({@link WorldSnapshot#octantSafe}). The whole point is that a panic sprint or a retreat never
 * runs the bot straight into lava or off a ledge just because that happened to be "directly away"
 * from the mob — it picks the safe direction closest to the one it wanted.
 */
public final class Moves {

    private Moves() {
    }

    /** True if any compass direction is currently safe to move into. */
    public static boolean anySafe(WorldSnapshot s) {
        for (boolean ok : s.octantSafe) {
            if (ok) {
                return true;
            }
        }
        return false;
    }

    /**
     * The octant to actually move along when we want to head toward {@code desiredYaw}: the octant
     * nearest that yaw if it is safe, otherwise the safe octant closest to it by angle.
     *
     * @return an octant index 0..7, or -1 when boxed in (no safe direction at all)
     */
    public static int safeOctantToward(WorldSnapshot s, float desiredYaw) {
        int start = ReflexMath.nearestOctant(desiredYaw);
        if (s.octantSafe[start]) {
            return start;
        }
        for (int off = 1; off <= ReflexMath.OCTANTS / 2; off++) {
            int a = (start + off) % ReflexMath.OCTANTS;
            int b = (start - off + ReflexMath.OCTANTS) % ReflexMath.OCTANTS;
            boolean aSafe = s.octantSafe[a];
            boolean bSafe = s.octantSafe[b];
            if (aSafe && bSafe) {
                float da = Math.abs(ReflexMath.angleDelta(desiredYaw, ReflexMath.octantYaw(a)));
                float db = Math.abs(ReflexMath.angleDelta(desiredYaw, ReflexMath.octantYaw(b)));
                return da <= db ? a : b;
            }
            if (aSafe) {
                return a;
            }
            if (bSafe) {
                return b;
            }
        }
        return -1;
    }

    /**
     * The yaw to flee toward: exactly {@code desiredYaw} when the straight-away direction is safe
     * (so behavior is unchanged in the open), otherwise snapped to the nearest safe octant. Returns
     * {@code desiredYaw} as a last resort when boxed in — callers should check {@link #boxedIn}.
     */
    public static float safeFleeYaw(WorldSnapshot s, float desiredYaw) {
        int octant = safeOctantToward(s, desiredYaw);
        if (octant < 0) {
            return desiredYaw;
        }
        return octant == ReflexMath.nearestOctant(desiredYaw) && s.octantSafe[octant]
                ? desiredYaw : ReflexMath.octantYaw(octant);
    }

    /** No safe direction toward where we wanted to go — running would step into a hazard. */
    public static boolean boxedIn(WorldSnapshot s, float desiredYaw) {
        return safeOctantToward(s, desiredYaw) < 0;
    }

    /** Average "away from all these mobs" yaw, weighting nearer mobs more. */
    public static float awayFromAll(WorldSnapshot s, Iterable<MobInfo> mobs) {
        double dx = 0;
        double dz = 0;
        for (MobInfo m : mobs) {
            double w = 1D / Math.max(1D, m.distance);
            dx += (s.posX - m.x) * w;
            dz += (s.posZ - m.z) * w;
        }
        if (dx == 0 && dz == 0) {
            return 0F;
        }
        return ReflexMath.yawToward(0, 0, dx, dz);
    }
}
