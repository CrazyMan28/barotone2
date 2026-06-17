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

package baritone.ai.reflex.sim;

import baritone.ai.reflex.BehaviorId;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

/**
 * The regression that the old Minecraft-free sim could NOT catch and that killed the bot in the live
 * game: a get-away that drives the bot with a raw {@code MOVE_FORWARD} locked to the looked direction
 * grinds to a halt the instant that direction is blocked by terrain the one-block hazard look-ahead
 * never saw (a wall just past it, a diagonal pinch, the smoothed-look lag). The chaser then catches the
 * stuck bot and kills it — exactly the "looks one way, tries to move, gets stuck" death the user saw.
 *
 * <p>{@link SurvivalSim} now models that faithfully ({@code octantWalkable} ≠ {@code octantSafe} and the
 * flee moves along the behavior's ACTUAL emitted actions), so these tests fail against a raw-input flee
 * and pass once the get-away hands Baritone a pathing goal that routes around the obstacle.
 */
public class MovementFidelityTest {

    private static final int TICKS = 300;

    /**
     * A creeper at point-blank (always a FLEE, never a brawl) with NO blocks (can't pillar — must run)
     * and the straight-away direction walled off (hazard-free, so the reflex's look-ahead still picks it)
     * but open lanes to the sides. A raw sprint locked to the wall is stuck and the creeper detonates on
     * it; a pathfinding flee routes out a side lane and opens distance.
     */
    @Test
    public void fleeRoutesAroundAWallInsteadOfGrindingIntoIt() {
        SurvivalSim s = new SurvivalSim();
        s.creeper(3, 270);       // point-blank toward -Z; "away" is +Z (octant 0). No blocks -> must run.
        s.wall(0);               // +Z (the straight-away lane) is a wall: safe-looking but not walkable.
        // octants 1..7 stay walkable: a goal can route out a side lane.

        SurvivalSim.Outcome o = s.run(TICKS);

        assertTrue("flee must route around the wall and survive, not grind into it and get blown up"
                        + " (behavior=" + o.lastBehavior + ", cause=" + o.cause
                        + ", hp=" + o.finalHp + ", seen=" + s.behaviorsSeen + ")",
                o.survived);
    }

    /**
     * Control: the SAME creeper with NO wall must survive — proving the death above is the locomotion
     * getting stuck on terrain, not the creeper being unsurvivable from 3 blocks.
     */
    @Test
    public void sameCreeperWithoutAWallSurvives() {
        SurvivalSim s = new SurvivalSim();
        s.creeper(3, 270);
        SurvivalSim.Outcome o = s.run(TICKS);
        assertTrue("an open-terrain flee from one creeper must survive (cause=" + o.cause
                + ", seen=" + s.behaviorsSeen + ")", o.survived);
    }

    /**
     * Boxed by walls on every side but one open lane: a raw sprint that looks at the (walled)
     * straight-away never finds the gap; a pathing goal does. The cave/ravine version of the same bug.
     */
    @Test
    public void fleeFindsTheOneOpenLaneWhenBoxedByWalls() {
        SurvivalSim s = new SurvivalSim();
        s.creeper(3, 270);   // away = +Z = octant 0
        s.wallsExcept(2);    // only +X (octant 2) is walkable; the straight-away +Z is walled
        SurvivalSim.Outcome o = s.run(TICKS);
        assertTrue("flee must find the single open lane and escape (behavior=" + o.lastBehavior
                        + ", cause=" + o.cause + ", seen=" + s.behaviorsSeen + ")",
                o.survived);
    }
}
