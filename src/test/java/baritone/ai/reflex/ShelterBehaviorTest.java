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

import baritone.ai.reflex.behavior.ShelterBehavior;
import baritone.api.utils.input.Input;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The SHELTER state machine: break a shooter's line of sight behind cover, wall in against the
 * night, dig a 2-deep turtle hole and seal it (never into a cave), then wait — eating to keep
 * regen alive — until released.
 */
public class ShelterBehaviorTest {

    private final ReflexTuning t = new ReflexTuning();

    private static MobInfo skeleton(int id, double dist, boolean los) {
        MobInfo m = new MobInfo();
        m.entityId = id;
        m.typeId = "skeleton";
        m.skeleton = true;
        m.hostile = true;
        m.distance = dist;
        m.x = dist; // due east-ish of the bot at origin
        m.lineOfSight = los;
        return m;
    }

    private static MobInfo zombie(int id, double dist) {
        MobInfo m = new MobInfo();
        m.entityId = id;
        m.typeId = "zombie";
        m.hostile = true;
        m.distance = dist;
        m.x = dist;
        return m;
    }

    private static ResponsePlan ranged(MobInfo source) {
        return new ResponsePlan(BehaviorId.SHELTER,
                new Threat(ThreatType.RANGED, Detectors.SEV_FLEE_MOB, source), FleeMode.NORMAL, -1);
    }

    private static ResponsePlan nightExposure() {
        return new ResponsePlan(BehaviorId.SHELTER,
                new Threat(ThreatType.NIGHT_EXPOSURE, Detectors.SEV_NIGHT_EXPOSURE), FleeMode.NORMAL, -1);
    }

    private static ReflexAction find(List<ReflexAction> actions, ReflexAction.Kind kind) {
        for (ReflexAction a : actions) {
            if (a.kind == kind) {
                return a;
            }
        }
        return null;
    }

    private static boolean holds(List<ReflexAction> actions, Input input) {
        for (ReflexAction a : actions) {
            if (a.kind == ReflexAction.Kind.HOLD_INPUT && a.input == input && a.pressed) {
                return true;
            }
        }
        return false;
    }

    @Test
    public void shooterWithLineOfSightSendsTheBotRunningForCover() {
        ShelterBehavior b = new ShelterBehavior();
        WorldSnapshot s = new WorldSnapshot();
        MobInfo sk = skeleton(7, 6, true);
        s.mobs.add(sk);
        s.octantCover[6] = true; // solid cover to the west (away from the eastern shooter)
        ResponsePlan plan = ranged(sk);
        b.enter(s, plan);
        List<ReflexAction> actions = b.tick(s, t, plan);
        ReflexAction g = find(actions, ReflexAction.Kind.SET_GOAL);
        assertNotNull("must PATH toward cover (Baritone routes around terrain), not raw-sprint into it", g);
        assertEquals("a RUN_AWAY goal from the shooter", GoalSpec.Kind.RUN_AWAY, g.goal.kind);
    }

    @Test
    public void losBrokenLongEnoughSettlesIntoWaiting() {
        ShelterBehavior b = new ShelterBehavior();
        WorldSnapshot s = new WorldSnapshot();
        MobInfo sk = skeleton(7, 6, false); // cover already between us
        s.mobs.add(sk);
        ResponsePlan plan = ranged(sk);
        b.enter(s, plan);
        List<ReflexAction> actions = List.of();
        for (int i = 0; i <= t.shelterLosGraceTicks; i++) {
            actions = b.tick(s, t, plan);
        }
        assertTrue("settled: no more running",
                !holds(actions, Input.MOVE_FORWARD));
    }

    @Test
    public void nightExposureDigsInWhenTheGroundIsSafe() {
        ShelterBehavior b = new ShelterBehavior();
        WorldSnapshot s = new WorldSnapshot();
        s.digDownSafe = true;
        s.blockSlot = 2;
        s.blockCount = 10;
        ResponsePlan plan = nightExposure();
        b.enter(s, plan);
        List<ReflexAction> actions = b.tick(s, t, plan);
        ReflexAction lookDown = find(actions, ReflexAction.Kind.SNAP_LOOK);
        assertNotNull("aim straight down", lookDown);
        assertEquals(90F, lookDown.pitch, 0.01F);
        assertTrue("dig the floor", holds(actions, Input.CLICK_LEFT));
    }

    @Test
    public void digsInWithBareHandsWhenThereAreNoBlocksToPlace() {
        // the live death loop: a freshly-respawned bot has NO blocks, a skeleton shoots it in the
        // open, BREAK_LOS just runs and it dies. Digging needs no items — drop below the arrows.
        ShelterBehavior b = new ShelterBehavior();
        WorldSnapshot s = new WorldSnapshot();
        s.digDownSafe = true;
        s.blockSlot = -1; // nothing to place
        ResponsePlan plan = nightExposure();
        b.enter(s, plan);
        List<ReflexAction> actions = b.tick(s, t, plan);
        ReflexAction lookDown = find(actions, ReflexAction.Kind.SNAP_LOOK);
        assertNotNull("aim down to dig even with no blocks", lookDown);
        assertEquals(90F, lookDown.pitch, 0.01F);
        assertTrue("dig the floor with bare hands", holds(actions, Input.CLICK_LEFT));
    }

