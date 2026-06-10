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
 * Decides which behavior answers this tick's threats. Replaces the old {@code ReflexPlanner.pick()}
 * enum ladder with severity scoring, but preserves every semantic the ladder had (each was earned
 * from a live failure):
 *
 * <ul>
 * <li>lethal terrain (lava/void/suffocation/drown/fire/fall) preempts anything, even a running
 *     reflex;</li>
 * <li>a running behavior is sticky until its release condition fires (hysteresis — release radii
 *     are wider than engage radii), and only a strictly more severe threat preempts it;</li>
 * <li>mob defense (FLEE/COMBAT/RETREAT) engages even when idle by default ({@code defendIdle} —
 *     the live logs showed bots beaten to death standing around post-mission); with
 *     {@code defendIdle=false} it engages only while {@code working} so manual play is never
 *     hijacked, though an engaged mob behavior may still escalate mid-episode;</li>
 * <li>low hp scales mob-flee severities up ({@code lowHpFleeBias}) and a losing fight disengages
 *     into RETREAT_HEAL instead of trading to death;</li>
 * <li>the flee-episode clock ({@link FleeEscalation}) stops "fleeing forever".</li>
 * </ul>
 */
public final class ResponseArbiter {

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

    public ResponsePlan decide(WorldSnapshot s, ReflexTuning t) {
        if (flee == null) {
            flee = new FleeEscalation(t.maxFleeTicks, t.fleeCooldownTicks, t.fleeEpisodeGapTicks);
        }
        trackHpWindow(s, t);

        // 1. detect
        List<Threat> threats = new ArrayList<>(10);
        add(threats, Detectors.lava(s, t));
        add(threats, Detectors.voidDrop(s, t));
        add(threats, Detectors.suffocation(s, t));
        add(threats, Detectors.drown(s, t));
        add(threats, Detectors.fire(s, t));
        add(threats, Detectors.fall(s, t));
        Threat fleeThreat = Detectors.fleeMob(s, t);
        Threat meleeThreat = Detectors.meleeFight(s, t);
        add(threats, fleeThreat);
        add(threats, meleeThreat);
        add(threats, Detectors.swarm(s, t));
        add(threats, Detectors.overwhelmed(s, t));
        add(threats, Detectors.poison(s, t));
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

        // 4. a fresh melee threat refreshes the combat target (switch to whoever is hitting us)
        if (meleeThreat != null && meleeThreat.source != null) {
            combatTargetId = meleeThreat.source.entityId;
        }

        long now = s.gameTime;

        // 5. running behavior: preemption, escalation, stickiness, release
        if (active != BehaviorId.NONE) {
            activeTicks++;
            Threat top = best(threats, s, t, true);
            if (top != null && activeCause != null && top.severity > activeCause.severity
                    && behaviorFor(top.type) != active) {
                BehaviorId wanted = behaviorFor(top.type);
                if (!isMobBehavior(wanted)) {
                    return engage(wanted, top, now); // lethal terrain always preempts
                }
                // a sheltered bot stays sheltered: the zombie crowd outside the wall must not
                // pull it into the open night — unless it is being hit right now (breached)
                boolean breached = s.ticksSinceHurt <= 5;
                if (active == BehaviorId.SHELTER && !breached) {
                    // hold the shelter
                } else if (active != BehaviorId.RETREAT_HEAL) {
                    // (healing already breaks contact — don't let a mere flee threat cancel it)
                    if (active == BehaviorId.COMBAT && losingTrade(s, t)) {
                        return engage(BehaviorId.RETREAT_HEAL, activeCause, now);
                    }
                    return engage(wanted, top, now); // creeper joins the fight -> flee, etc.
                }
            }
            if (active == BehaviorId.COMBAT && losingTrade(s, t)) {
                return engage(BehaviorId.RETREAT_HEAL, activeCause, now);
            }
            // anti-flap: a committed mob episode debounces its release so a mob crossing the
            // engage boundary can't make us thrash; terrain reflexes release the instant the
            // physical condition clears (no flapping risk there, and we want them snappy).
            boolean wantRelease = released(s, t, fleeSuppressed);
            boolean doRelease = isMobBehavior(active)
                    ? mobMemory.shouldRelease(now, wantRelease, t.minMobDwellTicks, t.mobReleaseGraceTicks)
                    : wantRelease;
            if (!doRelease) {
                // running isn't working: RESOLVE the chase instead of fleeing forever
                if (active == BehaviorId.FLEE && fleeMode == FleeMode.NORMAL
                        && flee.unresolved(s.gameTime)) {
                    fleeMode = pickFleeResolution(s, t);
                }
                return new ResponsePlan(active, activeCause, fleeMode, combatTargetId);
            }
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
        return engage(behaviorFor(top.type), top, now);
    }

    /** Ticks the current behavior has been running (for eat timeout, telemetry). */
    public int activeTicks() {
        return activeTicks;
    }

    public BehaviorId active() {
        return active;
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
     * Highest severity wins; declaration order of {@link ThreatType} breaks ties. Threats whose
     * behavior needs the working gate are skipped when not working — unless the bot is already
     * mid mob-episode ({@code escalating}), where escalation must stay armed.
     */
    private Threat best(List<Threat> threats, WorldSnapshot s, ReflexTuning t, boolean escalating) {
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

    private ResponsePlan engage(BehaviorId behavior, Threat cause, long now) {
        if (behavior != active) {
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

    /**
     * The resolution ladder for a chase that running couldn't shake: pillar above a creeper
     * (it can't reach and won't detonate), wall off anything else's approach/arrows, and with
     * no blocks to spare, at least try a perpendicular escape route.
     */
    private FleeMode pickFleeResolution(WorldSnapshot s, ReflexTuning t) {
        double radius = Detectors.fleeEngageRadius(t) + 4D;
        boolean creeperChasing = Detectors.anyWithin(s, radius, m -> m.creeper);
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
                return !s.onFire;
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
                    // sheltering from a shooter: out when it leaves (hysteresis) or we can take it
                    return !Detectors.anyWithin(s, Detectors.fleeEngageRadius(t) + 4D, m -> m.skeleton)
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
                return BehaviorId.EXTINGUISH_FIRE;
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
