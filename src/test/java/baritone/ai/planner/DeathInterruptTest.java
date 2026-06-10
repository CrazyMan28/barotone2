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

package baritone.ai.planner;

import org.junit.Test;

import java.util.Locale;

import static org.junit.Assert.assertTrue;

/**
 * When the bot dies mid-tool (a blocking mine/goto/hunt wait), the tool returns this message
 * immediately instead of grinding on bare-fisted for minutes after respawn. The text must name
 * the death spot and tell the (sub-)agent exactly what to do next: stop, the planner replans.
 */
public class DeathInterruptTest {

    @Test
    public void deathAbortMessageNamesTheSpotKillerAndNextStep() {
        DeathEvent d = new DeathEvent(10.4, -58.2, 20.9, "minecraft:overworld", 200,
                "arrow", "skeleton", false);
        String msg = PlannerPrompts.deathAbortMessage(d);
        assertTrue(msg.contains("YOU DIED"));
        assertTrue("coordinates so the agent can reason about the drops",
                msg.contains("10") && msg.contains("-58") && msg.contains("20"));
        assertTrue(msg.toLowerCase(Locale.ROOT).contains("skeleton"));
        assertTrue("must direct the agent to stop and hand back",
                msg.toLowerCase(Locale.ROOT).contains("done"));
    }

    @Test
    public void deathAbortMessageSurvivesAMissingEvent() {
        String msg = PlannerPrompts.deathAbortMessage(null);
        assertTrue(msg.contains("YOU DIED"));
        assertTrue(msg.toLowerCase(Locale.ROOT).contains("done"));
    }
}
