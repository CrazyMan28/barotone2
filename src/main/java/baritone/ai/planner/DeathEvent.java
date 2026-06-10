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

/**
 * Where and when the bot died — captured on the rising edge of isDeadOrDying by
 * {@link DeathWatch} so the planner knows where the dropped items are. Pure POJO,
 * in-memory only (not gson-persisted).
 */
public final class DeathEvent {

    public final double x;
    public final double y;
    public final double z;
    public final String dimension;
    /** Game time (ticks) at the moment of death — drives the despawn-window math. */
    public final long gameTime;
    /** Damage-source msgId at death ("lava", "arrow", "mob", ... or "unknown"). */
    public final String cause;
    /** Entity type that gets kill credit ("zombie", "skeleton", ...), empty when none. */
    public final String killer;
    /** Lava/fire/void deaths destroy the drops — recovery trips are pointless. */
    public final boolean dropsLikelyDestroyed;

    public DeathEvent(double x, double y, double z, String dimension, long gameTime) {
        this(x, y, z, dimension, gameTime, "unknown", "", false);
    }

    public DeathEvent(double x, double y, double z, String dimension, long gameTime,
                      String cause, String killer, boolean dropsLikelyDestroyed) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.dimension = dimension;
        this.gameTime = gameTime;
        this.cause = cause == null || cause.isEmpty() ? "unknown" : cause;
        this.killer = killer == null ? "" : killer;
        this.dropsLikelyDestroyed = dropsLikelyDestroyed;
    }

    @Override
    public String toString() {
        return String.format("death@%.0f,%.0f,%.0f (%s, t=%d, %s%s%s)", x, y, z, dimension, gameTime,
                cause, killer.isEmpty() ? "" : " by " + killer, dropsLikelyDestroyed ? ", drops destroyed" : "");
    }
}
