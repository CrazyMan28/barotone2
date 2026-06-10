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
 * "Know when to fight and when to get the fuck away": stand and fight a lone, winnable threat,
 * but break off to heal the moment we're low on hp or outnumbered.
 */
public class FightOrFleeTest {

    private final ReflexTuning t = new ReflexTuning();

    private static WorldSnapshot working() {
        WorldSnapshot s = new WorldSnapshot();
        s.working = true;
        return s;
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
    public void unarmedBotFleesALoneZombie() {
        // the old "brawl it bare-handed" policy was the #1 recorded death cause (59% zombies):
        // with no weapon and no armor the power score says we lose the trade — run instead
        WorldSnapshot s = working();
        s.ticksSinceHurt = 3; // it just hit us
        s.mobs.add(zombieAt(1, 3));
        assertEquals(BehaviorId.FLEE, decide(s));
    }

    @Test
    public void swordedBotBrawlsALoneZombie() {
        WorldSnapshot s = working();
        s.bestWeaponSlot = 0;
        s.bestWeaponTier = 3; // stone sword
        s.ticksSinceHurt = 3;
        s.mobs.add(zombieAt(1, 3));
        assertEquals(BehaviorId.COMBAT, decide(s));
    }

    @Test
    public void lowHealthBreaksOffToHealInsteadOfTrading() {
        WorldSnapshot s = working();
        s.bestWeaponSlot = 0;
        s.bestWeaponTier = 2; // armed and otherwise winning, but...
        s.hp = 5; // ...below combatMinHealth (8): can't win the trade
        s.ticksSinceHurt = 3;
        s.mobs.add(zombieAt(1, 3));
        assertEquals(BehaviorId.RETREAT_HEAL, decide(s));
    }

    @Test
    public void outnumberedEvenWhenArmedRunsRatherThanFights() {
        WorldSnapshot s = working();
        s.bestWeaponSlot = 0;
        s.bestWeaponTier = 2;
        s.ticksSinceHurt = 3;
        s.mobs.add(zombieAt(1, 2));
        s.mobs.add(zombieAt(2, 3));
        s.mobs.add(zombieAt(3, 4)); // three crowding melee range -> swarm, get away
        assertEquals(BehaviorId.FLEE, decide(s));
    }

    @Test
    public void twoIsStillWinnableWhenHealthy() {
        WorldSnapshot s = working();
        s.bestWeaponSlot = 0;
        s.bestWeaponTier = 2;
        s.ticksSinceHurt = 3;
        s.mobs.add(zombieAt(1, 2));
        s.mobs.add(zombieAt(2, 3));
        assertEquals(BehaviorId.COMBAT, decide(s));
    }

    @Test
    public void eatsToEnableRegenWhenHurtAndSafe() {
        WorldSnapshot s = working();
        s.hp = 10; // hurt but safe
        s.food = 17; // below the regen floor (18) but above the proactive threshold
        s.bestFoodSlot = 2;
        s.bestFoodNutrition = 4;
        assertEquals(BehaviorId.EAT, decide(s));
    }
}
