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
 * Everything the bot can die to (plus hunger). Declaration order is the tie-break when two
 * threats score the same severity — earlier wins.
 */
public enum ThreatType {
    LAVA,
    VOID,
    DROWN,
    SUFFOCATION,
    FIRE,
    FALL,
    /** The Warden: unwinnable, one-shots geared players — always flee, ranks above every other mob. */
    WARDEN,
    CREEPER,
    SWARM,
    RANGED,
    MELEE_MOB,
    /** Being beaten by mob(s) we can't/shouldn't trade with (low hp or outnumbered) — retreat+heal. */
    OVERWHELMED,
    POISON,
    HUNGER,
    /** A plain hostile (zombie etc.) the gear-aware judgment says we'd lose to — flee, don't brawl. */
    OUTMATCHED,
    /** Night + undergeared + hostiles visible: proactively turtle up instead of working until dead. */
    NIGHT_EXPOSURE,
    /**
     * Food so low natural regen is off and a single hit could end us, with no mob close enough to
     * make eating suicide — eat NOW, even mid-mission. Ranks above mob-flee so the bot stops kiting a
     * distant creeper forever while it quietly starves (a real death mode), but the detector itself
     * only fires when nothing is in melee/blast range, so a near threat still flees first.
     */
    STARVATION
}
