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
 * End-to-end of the DECISION pipeline (no Minecraft, no LLM): a danger the rules can't resolve flows
 * through the {@link SurvivalBrain} into distress, the {@link SurvivalEscalation} debounce eventually
 * fires the escalation, and once the threat is gone the resolve condition becomes true. This is the
 * exact chain {@code ReflexProcess.handleDistressEscalation} runs every tick — just without the
 * adapter, so it is unit-testable. (The off-thread agent START itself is integration-only.)
 */
public class SurvivalEscalationTriggerTest {

    private final ReflexTuning t = new ReflexTuning();

    private static MobInfo zombieAt(double dist) {
        MobInfo m = new MobInfo();
        m.entityId = 1;
        m.typeId = "zombie";
        m.hostile = true;
        m.aggro = true;
        m.distance = dist;
        m.x = dist;
        return m;
    }

    /** A bot being beaten by a mob it can't out-trade, taking hits, no way out — the death-spiral. */
    private static WorldSnapshot cornered() {
        WorldSnapshot s = new WorldSnapshot();
        s.working = true;
        s.hp = 8;
        s.ticksSinceHurt = 3; // freshly hit
        for (int i = 0; i < s.octantSafe.length; i++) {
            s.octantSafe[i] = false; // boxed in
        }
        s.blockSlot = -1;
        s.blockCount = 0;
        s.digDownSafe = false;
        s.mobs.add(zombieAt(2));
        return s;
    }

    @Test
    public void distressLadderEventuallyTriggersEscalationButNotInstantly() {
        SurvivalBrain brain = new SurvivalBrain();
        int sustainRequired = 60;
        int sustained = 0;
        boolean firedEarly = false;

        for (int tick = 0; tick < sustainRequired - 1; tick++) {
            WorldSnapshot s = cornered();
            s.gameTime = tick;
            brain.decide(s, t);
            if (brain.inDistress()) {
                sustained++;
            } else {
                sustained = 0;
            }
            if (SurvivalEscalation.shouldEscalate(brain.inDistress(), sustained, sustainRequired,
                    false, true, true, true)) {
                firedEarly = true;
            }
        }
        // the debounce must not have fired before the window filled
        assertFalse("escalation must wait out the debounce window", firedEarly);

        // one more tick reaches the threshold -> now it fires
        WorldSnapshot s = cornered();
        s.gameTime = sustainRequired;
        brain.decide(s, t);
        sustained++;
        assertTrue(brain.inDistress());
        assertTrue(SurvivalEscalation.shouldEscalate(brain.inDistress(), sustained, sustainRequired,
                false, true, true, true));
    }

    @Test
    public void resolveBecomesTrueOnceTheThreatIsGone() {
        SurvivalBrain brain = new SurvivalBrain();
        // first, get into distress
        for (int tick = 0; tick < 80; tick++) {
            WorldSnapshot s = cornered();
            s.gameTime = tick;
            brain.decide(s, t);
        }
        assertTrue(brain.inDistress());

        // now the danger clears: no mobs, healthy, open ground. Count calm ticks like the adapter does.
        int requiredClear = 60;
        int clear = 0;
        boolean resolved = false;
        for (int tick = 80; tick < 80 + requiredClear; tick++) {
            WorldSnapshot s = new WorldSnapshot();
            s.working = true;
            s.gameTime = tick;
            brain.decide(s, t);
            boolean hostilesNear = brain.situation().hostilesNear > 0;
            if (!brain.inDistress() && !hostilesNear) {
                clear++;
            } else {
                clear = 0;
            }
            if (SurvivalEscalation.isResolved(brain.inDistress(), hostilesNear, clear, requiredClear)) {
                resolved = true;
            }
        }
        assertTrue("once the threat is gone for the window, the survival situation resolves", resolved);
    }
}
