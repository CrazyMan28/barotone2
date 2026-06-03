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
import baritone.ai.ReflexLog;
import net.minecraft.ChatFormatting;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Toggle and inspect the survival reflexes (auto-eat, creeper flee, fight back, anti-lava, anti-drown).
 */
public class ReflexCommand extends Command {

    public ReflexCommand(IBaritone baritone) {
        super(baritone, "reflex", "reflexes");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        args.requireMax(1);
        Settings s = BaritoneAPI.getSettings();
        if (!args.hasAny() || args.peekString().toLowerCase(Locale.ROOT).equals("status")) {
            logDirect("Reflexes are " + (s.reflexesEnabled.value ? "ON" : "OFF")
                            + " (eat=" + s.reflexAutoEat.value
                            + " fleeCreepers=" + s.reflexFleeCreepers.value
                            + " fightBack=" + s.reflexFightBack.value
                            + " antiLava=" + s.reflexAntiLava.value
                            + " antiDrown=" + s.reflexAntiDrown.value + ")",
                    s.reflexesEnabled.value ? ChatFormatting.GREEN : ChatFormatting.YELLOW);
            List<String> recent = ReflexLog.recent(5);
            if (recent.isEmpty()) {
                logDirect("No recent reflex activity.", ChatFormatting.GRAY);
            } else {
                for (String line : recent) {
                    logDirect(line, ChatFormatting.GRAY);
                }
            }
            return;
        }
        String mode = args.getString().toLowerCase(Locale.ROOT);
        if (mode.equals("on")) {
            s.reflexesEnabled.value = true;
            SettingsUtil.save(s);
            logDirect("Reflexes ON: auto-eat, creeper flee, fight back, anti-lava, anti-drown.", ChatFormatting.GREEN);
        } else if (mode.equals("off")) {
            s.reflexesEnabled.value = false;
            SettingsUtil.save(s);
            logDirect("Reflexes OFF.", ChatFormatting.YELLOW);
        } else {
            logDirect("Usage: reflex on | reflex off | reflex status", ChatFormatting.RED);
        }
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        if (args.hasExactlyOne()) {
            return new TabCompleteHelper().append("on", "off", "status").filterPrefix(args.getString()).stream();
        }
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Survival reflexes on/off/status";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "Survival reflexes keep the bot alive without waiting on the AI:",
                "auto-eat from the hotbar, flee creepers, fight back when hurt,",
                "float out of lava, and surface before drowning. Interrupted",
                "missions resume automatically once the danger has passed.",
                "",
                "Usage:",
                "> reflex          - show status and recent reflex activity",
                "> reflex on/off   - master switch (individual reflex* settings exist too)"
        );
    }
}
