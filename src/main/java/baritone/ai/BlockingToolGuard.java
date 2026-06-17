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
 * The bail decision shared by every blocking tool wait-loop (open_station, hunt, furnace_smelt,
 * brewing_brew, wait_until_idle). A blocking loop must yield control PROMPTLY — not run to its own
 * deadline — when any of these happen, in priority order:
 *
 * <ol>
 *   <li><b>cancelled</b> — the operator typed {@code ai stop}.</li>
 *   <li><b>died</b> — the agent died (its gear is gone); hand back so the planner can replan.</li>
 *   <li><b>reflex engaged</b> — the survival reflex took the bot to fight/flee a threat. It closes
 *       any open container GUI, so the loop can no longer make progress; the agent should retry the
 *       tool after the danger passes.</li>
 *   <li><b>expired</b> — the (reflex-paused) deadline has finally elapsed.</li>
 * </ol>
 *
 * Pure decision logic — Minecraft-free, unit-tested. The actual loops feed it
 * {@code WatchdogClock.expired(now)} (so a long shelter doesn't read as a timeout) and the live
 * {@code MistralAgent}/{@code ReflexProcess} flags.
 */
public final class BlockingToolGuard {

    /** Why a blocking loop should stop before its work finished. */
    public enum Bail {
        /** No reason to bail yet — keep polling. */
        NONE,
        /** Operator cancelled (ai stop). */
        CANCELLED,
        /** Agent died — return to the planner for death-replan. */
        DIED,
        /** Survival reflex took control (threat) — retry after danger passes. */
        REFLEX,
        /** The (reflex-paused) deadline elapsed without the work completing. */
        TIMEOUT
    }

    private BlockingToolGuard() {
    }

    /**
     * The highest-priority reason this poll iteration should bail, or {@link Bail#NONE} to continue.
     * Priority is cancel &gt; died &gt; reflex &gt; timeout: a death must hand back even if a threat
     * is also present, and neither should be masked by a deadline that the reflex pause kept alive.
     *
     * @param cancelled {@code MistralAgent.isCancelled()}
     * @param died      {@code MistralAgent.diedSinceRunStart()}
     * @param reflex    {@code ReflexProcess.ENGAGED}
     * @param expired   {@code WatchdogClock.expired(now)} — already reflex-pause-aware
     */
    public static Bail evaluate(boolean cancelled, boolean died, boolean reflex, boolean expired) {
        if (cancelled) {
            return Bail.CANCELLED;
        }
        if (died) {
            return Bail.DIED;
        }
        if (reflex) {
            return Bail.REFLEX;
        }
        if (expired) {
            return Bail.TIMEOUT;
        }
        return Bail.NONE;
    }
}
