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

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/** Rising-edge death capture: the tick where isDeadOrDying flips true is where the drops are. */
public class DeathWatchTest {

    @Before
    public void reset() {
        DeathWatch.resetForTests();
    }

    @Test
    public void capturesPositionOnTheRisingEdgeOnly() {
        DeathWatch.onClientTick(false, 1, 2, 3, "minecraft:overworld", 100);
        assertEquals(0, DeathWatch.currentSeq());
        assertNull(DeathWatch.pollNewDeath(0));

        DeathWatch.onClientTick(true, 10, -58, 20, "minecraft:overworld", 200);
        assertEquals(1, DeathWatch.currentSeq());
        DeathEvent death = DeathWatch.pollNewDeath(0);
        assertNotNull(death);
        assertEquals(10, death.x, 0.001);
        assertEquals(-58, death.y, 0.001);
        assertEquals(200, death.gameTime);

        // staying dead must NOT register more deaths (death screen lasts many ticks)
        DeathWatch.onClientTick(true, 11, -58, 21, "minecraft:overworld", 210);
        assertEquals(1, DeathWatch.currentSeq());
        // but the clock keeps advancing — the planner uses it for the despawn window
        assertEquals(210, DeathWatch.currentGameTime());
    }

    @Test
    public void secondDeathAfterRespawnIncrementsSeq() {
        DeathWatch.onClientTick(true, 10, -58, 20, "minecraft:overworld", 200);
        DeathWatch.onClientTick(false, 0, 70, 0, "minecraft:overworld", 300); // respawned
        DeathWatch.onClientTick(true, 50, 12, 60, "minecraft:overworld", 400);

        assertEquals(2, DeathWatch.currentSeq());
        DeathEvent death = DeathWatch.pollNewDeath(1);
        assertNotNull(death);
        assertEquals(50, death.x, 0.001);
        assertEquals(400, death.gameTime);
    }

    @Test
    public void pollIsNullWhenCallerHasSeenTheLatestDeath() {
        DeathWatch.onClientTick(true, 10, -58, 20, "minecraft:overworld", 200);
        long seen = DeathWatch.currentSeq();
        assertNull(DeathWatch.pollNewDeath(seen));
    }
}
