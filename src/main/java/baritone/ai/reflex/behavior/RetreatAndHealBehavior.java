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
import java.util.List;

/**
 * Lick your wounds: BREAK CONTACT (run until no hostile is close), then EAT back to full
 * hunger so regen kicks in, then WAIT for hp to recover. The arbiter releases us at
 * {@code retreatTargetHp} and the interrupted mission resumes.
 *
 * <p>If the bot can't actually break contact (cornered, mob faster than us) the watchdog notices
 * the lack of progress and the bot WALLS the chaser off instead of running into a corner forever,
 * turning "retreat" into a defensible heal.
 */
public final class RetreatAndHealBehavior implements ReflexBehavior {

    private final BehaviorWatchdog watchdog = new BehaviorWatchdog(60, 3.0D, 2.0D);
    private boolean defending;
    private int defendTicks;

    @Override
    public BehaviorId id() {
        return BehaviorId.RETREAT_HEAL;
    }

    @Override
    public void enter(WorldSnapshot s, ResponsePlan plan) {
        watchdog.reset();
        defending = false;
        defendTicks = 0;
    }

    @Override
    public List<ReflexAction> tick(WorldSnapshot s, ReflexTuning t, ResponsePlan plan) {
        // BREAK_CONTACT: any hostile in pressure range -> keep running (or wall up if we can't)
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
            boolean stuck = watchdog.stuck(s.gameTime, s.posX, s.posZ, s.hp);
            if (stuck && s.blockSlot >= 0) {
                defending = true; // can't outrun them: brick up and heal behind it
            }
            if (defending && s.blockSlot >= 0 && defendTicks++ < 12 && nearest != null) {
                return wallOff(s, nearest);
            }
            defending = false;
            defendTicks = 0;
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
        // HEAL: top the hunger bar up so natural regen runs
        if (s.food < t.eatReleaseFood && s.bestFoodSlot >= 0) {
            return List.of(
                    ReflexAction.selectSlot(s.bestFoodSlot),
                    ReflexAction.look(s.yaw, -75F),
                    ReflexAction.hold(Input.CLICK_RIGHT, true)
            );
        }
        // WAIT: hold position and regen (pathing stays paused while we're engaged)
        return List.of(ReflexAction.releaseAll());
    }

    /** Brick the cell between us and the chaser (feet + head) so we can heal behind cover. */
    private static List<ReflexAction> wallOff(WorldSnapshot s, MobInfo mob) {
        double dx = mob.x - s.posX;
        double dz = mob.z - s.posZ;
        int ox = 0;
        int oz = 0;
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

    @Override
    public void exit() {
    }
}
