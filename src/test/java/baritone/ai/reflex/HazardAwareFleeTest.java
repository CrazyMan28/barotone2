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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Flee is PATHFINDING-FIRST at every range: it hands Baritone a {@link GoalSpec.Kind#RUN_AWAY} goal
 * (from the threat) and lets the pathfinder navigate AROUND terrain — walls, hills, water, lava,
 * ledges. It never drives a raw {@code MOVE_FORWARD}, which is what used to wedge the bot against a
 * wall the one-block hazard look-ahead couldn't see (the "looks one way, gets stuck" death). Routing
 * around hazards is no longer the behavior's job — it's the pathfinder's, which does it correctly.
 */
public class HazardAwareFleeTest {

    private final ReflexTuning t = new ReflexTuning();
    private final FleeBehavior b = new FleeBehavior();

    /** Creeper {@code dist} blocks due east. */
    private static WorldSnapshot panicFromEast(double dist) {
        WorldSnapshot s = new WorldSnapshot();
        s.working = true;
        s.posY = 64;
        s.blockSlot = 5;
        s.blockCount = 30;
        MobInfo c = new MobInfo();
        c.entityId = 1;
        c.creeper = true;
        c.distance = dist;
        c.x = dist;
        c.y = 64;
        s.mobs.add(c);
        return s;
    }

    private static ResponsePlan plan(WorldSnapshot s) {
        return new ResponsePlan(BehaviorId.FLEE,
                new Threat(ThreatType.CREEPER, Detectors.SEV_FLEE_MOB, s.mobs.get(0)),
                FleeMode.NORMAL, -1);
    }

    private static boolean holdsAnyMovement(List<ReflexAction> a) {
        return a.stream().anyMatch(x -> x.kind == ReflexAction.Kind.HOLD_INPUT
                && (x.input == Input.MOVE_FORWARD || x.input == Input.SPRINT) && x.pressed);
    }

    private static GoalSpec goal(List<ReflexAction> a) {
        return a.stream().filter(x -> x.kind == ReflexAction.Kind.SET_GOAL)
                .map(x -> x.goal).findFirst().orElse(null);
    }

    @Test
    public void pointBlankHandsBaritoneARunAwayGoal() {
        WorldSnapshot s = panicFromEast(3); // point-blank — the old code raw-sprinted here and wedged
        b.enter(s, plan(s));
        List<ReflexAction> a = b.tick(s, t, plan(s));
        GoalSpec g = goal(a);
        assertNotNull("flee must hand pathing a goal even point-blank", g);
        assertEquals("a RUN_AWAY goal", GoalSpec.Kind.RUN_AWAY, g.kind);
        assertTrue("running away from at least the creeper", g.from.length >= 1);
        assertFalse("never drives a raw forward sprint (that is what got stuck)", holdsAnyMovement(a));
    }

    @Test
    public void beyondPanicRangeAlsoPathsAway() {
        WorldSnapshot s = panicFromEast(6);
        b.enter(s, plan(s));
        List<ReflexAction> a = b.tick(s, t, plan(s));
        GoalSpec g = goal(a);
        assertNotNull("flee paths away at range too", g);
        assertEquals(GoalSpec.Kind.RUN_AWAY, g.kind);
    }

    @Test
    public void surroundedByHazardsStillPathsRatherThanSprintingBlind() {
        WorldSnapshot s = panicFromEast(3);
        for (int i = 0; i < s.octantSafe.length; i++) {
            s.octantSafe[i] = false; // hazards/walls all around — Baritone (not a raw sprint) judges escape
        }
        b.enter(s, plan(s));
        List<ReflexAction> a = b.tick(s, t, plan(s));
        // It hands a goal (the pathfinder won't step into lava/off a ledge, and finds no path if truly
        // boxed — at which point the brain's progress watchdog escalates to pillar/wall). The key
        // invariant: it does NOT blindly sprint into a hazard.
        assertFalse("must not raw-sprint into the hazards", holdsAnyMovement(a));
        assertNotNull("hands pathing a run-away goal", goal(a));
    }
}
