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
                baritone.getCommandManager().execute(command);
            }
        } catch (IOException | RuntimeException e) {
            logDirect("[remote] bridge error: " + e.getMessage(), ChatFormatting.RED);
        }
    }
}
