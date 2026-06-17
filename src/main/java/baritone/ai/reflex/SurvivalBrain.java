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
 * The survival decision core — a rule-based "brain" that takes in the <em>whole</em> tick (every
 * threat, the gear, the escape routes, the resources) and decides one coherent survival response,
 * always erring toward staying alive.
 *
 * <p>This replaces the old single-threat {@code ResponseArbiter.pick()} model. The difference that
 * matters: the brain first builds a {@link SituationAssessment} of the <em>entire</em> picture, then
 * reasons about <em>combinations</em> — the deaths in the live logs were almost never one threat,
 * they were skeleton+zombie, lava+mob, cornered-while-fleeing, or quietly starving while kiting.
 * Every proven single-threat semantic the old ladder earned from a real death is preserved (the
 * existing arbiter test-suite still guards them); the holistic rules are <em>added on top</em> and
 * are inert unless their combination actually occurs.
 *
 * <p>Pure, no Minecraft, tick-fed — the same hand-built-snapshot testability as before. When an
 * episode ends it leaves a {@link SurvivalReport} so the adapter can hand the paused LLM agent an
 * accurate "here's what I did, here's where not to walk back" instead of a stale view.
 *
 * <p>Holistic rules layered on the ported core:
 * <ul>
 *   <li><b>critical starvation</b> ({@link Detectors#starvation}) — eat before a far mob kites us to
 *       death, but only when nothing is close enough to make eating suicide;</li>
 *   <li><b>cornered → bunker</b> — when fleeing a non-creeper with no safe direction left, wall off
 *       and heal instead of sprinting into the wall that eats hits;</li>
 *   <li><b>hard low-hp floor</b> — at/under {@link ReflexTuning#criticalHp} never stand and trade.</li>
 * </ul>
 */
public class SurvivalBrain {

    private BehaviorId active = BehaviorId.NONE;
    private Threat activeCause;
    private int activeTicks;
    private int combatTargetId = -1;
    private FleeMode fleeMode = FleeMode.NORMAL;
    private FleeEscalation flee;
    /** Debounces release of mob behaviors so a mob bobbing in/out of range can't cause flapping. */
    private final ThreatMemory mobMemory = new ThreatMemory();

    // hp-loss window for losingTrade()
    private long hpWindowStart = Long.MIN_VALUE;
    private float hpAtWindowStart;

    /** NIGHT_EXPOSURE is suppressed until this game time after a shelter timeout. */
    private long shelterCooldownUntil = Long.MIN_VALUE;

    // ---- holistic state (the whole-picture read + the post-episode report)
    private SituationAssessment lastAssessment = new SituationAssessment();
    private SurvivalReport lastReport;
    // episode bookkeeping for the report
    private double epStartX, epStartY, epStartZ;
    private double epThreatX, epThreatY, epThreatZ;
    private boolean epThreatHadPos;
    private boolean epEscalated;

    public ResponsePlan decide(WorldSnapshot s, ReflexTuning t) {
        if (flee == null) {
            flee = new FleeEscalation(t.maxFleeTicks, t.fleeCooldownTicks, t.fleeEpisodeGapTicks);
        }
        trackHpWindow(s, t);

        // 1. detect EVERYTHING (one place, the full threat picture)
        List<Threat> threats = new ArrayList<>(12);
        add(threats, Detectors.lava(s, t));
        add(threats, Detectors.voidDrop(s, t));
        add(threats, Detectors.suffocation(s, t));
        add(threats, Detectors.drown(s, t));
        add(threats, Detectors.fire(s, t));
        add(threats, Detectors.contactHazard(s, t));
        add(threats, Detectors.fall(s, t));
        Threat fleeThreat = Detectors.fleeMob(s, t);
        Threat meleeThreat = Detectors.meleeFight(s, t);
        add(threats, fleeThreat);
        add(threats, meleeThreat);
        add(threats, Detectors.swarm(s, t));
        add(threats, Detectors.overwhelmed(s, t));
        add(threats, Detectors.poison(s, t));
        add(threats, Detectors.starvation(s, t));
        // a shelter that timed out must not re-trigger immediately — back to work for a while
        if (s.gameTime >= shelterCooldownUntil) {
            add(threats, Detectors.nightExposure(s, t));
        }
        add(threats, Detectors.hunger(s, t));

        // 2. the flee-episode clock runs EVERY tick (episodes span engage gaps)
        boolean fleeSuppressed = flee.suppressed(s.gameTime, fleeThreat != null);
        if (fleeSuppressed && fleeThreat != null) {
            threats.remove(fleeThreat);
        }

        // 3. health modulation: the lower our hp, the scarier every mob we'd have to outrun
        double hpFrac = s.maxHp <= 0 ? 1D : Math.min(1D, s.hp / (double) s.maxHp);
        for (Threat th : threats) {
            if (th.type == ThreatType.CREEPER || th.type == ThreatType.RANGED
                    || th.type == ThreatType.SWARM) {
                th.severity = Math.min(100,
                        (int) Math.round(th.severity * (1D + t.lowHpFleeBias * (1D - hpFrac))));
            }
        }

        // 3b. read the WHOLE situation once, off the modulated threat list (for combo rules + report)
        lastAssessment = assess(s, t, threats);

        // 4. a fresh melee threat refreshes the combat target (switch to whoever is hitting us)
        if (meleeThreat != null && meleeThreat.source != null) {
            combatTargetId = meleeThreat.source.entityId;
        }

        long now = s.gameTime;

        // 5. running behavior: preemption, escalation, stickiness, release
        if (active != BehaviorId.NONE) {
            activeTicks++;
            Threat top = best(threats, s, t, true);
            // terrain hazards preempt a running behavior REGARDLESS of severity (a low-hp creeper
            // scores 100 and would otherwise keep us fleeing while we drown/burn) — get out first.
            boolean terrainPreempt = top != null && isTerrainHazard(top.type)
                    && behaviorFor(top.type) != active;
            if (top != null && activeCause != null && resolve(top, s, t) != active
                    && (top.severity > activeCause.severity || terrainPreempt)) {
                BehaviorId wanted = resolve(top, s, t);
                if (!isMobBehavior(wanted)) {
                    return engage(wanted, top, now, s); // lethal terrain (or eat-now) always preempts
                }
                // COMMIT to a running shelter. Abandoning it to flee/retreat the very mobs it's
                // walling out just thrashes (shelter->retreat->flee->shelter — the live logs showed
                // the bot dying that way, oscillating for 80s and never sealing in) and never lets the
                // dig-in finish. The ONLY thing worth breaking a shelter for is a creeper, which blasts
                // the wall; every other mob is handled by sealing ourselves in.
                if (active == BehaviorId.SHELTER) {
                    if (top.type == ThreatType.CREEPER) {
                        return engage(wanted, top, now, s);
                    }
                    // else hold the shelter — finish digging in / sealing
                } else if (active != BehaviorId.RETREAT_HEAL) {
                    // (healing already breaks contact — don't let a mere flee threat cancel it)
                    if (active == BehaviorId.COMBAT && losingTrade(s, t)) {
                        return engage(BehaviorId.RETREAT_HEAL, activeCause, now, s);
                    }
                    return engage(wanted, top, now, s); // creeper joins the fight -> flee, etc.
                }
            }
            if (active == BehaviorId.COMBAT && losingTrade(s, t)) {
                return engage(BehaviorId.RETREAT_HEAL, activeCause, now, s);
            }
            // anti-flap: a committed mob episode debounces its release so a mob crossing the
            // engage boundary can't make us thrash; terrain reflexes release the instant the
            // physical condition clears (no flapping risk there, and we want them snappy).
            boolean wantRelease = released(s, t, fleeSuppressed);
            boolean doRelease = isMobBehavior(active)
                    ? mobMemory.shouldRelease(now, wantRelease, t.minMobDwellTicks, t.mobReleaseGraceTicks)
                    : wantRelease;
            if (!doRelease) {
                // running isn't working: RESOLVE the chase instead of fleeing forever. Normally this
                // waits for the flee clock to confirm we're not shaking it, but when we're cornered
                // (no room to run) waiting just stands us still next to the threat — resolve NOW.
                if (active == BehaviorId.FLEE && fleeMode == FleeMode.NORMAL
                        && (flee.unresolved(s.gameTime) || lastAssessment.cornered)) {
                    fleeMode = pickFleeResolution(s, t);
                    if (fleeMode != FleeMode.NORMAL) {
                        epEscalated = true;
                    }
                } else if (active == BehaviorId.FLEE && fleeMode != FleeMode.NORMAL
                        && exhaustedFleeMode(s, t)) {
                    // already escalated to PILLAR/WALL but the blocks ran out before it sealed us in
                    // (e.g. a creeper on a ledge needs an 8-tall pillar, we had 3) — don't keep
                    // re-emitting a build we can't finish. Re-pick: another build if any blocks are
                    // left, otherwise run a fresh direction instead of standing on a stub pillar.
                    fleeMode = pickFleeResolution(s, t);
                    epEscalated = true;
                }
                return new ResponsePlan(active, activeCause, fleeMode, combatTargetId);
            }
            // episode over: leave a report for the paused agent, then go idle
            finishEpisode(s);
            active = BehaviorId.NONE;
            activeCause = null;
            combatTargetId = -1;
            fleeMode = FleeMode.NORMAL;
            mobMemory.reset();
        }

        // 6. fresh engagement (working-gated)
        activeTicks = 0;
        Threat top = best(threats, s, t, false);
        if (top == null) {
            return ResponsePlan.NONE;
        }
        return engage(resolve(top, s, t), top, now, s);
    }

    /** Ticks the current behavior has been running (for eat timeout, telemetry). */
    public int activeTicks() {
        return activeTicks;
    }

    public BehaviorId active() {
        return active;
    }

    /** The whole-picture read from the most recent tick — for the HUD, telemetry, and the report. */
    public SituationAssessment situation() {
        return lastAssessment;
    }

    /** The report left by the last episode that ended (null until one has). */
    public SurvivalReport lastReport() {
        return lastReport;
    }

    // ---------------------------------------------------------------- holistic assessment

    /**
     * Read the entire tick into one summary: overall danger level, power balance, whether we're
     * cornered, what resources we have, and the categorised mob counts. This is the "take in all the
     * data" step the combo rules and the agent report both read from.
     */
    private SituationAssessment assess(WorldSnapshot s, ReflexTuning t, List<Threat> threats) {
        SituationAssessment a = new SituationAssessment();
        a.threats = new ArrayList<>(threats);
        a.hpFrac = s.maxHp <= 0 ? 1F : Math.min(1F, s.hp / s.maxHp);
        a.lowHp = s.hp <= t.criticalHp;
        a.starving = s.food <= t.criticalStarvationFood;
        a.night = s.night;
        a.inLiquidHazard = s.inLava || (s.underWater && s.air < t.drownEngageAir);

        a.playerPower = CombatPower.playerPower(s);
        a.threatPower = CombatPower.threatPower(s, t);
        a.powerFavorable = CombatPower.fightFavorable(s, t);

        int safe = 0;
        for (boolean ok : s.octantSafe) {
            if (ok) {
                safe++;
            }
        }
        a.safeDirections = safe;
        a.cornered = safe <= t.corneredSafeDirections;

        a.hasBlocks = s.blockSlot >= 0 && s.blockCount >= 2;
        a.hasFood = s.bestFoodSlot >= 0;
        a.hasWaterBucket = s.waterBucketSlot >= 0;
        a.hasBed = s.bedSlot >= 0;
        a.hasWeapon = s.bestWeaponSlot >= 0;

        for (MobInfo m : s.mobs) {
            if (m.distance > t.perceptionRadius) {
                continue;
            }
            if (m.creeper) {
                a.creepersNear++;
            } else if (Detectors.isShooter(m)) {
                a.rangedNear++;
            } else if (m.hostile) {
                a.meleeNear++;
            }
        }
        a.hostilesNear = a.creepersNear + a.rangedNear + a.meleeNear;
        a.terrainHazard = hasTerrainHazard(threats);

        a.level = level(s, t, threats, a);
        return a;
    }

    private static boolean hasTerrainHazard(List<Threat> threats) {
        for (Threat th : threats) {
            switch (th.type) {
                case LAVA:
                case VOID:
                case SUFFOCATION:
                case DROWN:
                case FIRE:
                case FALL:
                    return true;
                default:
            }
        }
        return false;
    }

    private static SituationAssessment.Level level(WorldSnapshot s, ReflexTuning t,
                                                   List<Threat> threats, SituationAssessment a) {
        if (a.terrainHazard || (a.lowHp && a.hostilesNear > 0)
                || (a.hostilesNear > 0 && a.cornered && !a.powerFavorable)) {
            return SituationAssessment.Level.CRITICAL;
        }
        int top = 0;
        for (Threat th : threats) {
            top = Math.max(top, th.severity);
        }
        if (top >= Detectors.SEV_OVERWHELMED || (a.hostilesNear > 0 && !a.powerFavorable)) {
            return SituationAssessment.Level.ENDANGERED;
        }
        if (top > 0) {
            return SituationAssessment.Level.WATCHFUL;
        }
        return SituationAssessment.Level.SAFE;
    }

    // ---------------------------------------------------------------- combo / survival overrides

    /**
     * Map a threat to the behavior that answers it, then apply the holistic combo overrides. Plain
     * {@link #behaviorFor} is the proven 1:1 mapping; the overrides only ever fire when their
     * combination is actually present, so single-threat decisions are unchanged.
     */
    private BehaviorId resolve(Threat top, WorldSnapshot s, ReflexTuning t) {
        BehaviorId behavior = behaviorFor(top.type);
        SituationAssessment a = lastAssessment;
        // A Warden is unwinnable AND tunnels through blocks: no bunker holds it and no fight wins it.
        // It MUST always stay on the flee ladder — bypass every combo override (slowness→shelter,
        // cornered→retreat, undergeared→dig-in) that would otherwise sit us still next to it.
        if (top.type == ThreatType.WARDEN) {
            return BehaviorId.FLEE;
        }
        // Undergeared last resort: being beaten with NOTHING to fight/flee/heal with (no blocks to
        // pillar/wall, no food to heal) — digging straight down (SHELTER) is the one defense that needs
        // no resources, breaking contact through terrain. Far better than fleeing nowhere or "healing"
        // with no food (the live death loop: thrashing flee<->retreat for 80s, then a creeper got it).
        // Excludes creepers: a bare hole can't be sealed against a blast, so they keep the flee ladder.
        boolean broke = !a.hasBlocks && !a.hasFood;
        if (broke && s.digDownSafe && t.shelter && a.creepersNear == 0
                && (top.type == ThreatType.OVERWHELMED || top.type == ThreatType.OUTMATCHED
                    || top.type == ThreatType.SWARM)) {
            return BehaviorId.SHELTER;
        }
        // RETREAT_HEAL with no food to heal with and a hostile still nearby: running in a heal loop is
        // futile (nothing to eat, regen is off below 18 hunger, the mob keeps re-engaging — the live
        // thrash). Seal in (SHELTER) instead so contact breaks and natural regen can tick once we're
        // walled away. Excludes creepers (a hole can't be sealed against a blast — keep the flee ladder).
        if (behavior == BehaviorId.RETREAT_HEAL && !a.hasFood && a.creepersNear == 0
                && t.shelter && (a.hasBlocks || s.digDownSafe)
                && Detectors.anyWithin(s, t.retreatSafeDistance, m -> m.hostile || m.skeleton)) {
            return BehaviorId.SHELTER;
        }
        // Slowed (witch's Slowness, soul sand, cobwebs): we can't kite, flee, OR break contact — every
        // mobile tactic fails. The only thing that works is to wall/dig in. Route ALL mob responses to
        // SHELTER (no creeper around: never bunker beside one).
        if (s.slownessLevel >= 1 && a.creepersNear == 0 && t.shelter && (s.digDownSafe || a.hasBlocks)
                && (behavior == BehaviorId.FLEE || behavior == BehaviorId.COMBAT
                    || behavior == BehaviorId.RETREAT_HEAL)) {
            return BehaviorId.SHELTER;
        }
        // Withered: a DoT natural regen can't outrun, so every blow we trade compounds it — don't stand
        // and fight, break contact and wait it out (retreat). (Plain poison can't kill, so we still fight.)
        if (behavior == BehaviorId.COMBAT && s.withered) {
            return BehaviorId.RETREAT_HEAL;
        }
        // cornered while fleeing something that isn't a creeper: sprinting just runs into the wall
        // and eats hits. If we have blocks, wall off and heal instead of dying in the open. (A
        // creeper is the exception — never bunker beside one; the flee ladder pillars up instead.)
        if (behavior == BehaviorId.FLEE && a.cornered && top.type != ThreatType.CREEPER
                && a.hasBlocks && a.creepersNear == 0) {
            return BehaviorId.RETREAT_HEAL;
        }
        return behavior;
    }

    // ---------------------------------------------------------------- internals

    private void trackHpWindow(WorldSnapshot s, ReflexTuning t) {
        if (hpWindowStart == Long.MIN_VALUE || s.gameTime < hpWindowStart
                || s.gameTime - hpWindowStart >= t.combatLossWindowTicks) {
            hpWindowStart = s.gameTime;
            hpAtWindowStart = s.hp;
        }
    }

    /** Losing this fight: hp critically low, or bleeding hp fast within the loss window. */
    private boolean losingTrade(WorldSnapshot s, ReflexTuning t) {
        return s.hp < t.combatRetreatHp || hpAtWindowStart - s.hp >= t.combatLossDelta;
    }

    private static void add(List<Threat> threats, Threat t) {
        if (t != null) {
            threats.add(t);
        }
    }

    /**
     * Pick the threat to answer. <b>Lethal terrain always wins outright</b> — you must get out of
     * lava/drowning/fire/suffocation/a killing fall BEFORE dealing with any mob, no matter how scary
     * the mob is (a low-hp creeper scores 100 and would otherwise out-rank drowning/fire and leave the
     * bot fleeing while it suffocates — a real death). Among non-terrain threats, highest severity wins
     * and {@link ThreatType} order breaks ties. Working-gated behaviors are skipped when not working
     * unless we're already mid mob-episode.
     */
    private Threat best(List<Threat> threats, WorldSnapshot s, ReflexTuning t, boolean escalating) {
        Threat terrain = null;
        for (Threat th : threats) {
            if (isTerrainHazard(th.type) && (terrain == null || th.severity > terrain.severity
                    || (th.severity == terrain.severity && th.type.ordinal() < terrain.type.ordinal()))) {
                terrain = th;
            }
        }
        if (terrain != null) {
            return terrain; // terrain owns the body until it's resolved
        }
        boolean workingOk = s.working || (escalating && isMobBehavior(active));
        Threat best = null;
        for (Threat th : threats) {
            if (requiresWorking(behaviorFor(th.type), t) && !workingOk) {
                continue;
            }
            if (best == null || th.severity > best.severity
                    || (th.severity == best.severity && th.type.ordinal() < best.type.ordinal())) {
                best = th;
            }
        }
        return best;
    }

    /**
     * Hazards the bot is CURRENTLY INSIDE and takes continuous damage from — these own the body
     * unconditionally (you can't flee a mob while drowning/burning). FALL and VOID are deliberately
     * NOT here: a falling bot at low hp may rightly choose to flee a creeper and eat the landing
     * (the fall is a recoverable trade; lava/fire/drown are not). They still win on raw severity.
     */
    private static boolean isTerrainHazard(ThreatType type) {
        switch (type) {
            case LAVA:
            case DROWN:
            case SUFFOCATION:
            case FIRE:
                return true;
            default:
                return false;
        }
    }

    private ResponsePlan engage(BehaviorId behavior, Threat cause, long now, WorldSnapshot s) {
        if (behavior != active) {
            // a brand-new episode (was idle): record where/what for the post-episode report
            if (active == BehaviorId.NONE) {
                beginEpisode(s, cause);
            }
            active = behavior;
            activeCause = cause;
            activeTicks = 0;
            fleeMode = FleeMode.NORMAL;
            if (isMobBehavior(behavior)) {
                mobMemory.onEngage(now);
            } else {
                mobMemory.reset();
            }
        } else {
            activeCause = cause;
        }
        if (behavior == BehaviorId.COMBAT && cause.source != null) {
            combatTargetId = cause.source.entityId;
        }
        return new ResponsePlan(active, activeCause, fleeMode, combatTargetId);
    }

    private void beginEpisode(WorldSnapshot s, Threat cause) {
        epStartX = s.posX;
        epStartY = s.posY;
        epStartZ = s.posZ;
        epEscalated = false;
        epThreatHadPos = cause != null && cause.source != null;
        if (epThreatHadPos) {
            epThreatX = cause.source.x;
            epThreatY = cause.source.y;
            epThreatZ = cause.source.z;
        } else {
            epThreatX = s.posX;
            epThreatY = s.posY;
            epThreatZ = s.posZ;
        }
    }

    private void finishEpisode(WorldSnapshot s) {
        SurvivalReport r = new SurvivalReport();
        r.behavior = active.describe;
        r.threat = activeCause != null ? activeCause.type.name().toLowerCase() : "danger";
        r.ticks = activeTicks;
        r.outcome = epEscalated ? "escalated" : "resolved";
        double dx = s.posX - epStartX;
        double dy = s.posY - epStartY;
        double dz = s.posZ - epStartZ;
        r.movedBlocks = Math.sqrt(dx * dx + dy * dy + dz * dz);
        // worth telling the agent to avoid the spot only when the threat was a mob/place we left
        r.hasAvoid = epThreatHadPos && r.movedBlocks >= 3D;
        r.avoidX = epThreatX;
        r.avoidY = epThreatY;
        r.avoidZ = epThreatZ;
        StringBuilder sb = new StringBuilder();
        sb.append(active.describe).append(" handled ").append(r.threat);
        if (r.movedBlocks >= 2D) {
            sb.append("; moved ").append((int) Math.round(r.movedBlocks)).append(" blocks");
        }
        if (epEscalated) {
            sb.append("; had to dig in / wall off");
        }
        if (r.hasAvoid) {
            sb.append("; avoid ~(").append((int) Math.round(r.avoidX)).append(",")
              .append((int) Math.round(r.avoidZ)).append(")");
        }
        r.summary = sb.toString();
        lastReport = r;
    }

    /**
     * The resolution ladder for a chase that running couldn't shake: pillar above a creeper
     * (it can't reach and won't detonate), wall off anything else's approach/arrows, and with
     * no blocks to spare, at least try a perpendicular escape route.
     */
    /**
     * The escalated flee mode (PILLAR/WALL) has run out of the blocks it needs to finish: a pillar
     * that can no longer reach safe height (or has no blocks at all), or a wall with nothing to place.
     * When this is true the bot is stuck re-emitting a build it can't complete while the chaser closes
     * — re-pick the resolution (a cheaper build if any blocks remain, else just run).
     */
    private boolean exhaustedFleeMode(WorldSnapshot s, ReflexTuning t) {
        boolean noBlocks = s.blockSlot < 0 || s.blockCount <= 0;
        if (fleeMode == FleeMode.PILLAR) {
            // a creeper above/level with us needs the pillar to out-climb IT by the safe gap; if the
            // blocks left can't cover the climb still owed, the pillar will strand us in the blast.
            double owed = neededPillarClimb(s, t);
            return noBlocks || s.blockCount < owed;
        }
        if (fleeMode == FleeMode.WALL) {
            return noBlocks || s.blockCount < 2;
        }
        return false;
    }

    /** Blocks of vertical climb still owed to clear every nearby creeper by the safe gap. */
    private double neededPillarClimb(WorldSnapshot s, ReflexTuning t) {
        double need = 0D;
        for (MobInfo m : s.mobs) {
            if (m.creeper && m.distance <= t.perceptionRadius) {
                // climb to creeperSafeGap above the creeper's Y; how much of that is still left
                need = Math.max(need, (m.y - s.posY) + t.creeperSafeGap);
            }
        }
        return Math.max(0D, need);
    }

    private FleeMode pickFleeResolution(WorldSnapshot s, ReflexTuning t) {
        // a Warden tunnels through any wall and out-climbs a pillar — no static defense holds it.
        // The only thing that helps is opening distance, so keep sprinting (NEW_DIRECTION).
        if (Detectors.anyWithin(s, t.perceptionRadius, m -> m.unkillable)) {
            return FleeMode.NEW_DIRECTION;
        }
        // a creeper anywhere in perception means PILLAR is the right answer (it can't reach up and
        // won't detonate) — wider than the flee radius so a creeper hovering at the engage boundary
        // still gets the pillar, never a wall we'd have to stand behind next to it.
        boolean creeperChasing = Detectors.anyWithin(s, t.perceptionRadius, m -> m.creeper);
        boolean canPlace = s.onGround && s.blockSlot >= 0;
        if (creeperChasing && canPlace && s.blockCount >= t.pillarHeight) {
            return FleeMode.PILLAR;
        }
        if (canPlace && s.blockCount >= 2) {
            return FleeMode.WALL;
        }
        return FleeMode.NEW_DIRECTION;
    }

    private boolean released(WorldSnapshot s, ReflexTuning t, boolean fleeSuppressed) {
        switch (active) {
            case ESCAPE_LAVA:
                return !(t.antiLava && s.inLava);
            case SURFACE:
                return !s.underWater || s.air >= t.drownReleaseAir;
            case DIG_OUT:
                return !s.headBlockedByGravity;
            case EXTINGUISH_FIRE:
                // released once we're off the fire AND off any contact-damage block (same behavior
                // handles both: run to clear ground — don't release while still standing on the spikes)
                return !s.onFire && !s.contactHazardAtFeet;
            case ANTI_FALL:
                return s.onGround || s.underWater;
            case FLEE: {
                if (fleeSuppressed) {
                    return true;
                }
                boolean fleeMobGone = !Detectors.fleeRequiredWithin(s, t, Detectors.fleeEngageRadius(t) + 4D);
                boolean swarmGone = activeCause == null || activeCause.type != ThreatType.SWARM
                        || hostileCount(s, t.swarmRadius + 4D) < t.swarmCount;
                return fleeMobGone && swarmGone;
            }
            case COMBAT: {
                MobInfo target = findMob(s, combatTargetId);
                if (target == null || target.distance > t.fightReleaseDistance) {
                    return true;
                }
                // a melee-mob fight ends when we stop being hit; a chosen skeleton fight sees it through
                return !target.skeleton && !Detectors.recentlyHurt(s, t);
            }
            case RETREAT_HEAL:
                if (s.hp >= t.retreatTargetHp || activeTicks > t.retreatTimeoutTicks) {
                    return true;
                }
                // nothing left to do: no food to heal with and nobody chasing us
                return s.bestFoodSlot < 0 && !s.poisoned
                        && hostileCount(s, t.retreatSafeDistance) == 0;
            case EAT:
                return s.food >= t.eatReleaseFood || s.screenOpen || s.bestFoodSlot < 0
                        || activeTicks > t.eatTimeoutTicks;
            case SHELTER: {
                if (activeTicks > t.shelterMaxTicks) {
                    // failsafe: don't cower forever — and don't re-trigger right away either
                    shelterCooldownUntil = s.gameTime + t.shelterRetryCooldownTicks;
                    return true;
                }
                if (activeCause != null && activeCause.type == ThreatType.RANGED) {
                    // sheltering from a shooter: hold until it has fully left our perception, not
                    // just stepped past melee range — a kiting skeleton/blaze backs off and keeps
                    // shooting, and resuming into that (the live death loop) is fatal
                    return !Detectors.anyWithin(s, t.perceptionRadius, Detectors::isShooter)
                            || Detectors.combatReady(s, t);
                }
                // night turtle: out at dawn, once geared, or once nobody is visible any more
                return Detectors.nightExposure(s, t) == null;
            }
            default:
                return true;
        }
    }

    private static int hostileCount(WorldSnapshot s, double radius) {
        int count = 0;
        for (MobInfo m : s.mobs) {
            if (m.distance <= radius && (m.hostile || m.creeper || m.skeleton)) {
                count++;
            }
        }
        return count;
    }

    private static MobInfo findMob(WorldSnapshot s, int entityId) {
        if (entityId < 0) {
            return null;
        }
        for (MobInfo m : s.mobs) {
            if (m.entityId == entityId) {
                return m;
            }
        }
        return null;
    }

    private static BehaviorId behaviorFor(ThreatType type) {
        switch (type) {
            case LAVA:
                return BehaviorId.ESCAPE_LAVA;
            case VOID:
            case FALL:
                return BehaviorId.ANTI_FALL;
            case DROWN:
                return BehaviorId.SURFACE;
            case SUFFOCATION:
                return BehaviorId.DIG_OUT;
            case FIRE:
            case CONTACT_HAZARD:
                // both answered by "get off the burning/spiked block" — run to clear ground
                return BehaviorId.EXTINGUISH_FIRE;
            case WARDEN:
            case CREEPER:
            case SWARM:
            case OUTMATCHED:
                return BehaviorId.FLEE;
            case RANGED:
            case NIGHT_EXPOSURE:
                // shooters are answered with cover (open-field fleeing just eats arrows in the
                // back — 31% of the analyzed deaths), night exposure with a proactive turtle
                return BehaviorId.SHELTER;
            case MELEE_MOB:
                return BehaviorId.COMBAT;
            case OVERWHELMED:
            case POISON:
                return BehaviorId.RETREAT_HEAL;
            case HUNGER:
            case STARVATION:
                return BehaviorId.EAT;
            default:
                return BehaviorId.NONE;
        }
    }

    private static boolean requiresWorking(BehaviorId b, ReflexTuning t) {
        return !t.defendIdle && isMobBehavior(b);
    }

    private static boolean isMobBehavior(BehaviorId b) {
        return b == BehaviorId.FLEE || b == BehaviorId.COMBAT || b == BehaviorId.RETREAT_HEAL
                || b == BehaviorId.SHELTER;
    }
}
