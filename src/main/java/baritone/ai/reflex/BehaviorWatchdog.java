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
 * Generic progress watchdog shared by all reflex behaviors. Fed the bot's position plus a
 * behavior-specific metric (air refilling, target hp dropping, own hp recovering...) every tick;
 * if a full window elapses with neither enough horizontal movement nor enough metric gain, the
 * behavior is "stuck" and the arbiter escalates instead of letting it spin forever.
 *
 * <p>Pure tick-fed state — unit-testable without Minecraft, like {@code ReflexPlanner.FleeWatchdog}
 * before it.
 */
public final class BehaviorWatchdog {

    private final int windowTicks;
    private final double minPosDelta;
    private final double minMetricDelta;

    private long windowStart = Long.MIN_VALUE;
    private double startX, startZ, startMetric;

    /**
     * @param windowTicks    how long a behavior may go without progress before it is stuck
     * @param minPosDelta    horizontal blocks moved within a window that count as progress
     * @param minMetricDelta metric gain within a window that counts as progress
     */
    public BehaviorWatchdog(int windowTicks, double minPosDelta, double minMetricDelta) {
        this.windowTicks = windowTicks;
        this.minPosDelta = minPosDelta;
        this.minMetricDelta = minMetricDelta;
    }

    /** Start fresh (call when a behavior is entered). */
    public void reset() {
        windowStart = Long.MIN_VALUE;
    }

    /**
     * Advance one tick.
     *
     * @param tick   current game tick
     * @param x      bot x
     * @param z      bot z
     * @param metric behavior-specific progress metric (higher = better)
     * @return true if a full window just elapsed with no movement AND no metric gain
     */
    public boolean stuck(long tick, double x, double z, double metric) {
        if (windowStart == Long.MIN_VALUE) {
            windowStart = tick;
            startX = x;
            startZ = z;
            startMetric = metric;
            return false;
        }
        if (tick - windowStart < windowTicks) {
            return false;
        }
        boolean moved = Math.hypot(x - startX, z - startZ) >= minPosDelta;
        boolean improved = metric - startMetric >= minMetricDelta;
        windowStart = tick;
        startX = x;
        startZ = z;
        startMetric = metric;
        return !moved && !improved;
    }
}
