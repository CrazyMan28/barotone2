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

import java.util.List;

/**
 * The {@link SurvivalBrain}'s holistic read of one tick — "what is my situation, all of it, at
 * once". The old arbiter only ever looked at the single worst threat; the brain first builds this
 * whole-picture summary (terrain hazards AND every mob AND my gear AND my escape routes) and then
 * reasons about combinations off it.
 *
 * <p>Pure data, no Minecraft, built straight from a {@link WorldSnapshot} so it is trivially
 * unit-testable. It is also what the LLM/HUD report is rendered from, so the agent can see <em>why</em>
 * the bot did what it did.
 */
public final class SituationAssessment {

    /** Overall danger, from the whole picture (not just the top threat). */
    public enum Level {
        SAFE,       // nothing wrong
        WATCHFUL,   // hunger / a distant mob — handle calmly
        ENDANGERED, // an active threat that can kill if ignored
        CRITICAL    // dying right now: lethal terrain, low hp under attack, or surrounded
    }

    public Level level = Level.SAFE;

    // ---- power balance (gear vs the local threat)
    public double playerPower;
    public double threatPower;
    /** We strictly win the melee trade against what's around us. */
    public boolean powerFavorable = true;

    // ---- vitals shortcuts
    public float hpFrac = 1F;
    public boolean lowHp;        // hp at/under the critical floor — never trade blows here
    public boolean starving;     // food so low natural regen is off and a hit could end us

    // ---- environment
    public boolean night;
    /** Few or no safe directions left to move/flee — running just hits a wall, so bunker instead. */
    public boolean cornered;
    /** How many of the 8 octants are actually safe to move into. */
    public int safeDirections = 8;
    public boolean inLiquidHazard; // in lava or drowning

    // ---- resources on hand (drives which survival options are even possible)
    public boolean hasBlocks;
    public boolean hasFood;
    public boolean hasWaterBucket;
    public boolean hasBed;
    public boolean hasWeapon;

    // ---- the threat picture, categorised (counts within perception)
    public int creepersNear;
    public int rangedNear;   // skeletons
    public int meleeNear;    // zombies / other monsters
    public int hostilesNear; // total of the above
    /** Lethal terrain hazards present this tick (lava/void/drown/suffocate/fire/fall). */
    public boolean terrainHazard;

    /** Every threat detected this tick, highest severity first — the full list, for the report. */
    public List<Threat> threats = List.of();

    /** A one-line human summary for logs / the LLM report. */
    public String describe() {
        StringBuilder sb = new StringBuilder(level.name().toLowerCase());
        if (hostilesNear > 0) {
            sb.append(" — ").append(hostilesNear).append(" hostile").append(hostilesNear == 1 ? "" : "s")
              .append(" (").append(creepersNear).append("c/").append(rangedNear).append("r/")
              .append(meleeNear).append("m)");
        }
        if (cornered) {
            sb.append(", cornered");
        }
        if (lowHp) {
            sb.append(", low hp");
        }
        if (starving) {
            sb.append(", starving");
        }
        sb.append(powerFavorable ? ", can win" : ", outmatched");
        return sb.toString();
    }
}
