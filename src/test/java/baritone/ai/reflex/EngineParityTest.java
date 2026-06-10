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

import baritone.api.utils.input.Input;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Pins the actions each parity behavior emits to what the old tick methods physically did,
 * so the engine swap ships with zero behavior change.
 */
public class EngineParityTest {

    private final ReflexTuning t = new ReflexTuning();

    private static WorldSnapshot working() {
        WorldSnapshot s = new WorldSnapshot();
        s.working = true;
        return s;
    }

    private static boolean holds(List<ReflexAction> actions, Input input) {
        return actions.stream().anyMatch(a ->
                a.kind == ReflexAction.Kind.HOLD_INPUT && a.input == input && a.pressed);
    }

    private static ReflexAction find(List<ReflexAction> actions, ReflexAction.Kind kind) {
        return actions.stream().filter(a -> a.kind == kind).findFirst().orElse(null);
    }

    @Test
    public void drowningHoldsJump() {
        ReflexEngine e = new ReflexEngine();
        WorldSnapshot s = working();
        s.underWater = true;
        s.air = 50;
        ReflexEngine.Output out = e.tick(s, t);
        assertEquals(BehaviorId.SURFACE, out.plan.behavior);
        assertTrue("surfacing must hold JUMP", holds(out.actions, Input.JUMP));
    }

    @Test
    public void lavaHoldsJumpAndPushesTowardEscape() {
        ReflexEngine e = new ReflexEngine();
        WorldSnapshot s = working();
        s.inLava = true;
        s.lavaEscape = new BlockPosSpec(4, 64, 0); // east of the bot at origin
        ReflexEngine.Output out = e.tick(s, t);
        assertEquals(BehaviorId.ESCAPE_LAVA, out.plan.behavior);
        assertTrue(holds(out.actions, Input.JUMP));
        assertTrue(holds(out.actions, Input.MOVE_FORWARD));
        ReflexAction look = find(out.actions, ReflexAction.Kind.LOOK);
        assertNotNull("must aim at the escape column", look);
        // block center (4.5, 0.5) from origin => roughly due east (minecraft yaw ~-84)
        assertEquals(-83.7F, look.yaw, 2F);
    }

    @Test
    public void eatSelectsFoodLooksUpAndHoldsUse() {
        ReflexEngine e = new ReflexEngine();
        WorldSnapshot s = working();
        s.food = 6;
        s.bestFoodSlot = 2;
        s.yaw = 123F;
        ReflexEngine.Output out = e.tick(s, t);
        assertEquals(BehaviorId.EAT, out.plan.behavior);
        ReflexAction slot = find(out.actions, ReflexAction.Kind.SELECT_SLOT);
        assertNotNull(slot);
        assertEquals(2, slot.slot);
        ReflexAction look = find(out.actions, ReflexAction.Kind.LOOK);
        assertNotNull(look);
        assertEquals("keep current yaw", 123F, look.yaw, 0.01F);
        assertEquals("look skyward so use can't open a chest", -75F, look.pitch, 0.01F);
        assertNotNull("must actually drive the use key so vanilla eats",
                find(out.actions, ReflexAction.Kind.USE_ITEM));
    }

    @Test
    public void fleePanicSprintsDirectlyAway() {
        ReflexEngine e = new ReflexEngine();
        WorldSnapshot s = working();
        MobInfo creeper = new MobInfo();
        creeper.entityId = 1;
        creeper.creeper = true;
        creeper.distance = 3;
        creeper.x = 3; // due east
        s.mobs.add(creeper);
        ReflexEngine.Output out = e.tick(s, t);
        assertEquals(BehaviorId.FLEE, out.plan.behavior);
        assertTrue(holds(out.actions, Input.MOVE_FORWARD));
        assertTrue(holds(out.actions, Input.SPRINT));
        ReflexAction look = find(out.actions, ReflexAction.Kind.LOOK);
        assertNotNull(look);
        // away from due-east => face west => minecraft yaw 90
        assertEquals(90F, look.yaw, 1.5F);
        assertFalse("panic mode must not hand pathing a goal",
                out.actions.stream().anyMatch(a -> a.kind == ReflexAction.Kind.SET_GOAL));
    }

    @Test
    public void fleeBeyondPanicRangePathsAway() {
        ReflexEngine e = new ReflexEngine();
        WorldSnapshot s = working();
        MobInfo creeper = new MobInfo();
        creeper.entityId = 1;
        creeper.creeper = true;
        creeper.distance = 6;
        creeper.x = 6;
        s.mobs.add(creeper);
        ReflexEngine.Output out = e.tick(s, t);
        assertEquals(BehaviorId.FLEE, out.plan.behavior);
        ReflexAction goal = find(out.actions, ReflexAction.Kind.SET_GOAL);
        assertNotNull("calm-range flee paths away with GoalRunAway", goal);
        assertEquals(GoalSpec.Kind.RUN_AWAY, goal.goal.kind);
        assertEquals(t.fleeGoalDistance, goal.goal.distance);
        assertEquals(1, goal.goal.from.length);
    }

