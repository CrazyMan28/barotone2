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
}
