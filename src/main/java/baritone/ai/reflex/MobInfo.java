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
 * One nearby hostile as plain data. The adapter resolves the {@code instanceof} checks
 * (Creeper / AbstractSkeleton / Monster) into semantic flags so the pure core never does
 * string-matching on entity ids. Mobs cross the pure boundary as entity ids; the executor
 * re-looks the entity up each tick, so no stale {@code LivingEntity} refs live in the core.
 */
public final class MobInfo {

    /** Entity id for executor-side re-lookup (e.g. attack target). */
    public int entityId = -1;
    /** Item-path style id, e.g. "creeper", for logs/telemetry. */
    public String typeId = "";
    /** Feet position (blockPosition-style), for flee vectors and pathing goals. */
    public double x, y, z;
    /** Hitbox-center Y, for combat aim (a mob above/below us still gets hit). */
    public double aimY;
    /** Distance from the player (same metric as {@code player.distanceTo}). */
    public double distance;
    /**
     * Closing speed in blocks/tick toward the player (positive = getting closer, negative =
     * retreating). The adapter derives it from the change in distance across ticks; it lets the
     * detectors engage a fast-approaching threat earlier instead of waiting for it to reach the
     * fixed engage radius.
     */
    public double approachingSpeed;
    /** This mob is actively targeting the player (its attack/aggro target is us). */
    public boolean aggro;
    public boolean lineOfSight = true;
    /** Creeper — explodes, must NEVER be meleed. */
    public boolean creeper;
    /** Skeleton/Stray/Bogged — fled when unarmed, fought when geared. */
    public boolean skeleton;
    /** Any other Monster (zombie, spider...). */
    public boolean hostile;
    /**
     * Attacks from range (blaze fireballs, ghast fireballs, drowned trident...). Like a skeleton it
     * out-trades a melee charge — answer it with cover/shelter, never chase it into its fire.
     */
    public boolean ranged;
    /**
     * Unwinnable by design (the Warden): can one-shot fully geared and out-damages any trade — there
     * is no fight to be had, only flee. Forced down the flee ladder regardless of gear.
     */
    public boolean unkillable;
    /** Creeper currently hissing/ignited (severity spike). */
    public boolean ignited;
}
