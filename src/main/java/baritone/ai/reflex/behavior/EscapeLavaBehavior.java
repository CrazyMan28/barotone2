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
import baritone.ai.reflex.EscapeColumns;
import baritone.ai.reflex.MobInfo;
import baritone.ai.reflex.ReflexAction;
import baritone.ai.reflex.ReflexBehavior;
import baritone.ai.reflex.ReflexMath;
import baritone.ai.reflex.ReflexTuning;
import baritone.ai.reflex.ResponsePlan;
import baritone.ai.reflex.WorldSnapshot;
import baritone.api.utils.input.Input;

import java.util.ArrayList;
import java.util.List;

/**
 * In lava: float up (hold JUMP) and push toward the chosen non-lava column. The adapter precomputes
 * that column into {@link WorldSnapshot#lavaEscape} mob-aware (the core can't scan blocks). When no
 * clear column exists (every side is lava/blocked, or a mob is parked on the only one), fall back to
 * the first safe octant out of the lava — any direction off the fire beats cooking in place.
 */
public final class EscapeLavaBehavior implements ReflexBehavior {

    /** Distance we'd have to make up before deciding the chosen column isn't getting closer. */
    private static final double NO_PROGRESS_EPSILON = 0.05D;
    /** Consecutive no-progress ticks toward the escape column before we give up on it and swim out. */
    private static final int NO_PROGRESS_TICKS = 12;

    private double lastDistToEscape = Double.MAX_VALUE;
    private int stalledTicks;

    @Override
    public BehaviorId id() {
        return BehaviorId.ESCAPE_LAVA;
    }

    @Override
    public void enter(WorldSnapshot s, ResponsePlan plan) {
        lastDistToEscape = Double.MAX_VALUE;
        stalledTicks = 0;
    }

    @Override
    public List<ReflexAction> tick(WorldSnapshot s, ReflexTuning t, ResponsePlan plan) {
        List<ReflexAction> actions = new ArrayList<>(3);
        actions.add(ReflexAction.hold(Input.JUMP, true));
        // Prefer the precomputed (mob-aware) escape column — UNLESS a hostile is parked on/near it, or
        // we've made no headway toward it for a while (a mob walked onto it mid-escape). Climbing out
        // right onto a mob means eating lava + melee at once (the lava+mob death cluster). When that
        // happens, fall back to swimming out a clear safe octant instead.
        if (s.lavaEscape != null && !columnMobBlocked(s) && !stalledTowardColumn(s)) {
            float yaw = ReflexMath.yawToward(s.posX, s.posZ, s.lavaEscape.x + 0.5D, s.lavaEscape.z + 0.5D);
            actions.add(ReflexAction.look(yaw, 0F));
            actions.add(ReflexAction.hold(Input.MOVE_FORWARD, true));
            return actions;
        }
        // no usable column: head out along a safe octant that leads AWAY from hostiles rather than
        // holding still in the lava (or swimming toward the mob the column was blocked by)
        int octant = safestOctantAwayFromMobs(s);
        if (octant >= 0) {
            actions.add(ReflexAction.look(ReflexMath.octantYaw(octant), 0F));
            actions.add(ReflexAction.hold(Input.MOVE_FORWARD, true));
        }
        return actions;
    }

    /** A hostile standing within the column-block radius of the chosen escape column. */
    private static boolean columnMobBlocked(WorldSnapshot s) {
        if (s.lavaEscape == null) {
            return false;
        }
        double cx = s.lavaEscape.x + 0.5D;
        double cz = s.lavaEscape.z + 0.5D;
        for (MobInfo m : s.mobs) {
            if (!(m.hostile || m.creeper || m.skeleton)) {
                continue;
            }
            if (Math.hypot(cx - m.x, cz - m.z) <= EscapeColumns.MOB_BLOCK_RADIUS) {
                return true;
            }
        }
        return false;
    }

    /** True once we've spent {@link #NO_PROGRESS_TICKS} not getting any closer to the escape column. */
    private boolean stalledTowardColumn(WorldSnapshot s) {
        double d = Math.hypot((s.lavaEscape.x + 0.5D) - s.posX, (s.lavaEscape.z + 0.5D) - s.posZ);
        if (d < lastDistToEscape - NO_PROGRESS_EPSILON) {
            stalledTicks = 0;
        } else {
            stalledTicks++;
        }
        lastDistToEscape = d;
        return stalledTicks >= NO_PROGRESS_TICKS;
    }

    /**
     * Among the safe octants, the one pointing most away from the nearest hostiles — so the fallback
     * swim heads for clear water/ground, not straight at the mob the column was blocked by. Falls back
     * to the first safe octant when there are no mobs to avoid.
     */
    private static int safestOctantAwayFromMobs(WorldSnapshot s) {
        int best = -1;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int o = 0; o < s.octantSafe.length; o++) {
            if (!s.octantSafe[o]) {
                continue;
            }
            double ux = ReflexMath.OCTANT_DX[o];
            double uz = ReflexMath.OCTANT_DZ[o];
            double ulen = Math.hypot(ux, uz);
            if (ulen > 1e-6) {
                ux /= ulen;
                uz /= ulen;
            }
            double worstAhead = Double.NEGATIVE_INFINITY;
            for (MobInfo m : s.mobs) {
                if (!(m.hostile || m.creeper || m.skeleton)) {
                    continue;
                }
                double mdx = m.x - s.posX;
                double mdz = m.z - s.posZ;
                double len = Math.hypot(mdx, mdz);
                if (len < 1e-6) {
                    continue;
                }
                worstAhead = Math.max(worstAhead, (ux * mdx + uz * mdz) / len);
            }
            // prefer the heading with the nearest mob most BEHIND us (lowest worstAhead)
            double score = worstAhead == Double.NEGATIVE_INFINITY ? 0D : -worstAhead;
            if (best < 0 || score > bestScore) {
                bestScore = score;
                best = o;
            }
        }
        return best;
    }

    @Override
    public void exit() {
    }
}
