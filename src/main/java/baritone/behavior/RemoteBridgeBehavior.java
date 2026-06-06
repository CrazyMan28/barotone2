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

package baritone.behavior;

import baritone.Baritone;
import baritone.ai.LiveVideoCapture;
import baritone.ai.ScreenshotHelper;
import baritone.api.event.events.TickEvent;
import net.minecraft.ChatFormatting;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * File-based remote command bridge for unattended data-farming sessions: when
 * {@code aiRemoteBridge} is enabled, lines written to {@code <gameDir>/baritone/remote_commands.txt}
 * are executed as Baritone chat commands (without the prefix, e.g. {@code ai get wood}) and the
 * file is truncated. Local filesystem only - no network surface. Off by default.
 */
public final class RemoteBridgeBehavior extends Behavior implements baritone.api.utils.Helper {

    private static final int POLL_INTERVAL_TICKS = 20; // once a second
    private final Path commandFile;
    private int tickCounter;

    public RemoteBridgeBehavior(Baritone baritone) {
        super(baritone);
        this.commandFile = baritone.getDirectory().resolve("remote_commands.txt");
    }

    @Override
    public void onTick(TickEvent event) {
        if (event.getType() != TickEvent.Type.IN || !Baritone.settings().aiRemoteBridge.value) {
            return;
        }
        if (++tickCounter % POLL_INTERVAL_TICKS != 0) {
            return;
        }
        try {
            if (!Files.exists(commandFile) || Files.size(commandFile) == 0) {
                return;
            }
            List<String> lines = Files.readAllLines(commandFile, StandardCharsets.UTF_8);
            Files.write(commandFile, new byte[0]); // consume before executing so a crash can't loop
            for (String line : lines) {
                String command = line.trim();
                if (command.isEmpty() || command.startsWith("#")) {
                    continue;
                }
                logDirect("[remote] " + command, ChatFormatting.LIGHT_PURPLE);
                // "screenshot" is an on-demand check-in, not a Baritone command — snap the window and
                // move on (doesn't disturb whatever Baritone is doing).
                if (command.equalsIgnoreCase("screenshot")) {
                    ScreenshotHelper.capture("remote");
                    continue;
                }
                // "live on [fps] [scale]" / "live off" drive the live-video capture — not Baritone
                // commands; capturing the already-rendered framebuffer doesn't disturb the agent.
                if (command.toLowerCase(java.util.Locale.ROOT).startsWith("live ")) {
                    String[] parts = command.trim().split("\\s+");
                    if (parts.length >= 2 && parts[1].equalsIgnoreCase("on")) {
                        int fps = parts.length >= 3 ? parseIntOr(parts[2], 8) : 8;
                        int scale = parts.length >= 4 ? parseIntOr(parts[3], 2) : 2;
                        LiveVideoCapture.start(fps, scale);
                    } else if (parts.length >= 2 && parts[1].equalsIgnoreCase("off")) {
                        LiveVideoCapture.stop("remote");
                    }
                    continue;
                }
                baritone.getCommandManager().execute(command);
            }
        } catch (IOException | RuntimeException e) {
            logDirect("[remote] bridge error: " + e.getMessage(), ChatFormatting.RED);
        }
    }

    private static int parseIntOr(String s, int fallback) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
