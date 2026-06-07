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

/**
 * A Baritone pathing goal as pure data; the executor turns it into the real
 * {@code GoalRunAway} / {@code GoalNear} / {@code GoalXZ}.
 */
public final class GoalSpec {

    public enum Kind { RUN_AWAY, NEAR, XZ }

    public final Kind kind;
    /** RUN_AWAY radius or NEAR range. */
    public final int distance;
    /** RUN_AWAY: positions to flee from. */
    public final BlockPosSpec[] from;
    /** NEAR / XZ target. */
    public final BlockPosSpec target;

    private GoalSpec(Kind kind, int distance, BlockPosSpec[] from, BlockPosSpec target) {
        this.kind = kind;
        this.distance = distance;
        this.from = from;
        this.target = target;
    }

    public static GoalSpec runAway(int distance, BlockPosSpec... from) {
        return new GoalSpec(Kind.RUN_AWAY, distance, from, null);
    }

    public static GoalSpec near(BlockPosSpec target, int range) {
        return new GoalSpec(Kind.NEAR, range, null, target);
    }

    public static GoalSpec xz(int x, int z) {
        return new GoalSpec(Kind.XZ, 0, null, new BlockPosSpec(x, 0, z));
    }
}
