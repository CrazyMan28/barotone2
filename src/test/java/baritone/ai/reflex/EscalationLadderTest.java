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
 * The escalation ladder: a flee that isn't working gets RESOLVED — pillar above a creeper,
 * wall off a skeleton's arrows, or run a different way — instead of the old "give up for a
 * cooldown and resume mining next to the creeper".
 */
public class EscalationLadderTest {

    private final ReflexTuning t = new ReflexTuning();

    private static WorldSnapshot chasedBy(boolean creeper, int blocks) {
        WorldSnapshot s = new WorldSnapshot();
        s.working = true;
        s.posY = 64;
        s.blockCount = blocks;
        s.blockSlot = blocks > 0 ? 5 : -1;
        MobInfo m = new MobInfo();
        m.entityId = 1;
        m.creeper = creeper;
        // non-creeper chases use a zombie vs an unarmed bot (OUTMATCHED): skeletons now route
        // to SHELTER instead of FLEE, so they never reach the flee-escalation ladder
        m.hostile = !creeper;
        m.distance = 5;
        m.x = 5;
        m.y = 64;
        s.mobs.add(m);
        return s;
    }

    /** Run the arbiter through a long failed flee and return the mode it resolves to. */
    private FleeMode resolveAfterLongChase(WorldSnapshot template) {
        ResponseArbiter a = new ResponseArbiter();
        ResponsePlan plan = null;
        for (long tick = 0; tick <= 140; tick++) {
            template.gameTime = tick;
            plan = a.decide(template, t);
            assertEquals("the chase keeps the bot fleeing", BehaviorId.FLEE, plan.behavior);
        }
        return plan.fleeMode;
    }

    @Test
    public void creeperChaseResolvesToPillar() {
        assertEquals(FleeMode.PILLAR, resolveAfterLongChase(chasedBy(true, 30)));
    }

    @Test
    public void unwinnableZombieChaseResolvesToWall() {
        assertEquals(FleeMode.WALL, resolveAfterLongChase(chasedBy(false, 30)));
    }

    @Test
    public void noBlocksResolvesToANewDirection() {
        assertEquals(FleeMode.NEW_DIRECTION, resolveAfterLongChase(chasedBy(true, 0)));
    }

    /**
     * A flee that is actually OPENING GROUND keeps running (NORMAL) — it must not needlessly escalate
     * to pillar/wall while the plan is working. (Progress is simulated by advancing the bot's position
     * each tick; the progress watchdog sees movement and holds off.)
     */
    @Test
    public void aProgressingFleeKeepsRunning() {
        ResponseArbiter a = new ResponseArbiter();
        WorldSnapshot s = chasedBy(true, 30);
        for (long tick = 0; tick <= 80; tick++) {
            s.gameTime = tick;
            s.posX += 0.2; // the bot is moving — a working flee, not a wedged one
            assertEquals(FleeMode.NORMAL, a.decide(s, t).fleeMode);
        }
    }

    /**
     * The fix: a flee that makes NO progress (wedged against terrain / boxed in) escalates FAST — within
     * a second or two — instead of letting the chaser tee off for the full ~6s flee clock. The bot here
     * never moves and never opens distance, so the progress watchdog trips and the ladder resolves.
     */
    @Test
    public void aPinnedFleeEscalatesWithoutWaitingOutTheClock() {
        ResponseArbiter a = new ResponseArbiter();
        WorldSnapshot s = chasedBy(true, 30); // creeper at dist 5, bot static (pinned)
        FleeMode mode = FleeMode.NORMAL;
        int escalatedAt = -1;
        for (long tick = 0; tick <= 60; tick++) {
            s.gameTime = tick;
            mode = a.decide(s, t).fleeMode;
            if (mode != FleeMode.NORMAL && escalatedAt < 0) {
                escalatedAt = (int) tick;
            }
        }
        assertEquals("a pinned creeper flee escalates to PILLAR", FleeMode.PILLAR, mode);
        org.junit.Assert.assertTrue("must escalate well before the ~6s time clock (got tick " + escalatedAt + ")",
                escalatedAt >= 0 && escalatedAt < 60);
    }

    @Test
    public void pillarThatRunsOutOfBlocksDowngradesToRunning() {
        // a creeper on a +3 ledge needs an ~8-tall pillar; with the few blocks gone the pillar can't
        // finish — the brain must re-pick (NEW_DIRECTION) instead of re-emitting a build it can't do.
        ResponseArbiter a = new ResponseArbiter();
        WorldSnapshot s = chasedBy(true, 8);
        s.mobs.get(0).y = 67; // creeper 3 blocks above the bot (a stub pillar won't clear it)
        // escalate to PILLAR over a long chase
        FleeMode mode = FleeMode.NORMAL;
        for (long tick = 0; tick <= 140; tick++) {
            s.gameTime = tick;
            mode = a.decide(s, t).fleeMode;
        }
        assertEquals("a ledge creeper escalates to PILLAR", FleeMode.PILLAR, mode);
        // now the blocks are spent — the next decision must downgrade away from the doomed pillar
        s.blockCount = 0;
        s.blockSlot = -1;
        s.gameTime = 141;
        assertEquals("an exhausted pillar downgrades to running",
                FleeMode.NEW_DIRECTION, a.decide(s, t).fleeMode);
    }

    @Test
    public void engineReportsTheResolution() {
        ReflexEngine e = new ReflexEngine();
        WorldSnapshot s = chasedBy(true, 30);
        boolean sawResolution = false;
        for (long tick = 0; tick <= 140; tick++) {
            s.gameTime = tick;
            ReflexEngine.Output out = e.tick(s, t);
            if (out.resolvedMode != null) {
                assertEquals(FleeMode.PILLAR, out.resolvedMode);
                sawResolution = true;
            }
        }
        assertEquals("the adapter needs exactly one 'resolve' phase event to log", true, sawResolution);
    }
}
