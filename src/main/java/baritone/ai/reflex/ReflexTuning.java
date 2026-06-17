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
    /** Mob defense engages even when idle (no mission/pathing); false = old working-only gate. */
    public boolean defendIdle = true;

    // ---- perception ("vision")
    /** How far the adapter scans for mobs (>= engage radius so we see threats coming). */
    public double perceptionRadius = 16.0D;
    /** Closing speed (blocks/tick) above which a mob counts as "approaching" for early engage. */
    public double approachSpeedThreshold = 0.06D;
    /** Extra engage distance granted to a mob that is approaching (or aggroed on us). */
    public double predictiveFleeBonus = 4.0D;
    /**
     * Meet/flee a hostile that is already committed to us (aggroed or closing fast) THIS far out,
     * before it lands a blow — proactive, not reactive. Wider than the normal engage radius so the
     * bot fights or runs with a head start instead of waiting to get hit.
     */
    public double proactiveEngageRadius = 14.0D;
    /**
     * How far to perceive LONG-range shooters (a ghast lobs fireballs from far past the normal
     * perception radius — up to ~64 blocks in vanilla). Detecting one this far out lets the bot break
     * line-of-sight / take cover BEFORE the first fireball lands, instead of standing in the open
     * eating explosions until the ghast drifts into normal perception. Only ghast-class shooters get
     * this extended reach; melee mobs and close shooters use {@link #perceptionRadius}.
     */
    public double rangedPerceptionRadius = 24.0D;

    // ---- anti-flap (committed mob episodes)
    /** A freshly engaged mob behavior won't release for at least this many ticks. */
    public int minMobDwellTicks = 12;
    /** Once a mob behavior wants to release, it must stay "clear" this long first (debounce). */
    public int mobReleaseGraceTicks = 16;

    // ---- proactive survival
    /** Top the hunger bar up during a calm lull once food drops to this (no threats around). */
    public int proactiveEatHunger = 16;
    /** At/under this food, eating jumps the priority queue (STARVATION) — even mid-mission. */
    public int criticalStarvationFood = 3;
    /** A starving bot only stops to eat when no hostile is within this radius (eating is ~1.6s frozen). */
    public double starvationSafeRadius = 10.0D;
    /** Hp at/under this is a hard "never trade blows" floor: disengage to heal/flee no matter what. */
    public float criticalHp = 4.0F;
    /** Two or fewer safe directions to move = cornered: bunker instead of fleeing into a wall. */
    public int corneredSafeDirections = 2;

    // ---- eat (pre-redesign constants)
    public int eatReleaseFood = 18;
    public int eatTimeoutTicks = 400;

    // ---- combat
    /** Weigh weapon+armor+hp against the local threat before standing to fight (see CombatPower). */
    public boolean gearAwareCombat = true;
    /** fightFavorable: playerPower must strictly exceed threatPower * this. */
    public double powerMargin = 1.0D;
    /** Night multiplies threat power (reinforcements keep spawning in the dark). */
    public double nightThreatMultiplier = 1.25D;
    public float combatMinHealth = 8.0F;     // below this, flee skeletons instead of trading
    public int fightDisengageTicks = 100;    // "recently hurt" window for melee fight-back
    public double fightReleaseDistance = 8.0D;
    public double meleeEngageRadius = 4.5D;  // fight back at melee mobs inside this
    public double strikeDistance = 3.6D;
    public double rushDistance = 6.0D;
    public float combatRetreatHp = 6.0F;     // disengage to heal below this
    public float combatLossDelta = 4.0F;     // hp lost within the loss window that means "losing"
    public int combatLossWindowTicks = 60;
    public int swarmCount = 3;               // this many hostiles = a swarm, don't brawl
    public double swarmRadius = 6.0D;
    public int combatStrafeTicks = 12;       // flip the kite/strafe direction every this many ticks
    public int fightMaxMobs = 2;             // more hostiles than this crowding us -> flee, don't trade

    // ---- flee + escalation (ports the FleeWatchdog values)
    public int maxFleeTicks = 200;           // ~10s unresolved -> escalate
    public int fleeCooldownTicks = 120;
    public int fleeEpisodeGapTicks = 100;
    public double panicDistance = 4.5D;      // inside this, sprint away before pathing
    public int fleeGoalDistance = 16;
    public int pillarHeight = 3;             // min blocks on hand to bother choosing PILLAR
    /** Climb at LEAST this high when pillaring (a 3-tall pillar still eats a creeper blast). */
    public int pillarTargetHeight = 6;
    /** Never pillar past this (out of blocks / diminishing returns / don't tower forever). */
    public int pillarMaxHeight = 16;
    /** Keep climbing until every creeper is at least this many blocks BELOW us (blast falls off ~4). */
    public int creeperSafeGap = 5;

    // ---- health modulation / retreat
    public double lowHpFleeBias = 0.8D;      // severity *= 1 + bias*(1-hpFrac) for mob-flee threats
    public float retreatTargetHp = 14.0F;    // heal up to this before resuming
    public double retreatSafeDistance = 10.0D; // break contact until hostiles are at least this far
    public int retreatTimeoutTicks = 600;    // give up healing after ~30s
    public float poisonTreatHp = 12.0F;      // treat poison/wither below this hp

    // ---- new threats
    public int drownEngageAir = 90;
    public int drownReleaseAir = 250;
    public double fireWaterRadius = 8.0D;
    public double mlgFallTrigger = 4.0D;     // fallDistance that arms the water-bucket MLG
    public int voidScanDepth = 24;           // gapBelow >= this counts as a void drop

    // ---- watchdog
    public int behaviorStuckTicks = 60;      // generic no-progress window

    // ---- distress (the trigger for the cooperative LLM survival agent)
    /**
     * A survival behavior running longer than this WITHOUT resolving (still taking damage) means the
     * rule ladder is exhausted — {@link SurvivalBrain#inDistress()} flips. Mirrors the
     * {@code aiSurvivalDistressTicks} setting the adapter refreshes.
     */
    public int distressTicks = 60;
    /** "Still being hurt" window for distress: damage this recently means the behavior isn't working. */
    public int distressDamageWindowTicks = 40;

    // ---- shelter (turtle-when-weak)
    /** Proactively shelter at night when undergeared with hostiles visible (NIGHT_EXPOSURE). */
    public boolean shelter = true;
    /** Failsafe: stop sheltering after this long even if the night/threat hasn't resolved. */
    public int shelterMaxTicks = 14000;      // a full Minecraft night is 10 min = 12000 ticks
    /** Ticks a shooter must stay without line-of-sight before BREAK_LOS settles into waiting. */
    public int shelterLosGraceTicks = 40;
    /** How deep the dig-in turtle hole goes before sealing overhead. */
    public int shelterDigDepth = 2;
    /** After a shelter timeout, don't re-trigger NIGHT_EXPOSURE for this long (get back to work). */
    public int shelterRetryCooldownTicks = 1200;
}
