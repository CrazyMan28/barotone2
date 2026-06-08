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

import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

/**
 * All threat detectors plus the shared judgment helpers (combat readiness, food safety...).
 * Base severities encode the old fixed priority ladder: LAVA > DROWN > flee-mobs > melee > hunger;
 * health modulation in the arbiter scales mob threats on top of these.
 */
public final class Detectors {

    public static final int SEV_LAVA = 100;
    public static final int SEV_VOID = 100;
    public static final int SEV_SUFFOCATION = 96;
    public static final int SEV_DROWN = 95;
    public static final int SEV_FALL = 90;
    public static final int SEV_SWARM = 85;
    public static final int SEV_FLEE_MOB = 80;
    /** A hissing creeper gets this on top of {@link #SEV_FLEE_MOB}. */
    public static final int SEV_IGNITED_BONUS = 15;
    public static final int SEV_MELEE = 60;
    public static final int SEV_POISON = 50;
    public static final int SEV_HUNGER = 30;

    /** Foods the auto-eat reflex refuses (poison, effects, or too valuable to waste on hunger). */
    private static final Set<String> RISKY_FOODS = Set.of(
            "rotten_flesh", "spider_eye", "poisonous_potato", "pufferfish", "chicken",
            "suspicious_stew", "chorus_fruit", "enchanted_golden_apple"
    );

    private Detectors() {
    }

    // ---------------------------------------------------------------- shared judgments

    /**
     * @param itemIdPath the item id path, e.g. "bread" or "minecraft:bread"
     * @return true if the auto-eat reflex may eat this food
     */
    public static boolean isSafeFood(String itemIdPath) {
        if (itemIdPath == null || itemIdPath.isEmpty()) {
            return false;
        }
        String path = itemIdPath.toLowerCase(Locale.ROOT);
        int colon = path.indexOf(':');
        if (colon >= 0) {
            path = path.substring(colon + 1);
        }
        return !RISKY_FOODS.contains(path);
    }

    /** Geared and healthy enough to trade hits instead of running. */
    public static boolean combatReady(WorldSnapshot s, ReflexTuning t) {
        return t.fightBack && s.hp >= t.combatMinHealth && s.bestWeaponSlot >= 0;
    }

    public static boolean recentlyHurt(WorldSnapshot s, ReflexTuning t) {
        return s.ticksSinceHurt <= t.fightDisengageTicks;
    }

    public static double fleeEngageRadius(ReflexTuning t) {
        return Math.max(2D, t.creeperRadius);
    }

    public static MobInfo nearest(WorldSnapshot s, double radius, Predicate<MobInfo> filter) {
        MobInfo best = null;
        for (MobInfo m : s.mobs) {
            if (m.distance <= radius && filter.test(m) && (best == null || m.distance < best.distance)) {
                best = m;
            }
        }
        return best;
    }

    /** A mob closing on us fast (or already aggroed) — engage it before it reaches melee range. */
    public static boolean approaching(MobInfo m, ReflexTuning t) {
        return m.aggro || m.approachingSpeed >= t.approachSpeedThreshold;
    }

    /** Engage radius for one mob: the base flee radius, extended when the mob is bearing down. */
    public static double effFleeRadius(MobInfo m, ReflexTuning t) {
        return fleeEngageRadius(t) + (approaching(m, t) ? t.predictiveFleeBonus : 0D);
    }

    /** Nearest mob matching {@code filter} inside its own (predictive) engage radius. */
    public static MobInfo nearestFlee(WorldSnapshot s, ReflexTuning t, Predicate<MobInfo> filter) {
        MobInfo best = null;
        for (MobInfo m : s.mobs) {
            if (filter.test(m) && m.distance <= effFleeRadius(m, t)
                    && (best == null || m.distance < best.distance)) {
                best = m;
            }
        }
        return best;
    }

    public static boolean anyWithin(WorldSnapshot s, double radius, Predicate<MobInfo> filter) {
        return nearest(s, radius, filter) != null;
    }

    /**
     * True if a mob we must FLEE is within radius: any creeper, or — when not combat-ready — any
     * skeleton. The flee release check uses this with radius+4 (hysteresis).
     */
    public static boolean fleeRequiredWithin(WorldSnapshot s, ReflexTuning t, double radius) {
        boolean ready = combatReady(s, t);
        return anyWithin(s, radius, m -> m.creeper || (m.skeleton && !ready));
    }

    /** A skeleton we choose to stand and fight: geared, healthy, and no creeper to flee first. */
    public static MobInfo skeletonToFight(WorldSnapshot s, ReflexTuning t) {
        double radius = fleeEngageRadius(t);
        if (!combatReady(s, t) || nearestFlee(s, t, m -> m.creeper) != null) {
            return null;
        }
        return nearest(s, radius, m -> m.skeleton);
    }

    // ---------------------------------------------------------------- detectors

    public static Threat lava(WorldSnapshot s, ReflexTuning t) {
        return t.antiLava && s.inLava ? new Threat(ThreatType.LAVA, SEV_LAVA) : null;
    }