    @Test
    public void combatStrikesWhenChargedAndClose() {
        ReflexEngine e = new ReflexEngine();
        WorldSnapshot s = working();
        s.bestWeaponSlot = 1;
        s.bestWeaponTier = 2;
        s.selectedSlot = 0;
        s.attackStrengthScale = 1F;
        MobInfo sk = new MobInfo();
        sk.entityId = 7;
        sk.skeleton = true;
        sk.distance = 3;
        sk.x = 3;
        sk.aimY = 1.2;
        s.mobs.add(sk);
        ReflexEngine.Output out = e.tick(s, t);
        assertEquals(BehaviorId.COMBAT, out.plan.behavior);
        ReflexAction slot = find(out.actions, ReflexAction.Kind.SELECT_SLOT);
        assertNotNull("equip the best weapon", slot);
        assertEquals(1, slot.slot);
        assertNotNull("aim must SNAP, not smooth-turn", find(out.actions, ReflexAction.Kind.SNAP_LOOK));
        ReflexAction attack = find(out.actions, ReflexAction.Kind.ATTACK);
        assertNotNull("charged & in reach -> swing", attack);
        assertEquals(7, attack.entityId);
    }

    @Test
    public void combatWaitsOutTheAttackCooldown() {
        ReflexEngine e = new ReflexEngine();
        WorldSnapshot s = working();
        s.bestWeaponSlot = 1;
        s.bestWeaponTier = 2;
        s.attackStrengthScale = 0.4F; // mid-swing recovery
        MobInfo sk = new MobInfo();
        sk.entityId = 7;
        sk.skeleton = true;
        sk.distance = 3;
        sk.x = 3;
        s.mobs.add(sk);
        ReflexEngine.Output out = e.tick(s, t);
        assertEquals(BehaviorId.COMBAT, out.plan.behavior);
        assertEquals("never swing mid-cooldown (0 damage hits)", null,
                find(out.actions, ReflexAction.Kind.ATTACK));
    }

    @Test
    public void combatRushesAtMidRangeAndPathsWhenFar() {
        ReflexEngine e = new ReflexEngine();
        WorldSnapshot mid = working();
        mid.bestWeaponSlot = 0;
        mid.bestWeaponTier = 2;
        MobInfo sk = new MobInfo();
        sk.entityId = 7;
        sk.skeleton = true;
        sk.distance = 5;
        sk.x = 5;
        mid.mobs.add(sk);
        ReflexEngine.Output out = e.tick(mid, t);
        assertEquals(BehaviorId.COMBAT, out.plan.behavior);
        assertTrue("rush a near target", holds(out.actions, Input.MOVE_FORWARD));
        assertTrue(holds(out.actions, Input.SPRINT));

        ReflexEngine e2 = new ReflexEngine();
        WorldSnapshot far = working();
        far.bestWeaponSlot = 0;
        far.bestWeaponTier = 2;
        MobInfo sk2 = new MobInfo();
        sk2.entityId = 7;
        sk2.skeleton = true;
        sk2.distance = 6.5; // inside the engage radius (7) but past rush range (6)
        sk2.x = 6.5;
        far.mobs.add(sk2);
        ReflexEngine.Output out2 = e2.tick(far, t);
        assertEquals(BehaviorId.COMBAT, out2.plan.behavior);
        ReflexAction goal = find(out2.actions, ReflexAction.Kind.SET_GOAL);
        assertNotNull("path to a far target", goal);
        assertEquals(GoalSpec.Kind.NEAR, goal.goal.kind);
    }

    @Test
    public void engineReportsPhaseChanges() {
        ReflexEngine e = new ReflexEngine();
        WorldSnapshot calm = working();
        assertFalse(e.tick(calm, t).engaged);

        WorldSnapshot drowning = working();
        drowning.underWater = true;
        drowning.air = 50;
        drowning.gameTime = 1;
        ReflexEngine.Output on = e.tick(drowning, t);
        assertTrue("engagement is a phase change", on.engaged);
        assertFalse(on.released);

        WorldSnapshot surfaced = working();
        surfaced.gameTime = 2;
        ReflexEngine.Output off = e.tick(surfaced, t);
        assertTrue("release is a phase change", off.released);
        assertEquals(BehaviorId.SURFACE, off.previous);
        assertEquals(BehaviorId.NONE, off.plan.behavior);
    }
}
