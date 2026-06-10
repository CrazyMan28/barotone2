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

package baritone.ai.reflex.behavior;

import baritone.ai.reflex.BehaviorId;
import baritone.ai.reflex.BlockPosSpec;
import baritone.ai.reflex.Detectors;
import baritone.ai.reflex.MobInfo;
import baritone.ai.reflex.ReflexAction;
import baritone.ai.reflex.ReflexBehavior;
import baritone.ai.reflex.ReflexMath;
import baritone.ai.reflex.ReflexTuning;
import baritone.ai.reflex.ResponsePlan;
import baritone.ai.reflex.ThreatType;
import baritone.ai.reflex.WorldSnapshot;
import baritone.api.utils.input.Input;

import java.util.ArrayList;
import java.util.List;

/**
 * Turtle up instead of dying in the open. Modes:
 * <ul>
 * <li>BED — a bed in the hotbar and no hostile nearby: place it and sleep (skips the night);</li>
 * <li>DIG_IN — dig {@code shelterDigDepth} blocks straight down (only while {@code digDownSafe}
 *     — never into a cave or lava) and seal the hole overhead;</li>
 * <li>WALL_IN — no safe ground but blocks to spare: brick up the approach (the FleeBehavior
 *     wall idiom);</li>
 * <li>BREAK_LOS — nothing to build with: move behind per-octant cover until no shooter has
 *     line of sight, then settle;</li>
 * <li>WAIT — sheltered: stand still and keep eating so natural regen runs until the arbiter
 *     releases us (dawn / geared / threats gone / timeout).</li>
 * </ul>
 */
public final class ShelterBehavior implements ReflexBehavior {

    /** Ticks spent placing the wall before settling. */
    private static final int WALL_PLACE_TICKS = 12;
    /** Give up on the bed after this long (mob wandered close, placement failing) and dig instead. */
    private static final int BED_TRY_TICKS = 60;
    /** Vanilla refuses sleep with a hostile within ~8; don't even try below this clearance. */
    private static final double BED_CALM_RADIUS = 12D;

    private enum Mode { BED, DIG_IN, WALL_IN, BREAK_LOS, WAIT }

    private Mode mode = Mode.BREAK_LOS;
    private double digBaseY = Double.NaN;
    private int losClearTicks;
    private int wallTicks;
    private int bedTicks;
    private boolean bedPlaced;
    private BlockPosSpec bedCell;

    @Override
    public BehaviorId id() {
        return BehaviorId.SHELTER;
    }

    @Override
    public void enter(WorldSnapshot s, ResponsePlan plan) {
        digBaseY = Double.NaN;
        losClearTicks = 0;
        wallTicks = 0;
        bedTicks = 0;
        bedPlaced = false;
        bedCell = null;
        if (plan != null && plan.cause != null && plan.cause.type == ThreatType.RANGED) {
            mode = Mode.BREAK_LOS;
        } else {
            mode = pickNightMode(s);
        }
    }

    @Override
    public List<ReflexAction> tick(WorldSnapshot s, ReflexTuning t, ResponsePlan plan) {
        if (s.sealedOverhead && mode != Mode.BED && mode != Mode.WAIT) {
            mode = Mode.WAIT; // already turtled in
        }
        switch (mode) {
            case BED:
                return tickBed(s, t);
            case DIG_IN:
                return tickDig(s, t);
            case WALL_IN:
                return tickWall(s, t);
            case BREAK_LOS:
                return tickBreakLos(s, t, plan);
            default:
                return tickWait(s, t);
        }
    }

    @Override
    public void exit() {
    }

    // ---------------------------------------------------------------- modes

    private Mode pickNightMode(WorldSnapshot s) {
        if (s.bedSlot >= 0 && !Detectors.anyWithin(s, BED_CALM_RADIUS, ShelterBehavior::hostile)) {
            return Mode.BED;
        }
        // Digging needs NO items — bare hands carve dirt/stone. A 2-deep hole drops the bot below
        // a skeleton's arrow line even unsealed (sealing is a bonus when we DO have blocks). This is
        // the universal answer a freshly-respawned, empty-handed bot has against a shooter in the open.
        if (s.digDownSafe) {
            return Mode.DIG_IN;
        }
        if (s.blockSlot >= 0 && s.blockCount >= 4) {
            return Mode.WALL_IN;
        }
        return Mode.BREAK_LOS;
    }

