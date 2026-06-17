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
import baritone.ai.reflex.FleeMode;
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
 * Run from creepers (and skeletons we can't take). NORMAL mode has two ranges:
 * <ul>
 * <li>PANIC (inside blast range): sprint directly away NOW — faster than any path calc;</li>
 * <li>beyond: hand pathing a GoalRunAway from every flee-mob.</li>
 * </ul>
 * When the arbiter's escalation ladder decides running isn't working, the plan's
 * {@link FleeMode} switches and this behavior RESOLVES the chase instead: PILLAR three
 * blocks up (a creeper can't reach and won't detonate), WALL the mob's approach/arrow line
 * off, or flee a rotated NEW_DIRECTION when there's nothing to build with.
 */
public final class FleeBehavior implements ReflexBehavior {

    /** Ticks spent placing the wall before resuming the run. */
    private static final int WALL_PLACE_TICKS = 12;
    /** Place at/after the jump apex (rising velocity has dropped below this). */
    private static final double PILLAR_APEX_VEL = 0.15D;

    private FleeMode lastMode = FleeMode.NORMAL;
    private double pillarBase = Double.NaN;
    private int wallTicks;

    @Override
    public BehaviorId id() {
        return BehaviorId.FLEE;
    }

    @Override
    public void enter(WorldSnapshot s, ResponsePlan plan) {
        lastMode = FleeMode.NORMAL;
        pillarBase = Double.NaN;
        wallTicks = 0;
    }

    @Override
    public List<ReflexAction> tick(WorldSnapshot s, ReflexTuning t, ResponsePlan plan) {
        if (plan.fleeMode != lastMode) {
            lastMode = plan.fleeMode;
            pillarBase = Double.NaN;
            wallTicks = 0;
        }
        switch (plan.fleeMode) {
            case PILLAR:
                return tickPillar(s, t);
            case WALL:
                return wallTicks++ < WALL_PLACE_TICKS ? tickWall(s, t) : tickRun(s, t, plan, false);
            case NEW_DIRECTION:
                return tickRun(s, t, plan, true);
            default:
                return tickRun(s, t, plan, false);
        }
    }

    @Override
    public void exit() {
    }

    // ---------------------------------------------------------------- modes

    /** Tower up out of reach: aim down, jump, fill the cell we vacate at each apex. */
    private List<ReflexAction> tickPillar(WorldSnapshot s, ReflexTuning t) {
        if (Double.isNaN(pillarBase)) {
            pillarBase = Math.floor(s.posY);
        }
        double built = s.posY - pillarBase;
        double needed = safePillarHeight(s, t);
        // climb until we're truly clear (a fixed 3-tall pillar still eats a creeper blast, and on
        // mesa/cliff terrain the creeper can be at our level — so the height is measured against the
        // creeper, not our start). Stop only when high enough, out of blocks, or capped out.
        if (built >= needed - 0.2D || s.blockSlot < 0 || s.blockCount <= 0) {
            return List.of(ReflexAction.releaseAll()); // safe height reached / nothing left to build with
        }
        List<ReflexAction> actions = new ArrayList<>(4);
        actions.add(ReflexAction.selectSlot(s.blockSlot));
        actions.add(ReflexAction.snapLook(s.yaw, 90F));
        if (s.onGround) {
            actions.add(ReflexAction.hold(Input.JUMP, true));
        } else if (s.velY <= PILLAR_APEX_VEL) {
            actions.add(ReflexAction.placeBlock(new BlockPosSpec(
                    (int) Math.floor(s.posX), (int) Math.floor(s.posY) - 1, (int) Math.floor(s.posZ))));
        }
        return actions;
    }

    /**
     * How high this pillar must reach to be safe: at least {@code pillarTargetHeight}, but raised so
     * that EVERY nearby creeper ends up {@code creeperSafeGap} blocks below us — measured against the
     * creeper's own Y, because on mesa/cliff terrain a creeper can stand level with or above us, and a
     * pillar relative only to our start would leave us inside the blast. Capped at {@code pillarMaxHeight}.
     */
    private double safePillarHeight(WorldSnapshot s, ReflexTuning t) {
        double needed = t.pillarTargetHeight;
        for (MobInfo m : s.mobs) {
            if (m.creeper && m.distance <= t.perceptionRadius) {
                needed = Math.max(needed, (m.y - pillarBase) + t.creeperSafeGap);
            }
        }
        return Math.min(needed, t.pillarMaxHeight);
    }

    /** Brick up the cell between us and the chaser, feet and head height. */
    private List<ReflexAction> tickWall(WorldSnapshot s, ReflexTuning t) {
        MobInfo nearest = nearestPursuer(s, Math.max(2D, t.creeperRadius) + 4D, true);
        if (nearest == null || s.blockSlot < 0) {
            return List.of(ReflexAction.releaseAll());
        }
        double dx = nearest.x - s.posX;
        double dz = nearest.z - s.posZ;
        int ox = 0, oz = 0;
        if (Math.abs(dx) >= Math.abs(dz)) {
            ox = dx >= 0 ? 1 : -1;
        } else {
            oz = dz >= 0 ? 1 : -1;
        }
        int feetY = (int) Math.floor(s.posY);
        BlockPosSpec feet = new BlockPosSpec((int) Math.floor(s.posX) + ox, feetY, (int) Math.floor(s.posZ) + oz);
        BlockPosSpec head = new BlockPosSpec(feet.x, feetY + 1, feet.z);
        return List.of(
                ReflexAction.releaseAll(),
                ReflexAction.selectSlot(s.blockSlot),
                ReflexAction.snapLook(ReflexMath.yawToward(s.posX, s.posZ, feet.x + 0.5D, feet.z + 0.5D), 35F),
                ReflexAction.placeBlock(feet),
                ReflexAction.placeBlock(head)
        );
    }

    /** The classic run — optionally with the flee sources rotated 90° (NEW_DIRECTION). */
    private List<ReflexAction> tickRun(WorldSnapshot s, ReflexTuning t, ResponsePlan plan, boolean rotated) {
        double radius = Math.max(2D, t.creeperRadius) + 4D;
        // engaged by a SWARM or OUTMATCHED: every hostile is a pursuer (zombies count), not just
        // the creeper/skeleton set
        boolean fleeAll = plan != null && plan.cause != null
                && (plan.cause.type == ThreatType.SWARM || plan.cause.type == ThreatType.OUTMATCHED);
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
            // PANIC: sprint away before pathing can even compute — but never INTO lava or off a
            // ledge. Use the average away-vector from every pursuer, snapped to a safe direction.
            float awayYaw = Moves.awayFromAll(s, mobs);
            List<ReflexAction> actions = new ArrayList<>(4);
            if (Moves.boxedIn(s, awayYaw)) {
                // hazards all around: don't run into one. Face the threat and let the escalation
                // ladder resolve it (pillar/wall) instead of sprinting to our death.
                actions.add(ReflexAction.look(awayYaw, 5F));
                return actions;
            }
            actions.add(ReflexAction.look(Moves.safeFleeYaw(s, awayYaw), 5F));
            actions.add(ReflexAction.hold(Input.MOVE_FORWARD, true));
            actions.add(ReflexAction.hold(Input.SPRINT, true));
            if (s.horizontalCollision) {
                actions.add(ReflexAction.hold(Input.JUMP, true));
            }
            return actions;
        }
        BlockPosSpec[] from = new BlockPosSpec[mobs.size()];
        for (int i = 0; i < mobs.size(); i++) {
            MobInfo m = mobs.get(i);
            if (rotated) {
                // pretend the threat sits 90° around us, so the escape path runs perpendicular
                // to the (blocked) straight-away route
                from[i] = new BlockPosSpec(
                        (int) Math.floor(s.posX - (m.z - s.posZ)),
                        (int) Math.floor(m.y),
                        (int) Math.floor(s.posZ + (m.x - s.posX)));
            } else {
                from[i] = ReflexMath.feetBlock(m);
            }
        }
        return List.of(
                ReflexAction.releaseAll(),
                ReflexAction.setGoal(GoalSpec.runAway(t.fleeGoalDistance, from))
        );
    }

    private static MobInfo nearestPursuer(WorldSnapshot s, double radius, boolean any) {
        MobInfo nearest = null;
        for (MobInfo m : s.mobs) {
            boolean relevant = any ? (m.hostile || m.creeper || m.skeleton) : (m.creeper || m.skeleton);
            if (relevant && m.distance <= radius && (nearest == null || m.distance < nearest.distance)) {
                nearest = m;
            }
        }
        return nearest;
    }
}
