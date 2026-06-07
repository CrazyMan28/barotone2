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
 * <li>lava preempts anything, even a running reflex;</li>
 * <li>a running behavior is sticky until its release condition fires (hysteresis — release radii
 *     are wider than engage radii), and only a strictly more severe threat preempts it;</li>
 * <li>FLEE/COMBAT engage only while {@code working} so manual play is never hijacked — but once
 *     engaged they stay through a mission's end, and a mob behavior may escalate to another mob
 *     behavior regardless of {@code working} (already mid-episode);</li>
 * <li>the flee-episode clock ({@link FleeEscalation}) stops "fleeing forever".</li>
 * </ul>
 */
public final class ResponseArbiter {

    private BehaviorId active = BehaviorId.NONE;
    private Threat activeCause;
    private int activeTicks;
    private int combatTargetId = -1;
    private FleeEscalation flee;

    public ResponsePlan decide(WorldSnapshot s, ReflexTuning t) {
        if (flee == null) {
            flee = new FleeEscalation(t.maxFleeTicks, t.fleeCooldownTicks, t.fleeEpisodeGapTicks);
        }

        // 1. detect
        List<Threat> threats = new ArrayList<>(6);
        add(threats, Detectors.lava(s, t));
        add(threats, Detectors.drown(s, t));
        Threat fleeThreat = Detectors.fleeMob(s, t);
        Threat meleeThreat = Detectors.meleeFight(s, t);
        add(threats, fleeThreat);
        add(threats, meleeThreat);
        add(threats, Detectors.hunger(s, t));

        // 2. the flee-episode clock runs EVERY tick (episodes span engage gaps)
        boolean fleeSuppressed = flee.suppressed(s.gameTime, fleeThreat != null);
        if (fleeSuppressed && fleeThreat != null) {
            threats.remove(fleeThreat);
        }

        // 3. a fresh melee threat refreshes the combat target (switch to whoever is hitting us)
        if (meleeThreat != null && meleeThreat.source != null) {
            combatTargetId = meleeThreat.source.entityId;
        }

        // 4. running behavior: preemption, then stickiness, then release
        if (active != BehaviorId.NONE) {
            activeTicks++;
            Threat top = best(threats, s, true);
            if (top != null) {
                BehaviorId wanted = behaviorFor(top.type);
                if (wanted != active && activeCause != null && top.severity > activeCause.severity) {
                    return engage(wanted, top);
                }
            }
            if (!released(s, t, fleeSuppressed)) {
                return new ResponsePlan(active, activeCause, FleeMode.NORMAL, combatTargetId);
            }
            active = BehaviorId.NONE;
            activeCause = null;
            combatTargetId = -1;
        }

        // 5. fresh engagement (working-gated)
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

    private static void add(List<Threat> threats, Threat t) {
        if (t != null) {
            threats.add(t);
        }
    }

    /**
     * Highest severity wins; declaration order of {@link ThreatType} breaks ties. Threats whose
     * behavior needs the working gate are skipped when not working — unless the bot is already
     * mid mob-episode ({@code midMobEpisode}), where escalation must stay armed.
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
            case FLEE:
                return fleeSuppressed
                        || !Detectors.fleeRequiredWithin(s, t, Detectors.fleeEngageRadius(t) + 4D);
            case COMBAT: {
                MobInfo target = findMob(s, combatTargetId);
                if (target == null || target.distance > t.fightReleaseDistance) {
                    return true;
                }
                // a melee-mob fight ends when we stop being hit; a chosen skeleton fight sees it through
                return !target.skeleton && !Detectors.recentlyHurt(s, t);
            }
            case EAT:
                return s.food >= t.eatReleaseFood || s.screenOpen || s.bestFoodSlot < 0
                        || activeTicks > t.eatTimeoutTicks;
            default:
                return true;
        }
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
            case DROWN:
                return BehaviorId.SURFACE;
            case CREEPER:
            case RANGED:
                return BehaviorId.FLEE;
            case MELEE_MOB:
            case SWARM:
                return BehaviorId.COMBAT;
            case HUNGER:
                return BehaviorId.EAT;
            default:
                return BehaviorId.NONE;
        }
    }

    private static boolean requiresWorking(BehaviorId b) {
        return b == BehaviorId.FLEE || b == BehaviorId.COMBAT;
    }

    private static boolean isMobBehavior(BehaviorId b) {
        return b == BehaviorId.FLEE || b == BehaviorId.COMBAT || b == BehaviorId.RETREAT_HEAL;
    }
}
