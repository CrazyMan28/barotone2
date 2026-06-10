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
 * Health-based reasoning: low HP biases decisions toward flee/heal, a losing fight is
 * disengaged, a swarm is never brawled, poison gets treated. The old system had exactly one
 * health check (the static combat gate) — these are the behaviors that kept dying without it.
 */
public class HealthModulationTest {

    private final ReflexTuning t = new ReflexTuning();

    private static WorldSnapshot working() {
        WorldSnapshot s = new WorldSnapshot();
        s.working = true;
        return s;
    }

    private static MobInfo creeperAt(double dist) {
        MobInfo m = new MobInfo();
        m.entityId = 100;
        m.creeper = true;
        m.distance = dist;
        m.x = dist;
        return m;
    }

    private static MobInfo skeletonAt(double dist) {
        MobInfo m = new MobInfo();
        m.entityId = 200;
        m.skeleton = true;
        m.distance = dist;
        m.x = dist;
        return m;
    }

    @Test
    public void lowHpBiasFlipsABorderlineCall() {
        // falling (bucket ready, FALL=90) while a creeper stalks (80): at full HP handle the
        // fall; nearly dead, the creeper is the bigger killer and flee wins.
        WorldSnapshot s = working();
        s.onGround = false;
        s.velY = -0.7;
        s.fallDistance = 5;
        s.gapBelow = 8;
        s.waterBucketSlot = 3;
        s.mobs.add(creeperAt(5));
        assertEquals(BehaviorId.ANTI_FALL, new ResponseArbiter().decide(s, t).behavior);

        s.hp = 4;
        assertEquals(BehaviorId.FLEE, new ResponseArbiter().decide(s, t).behavior);
    }

    @Test
    public void combatDisengagesToHealWhenHpCrashes() {
        ResponseArbiter a = new ResponseArbiter();
        WorldSnapshot s = working();
        s.bestWeaponSlot = 0;
        s.bestWeaponTier = 2;
        s.mobs.add(skeletonAt(5));
        assertEquals(BehaviorId.COMBAT, a.decide(s, t).behavior);
        // the trade goes badly
        s.hp = 5; // below combatRetreatHp (6)
        s.gameTime = 1;
        assertEquals(BehaviorId.RETREAT_HEAL, a.decide(s, t).behavior);
    }

    @Test
    public void combatDisengagesWhenLosingTheTrade() {
        ResponseArbiter a = new ResponseArbiter();
        WorldSnapshot s = working();
        s.bestWeaponSlot = 0;
        s.bestWeaponTier = 2;
        s.mobs.add(skeletonAt(5));
        assertEquals(BehaviorId.COMBAT, a.decide(s, t).behavior);
        // hp 20 -> 14 within the loss window while the target still lives: stop trading
        s.hp = 14;
        s.gameTime = 30;
        assertEquals(BehaviorId.RETREAT_HEAL, a.decide(s, t).behavior);
    }

    @Test
    public void swarmIsFledNotFought() {
        ResponseArbiter a = new ResponseArbiter();
        WorldSnapshot s = working();
        s.bestWeaponSlot = 0;
        s.bestWeaponTier = 2;
        s.ticksSinceHurt = 5;
        MobInfo z = new MobInfo();
        z.entityId = 1;
        z.hostile = true;
        z.distance = 3;
        z.x = 3;
        s.mobs.add(z);
        assertEquals(BehaviorId.COMBAT, a.decide(s, t).behavior);
        // two friends join
        for (int i = 2; i <= 3; i++) {
            MobInfo m = new MobInfo();
            m.entityId = i;
            m.hostile = true;
            m.distance = 4;
            m.x = 4;
            s.mobs.add(m);
        }
        s.gameTime = 1;
        assertEquals(BehaviorId.FLEE, a.decide(s, t).behavior);
    }

    @Test
    public void poisonGetsTreatedThenReleases() {
        ResponseArbiter a = new ResponseArbiter();
        WorldSnapshot s = working();
        s.poisoned = true;
        s.hp = 8;
        s.bestFoodSlot = 2;
        assertEquals(BehaviorId.RETREAT_HEAL, a.decide(s, t).behavior);
        // healed back up
        s.hp = 15;
        s.poisoned = false;
        s.gameTime = 1;
        // committed episode: doesn't flap closed the instant we cross the target hp
        assertEquals(BehaviorId.RETREAT_HEAL, a.decide(s, t).behavior);
        // ...but once we've been clear past the commit window the mission resumes
        s.gameTime = 40;
        assertEquals(BehaviorId.NONE, a.decide(s, t).behavior);
    }

    @Test
    public void retreatKeepsRunningWhileHostilesPress() {
        // no food, hp low, zombie chasing: retreat must not flap closed (the run itself matters)
        ResponseArbiter a = new ResponseArbiter();
        WorldSnapshot s = working();
        s.bestWeaponSlot = 0;
        s.bestWeaponTier = 2;
        s.mobs.add(skeletonAt(5));
        assertEquals(BehaviorId.COMBAT, a.decide(s, t).behavior);
        s.hp = 5;
        s.gameTime = 1;
        assertEquals(BehaviorId.RETREAT_HEAL, a.decide(s, t).behavior);
        s.gameTime = 2;
        assertEquals("no food but the skeleton is still there: keep retreating",
                BehaviorId.RETREAT_HEAL, a.decide(s, t).behavior);
    }
}