    private Mode pickFallback(WorldSnapshot s) {
        if (s.digDownSafe) {
            return Mode.DIG_IN;
        }
        if (s.blockSlot >= 0 && s.blockCount >= 2) {
            return Mode.WALL_IN;
        }
        return Mode.BREAK_LOS;
    }

    /** Place the bed in an adjacent safe cell and right-click it until we're asleep. */
    private List<ReflexAction> tickBed(WorldSnapshot s, ReflexTuning t) {
        bedTicks++;
        if (s.bedSlot < 0 || bedTicks > BED_TRY_TICKS
                || Detectors.anyWithin(s, 8D, ShelterBehavior::hostile)) {
            mode = pickFallback(s);
            return List.of(ReflexAction.releaseAll());
        }
        if (bedCell == null) {
            int octant = Moves.safeOctantToward(s, s.yaw);
            int dx = octant >= 0 ? ReflexMath.OCTANT_DX[octant] : 0;
            int dz = octant >= 0 ? ReflexMath.OCTANT_DZ[octant] : 1;
            bedCell = new BlockPosSpec(
                    (int) Math.floor(s.posX) + dx, (int) Math.floor(s.posY), (int) Math.floor(s.posZ) + dz);
        }
        if (!bedPlaced) {
            bedPlaced = true;
            return List.of(
                    ReflexAction.releaseAll(),
                    ReflexAction.selectSlot(s.bedSlot),
                    ReflexAction.snapLook(ReflexMath.yawToward(s.posX, s.posZ,
                            bedCell.x + 0.5D, bedCell.z + 0.5D), 35F),
                    ReflexAction.placeBlock(bedCell)
            );
        }
        return List.of(ReflexAction.useBlock(bedCell));
    }

    /** Dig straight down (while it stays safe), then seal the hole two above the feet. */
    private List<ReflexAction> tickDig(WorldSnapshot s, ReflexTuning t) {
        if (Double.isNaN(digBaseY)) {
            digBaseY = Math.floor(s.posY);
        }
        double depth = digBaseY - s.posY;
        if (depth >= t.shelterDigDepth - 0.2D) {
            if (s.blockSlot < 0) {
                mode = Mode.WAIT; // nothing to seal with — at least we're below ground level
                return tickWait(s, t);
            }
            return List.of(
                    ReflexAction.releaseAll(),
                    ReflexAction.selectSlot(s.blockSlot),
                    ReflexAction.placeBlock(new BlockPosSpec(
                            (int) Math.floor(s.posX), (int) Math.floor(s.posY) + 2, (int) Math.floor(s.posZ)))
            );
        }
        if (!s.digDownSafe) {
            // a cave/lava opened under the next block — stop and brick up instead
            mode = s.blockSlot >= 0 ? Mode.WALL_IN : Mode.BREAK_LOS;
            return mode == Mode.WALL_IN ? tickWall(s, t) : List.of(ReflexAction.releaseAll());
        }
        return List.of(
                ReflexAction.releaseAll(),
                ReflexAction.snapLook(s.yaw, 90F),
                ReflexAction.hold(Input.CLICK_LEFT, true)
        );
    }

    /** Brick up the cell toward the nearest menace, feet and head height (the flee-wall idiom). */
    private List<ReflexAction> tickWall(WorldSnapshot s, ReflexTuning t) {
        wallTicks++;
        if (wallTicks > WALL_PLACE_TICKS || s.blockSlot < 0) {
            mode = Mode.WAIT;
            return tickWait(s, t);
        }
        MobInfo menace = Detectors.nearest(s, Double.MAX_VALUE, ShelterBehavior::hostile);
        int ox = 0, oz = 1;
        if (menace != null) {
            double dx = menace.x - s.posX;
            double dz = menace.z - s.posZ;
            if (Math.abs(dx) >= Math.abs(dz)) {
                ox = dx >= 0 ? 1 : -1;
                oz = 0;
            } else {
                ox = 0;
                oz = dz >= 0 ? 1 : -1;
            }
        }
        int feetY = (int) Math.floor(s.posY);
        BlockPosSpec feet = new BlockPosSpec((int) Math.floor(s.posX) + ox, feetY, (int) Math.floor(s.posZ) + oz);
        BlockPosSpec head = new BlockPosSpec(feet.x, feetY + 1, feet.z);
        return List.of(
                ReflexAction.releaseAll(),
                ReflexAction.selectSlot(s.blockSlot),
                ReflexAction.snapLook(ReflexMath.yawToward(s.posX, s.posZ, feet.x + 0.5D, feet.z + 0.5D), 35F),
                ReflexAction.placeBlock(feet),
                ReflexAction.placeBlock(head)
        );
    }

