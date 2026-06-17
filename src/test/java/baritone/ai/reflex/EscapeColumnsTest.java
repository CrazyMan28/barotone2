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

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/** Mob-aware escape-column selection (lava climb-out / drowning surface) — never onto a waiting mob. */
public class EscapeColumnsTest {

    private static MobInfo mobAt(double x, double z) {
        MobInfo m = new MobInfo();
        m.x = x;
        m.z = z;
        m.hostile = true;
        return m;
    }

    @Test
    public void prefersTheColumnAwayFromAMob() {
        BlockPosSpec near = new BlockPosSpec(2, 64, 0);   // a mob is parked here
        BlockPosSpec far = new BlockPosSpec(-3, 64, 0);   // clear
        MobInfo mob = mobAt(2.4, 0);
        BlockPosSpec chosen = EscapeColumns.best(List.of(near, far), List.of(mob));
        assertEquals("must avoid the mob-parked column even though it is nearer", far, chosen);
    }

    @Test
    public void picksTheFarthestFromMobWhenEveryColumnIsBlocked() {
        BlockPosSpec a = new BlockPosSpec(1, 64, 0);
        BlockPosSpec b = new BlockPosSpec(-1, 64, 0);
        MobInfo mob = mobAt(0.8, 0); // closer to a
        BlockPosSpec chosen = EscapeColumns.best(List.of(a, b), List.of(mob));
        assertEquals("when all are mob-adjacent, take the least-bad (farthest)", b, chosen);
    }

    @Test
    public void withNoMobsTakesAnyColumn() {
        BlockPosSpec only = new BlockPosSpec(3, 64, 0);
        assertEquals(only, EscapeColumns.best(List.of(only), List.of()));
        assertNull("no candidates -> nothing", EscapeColumns.best(List.of(), List.of()));
    }
}
