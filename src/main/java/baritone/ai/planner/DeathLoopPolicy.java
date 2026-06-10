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
 * What to do when the bot dies — the anti-spiral policy. The live failure mode this fixes: a
 * skeleton camped the drop site, the recovery step marched the bot back into the arrows, every
 * death triggered a full LLM replan, and the mission gave up after 5 replans. Here a routine death
 * is cheap (no replan, no LLM), a repeatedly-deadly spot is ABANDONED instead of revisited, and the
 * mission only gives up once it has truly bled out. Pure and unit-tested.
 */
public final class DeathLoopPolicy {

    private DeathLoopPolicy() {
    }

    public enum Action {
        /** First/isolated death with reachable drops — go pick them up, then carry on. */
        CONTINUE_RECOVER,
        /** Drops gone/unreachable — skip recovery, carry on with the remaining plan. */
        CONTINUE_NO_RECOVER,
        /** Something is camping this spot (repeated nearby death / died mid-recovery) — flee it. */
        ESCAPE_TRAP,
        /** The mission has died too many times overall — stop. */
        GIVE_UP
    }

    /**
     * @param deathsTotal        total deaths this mission (after this one)
     * @param deathsNearLast     consecutive deaths in the same area (after this one)
     * @param maxMissionDeaths   give up once total strictly exceeds this
     * @param recoverable        the despawn-window feasibility check said the drops are reachable
     * @param dropsDestroyed     a lava/fire/void death — nothing left to recover
     * @param diedDuringRecovery the current step is itself a "Recover drops" step (we died doing it)
     */
    public static Action decide(int deathsTotal, int deathsNearLast, int maxMissionDeaths,
                                boolean recoverable, boolean dropsDestroyed, boolean diedDuringRecovery) {
        if (deathsTotal > maxMissionDeaths) {
            return Action.GIVE_UP;
        }
        if (deathsNearLast >= 2 || diedDuringRecovery) {
            return Action.ESCAPE_TRAP;
        }
        if (recoverable && !dropsDestroyed) {
            return Action.CONTINUE_RECOVER;
        }
        return Action.CONTINUE_NO_RECOVER;
    }
}
