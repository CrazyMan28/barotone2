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

import java.util.List;

/**
 * Pure mob-aware selection of an escape column (out of lava, or up from drowning). The adapter does
 * the block scan to find the physically-valid candidate columns; this picks among them the one that
 * keeps us away from the mobs — the lava-escape and surface deaths in the logs were the bot climbing
 * out RIGHT next to a waiting mob (or cooking because a mob stood on the only open column).
 *
 * <p>Minecraft-free so both the adapter and the survival sim choose the column with the same logic,
 * which is what makes the "mob blocks the escape" scenarios a fair test of the real rules.
 */
public final class EscapeColumns {

    /** A candidate column blocked when a hostile is standing within this radius of it. */
    public static final double MOB_BLOCK_RADIUS = 2.0D;

    private EscapeColumns() {
    }

    /**
     * Pick the best escape column: among the candidates, prefer ones with NO mob within
     * {@link #MOB_BLOCK_RADIUS}; among those, the one farthest from the nearest hostile. If every
     * candidate is mob-adjacent, still take the one farthest from a mob (least-bad), so the bot
     * always climbs out somewhere rather than cooking in place. Null only when there are no candidates.
     */
    public static BlockPosSpec best(List<BlockPosSpec> candidates, List<MobInfo> mobs) {
        BlockPosSpec best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (BlockPosSpec c : candidates) {
            double nearest = nearestMobDistance(c, mobs);
            // a clear column always beats a mob-adjacent one; within each class, farther is better
            double score = (nearest >= MOB_BLOCK_RADIUS ? 1_000_000D : 0D) + nearest;
            if (score > bestScore) {
                bestScore = score;
                best = c;
            }
        }
        return best;
    }

    private static double nearestMobDistance(BlockPosSpec c, List<MobInfo> mobs) {
        double nearest = Double.MAX_VALUE;
        for (MobInfo m : mobs) {
            double dx = (c.x + 0.5D) - m.x;
            double dz = (c.z + 0.5D) - m.z;
            nearest = Math.min(nearest, Math.hypot(dx, dz));
        }
        return nearest == Double.MAX_VALUE ? Double.MAX_VALUE : nearest;
    }
}