    /** Move behind per-octant cover until no shooter holds line of sight, then settle. */
    private List<ReflexAction> tickBreakLos(WorldSnapshot s, ReflexTuning t, ResponsePlan plan) {
        boolean nightCause = plan != null && plan.cause != null
                && plan.cause.type == ThreatType.NIGHT_EXPOSURE;
        MobInfo shooter = Detectors.nearest(s, t.perceptionRadius,
                m -> m.lineOfSight && hostile(m));
        if (shooter == null) {
            losClearTicks++;
            if (losClearTicks >= t.shelterLosGraceTicks) {
                mode = Mode.WAIT;
                return tickWait(s, t);
            }
        } else {
            losClearTicks = 0;
        }
        // with the night cause, mobs that merely see us (no LOS flag) are still worth hiding from
        MobInfo menace = shooter != null ? shooter
                : nightCause ? Detectors.nearest(s, t.perceptionRadius, ShelterBehavior::hostile) : null;
        if (menace == null) {
            return List.of(ReflexAction.releaseAll()); // hold position behind the cover we found
        }
        float awayYaw = ReflexMath.yawAway(s.posX, s.posZ, menace.x, menace.z);
        int cover = bestCoverOctant(s, awayYaw);
        if (cover < 0) {
            // no terrain cover to duck behind. Dig DOWN (no blocks needed) to drop below the arrow
            // line — far better than running in a straight line and getting shot in the back...
            if (s.digDownSafe) {
                mode = Mode.DIG_IN;
                return tickDig(s, t);
            }
            // ...else wall it off if we have blocks, else just keep distance along a safe direction
            if (s.blockSlot >= 0 && s.blockCount >= 2) {
                mode = Mode.WALL_IN;
                return tickWall(s, t);
            }
            return run(Moves.safeFleeYaw(s, awayYaw));
        }
        return run(ReflexMath.octantYaw(cover));
    }

    private List<ReflexAction> run(float yaw) {
        List<ReflexAction> actions = new ArrayList<>(3);
        actions.add(ReflexAction.look(yaw, 5F));
        actions.add(ReflexAction.hold(Input.MOVE_FORWARD, true));
        actions.add(ReflexAction.hold(Input.SPRINT, true));
        return actions;
    }

    /** Sheltered: stand still and keep the hunger bar high enough for natural regen. */
    private List<ReflexAction> tickWait(WorldSnapshot s, ReflexTuning t) {
        List<ReflexAction> actions = new ArrayList<>(4);
        actions.add(ReflexAction.releaseAll());
        if (s.bestFoodSlot >= 0 && s.food < t.eatReleaseFood && !s.screenOpen) {
            actions.add(ReflexAction.selectSlot(s.bestFoodSlot));
            actions.add(ReflexAction.look(s.yaw, -75F));
            actions.add(ReflexAction.useItem());
        }
        return actions;
    }

    /** The covered AND safe octant nearest the direction we want to go (-1 = none). */
    private static int bestCoverOctant(WorldSnapshot s, float desiredYaw) {
        int best = -1;
        float bestDelta = Float.MAX_VALUE;
        for (int i = 0; i < ReflexMath.OCTANTS; i++) {
            if (!s.octantCover[i] || !s.octantSafe[i]) {
                continue;
            }
            float d = Math.abs(ReflexMath.angleDelta(desiredYaw, ReflexMath.octantYaw(i)));
            if (d < bestDelta) {
                bestDelta = d;
                best = i;
            }
        }
        return best;
    }

    private static boolean hostile(MobInfo m) {
        return m.hostile || m.creeper || m.skeleton;
    }
}
