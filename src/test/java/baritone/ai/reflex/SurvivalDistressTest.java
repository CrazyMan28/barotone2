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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The pure distress signal — {@link SurvivalBrain#inDistress()} — that triggers the cooperative LLM
 * survival agent. Distress means the rule ladder is EXHAUSTED and the bot is STILL endangered. Each
 * trigger condition is exercised here with hand-built snapshots; the negative cases prove a calm or a
 * coping bot never flips distress (which would needlessly summon the LLM).
 */
public class SurvivalDistressTest {

    private final ReflexTuning t = new ReflexTuning();

    private static WorldSnapshot working() {
        WorldSnapshot s = new WorldSnapshot();
        s.working = true;
        return s;
    }

    private static MobInfo zombieAt(double dist) {
        MobInfo m = new MobInfo();
        m.entityId = 42;
        m.typeId = "zombie";
        m.hostile = true;
        m.aggro = true;
        m.distance = dist;
        m.x = dist;
        return m;
    }

    private static MobInfo creeperAt(double dist) {
        MobInfo m = new MobInfo();
        m.entityId = 7;
        m.creeper = true;
        m.distance = dist;
        m.x = dist;
        return m;
    }

    private static void boxIn(WorldSnapshot s) {
        for (int i = 0; i < s.octantSafe.length; i++) {
            s.octantSafe[i] = false;
        }
    }

    /** Advance the brain over the same snapshot for N ticks, returning the last distress reading. */
    private boolean tickFor(SurvivalBrain b, WorldSnapshot s, int ticks) {
        boolean d = false;
        long base = s.gameTime;
        for (int i = 0; i < ticks; i++) {
            s.gameTime = base + i;
            b.decide(s, t);
            d = b.inDistress();
        }
        return d;
    }

    // ---------------------------------------------------------------- negative (no false alarms)

    @Test
    public void calmBotIsNotInDistress() {
        SurvivalBrain b = new SurvivalBrain();
        b.decide(working(), t);
        assertFalse(b.inDistress());
    }

    @Test
    public void fleeingButStillShakingItIsNotDistress() {
        // a creeper just appeared — fleeing is the right answer and it hasn't been suppressed yet
        SurvivalBrain b = new SurvivalBrain();
        WorldSnapshot s = working();
        s.mobs.add(creeperAt(4));
        s.blockSlot = 1;
        s.blockCount = 32;
        // a few ticks of a fresh flee — well under the suppression window
        assertFalse(tickFor(b, s, 10));
    }

    @Test
    public void corneredButHasBlocksToWallIsNotDistress() {
        // cornered + a zombie, but we still have blocks AND can dig in: a viable resolve remains
        SurvivalBrain b = new SurvivalBrain();
        WorldSnapshot s = working();
        boxIn(s);
        s.mobs.add(zombieAt(3));
        s.blockSlot = 1;
        s.blockCount = 32;
        s.digDownSafe = true;
        b.decide(s, t);
        assertFalse(b.inDistress());
    }

    // ---------------------------------------------------------------- (1) flee suppressed, mob still on us

    @Test
    public void fleeSuppressedWhileMobStillInRangeIsDistress() {
        SurvivalBrain b = new SurvivalBrain();
        WorldSnapshot s = working();
        s.mobs.add(creeperAt(4));
        // no blocks/dig so the flee can't escalate to a build that resolves it; it just runs forever
        // until the flee clock gives up. Run past the suppression window (maxFleeTicks + cooldown grace).
        boolean distress = tickFor(b, s, t.maxFleeTicks + 5);
        assertTrue("flee gave up but the creeper is still in range -> distress", distress);
    }

    // ---------------------------------------------------------------- (2) cornered, no defenses left

    @Test
    public void corneredWithHostilesAndNoDefensesIsDistress() {
        SurvivalBrain b = new SurvivalBrain();
        WorldSnapshot s = working();
        boxIn(s);
        s.mobs.add(zombieAt(3));
        s.blockSlot = -1;     // no blocks to wall with
        s.blockCount = 0;
        s.digDownSafe = false; // can't dig in (cave/lava/sand below)
        b.decide(s, t);
        assertTrue(b.inDistress());
    }

    // ---------------------------------------------------------------- (3) behavior runs but still hurt

    @Test
    public void survivalBehaviorRunningWhileStillTakingDamageIsDistress() {
        SurvivalBrain b = new SurvivalBrain();
        WorldSnapshot s = working();
        s.mobs.add(zombieAt(3));   // OUTMATCHED -> a mob behavior runs
        s.hp = 12;                  // not critical, just being worn down
        s.ticksSinceHurt = 5;       // hit within the damage window — the behavior isn't resolving
        boolean distress = tickFor(b, s, t.distressTicks + 5);
        assertTrue("a survival behavior ran past distressTicks while still being hurt", distress);
    }

    @Test
    public void survivalBehaviorRunningButNotHurtRecentlyIsNotDistress() {
        // a behavior that has been running a while but we stopped taking damage is COPING, not distress
        SurvivalBrain b = new SurvivalBrain();
        WorldSnapshot s = working();
        s.mobs.add(zombieAt(9));   // far enough that it's a flee/outmatched but not landing hits
        s.hp = 16;
        s.ticksSinceHurt = 200;     // no recent damage
        assertFalse(tickFor(b, s, t.distressTicks + 5));
    }

    // ---------------------------------------------------------------- (4) critical hp under attack

    @Test
    public void criticalHpUnderAttackWhileBehaviorRunsIsDistress() {
        SurvivalBrain b = new SurvivalBrain();
        WorldSnapshot s = working();
        s.mobs.add(zombieAt(4));
        s.hp = 3;                   // at/under criticalHp (default 4)
        s.ticksSinceHurt = 60;       // not freshly hit, but hp is critical with a hostile near
        boolean distress = tickFor(b, s, t.distressTicks / 2 + 3);
        assertTrue("critically low hp with a hostile near while a survival behavior runs", distress);
    }
}
