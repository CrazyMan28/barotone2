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
    /** Slowness amplifier + 1 (0 = none). Slowed, we can't outrun mobs — fleeing is futile, dig in instead. */
    public int slownessLevel;
    /** Weakness effect — melee does far less damage, so a "winnable" fight becomes a losing one. */
    public boolean weakened;
    /** Wither effect — damage-over-time that natural regen can't outpace; treat like poison (retreat + heal). */
    public boolean withered;
    /**
     * Blindness or Darkness — vision is gutted, so we can't aim/kite/path reliably and threats close
     * unseen. With a hostile near, the safe play is to seal in (SHELTER) rather than flail blind in the
     * open. Set by the adapter from MobEffects.BLINDNESS / DARKNESS.
     */
    public boolean blinded;
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
    /**
     * The head is inside ANY solid block — suffocating in a wall (cave-in, a piston shove, a bad
     * spawn/teleport, a closing gap). Unlike {@link #headBlockedByGravity} the fix is to mine out AND
     * climb the shaft, since the bot is encased rather than just buried from above.
     */
    public boolean headInSolid;

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
    /**
     * Remaining durability of the best weapon as a percent 0-100 (-1 = unbreakable / no weapon).
     * A near-broken sword (about to snap) deals little/no damage, so the gear-aware power score must
     * discount it — otherwise the bot "wins" a fight on paper with a weapon that breaks mid-swing
     * and then bleeds out bare-handed (a real death). {@code CombatPower.playerPower} reads this.
     */
    public int bestWeaponDurabilityPercent = -1;
    public boolean hasShieldOffhand;
    /** Worn armor points, 0-20+ ({@code player.getArmorValue()}). */
    public int armorValue;
    public int bestFoodSlot = -1;
    public int bestFoodNutrition = -1;
    public int waterBucketSlot = -1;
    public int blockSlot = -1;
    public int blockCount;
    /** Hotbar slot holding a bed (-1 = none) — sleeping skips the night entirely. */
    public int bedSlot = -1;

    // ---- precomputed world scans (adapter-side, since the core can't read block states)
    /**
     * Stand-able non-lava column to climb out of lava toward, chosen mob-aware (farthest from
     * hostiles, never one a mob is standing on), else null. {@code ReflexProcess.findLavaEscape}.
     */
    public BlockPosSpec lavaEscape;
    /** Nearest reachable water block when on fire, else null. */
    public BlockPosSpec nearestWater;
    /**
     * Drowning: a SAFE open-air column to surface into — open straight up to air, never capped by
     * lava or a solid ceiling, chosen away from any mob waiting at the surface. Null when the only
     * way up is sealed (must dig) or into lava (must swim sideways). {@code ReflexProcess.findSurfaceEscape}.
     */
    public BlockPosSpec surfaceEscape;
    /** Drowning + a solid (non-water) block directly overhead — can't just bob up; must dig or swim out. */
    public boolean surfaceSealed;

    // ---- forward perception ("vision"): cheap look-ahead so reflexes act BEFORE the bot is
    //      already dying. Indexed by octant (see ReflexMath.OCTANT_*): 0=south(+Z), going
    //      clockwise through SW/W/NW/N/NE/E/SE. The adapter fills these from block scans.
    /** Lava within a couple blocks straight ahead (look/travel direction). */
    public boolean lavaAhead;
    /** A killing drop (open gap) within a couple blocks straight ahead. */
    public boolean dropAhead;
    /**
     * Per-octant "is it safe to move/flee that way" — false means lava, a long drop, or a wall
     * blocks that direction. All-true by default so unscanned tests treat every way as open.
     */
    public boolean[] octantSafe = {true, true, true, true, true, true, true, true};
    /**
     * Per-octant "is there a 2-high solid column within a few blocks that way" — cover that breaks
     * a skeleton's arrow line-of-sight. All-false by default (open field).
     */
    public boolean[] octantCover = new boolean[8];
    /** The 3 blocks under the feet are solid, non-gravity, with no lava below — safe to dig a turtle hole. */
    public boolean digDownSafe;
    /** A solid block sits within 2 above the head — the turtle hole is already sealed. */
    public boolean sealedOverhead;
    /**
     * Standing on / inside a contact-damage block (cactus, magma block, sweet-berry bush) that ticks
     * damage while we touch it but isn't fire/lava. After a fall-MLG the bot can land on one and stand
     * there bleeding because nothing else detects it — step off NOW. Set by the adapter's block scan.
     */
    public boolean contactHazardAtFeet;

    // ---- ambient (spawn / night awareness)
    /** Block-light at the feet (0-15); low light at night is where hostiles spawn on top of you. */
    public int lightLevel = 15;
    /** Derived from the world day-time: night is when new hostiles spawn. */
    public boolean night;
    /** World day-time, 0..23999 (dawn = 0/24000, night ~13000-23000). */
    public long dayTime;

    // ---- entities & damage
    public List<MobInfo> mobs = new ArrayList<>();
    public List<DamageEvent> recentDamage = new ArrayList<>();
}
