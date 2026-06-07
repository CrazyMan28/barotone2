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
 * How the FLEE behavior is currently trying to end the threat. NORMAL is plain running; the
 * rest are the escalation ladder for a chase that won't resolve (the old watchdog used to just
 * give up here — now the threat gets *resolved*).
 */
public enum FleeMode {
    NORMAL,
    /** Tower up out of melee reach. */
    PILLAR,
    /** Place blocks between self and the mob. */
    WALL,
    /** Run a rotated direction — the straight-away path was blocked. */
    NEW_DIRECTION
}
