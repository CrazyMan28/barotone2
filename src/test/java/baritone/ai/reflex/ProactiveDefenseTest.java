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
 * "Stop letting the zombie hit it first." The old logic was reactive: a geared bot only swung
 * AFTER it was hit (recentlyHurt), and an undergeared bot only started fleeing once a mob was
 * inside ~11 blocks. Now a hostile that is aggro/closing is met (fight) or fled (flee) the moment
 * it commits, with a head-start radius — before it lands a blow.
 */
public class ProactiveDefenseTest {

    private final ReflexTuning t = new ReflexTuning();

    private static MobInfo zombie(int id, double dist, boolean aggro) {
        MobInfo m = new MobInfo();
        m.entityId = id;
        m.typeId = "zombie";
        m.hostile = true;
        m.distance = dist;
        m.x = dist;
        m.aggro = aggro;
        return m;
    }

    private static void geared(WorldSnapshot s) {
        s.bestWeaponSlot = 0;
        s.bestWeaponTier = 3; // stone sword
        s.armorValue = 8;
    }

    private BehaviorId decide(WorldSnapshot s) {
        return new ResponseArbiter().decide(s, t).behavior;
    }

    @Test
    public void gearedBotAttacksAnApproachingZombieBeforeItLandsAHit() {
        WorldSnapshot s = new WorldSnapshot(); // never been hit
        geared(s);
        s.mobs.add(zombie(1, 8, true)); // aggro, closing, beyond melee reach
        assertEquals(BehaviorId.COMBAT, decide(s));
    }

    @Test
    public void unarmedBotFleesAChargingZombieFromAfar() {
        WorldSnapshot s = new WorldSnapshot();
        s.mobs.add(zombie(1, 13, true)); // aggro at 13 blocks — past the old 11-block cap
        assertEquals(BehaviorId.FLEE, decide(s));
    }

    @Test
    public void aCalmDistantZombieIsLeftAlone() {
        WorldSnapshot s = new WorldSnapshot();
        geared(s);
        s.mobs.add(zombie(1, 13, false)); // not aggro, not closing: don't chase it across the map
        assertEquals(BehaviorId.NONE, decide(s));
    }

    @Test
    public void stillFightsAZombieThatHitUsEvenIfNotFlaggedApproaching() {
        WorldSnapshot s = new WorldSnapshot();
        geared(s);
        s.ticksSinceHurt = 3; // it hit us
        s.mobs.add(zombie(1, 3, false)); // reactive fallback still works
        assertEquals(BehaviorId.COMBAT, decide(s));
    }

    @Test
    public void anApproachingZombieIsCountedAsAThreatEvenBeyondMeleeRange() {
        // the power comparison must see the charging zombie so an unarmed bot judges the fight
        // unfavorable (and flees) instead of "no threat in range -> safe"
        WorldSnapshot unarmed = new WorldSnapshot();
        unarmed.mobs.add(zombie(1, 13, true));
        org.junit.Assert.assertFalse(CombatPower.fightFavorable(unarmed, t));
    }
}
