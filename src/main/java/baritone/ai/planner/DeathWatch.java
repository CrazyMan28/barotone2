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

import java.util.concurrent.atomic.AtomicLong;

/**
 * Death detector bridging the game thread and the planner thread (the same pattern as
 * ReflexProcess.ACTIVE_STATUS): the game tick feeds {@link #onClientTick} every tick, the
 * rising edge of isDeadOrDying captures WHERE the drops are, and the planner polls
 * {@link #pollNewDeath} with the last sequence number it has handled. Minecraft-free on
 * purpose — the caller passes primitives.
 */
public final class DeathWatch {

    private DeathWatch() {}

    private static volatile boolean wasDead;
    private static volatile DeathEvent lastDeath;
    private static volatile long lastGameTime;
    private static final AtomicLong SEQ = new AtomicLong();

    /** Feed from the game tick. Captures the death position on the alive→dead edge only. */
    public static void onClientTick(boolean isDeadOrDying, double x, double y, double z,
                                    String dimension, long gameTime) {
        lastGameTime = gameTime;
        if (isDeadOrDying && !wasDead) {
            lastDeath = new DeathEvent(x, y, z, dimension, gameTime);
            SEQ.incrementAndGet();
        }
        wasDead = isDeadOrDying;
    }

    /** The newest game time (ticks) seen — drives "seconds since death" for the despawn window. */
    public static long currentGameTime() {
        return lastGameTime;
    }

    /** Total deaths seen since launch — the planner remembers the value it has handled. */
    public static long currentSeq() {
        return SEQ.get();
    }

    /** The newest death if there has been one after {@code sinceSeq}, else null. */
    public static DeathEvent pollNewDeath(long sinceSeq) {
        return SEQ.get() > sinceSeq ? lastDeath : null;
    }

    static void resetForTests() {
        wasDead = false;
        lastDeath = null;
        lastGameTime = 0;
        SEQ.set(0);
    }
}
