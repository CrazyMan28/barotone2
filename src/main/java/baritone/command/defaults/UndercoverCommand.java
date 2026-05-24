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

package baritone.command.defaults;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.Settings;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;
import baritone.api.command.helpers.TabCompleteHelper;
import baritone.api.utils.SettingsUtil;
import baritone.utils.UndercoverSettingsScreen;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * "Undercover" mode: visible block aiming, smoother look blending, and slower clicks/breaks.
 * It must not change route choice or make Baritone wander. Toggles on/off and restores your previous
 * values when turned off.
 */
public class UndercoverCommand extends Command {

    private static boolean enabled;
    private static Snapshot snap;

    public UndercoverCommand(IBaritone baritone) {
        super(baritone, "undercover");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        args.requireMax(1);
        Settings s = BaritoneAPI.getSettings();
        if (!args.hasAny()) {
            logDirect("Undercover is " + (enabled ? "ON" : "OFF") + ". Use: undercover on | undercover off | undercover setting",
                    ChatFormatting.GRAY);
            return;
        }
        String mode = args.getString().toLowerCase(Locale.ROOT);
        if (mode.equals("on")) {
            if (enabled) {
                logDirect("Undercover already on.", ChatFormatting.YELLOW);
                return;
            }
            snap = Snapshot.capture(s);
            applyUndercoverProfile(s);
            enabled = true;
            SettingsUtil.save(s);
            logDirect("Undercover ON: visible block aiming, capped block-interaction turns, slower clicks/breaks; normal pathing kept.",
                    ChatFormatting.GREEN);
        } else if (mode.equals("off")) {
            if (!enabled || snap == null) {
                logDirect("Undercover was not on.", ChatFormatting.YELLOW);
                return;
            }
            snap.restore(s);
            snap = null;
            enabled = false;
            SettingsUtil.save(s);
            logDirect("Undercover OFF: restored your previous movement settings.", ChatFormatting.GREEN);
        } else if (mode.equals("setting") || mode.equals("settings") || mode.equals("gui")) {
            Minecraft mc = Minecraft.getInstance();
            mc.execute(() -> mc.setScreen(new UndercoverSettingsScreen(null)));
        } else {
            logDirect("Usage: undercover on | undercover off | undercover setting", ChatFormatting.RED);
        }
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void applyUndercoverProfile(Settings s) {
        // Keep pathing mechanics normal. Undercover is about visible looking/click pacing,
        // not changing Baritone's movement planner or forcing random-looking routes.
        double aggression = Math.max(0D, Math.min(1D, s.undercoverMovementAggression.value));
        double calm = 1D - aggression;
        s.allowSprint.value = true;
        s.smoothLook.value = true;
        s.smoothLookTicks.value = Math.max(1, Math.max(s.undercoverSmoothLookTicks.value, 8 + (int) Math.round(calm * 16D)));
        s.blockFreeLook.value = false;
        s.freeLook.value = false;
        s.strictVisibleBlockInteractions.value = true;
        s.randomLooking113.value = 0D;
        s.randomLooking.value = 0D;
        s.rightClickSpeed.value = Math.max(1, Math.max(s.undercoverRightClickDelay.value, 4 + (int) Math.round(calm * 16D)));
        s.blockBreakSpeed.value = Math.max(1, Math.max(s.undercoverBlockBreakDelay.value, 6 + (int) Math.round(calm * 18D)));
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        if (!args.hasAny()) {
            return Stream.of("on", "off", "setting");
        }
        if (args.hasExactlyOne()) {
            return new TabCompleteHelper().append("on", "off", "setting", "settings", "gui").filterPrefix(args.getString()).stream();
        }
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Visible, smoother block interactions (undercover on/off)";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "Undercover mode makes Baritone visibly look at blocks before interacting,",
                "keeps sprint available, enables smooth look blending, removes random look jitter,",
                "slows break / right-click pacing, and leaves normal path following alone.",
                "",
                "Usage:",
                "> undercover on   - enable and remember your current settings",
                "> undercover off  - restore what you had before undercover on",
                "> undercover setting - open the tuning GUI",
                "",
                "Tip: combine with the AI agent for long crafting / mining sessions."
        );
    }

    private static final class Snapshot {
        boolean allowSprint;
        boolean sprintAscends;
        boolean sprintInWater;
        boolean allowParkour;
        boolean allowParkourPlace;
        boolean overshootTraverse;
        boolean smoothLook;
        int smoothLookTicks;
        boolean blockFreeLook;
        boolean freeLook;
        boolean strictVisibleBlockInteractions;
        boolean walkWhileBreaking;
        double randomLooking113;
        double randomLooking;
        int rightClickSpeed;
        int blockBreakSpeed;

        static Snapshot capture(Settings s) {
            Snapshot o = new Snapshot();
            o.allowSprint = s.allowSprint.value;
            o.sprintAscends = s.sprintAscends.value;
            o.sprintInWater = s.sprintInWater.value;
            o.allowParkour = s.allowParkour.value;
            o.allowParkourPlace = s.allowParkourPlace.value;
            o.overshootTraverse = s.overshootTraverse.value;
            o.smoothLook = s.smoothLook.value;
            o.smoothLookTicks = s.smoothLookTicks.value;
            o.blockFreeLook = s.blockFreeLook.value;
            o.freeLook = s.freeLook.value;
            o.strictVisibleBlockInteractions = s.strictVisibleBlockInteractions.value;
            o.walkWhileBreaking = s.walkWhileBreaking.value;
            o.randomLooking113 = s.randomLooking113.value;
            o.randomLooking = s.randomLooking.value;
            o.rightClickSpeed = s.rightClickSpeed.value;
            o.blockBreakSpeed = s.blockBreakSpeed.value;
            return o;
        }

        void restore(Settings s) {
            s.allowSprint.value = allowSprint;
            s.sprintAscends.value = sprintAscends;
            s.sprintInWater.value = sprintInWater;
            s.allowParkour.value = allowParkour;
            s.allowParkourPlace.value = allowParkourPlace;
            s.overshootTraverse.value = overshootTraverse;
            s.smoothLook.value = smoothLook;
            s.smoothLookTicks.value = smoothLookTicks;
            s.blockFreeLook.value = blockFreeLook;
            s.freeLook.value = freeLook;
            s.strictVisibleBlockInteractions.value = strictVisibleBlockInteractions;
            s.walkWhileBreaking.value = walkWhileBreaking;
            s.randomLooking113.value = randomLooking113;
            s.randomLooking.value = randomLooking;
            s.rightClickSpeed.value = rightClickSpeed;
            s.blockBreakSpeed.value = blockBreakSpeed;
        }
    }
}
