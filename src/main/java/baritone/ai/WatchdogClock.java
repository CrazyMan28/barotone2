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
 * Stuck/timeout clock arithmetic that PAUSES while the survival reflexes own the bot. A shelter
 * waiting out the night runs for many minutes — without the pause, the mine-stuck watchdog (30s)
 * would cancel + relocate right out of the shelter, and tool/mission deadlines would expire mid-
 * turtle. Pure (caller passes timestamps), unit-tested.
 */
public final class WatchdogClock {

    private long anchorMillis;
    private long deadlineMillis;
    private long lastTickMillis;

    /**
     * @param nowMillis      current time
     * @param deadlineMillis absolute deadline; {@code Long.MAX_VALUE} = no deadline
     */
    public WatchdogClock(long nowMillis, long deadlineMillis) {
        this.anchorMillis = nowMillis;
        this.deadlineMillis = deadlineMillis;
        this.lastTickMillis = nowMillis;
    }

    /** Call once per poll iteration: reflex-owned time slides BOTH clocks forward (pauses them). */
    public void onTick(boolean reflexActive, long nowMillis) {
        long elapsed = Math.max(0, nowMillis - lastTickMillis);
        lastTickMillis = nowMillis;
        if (reflexActive && elapsed > 0) {
            anchorMillis += elapsed;
            if (deadlineMillis != Long.MAX_VALUE) {
                deadlineMillis += elapsed;
            }
        }
    }

    /** Real progress happened (moved / picked something up) — restart the stuck window. */
    public void progress(long nowMillis) {
        anchorMillis = nowMillis;
    }

    /** No progress for longer than the window (reflex-owned time excluded). */
    public boolean stuckFor(long nowMillis, long stuckWindowMillis) {
        return nowMillis - anchorMillis > stuckWindowMillis;
    }

    /** The (reflex-extended) deadline has passed. */
    public boolean expired(long nowMillis) {
        return nowMillis > deadlineMillis;
    }
}
