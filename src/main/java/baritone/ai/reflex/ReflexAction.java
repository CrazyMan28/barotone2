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

import baritone.api.utils.input.Input;

/**
 * One concrete thing a behavior wants done this tick, as pure data. Behaviors emit lists of
 * these; {@code ReflexExecutor} (the only Minecraft-touching layer besides the adapter) turns
 * them into input overrides, look updates, hotbar switches, attacks, placements and pathing
 * goals. Keeping actions as data is what lets every behavior state machine be unit-tested by
 * asserting on the emitted list.
 *
 * <p>({@link Input} is a pure Baritone enum with no Minecraft imports, so the core may use it.)
 */
public final class ReflexAction {

    public enum Kind {
        /** Force a key down/up via InputOverrideHandler. */
        HOLD_INPUT,
        /** Release every forced key. */
        RELEASE_ALL_INPUTS,
        /** Smooth look via LookBehavior.updateTarget. */
        LOOK,
        /** Snap the player's rotation THIS tick (combat aim — smooth turns miss). */
        SNAP_LOOK,
        /** Select a hotbar slot (executor remembers/restores the previous one per episode). */
        SELECT_SLOT,
        /** gameMode.attack + swing on the entity with this id. */
        ATTACK,
        /** Place a block into this cell (hand-built BlockHitResult against a neighbor face). */
        PLACE_BLOCK,
        /**
         * Hold the real USE key down THIS tick so vanilla {@code handleKeybinds} actually starts and
         * keeps an item-use going (this is how eating works — the input-override right-click can't,
         * because Minecraft releases any item-use whose {@code keyUse} isn't physically down). Aim at
         * the sky alongside it so {@code startUseItem} eats instead of interacting with a block.
         */
        USE_ITEM,
        /** Right-click (use) the block in this cell with a hand-built hit — sleeping in a placed bed. */
        USE_BLOCK,
        /** Hand Baritone a pathing goal (FORCE_REVALIDATE). Without one, an active behavior pauses pathing. */
        SET_GOAL
    }

    public final Kind kind;
    public final Input input;
    public final boolean pressed;
    public final float yaw, pitch;
    public final int slot;
    public final int entityId;
    public final BlockPosSpec pos;
    public final GoalSpec goal;

    private ReflexAction(Kind kind, Input input, boolean pressed, float yaw, float pitch,
                         int slot, int entityId, BlockPosSpec pos, GoalSpec goal) {
        this.kind = kind;
        this.input = input;
        this.pressed = pressed;
        this.yaw = yaw;
        this.pitch = pitch;
        this.slot = slot;
        this.entityId = entityId;
        this.pos = pos;
        this.goal = goal;
    }

    public static ReflexAction hold(Input input, boolean pressed) {
        return new ReflexAction(Kind.HOLD_INPUT, input, pressed, 0, 0, -1, -1, null, null);
    }

    public static ReflexAction releaseAll() {
        return new ReflexAction(Kind.RELEASE_ALL_INPUTS, null, false, 0, 0, -1, -1, null, null);
    }

    public static ReflexAction look(float yaw, float pitch) {
        return new ReflexAction(Kind.LOOK, null, false, yaw, pitch, -1, -1, null, null);
    }

    public static ReflexAction snapLook(float yaw, float pitch) {
        return new ReflexAction(Kind.SNAP_LOOK, null, false, yaw, pitch, -1, -1, null, null);
    }

    public static ReflexAction selectSlot(int slot) {
        return new ReflexAction(Kind.SELECT_SLOT, null, false, 0, 0, slot, -1, null, null);
    }

    public static ReflexAction attack(int entityId) {
        return new ReflexAction(Kind.ATTACK, null, false, 0, 0, -1, entityId, null, null);
    }

    public static ReflexAction placeBlock(BlockPosSpec cell) {
        return new ReflexAction(Kind.PLACE_BLOCK, null, false, 0, 0, -1, -1, cell, null);
    }

    /** Hold the real use key this tick to drive vanilla item-use (eating). */
    public static ReflexAction useItem() {
        return new ReflexAction(Kind.USE_ITEM, null, true, 0, 0, -1, -1, null, null);
    }

    /** Right-click the block in this cell (hand-built hit) — e.g. sleep in a placed bed. */
    public static ReflexAction useBlock(BlockPosSpec cell) {
        return new ReflexAction(Kind.USE_BLOCK, null, false, 0, 0, -1, -1, cell, null);
    }

    public static ReflexAction setGoal(GoalSpec goal) {
        return new ReflexAction(Kind.SET_GOAL, null, false, 0, 0, -1, -1, null, goal);
    }

    @Override
    public String toString() {
        switch (kind) {
            case HOLD_INPUT:
                return "hold(" + input + "=" + pressed + ")";
            case LOOK:
                return "look(" + yaw + "," + pitch + ")";
            case SNAP_LOOK:
                return "snapLook(" + yaw + "," + pitch + ")";
            case SELECT_SLOT:
                return "slot(" + slot + ")";
            case ATTACK:
                return "attack(#" + entityId + ")";
            case PLACE_BLOCK:
                return "place" + pos;
            case USE_ITEM:
                return "useItem()";
            case SET_GOAL:
                return "goal(" + goal.kind + ")";
            default:
                return kind.toString();
        }
    }
}
