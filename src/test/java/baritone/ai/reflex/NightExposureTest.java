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
 * Turtle-when-weak: at night, undergeared, with hostiles visible, the bot proactively shelters
 * (NIGHT_EXPOSURE -> SHELTER) instead of working until something kills it — nearly all of the 58
 * analyzed deaths were undergeared night deaths. Skeletons also route to SHELTER (break the arrow
 * line-of-sight) instead of the old open-field flee that got the bot shot in the back.
 */
public class NightExposureTest {

    private final ReflexTuning t = new ReflexTuning();

    private static MobInfo mob(int id, String type, double dist) {
        MobInfo m = new MobInfo();
        m.entityId = id;
        m.typeId = type;
        m.hostile = true;
        m.skeleton = "skeleton".equals(type);
        m.creeper = "creeper".equals(type);
        m.distance = dist;
        m.x = dist;
        return m;
    }

    private static WorldSnapshot night() {
        WorldSnapshot s = new WorldSnapshot();
        s.night = true;
        return s;
    }

    private static void geared(WorldSnapshot s) {
        s.bestWeaponSlot = 0;
        s.bestWeaponTier = 3; // stone sword
        s.armorValue = 8;
    }

    private BehaviorId decide(ResponseArbiter a, WorldSnapshot s) {
        return a.decide(s, t).behavior;
    }

    @Test
    public void nightAndUndergearedWithHostilesAroundShelters() {
        WorldSnapshot s = night();
        s.mobs.add(mob(1, "zombie", 12)); // visible, not yet chasing
        assertEquals(BehaviorId.SHELTER, decide(new ResponseArbiter(), s));
    }

    @Test
    public void gearedBotKeepsWorkingThroughTheNight() {
        WorldSnapshot s = night();
        geared(s);
        s.mobs.add(mob(1, "zombie", 12));
        assertEquals(BehaviorId.NONE, decide(new ResponseArbiter(), s));
    }

    @Test
    public void evenAGearedBotHidesFromANightPack() {
        WorldSnapshot s = night();
        geared(s);
        for (int i = 1; i <= 4; i++) {
            s.mobs.add(mob(i, "zombie", 10 + i));
        }
        assertEquals(BehaviorId.SHELTER, decide(new ResponseArbiter(), s));
    }

    @Test
    public void daylightNeverTriggersNightExposure() {
        WorldSnapshot s = new WorldSnapshot();
        s.mobs.add(mob(1, "zombie", 12));
        assertEquals(BehaviorId.NONE, decide(new ResponseArbiter(), s));
    }

    @Test
    public void emptyNightIsJustANight() {
        assertEquals(BehaviorId.NONE, decide(new ResponseArbiter(), night()));
    }

    @Test
    public void skeletonRoutesToShelterNotOpenFieldFlee() {
        WorldSnapshot s = new WorldSnapshot(); // unarmed, daytime
        s.mobs.add(mob(1, "skeleton", 5));
        assertEquals(BehaviorId.SHELTER, decide(new ResponseArbiter(), s));
    }

    @Test
    public void creepersAreStillFledNotSheltered() {
        WorldSnapshot s = night();
        s.mobs.add(mob(1, "creeper", 5));
        assertEquals(BehaviorId.FLEE, decide(new ResponseArbiter(), s));
    }

    @Test
    public void lavaPreemptsAnActiveShelter() {
        ResponseArbiter a = new ResponseArbiter();
        WorldSnapshot s = night();
        s.mobs.add(mob(1, "zombie", 12));
        assertEquals(BehaviorId.SHELTER, decide(a, s));
        s.inLava = true;
        s.gameTime = 1;
        assertEquals(BehaviorId.ESCAPE_LAVA, decide(a, s));
    }

    @Test
    public void aZombiePackOutsideTheWallCannotPullTheBotOut() {
        ResponseArbiter a = new ResponseArbiter();
        WorldSnapshot s = night();
        s.mobs.add(mob(1, "zombie", 12));
        assertEquals(BehaviorId.SHELTER, decide(a, s));
        // three zombies crowd the shelter: SWARM (85) wants FLEE — but leaving the
        // shelter into the open night is exactly how bots die. Stay put.
        for (int i = 2; i <= 4; i++) {
            s.mobs.add(mob(i, "zombie", 4));
        }
        s.gameTime = 1;
        assertEquals(BehaviorId.SHELTER, decide(a, s));
    }

    @Test
    public void dawnReleasesTheShelter() {
        ResponseArbiter a = new ResponseArbiter();
        WorldSnapshot s = night();
        s.mobs.add(mob(1, "zombie", 12));
        assertEquals(BehaviorId.SHELTER, decide(a, s));
        s.night = false;
        s.gameTime = 10;
        assertEquals(BehaviorId.SHELTER, decide(a, s)); // debounced...
        s.gameTime = 30;
        assertEquals(BehaviorId.NONE, decide(a, s)); // ...then the day begins
    }

    @Test
    public void gettingGearedReleasesTheShelter() {
        ResponseArbiter a = new ResponseArbiter();
        WorldSnapshot s = night();
        s.mobs.add(mob(1, "zombie", 12));
        assertEquals(BehaviorId.SHELTER, decide(a, s));
        geared(s);
        s.gameTime = 10;
        assertEquals(BehaviorId.SHELTER, decide(a, s));
        s.gameTime = 30;
        assertEquals(BehaviorId.NONE, decide(a, s));
    }

    @Test
    public void shelterEventuallyTimesOut() {
        t.shelterMaxTicks = 5;
        ResponseArbiter a = new ResponseArbiter();
        WorldSnapshot s = night();
        s.mobs.add(mob(1, "zombie", 12));
        assertEquals(BehaviorId.SHELTER, decide(a, s));
        BehaviorId lastSeen = BehaviorId.SHELTER;
        for (int tick = 1; tick <= 40 && lastSeen == BehaviorId.SHELTER; tick++) {
            s.gameTime = tick;
            lastSeen = decide(a, s);
        }
        assertEquals(BehaviorId.NONE, lastSeen);
    }
}
