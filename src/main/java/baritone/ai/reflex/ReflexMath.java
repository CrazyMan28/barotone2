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

/** Minecraft look-angle math, pure (yaw 0 = +Z/south, -90 = +X/east; pitch positive = down). */
public final class ReflexMath {

    /** Standing eye height — close enough for combat aim and escape looks. */
    public static final double EYE_HEIGHT = 1.62D;

    private ReflexMath() {
    }

    /** Yaw that faces (toX,toZ) from (fromX,fromZ). */
    public static float yawToward(double fromX, double fromZ, double toX, double toZ) {
        return (float) Math.toDegrees(Math.atan2(-(toX - fromX), toZ - fromZ));
    }

    /** Yaw that faces directly AWAY from (awayX,awayZ). */
    public static float yawAway(double fromX, double fromZ, double awayX, double awayZ) {
        return yawToward(awayX, awayZ, fromX, fromZ);
    }

    /** Pitch that aims an eye at {@code fromY + EYE_HEIGHT} onto a target point. */
    public static float pitchToward(double fromX, double fromY, double fromZ,
                                    double targetX, double targetY, double targetZ) {
        double dy = targetY - (fromY + EYE_HEIGHT);
        double horiz = Math.hypot(targetX - fromX, targetZ - fromZ);
        return (float) -Math.toDegrees(Math.atan2(dy, horiz));
    }

    /** Feet-position block spec for a mob (matches {@code Entity.blockPosition()}). */
    public static BlockPosSpec feetBlock(MobInfo m) {
        return new BlockPosSpec((int) Math.floor(m.x), (int) Math.floor(m.y), (int) Math.floor(m.z));
    }
}
