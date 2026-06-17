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

import baritone.ai.reflex.behavior.FleeBehavior;
import baritone.api.utils.input.Input;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The flee resolution modes — what the bot DOES once running stopped working:
 * pillar out of reach, wall off the line of sight, or run a different way.
 */
public class FleeBehaviorTest {

    private final ReflexTuning t = new ReflexTuning();
    private final FleeBehavior b = new FleeBehavior();

    private static WorldSnapshot chased(double mobDist) {
        WorldSnapshot s = new WorldSnapshot();
        s.working = true;
        s.posY = 64;
        s.blockSlot = 5;
        s.blockCount = 30;
        MobInfo c = new MobInfo();
        c.entityId = 1;
        c.creeper = true;
        c.distance = mobDist;
        c.x = mobDist;
        c.y = 64;
        s.mobs.add(c);
        return s;
    }

    private static ResponsePlan plan(WorldSnapshot s, FleeMode mode) {
        return new ResponsePlan(BehaviorId.FLEE,
                new Threat(ThreatType.CREEPER, Detectors.SEV_FLEE_MOB, s.mobs.get(0)), mode, -1);
    }

    private static boolean holds(List<ReflexAction> actions, Input input) {
        return actions.stream().anyMatch(a ->
                a.kind == ReflexAction.Kind.HOLD_INPUT && a.input == input && a.pressed);
    }

    private static ReflexAction find(List<ReflexAction> actions, ReflexAction.Kind kind) {
        return actions.stream().filter(a -> a.kind == kind).findFirst().orElse(null);
    }

    @Test
    public void pillarJumpsThenPlacesUnderFeetThenStops() {
        WorldSnapshot ground = chased(4);
        b.enter(ground, plan(ground, FleeMode.PILLAR));

        // on the ground: aim down, hold the blocks, jump — no placement yet
        List<ReflexAction> a1 = b.tick(ground, t, plan(ground, FleeMode.PILLAR));
        assertEquals(5, find(a1, ReflexAction.Kind.SELECT_SLOT).slot);
        assertEquals("aim straight down", 90F, find(a1, ReflexAction.Kind.SNAP_LOOK).pitch, 0.01F);
        assertTrue(holds(a1, Input.JUMP));
        assertNull("nothing to place into yet", find(a1, ReflexAction.Kind.PLACE_BLOCK));

        // at the jump apex: fill the cell we vacated
        WorldSnapshot apex = chased(4);
        apex.onGround = false;
        apex.posY = 65.2;
        apex.velY = 0.05;
        List<ReflexAction> a2 = b.tick(apex, t, plan(apex, FleeMode.PILLAR));
        ReflexAction place = find(a2, ReflexAction.Kind.PLACE_BLOCK);
        assertNotNull("place at the apex", place);
        assertEquals(64, place.pos.y);

        // a 3-tall pillar still eats a creeper blast (the real-world death) — keep climbing
        WorldSnapshot midClimb = chased(4);
        midClimb.posY = 67.1; // ~3 up: NOT safe yet
        List<ReflexAction> a25 = b.tick(midClimb, t, plan(midClimb, FleeMode.PILLAR));
        assertTrue("a 3-tall pillar is too short — keep jumping", holds(a25, Input.JUMP));

        // safely high (>= pillarTargetHeight above the creeper): stop building, stand safe
        WorldSnapshot top = chased(4);
        top.posY = 70.2; // ~6 up, clear of the blast
        List<ReflexAction> a3 = b.tick(top, t, plan(top, FleeMode.PILLAR));
        assertNull("pillar is done once safely high", find(a3, ReflexAction.Kind.PLACE_BLOCK));
        assertTrue("stop jumping", !holds(a3, Input.JUMP));
    }

    @Test
    public void wallPlacesBetweenSelfAndTheMob() {
        WorldSnapshot s = chased(4); // creeper due east
        b.enter(s, plan(s, FleeMode.WALL));
        List<ReflexAction> actions = b.tick(s, t, plan(s, FleeMode.WALL));
        assertEquals(5, find(actions, ReflexAction.Kind.SELECT_SLOT).slot);
        long places = actions.stream().filter(a -> a.kind == ReflexAction.Kind.PLACE_BLOCK).count();
        assertEquals("feet and head height", 2, places);
        for (ReflexAction a : actions) {
            if (a.kind == ReflexAction.Kind.PLACE_BLOCK) {
                assertEquals("one cell east, between us and the creeper", 1, a.pos.x);
                assertTrue(a.pos.y == 64 || a.pos.y == 65);
            }
        }
    }

    @Test
    public void newDirectionRunsPerpendicular() {
        WorldSnapshot s = chased(6); // creeper due east at (6, 0)
        b.enter(s, plan(s, FleeMode.NEW_DIRECTION));
        List<ReflexAction> actions = b.tick(s, t, plan(s, FleeMode.NEW_DIRECTION));
        ReflexAction goal = find(actions, ReflexAction.Kind.SET_GOAL);
        assertNotNull(goal);
        assertEquals(GoalSpec.Kind.RUN_AWAY, goal.goal.kind);
        BlockPosSpec from = goal.goal.from[0];
        assertEquals("flee source rotated 90 degrees: due south, not east", 0, from.x);
        assertEquals(6, from.z);
    }
}
