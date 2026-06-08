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
    CREEPER,
    SWARM,
    RANGED,
    MELEE_MOB,
    /** Being beaten by mob(s) we can't/shouldn't trade with (low hp or outnumbered) — retreat+heal. */
    OVERWHELMED,
    POISON,
    HUNGER
}
