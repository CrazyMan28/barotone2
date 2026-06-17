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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** End-to-end engine checks for the threats added by the redesign (fire, fall, suffocation). */
public class NewThreatBehaviorTest {

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
    public void extinguishSeeksVisibleWater() {
        ReflexEngine e = new ReflexEngine();
        WorldSnapshot s = working();
        s.onFire = true;
        s.nearestWater = new BlockPosSpec(3, 64, 2);
        ReflexEngine.Output out = e.tick(s, t);
        assertEquals(BehaviorId.EXTINGUISH_FIRE, out.plan.behavior);
        ReflexAction goal = find(out.actions, ReflexAction.Kind.SET_GOAL);
        assertNotNull(goal);
        assertEquals(GoalSpec.Kind.NEAR, goal.goal.kind);
        assertEquals(3, goal.goal.target.x);
    }

    @Test
    public void extinguishRunsOffTheFireWithoutWater() {
        ReflexEngine e = new ReflexEngine();
        WorldSnapshot s = working();
        s.onFire = true;
        ReflexEngine.Output out = e.tick(s, t);
        assertEquals(BehaviorId.EXTINGUISH_FIRE, out.plan.behavior);
        ReflexAction goal = find(out.actions, ReflexAction.Kind.SET_GOAL);
        assertNotNull("no water in sight: run off the burning ground", goal);
        assertEquals(GoalSpec.Kind.RUN_AWAY, goal.goal.kind);
    }

    @Test
    public void fireReleasesOnceOut() {
        ReflexEngine e = new ReflexEngine();
        WorldSnapshot s = working();
        s.onFire = true;
        assertEquals(BehaviorId.EXTINGUISH_FIRE, e.tick(s, t).plan.behavior);
        WorldSnapshot out = working();
        out.gameTime = 1;
        ReflexEngine.Output o = e.tick(out, t);
        assertEquals(BehaviorId.NONE, o.plan.behavior);
        assertTrue(o.released);
    }

    @Test
    public void antiFallDeploysTheBucketNearTheGround() {
        ReflexEngine e = new ReflexEngine();
        WorldSnapshot s = working();
        s.onGround = false;
        s.velY = -0.8;
        s.fallDistance = 6;
        s.gapBelow = 3;
        s.waterBucketSlot = 4;
        ReflexEngine.Output out = e.tick(s, t);
        assertEquals(BehaviorId.ANTI_FALL, out.plan.behavior);
        ReflexAction slot = find(out.actions, ReflexAction.Kind.SELECT_SLOT);
        assertNotNull(slot);
        assertEquals(4, slot.slot);
        ReflexAction look = find(out.actions, ReflexAction.Kind.SNAP_LOOK);
        assertNotNull(look);
        assertEquals("aim straight down", 90F, look.pitch, 0.01F);
        assertTrue("close to the ground: use the bucket", holds(out.actions, Input.CLICK_RIGHT));
    }

    @Test
    public void antiFallHoldsFireHighUp() {
        ReflexEngine e = new ReflexEngine();
        WorldSnapshot s = working();
        s.onGround = false;
        s.velY = -0.8;
        s.fallDistance = 6;
        s.gapBelow = 12;
        s.waterBucketSlot = 4;
        ReflexEngine.Output out = e.tick(s, t);
        assertEquals(BehaviorId.ANTI_FALL, out.plan.behavior);
        assertNotNull("pre-aim while falling", find(out.actions, ReflexAction.Kind.SNAP_LOOK));
        assertTrue("too high: placing now wastes the water",
                !holds(out.actions, Input.CLICK_RIGHT));
    }

    @Test
    public void contactHazardRunsOffTheSpikedBlock() {
        // standing on cactus/magma with no water: run off the block (same response as fire-no-water)
        ReflexEngine e = new ReflexEngine();
        WorldSnapshot s = working();
        s.contactHazardAtFeet = true;
        ReflexEngine.Output out = e.tick(s, t);
        assertEquals(BehaviorId.EXTINGUISH_FIRE, out.plan.behavior);
        ReflexAction goal = find(out.actions, ReflexAction.Kind.SET_GOAL);
        assertNotNull("must path off the contact-damage block", goal);
        assertEquals(GoalSpec.Kind.RUN_AWAY, goal.goal.kind);
    }

    @Test
    public void contactHazardHoldsUntilSteppedOff() {
        // must NOT release while still standing on the hazard (the bot would stop and keep bleeding)
        ReflexEngine e = new ReflexEngine();
        WorldSnapshot on = working();
        on.contactHazardAtFeet = true;
        assertEquals(BehaviorId.EXTINGUISH_FIRE, e.tick(on, t).plan.behavior);
        WorldSnapshot still = working();
        still.gameTime = 1;
        still.contactHazardAtFeet = true; // not off it yet
        assertEquals("still on the hazard: keep running off it",
                BehaviorId.EXTINGUISH_FIRE, e.tick(still, t).plan.behavior);
        WorldSnapshot off = working();
        off.gameTime = 2;
        ReflexEngine.Output o = e.tick(off, t); // stepped clear
        assertEquals(BehaviorId.NONE, o.plan.behavior);
        assertTrue(o.released);
    }

    @Test
    public void suffocationMinesTheHeadBlock() {
        ReflexEngine e = new ReflexEngine();
        WorldSnapshot s = working();
        s.headBlockedByGravity = true;
        ReflexEngine.Output out = e.tick(s, t);
        assertEquals(BehaviorId.DIG_OUT, out.plan.behavior);
        ReflexAction look = find(out.actions, ReflexAction.Kind.SNAP_LOOK);
        assertNotNull(look);
        assertEquals("look straight up at the sand/gravel", -90F, look.pitch, 0.01F);
        assertTrue(holds(out.actions, Input.CLICK_LEFT));
        assertNull("digging out must not move us", find(out.actions, ReflexAction.Kind.SET_GOAL));
    }

