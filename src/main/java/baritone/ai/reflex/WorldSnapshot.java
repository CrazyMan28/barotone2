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

import java.util.ArrayList;
import java.util.List;

/**
 * Everything the reflex engine knows about one tick, as plain data with zero Minecraft imports.
 * {@code ReflexProcess} (the adapter) builds one per tick from {@code ctx}; the engine, arbiter,
 * detectors and behaviors only ever read this — which is what makes the whole decision core
 * unit-testable by hand-building snapshots (the {@code ReflexPlanner.Conditions} pattern, grown up).
 *
 * <p>Defaults describe a calm, healthy bot so tests only set what a scenario needs.
 */
public final class WorldSnapshot {

    public long gameTime;

    // ---- vitals
    public float hp = 20F;
    public float maxHp = 20F;
    public int food = 20;
    public int air = 300;
    public int maxAir = 300;
    public boolean onFire;
    public boolean inLava;
    public boolean underWater;
    public boolean poisoned;
    /** Ticks since the player was last hurt (MAX_VALUE = never). */
    public int ticksSinceHurt = Integer.MAX_VALUE;
    /** Pathing / AI mission active within the last ~2s (the adapter latches this). */
    public boolean working;

    // ---- position & motion
    public double posX, posY, posZ;
    public double velY;
    public double fallDistance;
    public boolean onGround = true;
    public boolean horizontalCollision;
    /** Air blocks straight down before solid ground (capped scan; 0 = standing on solid). */
    public int gapBelow;
    /** No ground at all within the scan below — a void / death drop. */
    public boolean voidBelow;
    /** A gravity block (sand/gravel) occupies the head space — suffocating. */
    public boolean headBlockedByGravity;

    // ---- look & UI
    public float yaw, pitch;
    public boolean screenOpen;
    /** {@code player.getAttackStrengthScale(0)} — 1.0 = attack fully charged. */
    public float attackStrengthScale = 1F;

    // ---- hotbar / inventory summary (slots are hotbar 0-8, -1 = none)
    public int selectedSlot;
    public int bestWeaponSlot = -1;
    /** Rank in the melee-weapon table, lower = better (-1 = none). */
    public int bestWeaponTier = -1;
    public boolean hasShieldOffhand;
    public int bestFoodSlot = -1;
    public int bestFoodNutrition = -1;
    public int waterBucketSlot = -1;
    public int blockSlot = -1;
    public int blockCount;

    // ---- precomputed world scans (adapter-side, since the core can't read block states)
    /** Nearest stand-able non-lava column when in lava, else null. */
    public BlockPosSpec lavaEscape;
    /** Nearest reachable water block when on fire, else null. */
    public BlockPosSpec nearestWater;

    // ---- entities & damage
    public List<MobInfo> mobs = new ArrayList<>();
    public List<DamageEvent> recentDamage = new ArrayList<>();
}
