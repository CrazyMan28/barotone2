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

import baritone.ai.reflex.behavior.AntiFallBehavior;
import baritone.ai.reflex.behavior.CombatBehavior;
import baritone.ai.reflex.behavior.EatBehavior;
import baritone.ai.reflex.behavior.EscapeLavaBehavior;
import baritone.ai.reflex.behavior.ExtinguishFireBehavior;
import baritone.ai.reflex.behavior.FleeBehavior;
import baritone.ai.reflex.behavior.RetreatAndHealBehavior;
import baritone.ai.reflex.behavior.ShelterBehavior;
import baritone.ai.reflex.behavior.SuffocationBehavior;
import baritone.ai.reflex.behavior.SurfaceBehavior;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The whole reflex decision core behind one pure call: snapshot in, actions out. Owns the
 * arbiter and the behavior instances, runs enter/exit on engagement changes, and reports phase
 * changes so the adapter can log/telemeter exactly once per transition.
 */
public final class ReflexEngine {

    /** One tick's verdict. */
    public static final class Output {
        public ResponsePlan plan = ResponsePlan.NONE;
        public List<ReflexAction> actions = List.of();
        /** A behavior was engaged this tick (phase change). */
        public boolean engaged;
        /** A behavior ended this tick (phase change). */
        public boolean released;
        public BehaviorId previous = BehaviorId.NONE;
        /** How long the released behavior had run, in ticks. */
        public int previousTicks;
        /** Set (once) on the tick a stuck flee escalates to a resolution mode. */
        public FleeMode resolvedMode;
    }

    private final ResponseArbiter arbiter = new ResponseArbiter();
    private final Map<BehaviorId, ReflexBehavior> behaviors = new EnumMap<>(BehaviorId.class);

    private ReflexBehavior current;
    private BehaviorId last = BehaviorId.NONE;
    private FleeMode lastFleeMode = FleeMode.NORMAL;
    private int ticksInBehavior;

    public ReflexEngine() {
        register(new EscapeLavaBehavior());
        register(new SurfaceBehavior());
        register(new SuffocationBehavior());
        register(new ExtinguishFireBehavior());
        register(new AntiFallBehavior());
        register(new FleeBehavior());
        register(new CombatBehavior());
        register(new RetreatAndHealBehavior());
        register(new EatBehavior());
        register(new ShelterBehavior());
    }

    private void register(ReflexBehavior b) {
        behaviors.put(b.id(), b);
    }

    public Output tick(WorldSnapshot s, ReflexTuning t) {
        ResponsePlan plan = arbiter.decide(s, t);
        Output out = new Output();
        out.plan = plan;
        if (plan.behavior != last) {
            out.released = last != BehaviorId.NONE;
            out.previous = last;
            out.previousTicks = ticksInBehavior;
            if (current != null) {
                current.exit();
            }
            current = behaviors.get(plan.behavior);
            out.engaged = plan.behavior != BehaviorId.NONE && current != null;
            if (current != null) {
                current.enter(s, plan);
            }
            last = plan.behavior;
            ticksInBehavior = 0;
        } else {
            ticksInBehavior++;
        }
        if (plan.behavior == BehaviorId.FLEE && plan.fleeMode != lastFleeMode
                && plan.fleeMode != FleeMode.NORMAL) {
            out.resolvedMode = plan.fleeMode;
        }
        lastFleeMode = plan.behavior == BehaviorId.FLEE ? plan.fleeMode : FleeMode.NORMAL;
        if (current != null) {
            out.actions = current.tick(s, t, plan);
        }
        return out;
    }

    /** Currently engaged behavior (NONE when idle) — for status displays. */
    public BehaviorId active() {
        return last;
    }

    /** Ticks the current behavior has been engaged. */
    public int ticksInBehavior() {
        return ticksInBehavior;
    }

    /** Force-release whatever is running (reflexes disabled / process lost control). */
    public void abort() {
        if (current != null) {
            current.exit();
            current = null;
        }
        last = BehaviorId.NONE;
        ticksInBehavior = 0;
    }
}
