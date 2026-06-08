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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Skeletons get kited (strafe-dodge); brawl mobs do not. Cornering makes the bot sidestep. */
public class CombatKitingTest {

    private final ReflexTuning t = new ReflexTuning();
    private final CombatBehavior b = new CombatBehavior();

    private static WorldSnapshot armedAt(MobInfo target) {
        WorldSnapshot s = new WorldSnapshot();
        s.working = true;
        s.bestWeaponSlot = 0;
        s.bestWeaponTier = 2;
        s.selectedSlot = 0;
        s.mobs.add(target);
        return s;
    }

    private static MobInfo skeleton(double dist) {
        MobInfo m = new MobInfo();
        m.entityId = 7;
        m.skeleton = true;
        m.distance = dist;
        m.x = dist;
        return m;
    }

    private static MobInfo zombie(double dist) {
        MobInfo m = new MobInfo();
        m.entityId = 8;
        m.hostile = true;
        m.distance = dist;
        m.x = dist;
        return m;
    }

    private static ResponsePlan plan(MobInfo target) {
        return new ResponsePlan(BehaviorId.COMBAT,
                new Threat(ThreatType.MELEE_MOB, Detectors.SEV_MELEE, target),
                FleeMode.NORMAL, target.entityId);
    }

    private static boolean strafing(List<ReflexAction> a) {
        return a.stream().anyMatch(x -> x.kind == ReflexAction.Kind.HOLD_INPUT && x.pressed
                && (x.input == Input.MOVE_LEFT || x.input == Input.MOVE_RIGHT));
    }

    private static boolean holds(List<ReflexAction> a, Input in) {
        return a.stream().anyMatch(x -> x.kind == ReflexAction.Kind.HOLD_INPUT && x.input == in && x.pressed);
    }

    @Test
    public void skeletonIsStrafeApproached() {
        WorldSnapshot s = armedAt(skeleton(5)); // mid range: rush distance
        List<ReflexAction> a = b.tick(s, t, plan(s.mobs.get(0)));
        assertTrue("closes in", holds(a, Input.MOVE_FORWARD));
        assertTrue("while strafing to dodge arrows", strafing(a));
    }

    @Test
    public void brawlMobIsNotKited() {
        WorldSnapshot s = armedAt(zombie(5));
        List<ReflexAction> a = b.tick(s, t, plan(s.mobs.get(0)));
        assertTrue("rushes in", holds(a, Input.MOVE_FORWARD));
        assertFalse("no strafing against a melee mob", strafing(a));
    }

    @Test
    public void corneredSpacingSidestepsInsteadOfBackingIntoTheWall() {
        WorldSnapshot s = armedAt(zombie(2)); // point blank
        s.attackStrengthScale = 0.2F;         // mid-cooldown: would normally step back
        s.horizontalCollision = true;         // but a wall is behind us
        List<ReflexAction> a = b.tick(s, t, plan(s.mobs.get(0)));
        assertFalse("doesn't keep shoving into the wall", holds(a, Input.MOVE_BACK));
        assertTrue("sidesteps out of the corner", strafing(a));
    }
}
