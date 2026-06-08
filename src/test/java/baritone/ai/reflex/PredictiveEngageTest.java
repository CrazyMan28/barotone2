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

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Predictive engage: a mob bearing down on the bot is fled while it is still beyond the fixed
 * engage radius, so the bot moves before a creeper reaches blast range — but a mob loitering at
 * the same distance (not approaching) is left alone.
 */
public class PredictiveEngageTest {

    private final ReflexTuning t = new ReflexTuning();

    private static WorldSnapshot calm() {
        WorldSnapshot s = new WorldSnapshot();
        s.working = true;
        return s;
    }

    private static MobInfo creeper(double dist, double closingSpeed, boolean aggro) {
        MobInfo m = new MobInfo();
        m.entityId = 1;
        m.creeper = true;
        m.distance = dist;
        m.x = dist;
        m.approachingSpeed = closingSpeed;
        m.aggro = aggro;
        return m;
    }

    private BehaviorId decide(WorldSnapshot s) {
        return new ResponseArbiter().decide(s, t).behavior;
    }

    @Test
    public void approachingCreeperBeyondBaseRadiusStillEngages() {
        WorldSnapshot s = calm();
        // distance 10 is past the base radius (7) but inside the predictive radius (7 + 4)
        s.mobs.add(creeper(10, 0.2D, false));
        assertEquals(BehaviorId.FLEE, decide(s));
    }

    @Test
    public void loiteringCreeperAtTheSameDistanceIsIgnored() {
        WorldSnapshot s = calm();
        s.mobs.add(creeper(10, 0.0D, false));
        assertEquals(BehaviorId.NONE, decide(s));
    }

    @Test
    public void aggroAloneCountsAsApproaching() {
        WorldSnapshot s = calm();
        s.mobs.add(creeper(10, 0.0D, true));
        assertEquals(BehaviorId.FLEE, decide(s));
    }

    @Test
    public void predictiveRangeCanBeDisabled() {
        ReflexTuning noPredict = new ReflexTuning();
        noPredict.predictiveFleeBonus = 0D;
        WorldSnapshot s = calm();
        s.mobs.add(creeper(10, 0.5D, true));
        assertEquals(BehaviorId.NONE, new ResponseArbiter().decide(s, noPredict).behavior);
    }
}
