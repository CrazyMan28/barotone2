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
 * Gear-aware fight-or-flee judgment. The old {@code combatReady} only asked "do I have a weapon
 * and 4 hearts?" — which is how unarmored bots brawled zombies bare-handed to death (59% of the
 * analyzed deaths). Power scoring weighs weapon tier, armor, health, shield and food against the
 * actual local threat (count, type, night), and the bot only trades blows when it strictly wins.
 */
public final class CombatPower {

    /**
     * Points by rank in the adapter's melee table: swords 0-5 (netherite, diamond, iron, stone,
     * golden, wooden), then axes 6-11 in the same material order, half a point behind (slower).
     * Gold cuts like wood.
     */
    private static final double[] WEAPON_POINTS = {
            6.0D, 5.5D, 4.0D, 3.0D, 2.0D, 2.0D,
            5.5D, 5.0D, 3.5D, 2.5D, 1.5D, 1.5D
    };

    private static final double SKELETON_POWER = 4.5D; // outranges us — closing in eats arrows
    private static final double HOSTILE_POWER = 3.5D;  // a bare fist (3.5) exactly ties one zombie

    private CombatPower() {
    }

    /**
     * Threat power for one melee/skeleton mob, by type. Most hostiles are {@link #HOSTILE_POWER};
     * skeletons/ranged out-range us; and a cave spider is "you can't safely melee this" — it
     * out-speeds you AND poisons, so it scores higher so a fresh/light bot flees rather than trading
     * blows it can't disengage from. (A witch/phantom is handled at the detector tier — they're flagged
     * ranged so they get cover/flee regardless of gear, since no player power out-heals/out-reaches them.)
     */
    static double perMobPower(MobInfo m) {
        if (m.skeleton || m.ranged) {
            return SKELETON_POWER;
        }
        String type = m.typeId == null ? "" : m.typeId;
        if (type.contains("cave_spider")) {
            return 5.0D;  // faster than us + poison on hit: trading just bleeds us out
        }
        return HOSTILE_POWER;
    }

    /** Below this remaining durability the weapon is "about to snap" — count it as bare-handed. */
    private static final int BROKEN_WEAPON_PERCENT = 5;

    static double weaponPoints(int tier) {
        return tier >= 0 && tier < WEAPON_POINTS.length ? WEAPON_POINTS[tier] : 0D;
    }

    /**
     * Weapon contribution, discounted by remaining durability. A weapon nearly out of durability
     * breaks mid-fight and then deals fist damage, so once it drops under {@link #BROKEN_WEAPON_PERCENT}
     * it contributes nothing (the bot should retreat/regear, not "win" a fight its sword can't finish).
     */
    static double effectiveWeaponPoints(WorldSnapshot s) {
        double pts = weaponPoints(s.bestWeaponTier);
        if (s.bestWeaponDurabilityPercent >= 0 && s.bestWeaponDurabilityPercent < BROKEN_WEAPON_PERCENT) {
            return 0D;
        }
        return pts;
    }

    /** What the bot brings to a melee: weapon + armor + health, with shield and full-belly tips. */
    public static double playerPower(WorldSnapshot s) {
        double hpFrac = s.maxHp <= 0 ? 1D : Math.min(1D, s.hp / (double) s.maxHp);
        double power = effectiveWeaponPoints(s)
                + 0.35D * s.armorValue
                + 3.0D * hpFrac
                + (s.hasShieldOffhand ? 1.0D : 0D)
                + (s.food >= 18 ? 0.5D : 0D);
        // Weakness (e.g. a witch's potion) guts melee damage — a "winnable" trade becomes a loss, so
        // drop our assessed power hard and let the gear-aware judgment flee instead of brawling.
        if (s.weakened) {
            power -= 3.0D;
        }
        return power;
    }

    /**
     * What the local mobs bring: every non-creeper hostile inside its own (predictive) engage
     * radius counts; night scales it up because the dark keeps sending reinforcements. Creepers
     * never count — they are never fought (the CREEPER threat flees them outright).
     */
    public static double threatPower(WorldSnapshot s, ReflexTuning t) {
        double sum = 0D;
        for (MobInfo m : s.mobs) {
            if (m.creeper || !(m.hostile || m.skeleton)) {
                continue;
            }
            // count a mob within normal engage range, OR one already charging us (committed) inside
            // the wider proactive radius — otherwise an unarmed bot reads a zombie sprinting in from
            // 13 blocks as "no threat in range, safe to fight" and never flees.
            boolean inRange = m.distance <= Detectors.effFleeRadius(m, t)
                    || (Detectors.approaching(m, t) && m.distance <= t.proactiveEngageRadius);
            if (!inRange) {
                continue;
            }
            sum += perMobPower(m);
        }
        return sum * (s.night ? t.nightThreatMultiplier : 1D);
    }

    /**
     * Threat power of every non-creeper hostile within a fixed radius (e.g. the whole perception
     * range) — "if all of those came at me, would I win?". Used by the night-exposure judgment,
     * where mobs merely visible in the dark are already a reason to turtle.
     */
    public static double threatPowerWithin(WorldSnapshot s, ReflexTuning t, double radius) {
        double sum = 0D;
        for (MobInfo m : s.mobs) {
            if (m.creeper || !(m.hostile || m.skeleton)) {
                continue;
            }
            if (m.distance > radius) {
                continue;
            }
            sum += perMobPower(m);
        }
        return sum * (s.night ? t.nightThreatMultiplier : 1D);
    }

    /** Stand and fight only when we strictly win the power comparison (ties flee). */
    public static boolean fightFavorable(WorldSnapshot s, ReflexTuning t) {
        if (!t.gearAwareCombat) {
            return true; // legacy judgment: hp/weapon/outnumbered checks elsewhere decide alone
        }
        return playerPower(s) > threatPower(s, t) * t.powerMargin;
    }
}
