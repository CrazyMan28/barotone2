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
 * The arbiter must preserve every semantic the old {@code ReflexPlanner.pick()} ladder had
 * (these are the 8 original planner tests, re-stated against world snapshots), since those
 * semantics were each earned from a live failure.
 */
public class ResponseArbiterTest {

    private final ReflexTuning t = new ReflexTuning();

    // ---------------------------------------------------------------- snapshot builders

    private static WorldSnapshot calm() {
        WorldSnapshot s = new WorldSnapshot();
        s.working = true;
        return s;
    }

    private static MobInfo creeperAt(double dist) {
        MobInfo m = new MobInfo();
        m.entityId = 100;
        m.typeId = "creeper";
        m.creeper = true;
        m.distance = dist;
        m.x = dist; // somewhere east of the bot
        return m;
    }

    private static MobInfo skeletonAt(double dist) {
        MobInfo m = new MobInfo();
        m.entityId = 200;
        m.typeId = "skeleton";
        m.skeleton = true;
        m.distance = dist;
        m.x = dist;
        return m;
    }

    private static MobInfo zombieAt(double dist) {
        MobInfo m = new MobInfo();
        m.entityId = 300;
        m.typeId = "zombie";
        m.hostile = true;
        m.distance = dist;
        m.x = dist;
        return m;
    }

    private static void armed(WorldSnapshot s) {
        s.bestWeaponSlot = 0;
        s.bestWeaponTier = 2;
    }

    private static void hungry(WorldSnapshot s) {
        s.food = 6;
        s.bestFoodSlot = 2;
        s.bestFoodNutrition = 5;
    }

    private static void recentlyHurt(WorldSnapshot s) {
        s.ticksSinceHurt = 5;
    }

    private BehaviorId decide(ResponseArbiter a, WorldSnapshot s) {
        return a.decide(s, t).behavior;
    }

    // ---------------------------------------------------------------- ported planner tests

    @Test
    public void idleWhenNothingWrong() {
        assertEquals(BehaviorId.NONE, decide(new ResponseArbiter(), calm()));
    }

    @Test
    public void lavaBeatsEverythingEvenMidReflex() {
        WorldSnapshot everything = calm();
        everything.inLava = true;
        everything.underWater = true;
        everything.air = 50;
        everything.mobs.add(creeperAt(5));
        // fresh
        assertEquals(BehaviorId.ESCAPE_LAVA, decide(new ResponseArbiter(), everything));
        // mid-EAT
        ResponseArbiter a = new ResponseArbiter();
        WorldSnapshot eating = calm();
        hungry(eating);
        assertEquals(BehaviorId.EAT, decide(a, eating));
        assertEquals(BehaviorId.ESCAPE_LAVA, decide(a, everything));
        // mid-COMBAT
        ResponseArbiter b = new ResponseArbiter();
        WorldSnapshot fighting = calm();
        armed(fighting);
        fighting.mobs.add(skeletonAt(5));
        assertEquals(BehaviorId.COMBAT, decide(b, fighting));
        assertEquals(BehaviorId.ESCAPE_LAVA, decide(b, everything));
    }

    @Test
    public void priorityOrderOnFreshEngagement() {
        WorldSnapshot s = calm();
        armed(s); // geared so the zombie is a winnable COMBAT, not an OUTMATCHED flee
        s.underWater = true;
        s.air = 50;
        s.mobs.add(creeperAt(5));
        s.mobs.add(zombieAt(3));
        recentlyHurt(s);
        hungry(s);
        assertEquals(BehaviorId.SURFACE, decide(new ResponseArbiter(), s));
        s.underWater = false;
        s.air = 300;
        assertEquals(BehaviorId.FLEE, decide(new ResponseArbiter(), s));
        s.mobs.remove(0); // creeper gone
        assertEquals(BehaviorId.COMBAT, decide(new ResponseArbiter(), s));
        s.mobs.clear(); // zombie gone
        assertEquals(BehaviorId.EAT, decide(new ResponseArbiter(), s));
    }

    @Test
    public void withDefendIdleOffFleeAndFightRequireWorkingButEatDoesNot() {
        t.defendIdle = false; // opt-out for manual play: never hijack movement for combat...
        WorldSnapshot s = calm();
        s.working = false;
        s.mobs.add(creeperAt(5));
        s.mobs.add(zombieAt(3));
        recentlyHurt(s);
        hungry(s);
        assertEquals(BehaviorId.EAT, decide(new ResponseArbiter(), s)); // ...but keep the player fed
    }

