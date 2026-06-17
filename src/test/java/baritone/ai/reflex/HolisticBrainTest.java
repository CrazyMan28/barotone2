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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The holistic rules the {@link SurvivalBrain} adds on top of the proven single-threat core:
 * critical starvation, cornered-flee→bunker, and the whole-picture {@link SituationAssessment}.
 * Each rule is inert unless its combination is actually present (the existing arbiter suite proves
 * single-threat decisions are unchanged), so these tests exercise exactly the combinations.
 */
public class HolisticBrainTest {

    private final ReflexTuning t = new ReflexTuning();

    private static WorldSnapshot calm() {
        WorldSnapshot s = new WorldSnapshot();
        s.working = true;
        return s;
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

    private static MobInfo creeperAt(double dist) {
        MobInfo m = new MobInfo();
        m.entityId = 100;
        m.creeper = true;
        m.distance = dist;
        m.x = dist;
        return m;
    }

    private static void hasFood(WorldSnapshot s) {
        s.bestFoodSlot = 2;
        s.bestFoodNutrition = 5;
    }

    private static void hasBlocks(WorldSnapshot s) {
        s.blockSlot = 1;
        s.blockCount = 32;
    }

    private static void boxIn(WorldSnapshot s) {
        // only one safe direction left — running just hits a wall
        for (int i = 0; i < s.octantSafe.length; i++) {
            s.octantSafe[i] = false;
        }
        s.octantSafe[0] = true;
    }

    private BehaviorId decide(SurvivalBrain b, WorldSnapshot s) {
        return b.decide(s, t).behavior;
    }

    // ---------------------------------------------------------------- critical starvation

    @Test
    public void starvingWithNoThreatNearEatsNow() {
        WorldSnapshot s = calm();
        s.food = 2;
        hasFood(s);
        assertEquals(BehaviorId.EAT, decide(new SurvivalBrain(), s));
    }

    @Test
    public void starvingButAMobIsCloseDoesNotStopToEat() {
        WorldSnapshot s = calm();
        s.food = 2;
        hasFood(s);
        s.mobs.add(zombieAt(5)); // inside starvationSafeRadius (8): eating here would be suicide
        // unarmed vs a zombie is an OUTMATCHED flee, not a stand-still meal
        assertEquals(BehaviorId.FLEE, decide(new SurvivalBrain(), s));
    }

    @Test
    public void starvationFiresWhenTheCreeperIsOutOfRange() {
        // a creeper well beyond flee + safe-eat range is no immediate danger; starving, eat now so we
        // aren't one-shot later. (Any creeper inside range would flee first — see the close-mob test.)
        WorldSnapshot s = calm();
        s.food = 1;
        hasFood(s);
        s.mobs.add(creeperAt(13)); // outside flee range and starvationSafeRadius
        assertEquals(BehaviorId.EAT, decide(new SurvivalBrain(), s));
    }

    @Test
    public void wellFedNeverStarves() {
        WorldSnapshot s = calm();
        s.food = 12; // low-ish but not critical
        hasFood(s);
        SurvivalBrain b = new SurvivalBrain();
        b.decide(s, t);
        // normal hunger may or may not fire, but it is NEVER the critical STARVATION path
        assertFalse(b.situation().starving);
    }

    // ---------------------------------------------------------------- cornered -> bunker

    @Test
    public void corneredFleeBecomesBunkerWhenWeHaveBlocks() {
        WorldSnapshot s = calm();
        s.mobs.add(zombieAt(5)); // unarmed -> OUTMATCHED -> would normally FLEE
        boxIn(s);
        hasBlocks(s);
        // no room to run + blocks in hand: wall off and heal instead of sprinting into the wall
        assertEquals(BehaviorId.RETREAT_HEAL, decide(new SurvivalBrain(), s));
    }

    @Test
    public void openFieldStillFleesNotBunkers() {
        WorldSnapshot s = calm();
        s.mobs.add(zombieAt(5));
        hasBlocks(s);
        // octantSafe defaults all-true: plenty of room, so running is still right
        assertEquals(BehaviorId.FLEE, decide(new SurvivalBrain(), s));
    }

    @Test
    public void corneredByACreeperStillFleesNeverBunkersBesideIt() {
        WorldSnapshot s = calm();
        s.mobs.add(creeperAt(5));
        boxIn(s);
        hasBlocks(s);
        // walling yourself in next to a creeper is death; the flee ladder pillars up instead
        assertEquals(BehaviorId.FLEE, decide(new SurvivalBrain(), s));
    }

    @Test
    public void corneredWithNoBlocksFallsBackToPlainFlee() {
        WorldSnapshot s = calm();
        s.mobs.add(zombieAt(5));
        boxIn(s);
        // no blocks to bunker with: plain flee (its own NEW_DIRECTION ladder takes over)
        assertEquals(BehaviorId.FLEE, decide(new SurvivalBrain(), s));
    }

    // ---------------------------------------------------------------- whole-picture assessment

    @Test
    public void assessmentCountsTheWholeThreatPicture() {
        WorldSnapshot s = calm();
        s.mobs.add(creeperAt(4));
        s.mobs.add(zombieAt(5));
        MobInfo sk = new MobInfo();
        sk.skeleton = true;
        sk.distance = 6;
        sk.x = 6;
        s.mobs.add(sk);
        SurvivalBrain b = new SurvivalBrain();
        b.decide(s, t);
        SituationAssessment a = b.situation();
        assertEquals(1, a.creepersNear);
        assertEquals(1, a.rangedNear);
        assertEquals(1, a.meleeNear);
        assertEquals(3, a.hostilesNear);
        assertEquals(SituationAssessment.Level.ENDANGERED, a.level);
    }

    @Test
    public void assessmentFlagsTerrainHazardAsCritical() {
        WorldSnapshot s = calm();
        s.inLava = true;
        SurvivalBrain b = new SurvivalBrain();
        b.decide(s, t);
        assertEquals(SituationAssessment.Level.CRITICAL, b.situation().level);
        assertTrue(b.situation().terrainHazard);
    }

    @Test
    public void calmBotAssessesAsSafe() {
        SurvivalBrain b = new SurvivalBrain();
        b.decide(calm(), t);
        assertEquals(SituationAssessment.Level.SAFE, b.situation().level);
    }

    // ---------------------------------------------------------------- post-episode report

    @Test
    public void episodeLeavesAReportForTheAgent() {
        SurvivalBrain b = new SurvivalBrain();
        WorldSnapshot s = calm();
        s.mobs.add(creeperAt(4));
        s.posX = 0;
        assertEquals(BehaviorId.FLEE, b.decide(s, t).behavior);
        // run away; once the creeper is gone the episode ends (after the anti-flap dwell+grace)
        BehaviorId last = BehaviorId.FLEE;
        for (long gt = 30; gt <= 400 && last != BehaviorId.NONE; gt += 20) {
            WorldSnapshot away = calm();
            away.posX = 20;
            away.gameTime = gt;
            last = b.decide(away, t).behavior;
        }
        assertEquals("the flee episode must end once safe", BehaviorId.NONE, last);
        SurvivalReport r = b.lastReport();
        org.junit.Assert.assertNotNull("episode must leave a report", r);
        assertEquals("creeper", r.threat);
        assertTrue("must record that the bot relocated", r.movedBlocks >= 3D);
        assertTrue("should tell the agent where not to walk back", r.hasAvoid);
    }
}
