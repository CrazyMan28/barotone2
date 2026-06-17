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
 * What the {@link SurvivalBrain} did during one survival episode, handed back to the LLM agent after
 * the reflex pauses it and resolves the danger. This is the "the model waits, then I tell it what I
 * did and what to check" channel: instead of the agent blindly resuming a mission with a stale view,
 * {@code get_state} surfaces this so the LLM knows it was moved, what threat it faced, and where NOT
 * to walk back into.
 *
 * <p>The pure core fills the tactical fields; the adapter ({@code ReflexProcess}) enriches with
 * absolute world facts it can read (final position, gear/items it noticed were lost). Plain POJO so
 * it serialises straight to JSON — it needs a {@code -keep} rule in {@code scripts/proguard.pro} or
 * ProGuard renames the fields and the agent receives {@code a/b/c}.
 */
public final class SurvivalReport {

    /** Threat type that triggered the episode, lower-case (e.g. "creeper", "lava"). */
    public String threat = "";
    /** Behavior that handled it (e.g. "fleeing danger"). */
    public String behavior = "";
    /** How many ticks the reflex owned the bot. */
    public int ticks;
    /** "resolved" when the danger cleared, "escalated" when it had to pillar/wall/bunker to survive. */
    public String outcome = "resolved";
    /** Blocks moved from where the episode started (so the agent knows it relocated). */
    public double movedBlocks;
    /** True when the agent should avoid pathing back toward {@link #avoidX}/{@link #avoidZ}. */
    public boolean hasAvoid;
    /** The spot the threat was at — re-pathing through it is how the death-loop happens. */
    public double avoidX, avoidY, avoidZ;
    /** Human one-liner for chat / the agent's context. */
    public String summary = "";

    public SurvivalReport() {
    }

    public String describe() {
        return summary;
    }
}
