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
 * The anti-flap memory for one mob behavior episode. The old arbiter released a flee/combat the
 * instant the mob crossed the (slightly wider) release radius, then re-engaged the very next tick
 * as it crossed back — the {@code engage->done->engage->done} thrash seen in live telemetry.
 *
 * <p>This debounces the <em>release</em> decision into a committed episode:
 * <ul>
 *   <li>a freshly engaged behavior holds for at least {@code minDwellTicks} (kills 1-tick flips);
 *   <li>after that, the raw "the threat is gone" verdict must stay true continuously for
 *       {@code releaseGraceTicks} before we actually let go — a threat that briefly drops out of
 *       range and comes back resets the grace, so we never thrash.
 * </ul>
 *
 * <p>Pure tick-fed state, unit-testable without Minecraft (like {@link FleeEscalation}). It only
 * gates release; preemption by a strictly more severe threat and mob-to-mob escalation still fire
 * immediately in the arbiter, so committing to an episode never makes the bot less safe.
 */
public final class ThreatMemory {

    private long engagedAt = Long.MIN_VALUE;
    private long wantReleaseSince = Long.MIN_VALUE;

    /** Begin a fresh episode (call when a mob behavior is (re)engaged). */
    public void onEngage(long now) {
        engagedAt = now;
        wantReleaseSince = Long.MIN_VALUE;
    }

    /** Drop all state (episode genuinely ended / behavior switched to a non-mob one). */
    public void reset() {
        engagedAt = Long.MIN_VALUE;
        wantReleaseSince = Long.MIN_VALUE;
    }

    /**
     * Debounce the raw release decision.
     *
     * @param now             current game tick
     * @param wantRelease     the arbiter's raw verdict that the threat is gone this tick
     * @param minDwellTicks   minimum ticks to hold an episode before any release
     * @param releaseGrace    ticks the raw verdict must hold continuously before we release
     * @return true only when the episode may actually end
     */
    public boolean shouldRelease(long now, boolean wantRelease, int minDwellTicks, int releaseGrace) {
        if (!wantRelease) {
            wantReleaseSince = Long.MIN_VALUE; // threat back in range: cancel any pending release
            return false;
        }
        if (wantReleaseSince == Long.MIN_VALUE) {
            wantReleaseSince = now;
        }
        if (engagedAt != Long.MIN_VALUE && now - engagedAt < minDwellTicks) {
            return false; // still inside the committed dwell window
        }
        return now - wantReleaseSince >= releaseGrace;
    }
}
