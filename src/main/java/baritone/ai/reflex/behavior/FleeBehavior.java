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

package baritone.ai.reflex.behavior;

import baritone.ai.reflex.BehaviorId;
import baritone.ai.reflex.BlockPosSpec;
import baritone.ai.reflex.GoalSpec;
import baritone.ai.reflex.MobInfo;
import baritone.ai.reflex.ReflexAction;
import baritone.ai.reflex.ReflexBehavior;
import baritone.ai.reflex.ReflexMath;
import baritone.ai.reflex.ReflexTuning;
import baritone.ai.reflex.ResponsePlan;
import baritone.ai.reflex.ThreatType;
import baritone.ai.reflex.WorldSnapshot;
import baritone.api.utils.input.Input;

import java.util.ArrayList;
import java.util.List;

/**
 * Run from creepers (and skeletons we can't take). Two ranges:
 * <ul>
 * <li>PANIC (inside blast range): sprint directly away NOW — faster than any path calc;</li>
 * <li>beyond: hand pathing a GoalRunAway from every flee-mob.</li>
 * </ul>
 * The escalation modes (PILLAR / WALL / NEW_DIRECTION) arrive with the resolution ladder.
 */
public final class FleeBehavior implements ReflexBehavior {

    @Override
    public BehaviorId id() {
        return BehaviorId.FLEE;
    }

    @Override
    public void enter(WorldSnapshot s, ResponsePlan plan) {
    }

    @Override
    public List<ReflexAction> tick(WorldSnapshot s, ReflexTuning t, ResponsePlan plan) {
        double radius = Math.max(2D, t.creeperRadius) + 4D;
        // engaged by a SWARM: every hostile is a pursuer, not just the creeper/skeleton set
        boolean fleeAll = plan != null && plan.cause != null && plan.cause.type == ThreatType.SWARM;
        List<MobInfo> mobs = new ArrayList<>();
        MobInfo nearest = null;
        for (MobInfo m : s.mobs) {
            boolean relevant = fleeAll ? (m.hostile || m.creeper || m.skeleton) : (m.creeper || m.skeleton);
            if (relevant && m.distance <= radius) {
                mobs.add(m);
                if (nearest == null || m.distance < nearest.distance) {
                    nearest = m;
                }
            }
        }
        if (nearest == null) {
            return List.of(ReflexAction.releaseAll());
        }
        if (nearest.distance <= t.panicDistance) {
            // PANIC: sprint directly away before pathing can even compute
            List<ReflexAction> actions = new ArrayList<>(4);
            actions.add(ReflexAction.look(ReflexMath.yawAway(s.posX, s.posZ, nearest.x, nearest.z), 5F));
            actions.add(ReflexAction.hold(Input.MOVE_FORWARD, true));
            actions.add(ReflexAction.hold(Input.SPRINT, true));
            if (s.horizontalCollision) {
                actions.add(ReflexAction.hold(Input.JUMP, true));
            }
            return actions;
        }
        BlockPosSpec[] from = new BlockPosSpec[mobs.size()];
        for (int i = 0; i < mobs.size(); i++) {
            from[i] = ReflexMath.feetBlock(mobs.get(i));
        }
        return List.of(
                ReflexAction.releaseAll(),
                ReflexAction.setGoal(GoalSpec.runAway(t.fleeGoalDistance, from))
        );
    }

    @Override
    public void exit() {
    }
}
