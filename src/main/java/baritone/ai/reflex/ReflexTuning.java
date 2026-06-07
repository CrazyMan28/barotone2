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
 * Every knob the engine reads, as a plain POJO so the core never imports {@code Settings}.
 * The adapter refreshes the user-facing fields from the {@code reflex*} settings each tick;
 * the rest are internal weights with defaults matching the proven pre-redesign constants.
 */
public final class ReflexTuning {

    // ---- user-facing (mirrors the existing reflex* settings)
    public boolean antiLava = true;
    public boolean antiDrown = true;
    public boolean fleeCreepers = true;
    public boolean fightBack = true;
    public boolean autoEat = true;
    public int eatAtHunger = 13;
    public double creeperRadius = 7.0D;

    // ---- eat (pre-redesign constants)
    public int eatReleaseFood = 18;
    public int eatTimeoutTicks = 400;

    // ---- combat
    public float combatMinHealth = 8.0F;     // below this, flee skeletons instead of trading
    public int fightDisengageTicks = 100;    // "recently hurt" window for melee fight-back
    public double fightReleaseDistance = 8.0D;
    public double meleeEngageRadius = 4.5D;  // fight back at melee mobs inside this
    public double strikeDistance = 3.6D;
    public double rushDistance = 6.0D;
    public float combatRetreatHp = 6.0F;     // disengage to heal below this
    public int swarmCount = 3;               // this many hostiles = a swarm, don't brawl

    // ---- flee + escalation (ports the FleeWatchdog values)
    public int maxFleeTicks = 200;           // ~10s unresolved -> escalate
    public int fleeCooldownTicks = 120;
    public int fleeEpisodeGapTicks = 100;
    public double panicDistance = 4.5D;      // inside this, sprint away before pathing
    public int fleeGoalDistance = 16;
    public int pillarHeight = 3;

    // ---- health modulation / retreat
    public double lowHpFleeBias = 0.8D;      // severity *= 1 + bias*(1-hpFrac) for mob threats
    public float retreatTargetHp = 14.0F;    // heal up to this before resuming

    // ---- new threats
    public int drownEngageAir = 90;
    public int drownReleaseAir = 250;
    public double fireWaterRadius = 8.0D;
    public double mlgFallTrigger = 4.0D;     // fallDistance that arms the water-bucket MLG
    public int voidScanDepth = 24;           // gapBelow >= this counts as a void drop

    // ---- watchdog
    public int behaviorStuckTicks = 60;      // generic no-progress window
}
