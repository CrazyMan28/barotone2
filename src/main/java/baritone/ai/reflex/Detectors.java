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
    public static final int SEV_DROWN = 95;
    public static final int SEV_FLEE_MOB = 80;
    public static final int SEV_MELEE = 60;
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
        if (!combatReady(s, t) || anyWithin(s, radius, m -> m.creeper)) {
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
        double radius = fleeEngageRadius(t);
        MobInfo creeper = nearest(s, radius, m -> m.creeper);
        MobInfo skeleton = nearest(s, radius, m -> m.skeleton);
        boolean fightSkeleton = skeleton != null && skeletonToFight(s, t) != null;
        if (creeper != null) {
            return new Threat(ThreatType.CREEPER, SEV_FLEE_MOB, creeper);
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
        return t.autoEat && s.food <= t.eatAtHunger && !s.screenOpen && s.bestFoodSlot >= 0
                ? new Threat(ThreatType.HUNGER, SEV_HUNGER) : null;
    }
}
