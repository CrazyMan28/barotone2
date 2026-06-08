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
 * The panic sprint must never run the bot straight into lava or off a ledge: it picks the safe
 * direction closest to "directly away", and if every direction is a hazard it refuses to sprint.
 */
public class HazardAwareFleeTest {

    private final ReflexTuning t = new ReflexTuning();
    private final FleeBehavior b = new FleeBehavior();

    /** Creeper {@code dist} blocks due east, so "directly away" is due west (yaw 90). */
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

    private static boolean holds(List<ReflexAction> a, Input in) {
        return a.stream().anyMatch(x -> x.kind == ReflexAction.Kind.HOLD_INPUT && x.input == in && x.pressed);
    }

    private static ReflexAction look(List<ReflexAction> a) {
        return a.stream().filter(x -> x.kind == ReflexAction.Kind.LOOK).findFirst().orElse(null);
    }

    @Test
    public void openGroundSprintsDirectlyAway() {
        WorldSnapshot s = panicFromEast(3); // all octants safe by default
        b.enter(s, plan(s));
        List<ReflexAction> a = b.tick(s, t, plan(s));
        assertTrue("sprints away", holds(a, Input.MOVE_FORWARD) && holds(a, Input.SPRINT));
        assertEquals("straight away = due west (yaw 90)", 90F, look(a).yaw, 1F);
    }

    @Test
    public void lavaDueWestDivertsToASafeNeighbor() {
        WorldSnapshot s = panicFromEast(3);
        s.octantSafe[6] = false; // due west (the straight-away direction) is lava
        b.enter(s, plan(s));
        List<ReflexAction> a = b.tick(s, t, plan(s));
        assertTrue("still flees", holds(a, Input.MOVE_FORWARD));
        assertNotNull(look(a));
        assertFalse("does NOT run due west into the lava", Math.abs(look(a).yaw - 90F) < 1F);
        // nearest safe octant to west is SW(45) or NW(135) — either is fine, both avoid the lava
        float yaw = look(a).yaw;
        assertTrue("diverted to a safe diagonal", Math.abs(yaw - 45F) < 1F || Math.abs(yaw - 135F) < 1F);
    }

    @Test
    public void boxedInRefusesToSprintIntoAHazard() {
        WorldSnapshot s = panicFromEast(3);
        for (int i = 0; i < s.octantSafe.length; i++) {
            s.octantSafe[i] = false; // hazards in every direction
        }
        b.enter(s, plan(s));
        List<ReflexAction> a = b.tick(s, t, plan(s));
        assertFalse("must not sprint to its death", holds(a, Input.MOVE_FORWARD));
        assertNotNull("still faces away, lets escalation (pillar/wall) resolve it", look(a));
    }
}