    @Test
    public void runningReflexIsStickyUntilDone() {
        ResponseArbiter a = new ResponseArbiter();
        WorldSnapshot s = calm();
        s.mobs.add(creeperAt(5));
        assertEquals(BehaviorId.FLEE, decide(a, s));
        // creeper drifts outside the engage radius (7) but inside the release radius (7+4):
        // hysteresis keeps us fleeing rather than flip-flopping at the boundary
        s.mobs.clear();
        s.mobs.add(creeperAt(9));
        s.gameTime = 1;
        assertEquals(BehaviorId.FLEE, decide(a, s));
        // even past the release radius, the committed episode debounces the release (anti-flap):
        // a single boundary crossing must NOT end the flee immediately
        s.mobs.clear();
        s.mobs.add(creeperAt(12));
        s.gameTime = 2;
        assertEquals(BehaviorId.FLEE, decide(a, s));
        // ...but once it has stayed gone past the dwell + release grace, the episode ends
        s.gameTime = 40;
        assertEquals(BehaviorId.NONE, decide(a, s));
    }

    @Test
    public void fleeStaysEngagedEvenIfMissionEndsMidFlee() {
        ResponseArbiter a = new ResponseArbiter();
        WorldSnapshot s = calm();
        s.mobs.add(creeperAt(5));
        assertEquals(BehaviorId.FLEE, decide(a, s));
        s.working = false; // mission ended while running away
        s.gameTime = 1;
        assertEquals(BehaviorId.FLEE, decide(a, s));
    }

    @Test
    public void fightEscalatesToFleeWhenCreeperArrives() {
        ResponseArbiter a = new ResponseArbiter();
        WorldSnapshot s = calm();
        armed(s);
        s.mobs.add(skeletonAt(5));
        assertEquals(BehaviorId.COMBAT, decide(a, s));
        // a creeper joins mid-fight — and the mission even ended: escalation must still fire
        s.working = false;
        s.mobs.add(creeperAt(5));
        s.gameTime = 1;
        assertEquals(BehaviorId.FLEE, decide(a, s));
    }

    @Test
    public void eatingIsInterruptedByDanger() {
        ResponseArbiter a = new ResponseArbiter();
        WorldSnapshot s = calm();
        armed(s); // geared so the zombie interrupts as COMBAT, not an OUTMATCHED flee
        hungry(s);
        assertEquals(BehaviorId.EAT, decide(a, s));
        // a zombie lands a hit mid-meal
        s.mobs.add(zombieAt(3));
        recentlyHurt(s);
        s.gameTime = 1;
        assertEquals(BehaviorId.COMBAT, decide(a, s));

        ResponseArbiter b = new ResponseArbiter();
        WorldSnapshot s2 = calm();
        hungry(s2);
        assertEquals(BehaviorId.EAT, decide(b, s2));
        s2.underWater = true;
        s2.air = 50;
        s2.gameTime = 1;
        assertEquals(BehaviorId.SURFACE, decide(b, s2));
    }

    // ---------------------------------------------------------------- redesign-specific

    @Test
    public void unarmedSkeletonIsShelteredArmedSkeletonIsFought() {
        WorldSnapshot unarmed = calm();
        unarmed.mobs.add(skeletonAt(5));
        // open-field fleeing a shooter just eats arrows in the back — get behind cover instead
        assertEquals(BehaviorId.SHELTER, decide(new ResponseArbiter(), unarmed));

        WorldSnapshot armed = calm();
        armed(armed);
        armed.mobs.add(skeletonAt(5));
        assertEquals(BehaviorId.COMBAT, decide(new ResponseArbiter(), armed));
    }

    @Test
    public void lowHealthRefusesTheSkeletonFight() {
        WorldSnapshot s = calm();
        armed(s);
        s.hp = 6; // below combatMinHealth (8)
        s.mobs.add(skeletonAt(5));
        assertEquals(BehaviorId.SHELTER, decide(new ResponseArbiter(), s));
    }

    @Test
    public void combatReleasesWhenTargetDies() {
        ResponseArbiter a = new ResponseArbiter();
        WorldSnapshot s = calm();
        armed(s);
        s.mobs.add(skeletonAt(5));
        assertEquals(BehaviorId.COMBAT, decide(a, s));
        s.mobs.clear(); // dead/despawned -> gone from the snapshot
        s.gameTime = 1;
        // committed: combat doesn't flap off the instant the target vanishes for one tick
        assertEquals(BehaviorId.COMBAT, decide(a, s));
        // but once the target has stayed gone past the commit window, the fight is over
        s.gameTime = 40;
        assertEquals(BehaviorId.NONE, decide(a, s));
    }

    @Test
    public void settingsGatesDisableTheirDetectors() {
        ReflexTuning off = new ReflexTuning();
        off.fleeCreepers = false;
        WorldSnapshot s = calm();
        s.mobs.add(creeperAt(5));
        assertEquals(BehaviorId.NONE, new ResponseArbiter().decide(s, off).behavior);

        ReflexTuning noLava = new ReflexTuning();
        noLava.antiLava = false;
        WorldSnapshot lava = calm();
        lava.inLava = true;
        assertEquals(BehaviorId.NONE, new ResponseArbiter().decide(lava, noLava).behavior);
    }
}
