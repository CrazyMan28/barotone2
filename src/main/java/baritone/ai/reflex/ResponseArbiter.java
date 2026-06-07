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
 * <li>FLEE/COMBAT/RETREAT engage only while {@code working} so manual play is never hijacked —
 *     but once engaged they stay through a mission's end, and a mob behavior may escalate to
 *     another mob behavior regardless of {@code working} (already mid-episode);</li>
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
    private FleeEscalation flee;

    // hp-loss window for losingTrade()
    private long hpWindowStart = Long.MIN_VALUE;
    private float hpAtWindowStart;

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
        add(threats, Detectors.poison(s, t));
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

        // 5. running behavior: preemption, escalation, stickiness, release
        if (active != BehaviorId.NONE) {
            activeTicks++;
            Threat top = best(threats, s, true);
            if (top != null && activeCause != null && top.severity > activeCause.severity
                    && behaviorFor(top.type) != active) {
                BehaviorId wanted = behaviorFor(top.type);
                if (!isMobBehavior(wanted)) {
                    return engage(wanted, top); // lethal terrain always preempts
                }
                // healing already breaks contact — don't let a mere flee threat cancel it
                if (active != BehaviorId.RETREAT_HEAL) {
                    if (active == BehaviorId.COMBAT && losingTrade(s, t)) {
                        return engage(BehaviorId.RETREAT_HEAL, activeCause);
                    }
                    return engage(wanted, top); // creeper joins the fight -> flee, etc.
                }
            }
            if (active == BehaviorId.COMBAT && losingTrade(s, t)) {
                return engage(BehaviorId.RETREAT_HEAL, activeCause);
            }
            if (!released(s, t, fleeSuppressed)) {
                return new ResponsePlan(active, activeCause, FleeMode.NORMAL, combatTargetId);
            }
            active = BehaviorId.NONE;
            activeCause = null;
            combatTargetId = -1;
        }

        // 6. fresh engagement (working-gated)
        activeTicks = 0;
        Threat top = best(threats, s, false);
        if (top == null) {
            return ResponsePlan.NONE;
        }
        return engage(behaviorFor(top.type), top);
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
    private Threat best(List<Threat> threats, WorldSnapshot s, boolean escalating) {
        boolean workingOk = s.working || (escalating && isMobBehavior(active));
        Threat best = null;
        for (Threat th : threats) {
            if (requiresWorking(behaviorFor(th.type)) && !workingOk) {
                continue;
            }
            if (best == null || th.severity > best.severity
                    || (th.severity == best.severity && th.type.ordinal() < best.type.ordinal())) {
                best = th;
            }
        }
        return best;
    }

    private ResponsePlan engage(BehaviorId behavior, Threat cause) {
        if (behavior != active) {
            active = behavior;
            activeCause = cause;
            activeTicks = 0;
        } else {
            activeCause = cause;
        }
        if (behavior == BehaviorId.COMBAT && cause.source != null) {
            combatTargetId = cause.source.entityId;
        }
        return new ResponsePlan(active, activeCause, FleeMode.NORMAL, combatTargetId);
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
            case RANGED:
            case SWARM:
                return BehaviorId.FLEE;
            case MELEE_MOB:
                return BehaviorId.COMBAT;
            case POISON:
                return BehaviorId.RETREAT_HEAL;
            case HUNGER:
                return BehaviorId.EAT;
            default:
                return BehaviorId.NONE;
        }
    }

    private static boolean requiresWorking(BehaviorId b) {
        return isMobBehavior(b);
    }

    private static boolean isMobBehavior(BehaviorId b) {
        return b == BehaviorId.FLEE || b == BehaviorId.COMBAT || b == BehaviorId.RETREAT_HEAL;
    }
}
