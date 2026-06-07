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
 * The flee-episode clock, ported from the proven {@code ReflexPlanner.FleeWatchdog}. Times a
 * single flee <em>episode</em> — tolerant of the bot bobbing in and out of engage range (a gap
 * shorter than {@code episodeGapTicks} is the same episode) — and once an episode runs longer
 * than {@code maxFleeTicks}, suppresses fleeing for {@code cooldownTicks}.
 *
 * <p>Where the old watchdog just gave up (resume mining next to the creeper...), the arbiter now
 * also uses {@link #unresolved} as the trigger for tactical resolution: fight it if winnable,
 * pillar up, wall off, or run a different way.
 */
public final class FleeEscalation {

    private final int maxFleeTicks;
    private final int cooldownTicks;
    private final int episodeGapTicks;

    private long lastFleeTick = Long.MIN_VALUE;
    private long episodeStart = Long.MIN_VALUE;
    private long cooldownUntil = Long.MIN_VALUE;

    public FleeEscalation(int maxFleeTicks, int cooldownTicks, int episodeGapTicks) {
        this.maxFleeTicks = maxFleeTicks;
        this.cooldownTicks = cooldownTicks;
        this.episodeGapTicks = episodeGapTicks;
    }

    /**
     * Advance the clock one tick.
     *
     * <p>While in cooldown we report "suppressed" and freeze episode tracking, so the moment the
     * cooldown ends a still-present mob begins a <em>fresh</em> flee window.
     *
     * @param now      the current game tick
     * @param mobsNear whether a flee-mob is within engage range this tick
     * @return true if fleeing should be SUPPRESSED this tick
     */
    public boolean suppressed(long now, boolean mobsNear) {
        if (now < cooldownUntil) {
            return true; // in cooldown: stay out of the way and don't advance the episode timer
        }
        if (mobsNear) {
            if (lastFleeTick == Long.MIN_VALUE || now - lastFleeTick > episodeGapTicks) {
                episodeStart = now; // fresh episode: first sighting, just-ended cooldown, or a lull
            }
            lastFleeTick = now;
            if (episodeStart != Long.MIN_VALUE && now - episodeStart > maxFleeTicks) {
                cooldownUntil = now + cooldownTicks;
                lastFleeTick = Long.MIN_VALUE; // force a fresh window once the cooldown expires
                episodeStart = Long.MIN_VALUE;
                return true;
            }
        }
        return false;
    }

    /**
     * @return how long the current episode has been running (0 if no episode), so the arbiter can
     * escalate to a resolution BEFORE the suppression cutoff fires.
     */
    public long episodeTicks(long now) {
        if (episodeStart == Long.MIN_VALUE || now < cooldownUntil) {
            return 0;
        }
        return now - episodeStart;
    }

    /** True once the current episode has outlived {@code maxFleeTicks} * 0.6 — running isn't working. */
    public boolean unresolved(long now) {
        return episodeTicks(now) > (long) (maxFleeTicks * 0.6D);
    }
}