    @Test
    public void corneredByAShooterWithNoBlocksDigsBelowTheArrows() {
        ShelterBehavior b = new ShelterBehavior();
        WorldSnapshot s = new WorldSnapshot();
        s.digDownSafe = true;
        s.blockSlot = -1; // no blocks to wall with
        // open ground: no cover in any octant
        MobInfo sk = skeleton(7, 6, true);
        s.mobs.add(sk);
        ResponsePlan plan = ranged(sk);
        b.enter(s, plan);
        // a couple of ticks to fall through BREAK_LOS's no-cover branch into digging
        List<ReflexAction> actions = List.of();
        for (int i = 0; i < 3; i++) {
            actions = b.tick(s, t, plan);
        }
        assertTrue("dig down instead of running in the open", holds(actions, Input.CLICK_LEFT));
    }

    @Test
    public void deepEnoughSealsTheHoleOverhead() {
        ShelterBehavior b = new ShelterBehavior();
        WorldSnapshot s = new WorldSnapshot();
        s.digDownSafe = true;
        s.blockSlot = 2;
        s.blockCount = 10;
        s.posY = 64;
        ResponsePlan plan = nightExposure();
        b.enter(s, plan);
        b.tick(s, t, plan);
        s.posY = 64 - t.shelterDigDepth; // reached depth
        List<ReflexAction> actions = b.tick(s, t, plan);
        ReflexAction place = find(actions, ReflexAction.Kind.PLACE_BLOCK);
        assertNotNull("seal the top of the hole", place);
        assertEquals((int) s.posY + 2, place.pos.y);
        assertNull("no more digging once at depth", find(actions, ReflexAction.Kind.SNAP_LOOK));
    }

    @Test
    public void groundTurningUnsafeMidDigFallsBackToWallingIn() {
        ShelterBehavior b = new ShelterBehavior();
        WorldSnapshot s = new WorldSnapshot();
        s.digDownSafe = true;
        s.blockSlot = 2;
        s.blockCount = 10;
        s.mobs.add(zombie(1, 8));
        ResponsePlan plan = nightExposure();
        b.enter(s, plan);
        b.tick(s, t, plan);
        s.digDownSafe = false; // a cave/lava opened under the next block
        List<ReflexAction> actions = b.tick(s, t, plan);
        assertTrue("stop digging", !holds(actions, Input.CLICK_LEFT));
        assertNotNull("wall in instead", find(actions, ReflexAction.Kind.PLACE_BLOCK));
    }

    @Test
    public void noBlocksAndNoSafeGroundStillSeeksCover() {
        ShelterBehavior b = new ShelterBehavior();
        WorldSnapshot s = new WorldSnapshot();
        s.mobs.add(zombie(1, 10));
        s.octantCover[4] = true;
        ResponsePlan plan = nightExposure();
        b.enter(s, plan);
        List<ReflexAction> actions = b.tick(s, t, plan);
        ReflexAction g = find(actions, ReflexAction.Kind.SET_GOAL);
        assertNotNull("nothing to build with: at least PATH to cover", g);
        assertEquals(GoalSpec.Kind.RUN_AWAY, g.goal.kind);
    }

    @Test
    public void waitingShelterEatsToKeepRegenAlive() {
        ShelterBehavior b = new ShelterBehavior();
        WorldSnapshot s = new WorldSnapshot();
        s.sealedOverhead = true; // already turtled
        s.digDownSafe = true;
        s.blockSlot = 2;
        s.blockCount = 10;
        s.food = 12;
        s.bestFoodSlot = 4;
        s.bestFoodNutrition = 5;
        ResponsePlan plan = nightExposure();
        b.enter(s, plan);
        List<ReflexAction> actions = b.tick(s, t, plan);
        assertNotNull("eat while waiting out the night", find(actions, ReflexAction.Kind.USE_ITEM));
        ReflexAction slot = find(actions, ReflexAction.Kind.SELECT_SLOT);
        assertNotNull(slot);
        assertEquals(4, slot.slot);
    }

    @Test
    public void aBedAndACalmMomentMeansSleepingThroughTheNight() {
        ShelterBehavior b = new ShelterBehavior();
        WorldSnapshot s = new WorldSnapshot();
        s.bedSlot = 3;
        s.blockSlot = 2;
        s.blockCount = 10;
        s.digDownSafe = true;
        s.mobs.add(zombie(1, 14)); // visible but far — calm enough to sleep
        ResponsePlan plan = nightExposure();
        b.enter(s, plan);
        List<ReflexAction> first = b.tick(s, t, plan);
        ReflexAction slot = find(first, ReflexAction.Kind.SELECT_SLOT);
        assertNotNull("bed in hand", slot);
        assertEquals(3, slot.slot);
        // within a few ticks the bed must be placed and used (slept in)
        boolean used = false;
        for (int i = 0; i < 5 && !used; i++) {
            used = find(b.tick(s, t, plan), ReflexAction.Kind.USE_BLOCK) != null;
        }
        assertTrue("bed gets used (sleep skips the night)", used);
    }

    @Test
    public void hostilesNearbyMeanNoBedJustDigIn() {
        ShelterBehavior b = new ShelterBehavior();
        WorldSnapshot s = new WorldSnapshot();
        s.bedSlot = 3;
        s.blockSlot = 2;
        s.blockCount = 10;
        s.digDownSafe = true;
        s.mobs.add(zombie(1, 6)); // too close — vanilla wouldn't allow sleep anyway
        ResponsePlan plan = nightExposure();
        b.enter(s, plan);
        List<ReflexAction> actions = b.tick(s, t, plan);
        assertTrue("dig, don't sleep", holds(actions, Input.CLICK_LEFT));
    }
}
