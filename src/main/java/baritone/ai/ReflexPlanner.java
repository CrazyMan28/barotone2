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

package baritone.ai;

import java.util.Locale;
import java.util.Set;

/**
 * Pure decision core for the survival reflexes. {@code baritone.process.ReflexProcess} gathers the
 * world conditions every tick and asks this class which reflex (if any) should be running; keeping
 * the logic here makes the priority/stickiness rules unit-testable without Minecraft.
 */
public final class ReflexPlanner {

    /** Reflexes in priority order (highest first, NONE last). */
    public enum Reflex {
        LAVA, DROWN, FLEE, FIGHT, EAT, NONE
    }

    /**
     * World conditions sampled by the process each tick. "Engage" flags start a reflex; "done" flags
     * release a running one (they carry hysteresis, e.g. fleeDone uses a larger radius than creeperNear).
     */
    public static final class Conditions {
        public boolean inLava;
        public boolean drowning;
        public boolean drownDone;
        public boolean creeperNear;
        public boolean fleeDone;
        public boolean hostileThreat;
        public boolean fightDone;
        public boolean hungry;
        public boolean eatDone;
        /** True when the bot is pathing or on an AI mission; gates FLEE/FIGHT engagement so the
         * reflexes never hijack a player who is playing manually. */
        public boolean working;
    }

    /** Foods the auto-eat reflex refuses (poison, effects, or too valuable to waste on hunger). */
    private static final Set<String> RISKY_FOODS = Set.of(
            "rotten_flesh", "spider_eye", "poisonous_potato", "pufferfish", "chicken",
            "suspicious_stew", "chorus_fruit", "enchanted_golden_apple"
    );

    private ReflexPlanner() {
    }

    /**
     * @param current the reflex that ran last tick (NONE if idle)
     * @param c       this tick's sampled conditions
     * @return the reflex that should run this tick
     */
    public static Reflex pick(Reflex current, Conditions c) {
        // Lava outranks everything, including a reflex already in progress.
        if (c.inLava) {
            return Reflex.LAVA;
        }
        // A running reflex is sticky until its release condition fires; higher-priority dangers
        // can still escalate over it. Note FLEE/FIGHT stay engaged even if the mission ends
        // mid-reflex (the working gate only applies to fresh engagement).
        switch (current) {
            case DROWN:
                if (!c.drownDone) {
                    return Reflex.DROWN;
                }
                break;
            case FLEE:
                if (c.drowning) {
                    return Reflex.DROWN;
                }
                if (!c.fleeDone) {
                    return Reflex.FLEE;
                }
                break;
            case FIGHT:
                if (c.drowning) {
                    return Reflex.DROWN;
                }
                if (c.creeperNear) {
                    return Reflex.FLEE;
                }
                if (!c.fightDone) {
                    return Reflex.FIGHT;
                }
                break;
            case EAT:
                if (c.drowning) {
                    return Reflex.DROWN;
                }
                if (c.creeperNear && c.working) {
                    return Reflex.FLEE;
                }
                if (c.hostileThreat && c.working) {
                    return Reflex.FIGHT;
                }
                if (!c.eatDone) {
                    return Reflex.EAT;
                }
                break;
            default:
                break;
        }
        // Fresh engagement, priority order.
        if (c.drowning) {
            return Reflex.DROWN;
        }
        if (c.creeperNear && c.working) {
            return Reflex.FLEE;
        }
        if (c.hostileThreat && c.working) {
            return Reflex.FIGHT;
        }
        if (c.hungry) {
            return Reflex.EAT;
        }
        return Reflex.NONE;
    }

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
}
