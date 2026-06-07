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

import baritone.ai.reflex.behavior.CombatBehavior;
import baritone.api.utils.input.Input;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Combat micro: strike on a charged attack, cover with the shield through the cooldown,
 * step back when a mob crowds us mid-cooldown. The old bot just held W and clicked.
 */
public class CombatBehaviorTest {

    private final ReflexTuning t = new ReflexTuning();
    private final CombatBehavior b = new CombatBehavior();

    private static WorldSnapshot armedVs(double dist, float charge, boolean shield) {
        WorldSnapshot s = new WorldSnapshot();
        s.working = true;
        s.bestWeaponSlot = 0;
        s.selectedSlot = 0;
        s.attackStrengthScale = charge;
        s.hasShieldOffhand = shield;
        MobInfo z = new MobInfo();
        z.entityId = 9;
        z.hostile = true;
        z.distance = dist;
        z.x = dist;
        z.aimY = 1.0;
        s.mobs.add(z);
        return s;
    }

    private ResponsePlan plan(WorldSnapshot s) {
        return new ResponsePlan(BehaviorId.COMBAT,
                new Threat(ThreatType.MELEE_MOB, Detectors.SEV_MELEE, s.mobs.get(0)));
    }

    private static boolean holds(List<ReflexAction> actions, Input input, boolean pressed) {
        return actions.stream().anyMatch(a ->
                a.kind == ReflexAction.Kind.HOLD_INPUT && a.input == input && a.pressed == pressed);
    }

    private static ReflexAction find(List<ReflexAction> actions, ReflexAction.Kind kind) {
        return actions.stream().filter(a -> a.kind == kind).findFirst().orElse(null);
    }

    @Test
    public void raisesTheShieldThroughTheCooldown() {
        WorldSnapshot s = armedVs(3.0, 0.4F, true);
        b.enter(s, plan(s));
        List<ReflexAction> actions = b.tick(s, t, plan(s));
        assertNull("never swing mid-cooldown", find(actions, ReflexAction.Kind.ATTACK));
        assertTrue("shield up while the attack recharges", holds(actions, Input.CLICK_RIGHT, true));
    }

    @Test
    public void noShieldMeansNoRaise() {
        WorldSnapshot s = armedVs(3.0, 0.4F, false);
        b.enter(s, plan(s));
        List<ReflexAction> actions = b.tick(s, t, plan(s));
        assertTrue("nothing to raise", !holds(actions, Input.CLICK_RIGHT, true));
    }

    @Test
    public void stepsBackWhenCrowdedMidCooldown() {
        WorldSnapshot s = armedVs(1.8, 0.4F, true);
        b.enter(s, plan(s));
        List<ReflexAction> actions = b.tick(s, t, plan(s));
        assertTrue("make space while covered", holds(actions, Input.MOVE_BACK, true));
    }

    @Test
    public void holdsGroundAtGoodSpacingMidCooldown() {
        WorldSnapshot s = armedVs(3.2, 0.4F, true);
        b.enter(s, plan(s));
        List<ReflexAction> actions = b.tick(s, t, plan(s));
        assertTrue("good spacing: don't kite away from our own reach",
                !holds(actions, Input.MOVE_BACK, true));
    }

    @Test
    public void dropsTheShieldToStrike() {
        WorldSnapshot s = armedVs(3.0, 1.0F, true);
        b.enter(s, plan(s));
        List<ReflexAction> actions = b.tick(s, t, plan(s));
        assertNotNull("charged & in reach: swing", find(actions, ReflexAction.Kind.ATTACK));
        assertTrue("shield must come down for the hit to land at full damage",
                holds(actions, Input.CLICK_RIGHT, false));
    }
}