    public static Threat drown(WorldSnapshot s, ReflexTuning t) {
        return t.antiDrown && s.underWater && s.air < t.drownEngageAir
                ? new Threat(ThreatType.DROWN, SEV_DROWN) : null;
    }

    /**
     * The mobs answered by FLEE: creepers always (they explode — never melee one), skeletons when
     * we aren't geared to take them (closing on a skeleton unarmed just eats arrows).
     */
    public static Threat fleeMob(WorldSnapshot s, ReflexTuning t) {
        if (!t.fleeCreepers) {
            return null;
        }
        MobInfo creeper = nearestFlee(s, t, m -> m.creeper);
        MobInfo skeleton = nearestFlee(s, t, m -> m.skeleton);
        boolean fightSkeleton = skeleton != null && skeletonToFight(s, t) != null;
        if (creeper != null) {
            int sev = SEV_FLEE_MOB + (creeper.ignited ? SEV_IGNITED_BONUS : 0);
            return new Threat(ThreatType.CREEPER, sev, creeper);
        }
        if (skeleton != null && !fightSkeleton) {
            return new Threat(ThreatType.RANGED, SEV_FLEE_MOB, skeleton);
        }
        return null;
    }

    /** A mob we stand and fight: a skeleton we're geared for, or any melee mob that just hit us. */
    public static Threat meleeFight(WorldSnapshot s, ReflexTuning t) {
        MobInfo skeleton = skeletonToFight(s, t);
        if (skeleton != null) {
            return new Threat(ThreatType.MELEE_MOB, SEV_MELEE, skeleton);
        }
        if (t.fightBack && recentlyHurt(s, t)) {
            MobInfo target = nearest(s, t.meleeEngageRadius, m -> m.hostile && !m.creeper && !m.skeleton);
            if (target != null) {
                return new Threat(ThreatType.MELEE_MOB, SEV_MELEE, target);
            }
        }
        return null;
    }

    public static Threat hunger(WorldSnapshot s, ReflexTuning t) {
        if (!t.autoEat || s.screenOpen || s.bestFoodSlot < 0) {
            return null;
        }
        // urgent: always eat once hunger is low enough to threaten regen / sprint.
        boolean urgent = s.food <= t.eatAtHunger;
        // proactive: top the bar up earlier during a calm lull so we never coast into starvation
        // mid-mission — but only when nothing hostile is around (EAT is the lowest priority, so a
        // real threat still preempts the moment one appears).
        boolean lull = s.food <= t.proactiveEatHunger
                && !anyWithin(s, t.swarmRadius, m -> m.hostile || m.creeper || m.skeleton);
        return urgent || lull ? new Threat(ThreatType.HUNGER, SEV_HUNGER) : null;
    }

    /** On fire (and not in lava/water, which own their cases). Scarier the lower our hp. */
    public static Threat fire(WorldSnapshot s, ReflexTuning t) {
        if (!s.onFire || s.inLava || s.underWater) {
            return null;
        }
        double hpFrac = s.maxHp <= 0 ? 1D : s.hp / (double) s.maxHp;
        int sev = 70 + (int) Math.round(20D * (1D - hpFrac));
        return new Threat(ThreatType.FIRE, sev);
    }

    /** A real fall with a water bucket ready — the only fall the reflex can actually break. */
    public static Threat fall(WorldSnapshot s, ReflexTuning t) {
        if (s.onGround || s.waterBucketSlot < 0) {
            return null;
        }
        if (s.fallDistance <= t.mlgFallTrigger || s.velY >= -0.4D) {
            return null;
        }
        return new Threat(ThreatType.FALL, SEV_FALL);
    }

    /** Falling with no ground at all in the scan below. */
    public static Threat voidDrop(WorldSnapshot s, ReflexTuning t) {
        return !s.onGround && s.voidBelow ? new Threat(ThreatType.VOID, SEV_VOID) : null;
    }

    /** Sand/gravel collapsed onto the head — kills in seconds, dig out NOW. */
    public static Threat suffocation(WorldSnapshot s, ReflexTuning t) {
        return s.headBlockedByGravity ? new Threat(ThreatType.SUFFOCATION, SEV_SUFFOCATION) : null;
    }

    /** Enough hostiles in brawling range that fighting is suicide — run instead. */
    public static Threat swarm(WorldSnapshot s, ReflexTuning t) {
        int count = 0;
        MobInfo nearestMob = null;
        for (MobInfo m : s.mobs) {
            if (m.distance <= t.swarmRadius && (m.hostile || m.creeper || m.skeleton)) {
                count++;
                if (nearestMob == null || m.distance < nearestMob.distance) {
                    nearestMob = m;
                }
            }
        }
        return count >= t.swarmCount ? new Threat(ThreatType.SWARM, SEV_SWARM, nearestMob) : null;
    }

    /** Poisoned/withering at low hp with food to heal back — retreat and treat. */
    public static Threat poison(WorldSnapshot s, ReflexTuning t) {
        return s.poisoned && s.hp <= t.poisonTreatHp && s.bestFoodSlot >= 0
                ? new Threat(ThreatType.POISON, SEV_POISON) : null;
    }
}
