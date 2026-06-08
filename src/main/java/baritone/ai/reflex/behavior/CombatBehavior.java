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
 * Stand and fight: equip the best weapon, SNAP the aim onto the target's hitbox center (smooth
 * turns haven't landed by the time we swing), swing only when the attack is charged, close the
 * gap by rushing near targets and pathing to far ones.
 *
 * <p>Against a skeleton it also <em>kites</em>: strafe (flipping direction periodically) while
 * closing and between swings so arrows miss, raising the shield through the attack cooldown. If a
 * wall stops a back-step the bot strafes instead of standing still in the corner getting hit.
 */
public final class CombatBehavior implements ReflexBehavior {

    @Override
    public BehaviorId id() {
        return BehaviorId.COMBAT;
    }

    @Override
    public void enter(WorldSnapshot s, ResponsePlan plan) {
    }

    @Override
    public List<ReflexAction> tick(WorldSnapshot s, ReflexTuning t, ResponsePlan plan) {
        MobInfo target = null;
        for (MobInfo m : s.mobs) {
            if (m.entityId == plan.targetEntityId) {
                target = m;
                break;
            }
        }
        if (target == null) {
            return List.of(ReflexAction.releaseAll());
        }
        List<ReflexAction> actions = new ArrayList<>(6);
        actions.add(ReflexAction.releaseAll());
        if (s.bestWeaponSlot >= 0 && s.selectedSlot != s.bestWeaponSlot) {
            actions.add(ReflexAction.selectSlot(s.bestWeaponSlot));
        }
        actions.add(ReflexAction.snapLook(
                ReflexMath.yawToward(s.posX, s.posZ, target.x, target.z),
                ReflexMath.pitchToward(s.posX, s.posY, s.posZ, target.x, target.aimY, target.z)));
        boolean kite = target.skeleton; // strafe-dodge ranged attackers; brawl everything else
        Input strafe = ((s.gameTime / Math.max(1, t.combatStrafeTicks)) % 2 == 0)
                ? Input.MOVE_RIGHT : Input.MOVE_LEFT;
        if (target.distance <= t.strikeDistance) {
            if (s.attackStrengthScale >= 0.9F) {
                // STRIKE: shield down (a raised shield soft-cancels the hit), full-charge swing
                if (s.hasShieldOffhand) {
                    actions.add(ReflexAction.hold(Input.CLICK_RIGHT, false));
                }
                actions.add(ReflexAction.attack(target.entityId));
                if (kite) {
                    actions.add(ReflexAction.hold(strafe, true)); // keep moving between swings
                }
            } else {
                // SPACE: cover behind the shield through the cooldown
                if (s.hasShieldOffhand) {
                    actions.add(ReflexAction.hold(Input.CLICK_RIGHT, true));
                }
                if (target.distance < 2.5D && !s.horizontalCollision) {
                    actions.add(ReflexAction.hold(Input.MOVE_BACK, true));
                } else if (kite || s.horizontalCollision) {
                    // cornered (can't back up) or kiting: sidestep instead of eating hits in place
                    actions.add(ReflexAction.hold(strafe, true));
                }
            }
        } else if (target.distance <= t.rushDistance) {
            // rush a near target directly — works in tight holes/caves where pathing is slow
            actions.add(ReflexAction.hold(Input.MOVE_FORWARD, true));
            actions.add(ReflexAction.hold(Input.SPRINT, true));
            if (kite) {
                actions.add(ReflexAction.hold(strafe, true)); // strafe-approach to dodge arrows
            }
            if (s.horizontalCollision) {
                actions.add(ReflexAction.hold(Input.JUMP, true));
            }
        } else {
            actions.add(ReflexAction.setGoal(GoalSpec.near(ReflexMath.feetBlock(target), 2)));
        }
        return actions;
    }

    @Override
    public void exit() {
    }
}