    @Test
    public void suffocationOutranksDrowning() {
        // gravel collapsing on you underwater: dig first, the air pocket question comes after
        ReflexEngine e = new ReflexEngine();
        WorldSnapshot s = working();
        s.headBlockedByGravity = true;
        s.underWater = true;
        s.air = 50;
        assertEquals(BehaviorId.DIG_OUT, e.tick(s, t).plan.behavior);
    }

    // ---------------------------------------------------------------- drowning surface safety

    @Test
    public void drowningSealedDigsUpInsteadOfBobbing() {
        ReflexEngine e = new ReflexEngine();
        WorldSnapshot s = working();
        s.underWater = true;
        s.air = 20;
        s.surfaceSealed = true; // capped overhead, no side opening
        ReflexEngine.Output out = e.tick(s, t);
        assertEquals(BehaviorId.SURFACE, out.plan.behavior);
        ReflexAction look = find(out.actions, ReflexAction.Kind.SNAP_LOOK);
        assertNotNull("sealed overhead: mine straight up", look);
        assertEquals(-90F, look.pitch, 0.01F);
        assertTrue("dig the ceiling out", holds(out.actions, Input.CLICK_LEFT));
        assertTrue("climb the shaft", holds(out.actions, Input.JUMP));
    }

    @Test
    public void drowningSwimsToTheSafeColumn() {
        ReflexEngine e = new ReflexEngine();
        WorldSnapshot s = working();
        s.underWater = true;
        s.air = 20;
        s.surfaceSealed = true;
        s.surfaceEscape = new BlockPosSpec(5, 64, 0); // open column to the side
        ReflexEngine.Output out = e.tick(s, t);
        assertEquals(BehaviorId.SURFACE, out.plan.behavior);
        assertNotNull("aim toward the open column", find(out.actions, ReflexAction.Kind.LOOK));
        assertTrue("swim to it", holds(out.actions, Input.MOVE_FORWARD));
        assertTrue("rise into it", holds(out.actions, Input.JUMP));
        assertNull("never blindly mine when a clear column exists",
                find(out.actions, ReflexAction.Kind.SNAP_LOOK));
    }

    // ---------------------------------------------------------------- lava escape mob-awareness

    @Test
    public void lavaEscapeFallsBackToASafeOctantWithNoColumn() {
        ReflexEngine e = new ReflexEngine();
        WorldSnapshot s = working();
        s.inLava = true;
        s.lavaEscape = null; // every near column is lava/blocked or mob-parked
        s.octantSafe = new boolean[]{false, false, true, false, false, false, false, false};
        ReflexEngine.Output out = e.tick(s, t);
        assertEquals(BehaviorId.ESCAPE_LAVA, out.plan.behavior);
        assertTrue("float up", holds(out.actions, Input.JUMP));
        assertTrue("still push out of the lava along a safe octant", holds(out.actions, Input.MOVE_FORWARD));
        assertNotNull("aim along the safe octant", find(out.actions, ReflexAction.Kind.LOOK));
    }

    @Test
    public void lavaEscapeHeadsToAClearColumn() {
        ReflexEngine e = new ReflexEngine();
        WorldSnapshot s = working();
        s.inLava = true;
        s.lavaEscape = new BlockPosSpec(4, 64, 0); // east, no mob on it
        ReflexEngine.Output out = e.tick(s, t);
        assertEquals(BehaviorId.ESCAPE_LAVA, out.plan.behavior);
        ReflexAction look = find(out.actions, ReflexAction.Kind.LOOK);
        assertNotNull("aim toward the clear column", look);
        float towardColumn = ReflexMath.yawToward(s.posX, s.posZ, 4.5D, 0.5D);
        assertEquals("must look toward the clear escape column", towardColumn, look.yaw, 1.0F);
        assertTrue("push toward it", holds(out.actions, Input.MOVE_FORWARD));
    }

    @Test
    public void lavaEscapeAvoidsAMobParkedOnTheColumn() {
        // the precomputed column is east (+X), but a zombie is standing right on it. Climbing out
        // onto the mob means eating lava + melee at once — fall back to a clear safe octant instead
        // of aiming at the blocked column.
        ReflexEngine e = new ReflexEngine();
        WorldSnapshot s = working();
        s.inLava = true;
        s.lavaEscape = new BlockPosSpec(4, 64, 0); // east column
        // only the west octant (index 5: dx=-1,dz=-1 ... pick a clearly-west one) is open
        s.octantSafe = new boolean[]{false, false, false, false, false, false, true, false};
        MobInfo zombie = new MobInfo();
        zombie.entityId = 1;
        zombie.typeId = "zombie";
        zombie.hostile = true;
        zombie.x = 4.4D; // within MOB_BLOCK_RADIUS of the column centre (4.5,0.5)
        zombie.y = 64;
        zombie.z = 0.2D;
        zombie.distance = 4.0D;
        zombie.aggro = true;
        s.mobs.add(zombie);
        ReflexEngine.Output out = e.tick(s, t);
        assertEquals(BehaviorId.ESCAPE_LAVA, out.plan.behavior);
        ReflexAction look = find(out.actions, ReflexAction.Kind.LOOK);
        assertNotNull("must still aim somewhere out of the lava", look);
        float towardBlockedColumn = ReflexMath.yawToward(s.posX, s.posZ, 4.5D, 0.5D);
        assertTrue("must NOT aim at the mob-blocked column",
                Math.abs(ReflexMath.angleDelta(look.yaw, towardBlockedColumn)) > 30F);
        assertTrue("still push out of the lava along the clear octant", holds(out.actions, Input.MOVE_FORWARD));
    }
}
