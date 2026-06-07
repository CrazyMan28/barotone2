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
 * A recent hit on the player, as plain data (~last 2s kept in {@link WorldSnapshot#recentDamage}).
 * Lets detectors react to damage whose source is not a visible mob — arrows from an unseen
 * skeleton, fire, cactus — by kind and rough incoming direction.
 */
public final class DamageEvent {

    public final long gameTime;
    public final float amount;
    /** Damage type id path, e.g. "arrow", "mob_attack", "in_fire", "lava", "fall". */
    public final String sourceKind;
    /** Normalized horizontal direction the damage came FROM (toward the source), 0,0 if unknown. */
    public final double dirX, dirZ;

    public DamageEvent(long gameTime, float amount, String sourceKind, double dirX, double dirZ) {
        this.gameTime = gameTime;
        this.amount = amount;
        this.sourceKind = sourceKind == null ? "" : sourceKind;
        this.dirX = dirX;
        this.dirZ = dirZ;
    }
}
