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
 * The death state machine, pure and unit-tested. First death: if the drops look reachable
 * before the ~5-minute despawn, go recover them and continue the sub-goal. Once the bot has
 * died MORE than maxDeaths times on one sub-goal — or the drops are unreachable anyway —
 * stop repeating the mistake: replan and re-gear (food, armor, torches) instead.
 */
public final class DeathPolicy {

    private DeathPolicy() {}

    /** Slack for pathing detours, mob dodges and pickup time on the way to the drops. */
    public static final double SAFETY_MARGIN_SECONDS = 30;

    public enum Decision {
        /** Drops reachable in time — run a recovery interlude, then resume the sub-goal. */
        RECOVER_THEN_CONTINUE,
        /** Death budget blown or drops lost — replan the remaining work with a re-gear directive. */
        REPLAN_AND_REGEAR
    }

    public static final class Verdict {
        public final Decision decision;
        /** Whether the drops looked reachable before despawn (independent of the decision). */
        public final boolean recoverable;
        public final String reason;

        Verdict(Decision decision, boolean recoverable, String reason) {
            this.decision = decision;
            this.recoverable = recoverable;
            this.reason = reason;
        }
    }

    /**
     * @param distanceBlocks      current position → death spot
     * @param secondsSinceDeath   how much of the despawn window is already burned
     * @param deathsThisSubGoal   running death count on the current sub-goal
     * @param maxDeaths           policy cap — strictly MORE than this forces a replan
     * @param walkSpeedBlocksPerSec  travel estimate (sprint-walk ~4.3)
     * @param despawnSeconds      item despawn window (vanilla 300)
     */
    public static Verdict decide(double distanceBlocks, double secondsSinceDeath,
                                 int deathsThisSubGoal, int maxDeaths,
                                 double walkSpeedBlocksPerSec, double despawnSeconds) {
        double eta = distanceBlocks / Math.max(0.1, walkSpeedBlocksPerSec);
        boolean recoverable = secondsSinceDeath + eta + SAFETY_MARGIN_SECONDS < despawnSeconds;

        if (deathsThisSubGoal > maxDeaths) {
            return new Verdict(Decision.REPLAN_AND_REGEAR, recoverable,
                    "died " + deathsThisSubGoal + "x on this step (max " + maxDeaths
                            + ") — change strategy and re-gear instead of repeating");
        }
        if (recoverable) {
            return new Verdict(Decision.RECOVER_THEN_CONTINUE, true,
                    String.format("drops ~%.0f blocks away, ~%.0fs travel, ~%.0fs of despawn window left",
                            distanceBlocks, eta, despawnSeconds - secondsSinceDeath));
        }
        return new Verdict(Decision.REPLAN_AND_REGEAR, false,
                String.format("drops unrecoverable (~%.0f blocks, ~%.0fs travel, only ~%.0fs left) — re-gear",
                        distanceBlocks, eta, Math.max(0, despawnSeconds - secondsSinceDeath)));
    }
}
