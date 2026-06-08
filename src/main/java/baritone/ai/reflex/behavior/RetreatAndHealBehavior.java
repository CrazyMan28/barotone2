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
import baritone.ai.reflex.BehaviorWatchdog;
import baritone.ai.reflex.BlockPosSpec;
import baritone.ai.reflex.GoalSpec;
import baritone.ai.reflex.MobInfo;
import baritone.ai.reflex.ReflexAction;
import baritone.ai.reflex.ReflexBehavior;
import baritone.ai.reflex.ReflexMath;
import baritone.ai.reflex.ReflexTuning;
import baritone.ai.reflex.ResponsePlan;
import baritone.ai.reflex.WorldSnapshot;
import baritone.api.utils.input.Input;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Lick your wounds: BREAK CONTACT (run until no hostile is close), then EAT back to full
 * hunger so regen kicks in, then WAIT for hp to recover. The arbiter releases us at
 * {@code retreatTargetHp} and the interrupted mission resumes.
 *
 * <p>If the bot can't actually break contact (cornered, mob faster than us) the watchdog notices
 * the lack of progress and the bot BUNKERS: it seals the open sides toward the attackers with
 * blocks — boxing itself into a corner — then eats and regenerates behind the wall instead of
 * running into a dead end forever. Once it can't be reached it heals; once healed the arbiter
 * hands control back.
 */
public final class RetreatAndHealBehavior implements ReflexBehavior {

    /** A mob this close can still land a melee hit / shoot us — keep sealing rather than eating. */
    private static final double REACH = 3.5D;

    private final BehaviorWatchdog watchdog = new BehaviorWatchdog(60, 3.0D, 2.0D);
    private boolean bunkering;

    @Override
    public BehaviorId id() {
        return BehaviorId.RETREAT_HEAL;
    }

    @Override
    public void enter(WorldSnapshot s, ResponsePlan plan) {
        watchdog.reset();
        bunkering = false;
    }

    @Override
    public List<ReflexAction> tick(WorldSnapshot s, ReflexTuning t, ResponsePlan plan) {
        // BREAK_CONTACT: any hostile in pressure range -> keep running (or bunker if we can't)
        List<MobInfo> chasing = new ArrayList<>();
        MobInfo nearest = null;
        for (MobInfo m : s.mobs) {
            if (m.distance <= t.retreatSafeDistance && (m.hostile || m.creeper || m.skeleton)) {
                chasing.add(m);
                if (nearest == null || m.distance < nearest.distance) {
                    nearest = m;
                }
            }
        }
        if (!chasing.isEmpty()) {
            // once we've failed to gain ground, commit to bunkering for the rest of the episode
            if (watchdog.stuck(s.gameTime, s.posX, s.posZ, s.hp) && s.blockSlot >= 0) {
                bunkering = true;
            }
            if (bunkering && s.blockSlot >= 0) {
                boolean reachable = nearest != null && nearest.distance <= REACH;
                if (reachable) {
                    return sealCorner(s, chasing); // a wall is still missing: keep bricking up
                }
                // walled off: heal behind cover
                return healOrWait(s, t);
            }
            // still mobile: run for safety (Baritone pathing keeps this off hazards)
            BlockPosSpec[] from = new BlockPosSpec[chasing.size()];
            for (int i = 0; i < chasing.size(); i++) {
                from[i] = ReflexMath.feetBlock(chasing.get(i));
            }
            return List.of(
                    ReflexAction.releaseAll(),
                    ReflexAction.setGoal(GoalSpec.runAway(t.fleeGoalDistance, from))
            );
        }
        watchdog.reset();
        bunkering = false;
        return healOrWait(s, t);
    }

    /** EAT to refill hunger so natural regen runs, else just hold position and regenerate. */
    private static List<ReflexAction> healOrWait(WorldSnapshot s, ReflexTuning t) {
        if (s.food < t.eatReleaseFood && s.bestFoodSlot >= 0) {
            return List.of(
                    ReflexAction.selectSlot(s.bestFoodSlot),
                    ReflexAction.look(s.yaw, -75F),
                    ReflexAction.hold(Input.CLICK_RIGHT, true)
            );
        }
        return List.of(ReflexAction.releaseAll());
    }

    /**
     * Brick the feet+head cell on the side of every distinct attacker direction, boxing ourselves
     * into a corner. Placement is idempotent in the executor (already-filled cells are skipped), so
     * re-emitting every tick simply completes and maintains the enclosure.
     */
    private static List<ReflexAction> sealCorner(WorldSnapshot s, List<MobInfo> attackers) {
        int feetY = (int) Math.floor(s.posY);
        int bx = (int) Math.floor(s.posX);
        int bz = (int) Math.floor(s.posZ);
        List<ReflexAction> actions = new ArrayList<>();
        actions.add(ReflexAction.releaseAll());
        actions.add(ReflexAction.selectSlot(s.blockSlot));
        Set<Long> sides = new HashSet<>();
        for (MobInfo m : attackers) {
            double dx = m.x - s.posX;
            double dz = m.z - s.posZ;
            int ox = 0;
            int oz = 0;
            if (Math.abs(dx) >= Math.abs(dz)) {
                ox = dx >= 0 ? 1 : -1;
            } else {
                oz = dz >= 0 ? 1 : -1;
            }
            if (!sides.add((((long) ox) << 32) ^ (oz & 0xffffffffL))) {
                continue; // already sealing that side this tick
            }
            BlockPosSpec feet = new BlockPosSpec(bx + ox, feetY, bz + oz);
            actions.add(ReflexAction.placeBlock(feet));
            actions.add(ReflexAction.placeBlock(new BlockPosSpec(feet.x, feetY + 1, feet.z)));
        }
        return actions;
    }

    @Override
    public void exit() {
    }
}
