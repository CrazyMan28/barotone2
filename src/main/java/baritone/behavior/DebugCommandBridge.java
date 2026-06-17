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
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Locale;

/**
 * Headless debug control bridge — lets an external agent drive the game with ZERO desktop interaction
 * (no window focus, no synthetic mouse/keyboard). When {@code aiDebugBridge} is enabled (default ON in
 * this debug fork), lines written to {@code <gameDir>/baritone/debug_commands.txt} are executed a few
 * times a second, the file is truncated, and an ack line per command is appended to {@code debug_log.txt}.
 *
 * <p>Line grammar:
 * <ul>
 *   <li>{@code /<vanilla>} — run as a VANILLA Minecraft command on the integrated (singleplayer) server
 *       at the SERVER console source (operator level), so it works even when the world has cheats off,
 *       anchored at the player so {@code ~} and {@code @p}/{@code @s} resolve correctly. e.g.
 *       {@code /time set midnight}, {@code /difficulty normal}, {@code /execute at @p run summon zombie ~2 ~ ~}.</li>
 *   <li>{@code screenshot} — snap the MC window to {@code baritone/screenshots} (proof check-in).</li>
 *   <li>{@code live on [fps] [scale]} / {@code live off} — drive the live-video capture.</li>
 *   <li>{@code //...} or blank — comment / ignored.</li>
 *   <li>anything else — run as a Baritone command (no prefix), e.g. {@code ai get wood}, {@code goto 0 70 0},
 *       {@code set allowBreak true}.</li>
 * </ul>
 *
 * <p>Local filesystem only — no network surface. This is the same idea as {@link RemoteBridgeBehavior}
 * but (a) defaults on for debugging, (b) adds first-class vanilla-command execution, and (c) writes an
 * ack log so the driving agent can confirm each line ran without reading the game window.
 */
public final class DebugCommandBridge extends Behavior implements baritone.api.utils.Helper {

    private static final int POLL_INTERVAL_TICKS = 10; // ~twice a second — snappy for interactive driving
    private final Path commandFile;
    private final Path logFile;
    private int tickCounter;

    public DebugCommandBridge(Baritone baritone) {
        super(baritone);
        this.commandFile = baritone.getDirectory().resolve("debug_commands.txt");
        this.logFile = baritone.getDirectory().resolve("debug_log.txt");
    }

    @Override
    public void onTick(TickEvent event) {
        if (event.getType() != TickEvent.Type.IN || !Baritone.settings().aiDebugBridge.value) {
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
            for (String raw : lines) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("//") || line.startsWith("#comment")) {
                    continue;
                }
                logDirect("[debug] " + line, ChatFormatting.AQUA);
                try {
                    dispatch(line);
                    ack(line, "ok");
                } catch (RuntimeException e) {
                    ack(line, "ERROR: " + e.getMessage());
                    logDirect("[debug] error on '" + line + "': " + e.getMessage(), ChatFormatting.RED);
                }
            }
        } catch (IOException | RuntimeException e) {
            logDirect("[debug] bridge error: " + e.getMessage(), ChatFormatting.RED);
        }
    }

    private void dispatch(String line) {
        if (line.startsWith("/")) {
            runVanilla(line.substring(1).trim());
            return;
        }
        String lower = line.toLowerCase(Locale.ROOT);
        if (line.equalsIgnoreCase("screenshot")) {
            ScreenshotHelper.capture("debug");
            return;
        }
        if (lower.startsWith("live ")) {
            String[] parts = line.split("\\s+");
            if (parts.length >= 2 && parts[1].equalsIgnoreCase("on")) {
                int fps = parts.length >= 3 ? parseIntOr(parts[2], 8) : 8;
                int scale = parts.length >= 4 ? parseIntOr(parts[3], 2) : 2;
                LiveVideoCapture.start(fps, scale);
            } else if (parts.length >= 2 && parts[1].equalsIgnoreCase("off")) {
                LiveVideoCapture.stop("debug");
            }
            return;
        }
        // Baritone command (strip an optional leading '#' for convenience).
        String cmd = line.startsWith("#") ? line.substring(1) : line;
        baritone.getCommandManager().execute(cmd);
    }

    /**
     * Run a vanilla command on the integrated server at the console source (operator level), anchored at
     * the player so {@code ~}/{@code @p}/{@code @s} resolve. Console perms bypass the world's cheat flag,
     * so {@code /time}, {@code /summon}, {@code /difficulty}, {@code /effect} all work regardless of how
     * the save was created. Scheduled onto the server thread (commands must not run on the client thread).
     */
    private void runVanilla(String command) {
        if (command.isEmpty()) {
            return;
        }
        IntegratedServer server = mc.getSingleplayerServer();
        if (server == null) {
            throw new IllegalStateException("no integrated server (not in singleplayer)");
        }
        server.execute(() -> {
            try {
                CommandSourceStack src = server.createCommandSourceStack(); // console == operator level
                List<ServerPlayer> players = server.getPlayerList().getPlayers();
                if (!players.isEmpty()) {
                    ServerPlayer p = players.get(0);
                    src = src.withEntity(p).withPosition(p.position()); // anchor ~ / @s / @p at the player
                }
                server.getCommands().performPrefixedCommand(src, command);
            } catch (RuntimeException e) {
                logDirect("[debug] vanilla '" + command + "' failed: " + e.getMessage(), ChatFormatting.RED);
            }
        });
    }

    private void ack(String line, String result) {
        try {
            long t = baritone.getPlayerContext() != null && mc.level != null ? mc.level.getGameTime() : 0L;
            String entry = "t" + t + "  " + line + "  -> " + result + System.lineSeparator();
            Files.write(logFile, entry.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
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
