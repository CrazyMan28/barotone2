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
 * Mob defense must work even when the bot is "idle" (no mission, no pathing): the live telemetry
 * showed a session with 8 deaths standing around AFTER the mission verified done, because
 * FLEE/COMBAT/RETREAT only engaged while {@code working}. {@code defendIdle} (default on) removes
 * that gate; setting it false restores the old manual-play-friendly behavior.
 */
public class DefendIdleTest {

    private final ReflexTuning t = new ReflexTuning();

    private static MobInfo creeperAt(double dist) {
        MobInfo m = new MobInfo();
        m.entityId = 7;
        m.typeId = "creeper";
        m.creeper = true;
        m.distance = dist;
        m.x = dist;
        return m;
    }

    private static MobInfo zombieAt(int id, double dist) {
        MobInfo m = new MobInfo();
        m.entityId = id;
        m.typeId = "zombie";
        m.hostile = true;
        m.distance = dist;
        m.x = dist;
        return m;
    }

    private BehaviorId decide(WorldSnapshot s) {
        return new ResponseArbiter().decide(s, t).behavior;
    }

    @Test
    public void idleBotStillFleesACreeper() {
        WorldSnapshot s = new WorldSnapshot(); // working defaults to false
        s.mobs.add(creeperAt(5));
        assertEquals(BehaviorId.FLEE, decide(s));
    }

    @Test
    public void idleArmedBotFightsBackWhenHit() {
        WorldSnapshot s = new WorldSnapshot();
        s.ticksSinceHurt = 3;
        s.bestWeaponSlot = 0;
        s.bestWeaponTier = 2;
        s.mobs.add(zombieAt(1, 3));
        assertEquals(BehaviorId.COMBAT, decide(s));
    }

    @Test
    public void defendIdleOffRestoresTheOldGate() {
        t.defendIdle = false;
        WorldSnapshot s = new WorldSnapshot();
        s.mobs.add(creeperAt(5));
        assertEquals(BehaviorId.NONE, decide(s));
    }

    @Test
    public void workingBotUnaffectedByTheFlag() {
        t.defendIdle = false;
        WorldSnapshot s = new WorldSnapshot();
        s.working = true;
        s.mobs.add(creeperAt(5));
        assertEquals(BehaviorId.FLEE, decide(s));
    }

    @Test
    public void terrainReflexesNeverNeededTheGate() {
        t.defendIdle = false;
        WorldSnapshot s = new WorldSnapshot();
        s.inLava = true;
        assertEquals(BehaviorId.ESCAPE_LAVA, decide(s));
    }
}
