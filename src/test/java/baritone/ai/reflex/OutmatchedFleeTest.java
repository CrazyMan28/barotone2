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
 * Zombies (and any plain melee hostile) become flee-worthy the moment the fight isn't favorable:
 * the OUTMATCHED threat routes them into FLEE instead of the old bare-handed brawl-to-death.
 */
public class OutmatchedFleeTest {

    private final ReflexTuning t = new ReflexTuning();

    private static MobInfo zombieAt(int id, double dist, boolean aggro) {
        MobInfo m = new MobInfo();
        m.entityId = id;
        m.typeId = "zombie";
        m.hostile = true;
        m.distance = dist;
        m.x = dist;
        m.aggro = aggro;
        return m;
    }

    private BehaviorId decide(ResponseArbiter a, WorldSnapshot s) {
        return a.decide(s, t).behavior;
    }

    @Test
    public void unarmedBotFleesAnAggroZombieBeforeItEvenHits() {
        WorldSnapshot s = new WorldSnapshot(); // idle, bare hands
        s.mobs.add(zombieAt(1, 5, true));
        assertEquals(BehaviorId.FLEE, decide(new ResponseArbiter(), s));
    }

    @Test
    public void unarmedBotHurtByAZombieFleesInsteadOfBunkering() {
        WorldSnapshot s = new WorldSnapshot();
        s.ticksSinceHurt = 3;
        s.mobs.add(zombieAt(1, 3, true));
        // OUTMATCHED (78) outranks OVERWHELMED (75): break away rather than heal mid-contact
        assertEquals(BehaviorId.FLEE, decide(new ResponseArbiter(), s));
    }

    @Test
    public void gearedBotIgnoresASingleCalmZombie() {
        WorldSnapshot s = new WorldSnapshot();
        s.bestWeaponSlot = 0;
        s.bestWeaponTier = 3; // stone sword
        s.armorValue = 8;
        s.mobs.add(zombieAt(1, 5, false));
        assertEquals(BehaviorId.NONE, decide(new ResponseArbiter(), s));
    }

    @Test
    public void outmatchedFleeReleasesOnceTheZombieIsGone() {
        ResponseArbiter a = new ResponseArbiter();
        WorldSnapshot s = new WorldSnapshot();
        s.mobs.add(zombieAt(1, 4, true));
        assertEquals(BehaviorId.FLEE, decide(a, s));
        s.mobs.clear();
        s.gameTime = 10; // gone, but the committed episode debounces the release...
        assertEquals(BehaviorId.FLEE, decide(a, s));
        s.gameTime = 30; // ...until it has stayed gone past dwell + release grace
        assertEquals(BehaviorId.NONE, decide(a, s));
    }

    @Test
    public void gettingGearMidFleeTurnsTheFlightIntoAFight() {
        ResponseArbiter a = new ResponseArbiter();
        WorldSnapshot s = new WorldSnapshot();
        s.mobs.add(zombieAt(1, 4, true));
        assertEquals(BehaviorId.FLEE, decide(a, s));
        // suddenly favorable (e.g. recovered gear): the zombie is no longer flee-required,
        // and since it's aggro and right there, the bot turns and fights it proactively
        s.bestWeaponSlot = 0;
        s.bestWeaponTier = 2; // iron sword
        s.armorValue = 10;
        s.gameTime = 10;
        assertEquals(BehaviorId.FLEE, decide(a, s)); // debounced release...
        s.gameTime = 30;
        assertEquals(BehaviorId.COMBAT, decide(a, s)); // ...then meets the now-winnable zombie
    }

    @Test
    public void gettingGearWithNoMobLeftEndsTheEpisode() {
        ResponseArbiter a = new ResponseArbiter();
        WorldSnapshot s = new WorldSnapshot();
        s.mobs.add(zombieAt(1, 4, true));
        assertEquals(BehaviorId.FLEE, decide(a, s));
        // geared up AND the zombie wandered off: nothing left to do (no fight to turn to)
        s.bestWeaponSlot = 0;
        s.bestWeaponTier = 2;
        s.armorValue = 10;
        s.mobs.clear();
        s.gameTime = 10;
        assertEquals(BehaviorId.FLEE, decide(a, s)); // debounced release...
        s.gameTime = 30;
        assertEquals(BehaviorId.NONE, decide(a, s)); // ...then idle, nothing to fight
    }
}
