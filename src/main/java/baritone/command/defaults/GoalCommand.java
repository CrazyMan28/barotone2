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

import baritone.ai.GoalTracker;
import baritone.ai.MissionMemory;
import baritone.ai.MissionQueue;
import baritone.ai.MistralAgent;
import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.datatypes.RelativeCoordinate;
import baritone.api.command.datatypes.RelativeGoal;
import baritone.api.command.exception.CommandException;
import baritone.api.command.helpers.TabCompleteHelper;
import baritone.api.pathing.goals.Goal;
import baritone.api.process.ICustomGoalProcess;
import baritone.api.utils.BetterBlockPos;
import baritone.cache.WorldData;
import net.minecraft.ChatFormatting;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

public class GoalCommand extends Command {

    public GoalCommand(IBaritone baritone) {
        super(baritone, "goal", "goals");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        ICustomGoalProcess goalProcess = baritone.getCustomGoalProcess();
        if (label.equals("goals") && !args.hasAny()) {
            boolean visible = GoalTracker.toggleVisible();
            logDirect(visible ? "Goal HUD shown." : "Goal HUD hidden.", ChatFormatting.GRAY);
            return;
        }
        if (args.hasAny()) {
            String raw = args.rawRest().trim();
            String low = raw.toLowerCase(Locale.ROOT);
            // Scope persistent memory to this world and wire AI runtime state before touching AI features.
            scopeMissionMemory();
            AiCommand.ensureAiRuntime(this);
            if (low.equals("recover")) {
                AiCommand.recoverInFlightMission(baritone, this);
                return;
            }
            if (low.equals("status")) {
                logDirect(GoalTracker.describe() + "\n" + MissionQueue.describe(), ChatFormatting.GRAY);
                return;
            }
            if (low.equals("queue")) {
                logDirect(MissionQueue.describe(), ChatFormatting.GRAY);
                return;
            }
            if (low.startsWith("queue ")) {
                String goal = raw.substring(6).trim();
                if (goal.isEmpty()) {
                    logDirect("Usage: goal queue <task>", ChatFormatting.RED);
                    return;
                }
                try {
                    MissionQueue.Mission mission = MissionQueue.enqueue(goal, true, "goal queue");
                    GoalTracker.showIdle();
                    GoalTracker.setStatus("Queued mission #" + mission.id + " (" + MissionQueue.snapshot().pending.size() + " pending)");
                    logDirect("[AI] queued mission #" + mission.id + ": " + mission.goal, ChatFormatting.AQUA);
                } catch (RuntimeException e) {
                    logDirect("Could not queue mission: " + e.getMessage(), ChatFormatting.RED);
                }
                return;
            }
            if (low.equals("pause")) {
                AiCommand.pauseMissionQueue(baritone, this);
                return;
            }
            if (low.equals("resume")) {
                AiCommand.resumeMissionQueue(baritone, this);
                return;
            }
            if (low.equals("clearqueue")) {
                int cleared = MissionQueue.clearPending();
                logDirect("Cleared " + cleared + " pending mission(s).", ChatFormatting.GRAY);
                return;
            }
            if (low.equals("memory") || low.equals("mem")) {
                scopeMissionMemory();
                logDirect(MissionMemory.describe(), ChatFormatting.GRAY);
                return;
            }
            if (low.startsWith("memory ") || low.startsWith("mem ")) {
                handleMemoryCommand(raw);
                return;
            }
            if (low.equals("remember") || low.startsWith("remember ")) {
                handleRememberCommand(raw);
                return;
            }
            if (low.equals("history")) {
                logDirect(GoalTracker.describeHistory(), ChatFormatting.GRAY);
                return;
            }
            if (low.equals("hide") || low.equals("clearhud")) {
                GoalTracker.hide();
                logDirect("Goal HUD hidden.", ChatFormatting.GRAY);
                return;
            }
            if (low.equals("retry") || low.startsWith("retry ")) {
                String retryGoal = retryGoal(raw);
                if (retryGoal.isEmpty()) {
                    GoalTracker.showIdle();
                    logDirect(GoalTracker.history().isEmpty()
                            ? "No previous AI goal to retry."
                            : "No matching AI goal in history. Run `goal history` to see available numbers.",
                            ChatFormatting.YELLOW);
                    return;
                }
                AiCommand.startAgent(baritone, retryGoal, true, this, "goal retry");
                return;
            }
            if (low.equals("stop") || low.equals("cancel")) {
                MistralAgent active = MistralAgent.ACTIVE.get();
                if (active == null) {
                    logDirect("No AI agent is currently running.", ChatFormatting.YELLOW);
                } else {
                    active.cancel();
                    GoalTracker.fail("Cancellation requested");
                    logDirect("Cancellation requested. The agent will stop between rounds.", ChatFormatting.YELLOW);
                }
                return;
            }
            if (low.startsWith("plan ")) {
                String goal = raw.substring(5).trim();
                if (goal.isEmpty()) {
                    logDirect("Usage: goal plan <task>", ChatFormatting.RED);
                    return;
                }
                AiCommand.startAgent(baritone, goal, true, this, "goal");
                return;
            }
            if (!looksLikeCoordinateGoal(args)) {
                if (seemsLikeCoordinateAttempt(args)) {
                    logDirect("Malformed coordinates. Usage: goal <x> <y> <z>", ChatFormatting.RED);
                    return;
                }
                AiCommand.startAgent(baritone, raw, true, this, "goal");
                return;
            }
        }
        if (args.hasAny() && Arrays.asList("reset", "clear", "none").contains(args.peekString())) {
            args.requireMax(1);
            if (goalProcess.getGoal() != null) {
                goalProcess.setGoal(null);
                logDirect("Cleared goal");
            } else {
                logDirect("There was no goal to clear");
            }
        } else {
            args.requireMax(3);
            BetterBlockPos origin = ctx.playerFeet();
            Goal goal = args.getDatatypePost(RelativeGoal.INSTANCE, origin);
            goalProcess.setGoal(goal);
            logDirect(String.format("Goal: %s", goal.toString()));
        }
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        TabCompleteHelper helper = new TabCompleteHelper();
        if (args.hasExactlyOne()) {
            helper.append("reset", "clear", "none", "status", "history", "stop", "plan", "retry",
                    "queue", "pause", "resume", "recover", "clearqueue", "memory", "remember", "hide", "~");
        } else {
            if (args.hasAtMost(3)) {
                while (args.has(2)) {
                    if (args.peekDatatypeOrNull(RelativeCoordinate.INSTANCE) == null) {
                        break;
                    }
                    args.get();
                    if (!args.has(2)) {
                        helper.append("~");
                    }
                }
            }
        }
        return helper.filterPrefix(args.getString()).stream();
    }

    @Override
    public String getShortDesc() {
        return "Set a coordinate goal, or open the live AI goal HUD";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "The goal command allows you to set or clear Baritone's goal.",
                "",
                "Wherever a coordinate is expected, you can use ~ just like in regular Minecraft commands. Or, you can just use regular numbers.",
                "",
                "Usage:",
                "> goal - Set the goal to your current position",
                "> goal <reset/clear/none> - Erase the goal",
                "> goal <y> - Set the goal to a Y level",
                "> goal <x> <z> - Set the goal to an X,Z position",
                "> goal <x> <y> <z> - Set the goal to an X,Y,Z position",
                "",
                "AI goal mode:",
                "> goals - toggle the live Goal HUD",
                "> goal get 10 jungle logs without exploring - plan and execute with the side HUD",
                "> goal retry - rerun the last AI goal in plan mode",
                "> goal retry <number> - rerun a numbered entry from goal history",
                "> goal queue - show queued AI missions",
                "> goal queue <task> - add a mission behind the current one",
                "> goal pause / goal resume - pause or resume the mission queue",
                "> goal recover - resume a mission interrupted by closing the game",
                "> goal clearqueue - remove pending missions without stopping the active one",
                "> goal remember base - save your current position as a persistent AI memory",
                "> goal remember <key> - save your current position under a custom key",
                "> goal memory - show persistent AI memories and recent checkpoints",
                "> goal memory find <text> - search persistent memories and checkpoints",
                "> goal memory forget <key> - remove a saved memory",
                "> goal memory checkpoints - show recent automatic mission checkpoints",
                "> goal history - show recent AI goals kept for retry",
                "> goal status - show the current AI plan/status",
                "> goal stop - cancel the running AI agent",
                "> goal hide - hide the side HUD"
        );
    }

    private static boolean looksLikeCoordinateGoal(IArgConsumer args) {
        if (!args.hasAtMost(3)) {
            return false;
        }
        for (int i = 0; args.has(i + 1); i++) {
            String s;
            try {
                s = args.peekString(i);
            } catch (CommandException e) {
                return false;
            }
            if (!s.matches("~?-?\\d*(\\.\\d+)?") && !s.equals("~")) {
                String low = s.toLowerCase(Locale.ROOT);
                return low.equals("reset") || low.equals("clear") || low.equals("none");
            }
        }
        return true;
    }

    private static boolean seemsLikeCoordinateAttempt(IArgConsumer args) {
        if (!args.hasAtMost(3)) {
            return false;
        }
        for (int i = 0; args.has(i + 1); i++) {
            String s;
            try {
                s = args.peekString(i);
            } catch (CommandException e) {
                return false;
            }
            String low = s.toLowerCase(Locale.ROOT);
            if (low.equals("reset") || low.equals("clear") || low.equals("none")) {
                return true;
            }
            // If any token contains a digit or a tilde, it's likely an attempt at coordinates
            if (s.matches(".*[0-9~].*")) {
                return true;
            }
        }
        return false;
    }

    private static String retryGoal(String raw) {
        String[] parts = raw.trim().split("\\s+", 2);
        if (parts.length == 1) {
            return GoalTracker.lastGoal();
        }
        try {
            return GoalTracker.historyGoal(Integer.parseInt(parts[1]));
        } catch (NumberFormatException e) {
            return "";
        }
    }

    private void handleRememberCommand(String raw) {
        scopeMissionMemory();
        String[] parts = raw.trim().split("\\s+", 3);
        if (parts.length < 2 || parts[1].isBlank()) {
            logDirect("Usage: goal remember <key> [value]", ChatFormatting.RED);
            return;
        }
        String key = parts[1];
        try {
            if (parts.length >= 3 && !parts[2].isBlank()) {
                MissionMemory.MemoryRecord memory = MissionMemory.remember(key, parts[2], "general", "player", null);
                logDirect("Saved memory " + memory.key + ".", ChatFormatting.AQUA);
            } else {
                MissionMemory.MemoryRecord memory = rememberCurrentLocation(key);
                logDirect("Saved location memory " + memory.key + ".", ChatFormatting.AQUA);
            }
        } catch (RuntimeException e) {
            logDirect("Could not save memory: " + e.getMessage(), ChatFormatting.RED);
        }
    }

    private void handleMemoryCommand(String raw) {
        scopeMissionMemory();
        String[] first = raw.trim().split("\\s+", 2);
        String rest = first.length >= 2 ? first[1].trim() : "";
        if (rest.isEmpty() || rest.equalsIgnoreCase("list")) {
            logDirect(MissionMemory.describe(), ChatFormatting.GRAY);
            return;
        }
        String low = rest.toLowerCase(Locale.ROOT);
        try {
            if (low.equals("checkpoints") || low.equals("checkpoint")) {
                logDirect(MissionMemory.describeCheckpoints(), ChatFormatting.GRAY);
                return;
            }
            if (low.equals("clear checkpoints") || low.equals("clear checkpoint")) {
                int cleared = MissionMemory.clearCheckpoints();
                logDirect("Cleared " + cleared + " checkpoint(s).", ChatFormatting.GRAY);
                return;
            }
            if (low.equals("clear all")) {
                MissionMemory.clearAll();
                logDirect("Cleared all mission memories and checkpoints.", ChatFormatting.GRAY);
                return;
            }
            if (low.startsWith("find ") || low.startsWith("search ")) {
                String query = rest.substring(rest.indexOf(' ') + 1).trim();
                logDirect(MissionMemory.recall(query, "", true), ChatFormatting.GRAY);
                return;
            }
            if (low.startsWith("forget ")) {
                String key = rest.substring("forget ".length()).trim();
                boolean removed = MissionMemory.forget(key);
                logDirect(removed ? "Forgot memory " + key + "." : "No memory found for " + key + ".",
                        removed ? ChatFormatting.GRAY : ChatFormatting.YELLOW);
                return;
            }
            if (low.startsWith("remember ")) {
                String[] parts = rest.split("\\s+", 3);
                if (parts.length < 3 || parts[1].isBlank() || parts[2].isBlank()) {
                    logDirect("Usage: goal memory remember <key> <value>", ChatFormatting.RED);
                    return;
                }
                MissionMemory.MemoryRecord memory = MissionMemory.remember(parts[1], parts[2], "general", "player", null);
                logDirect("Saved memory " + memory.key + ".", ChatFormatting.AQUA);
                return;
            }
            logDirect(MissionMemory.recall(rest, "", true), ChatFormatting.GRAY);
        } catch (RuntimeException e) {
            logDirect("Mission memory error: " + e.getMessage(), ChatFormatting.RED);
        }
    }

    private MissionMemory.MemoryRecord rememberCurrentLocation(String key) {
        BetterBlockPos pos = ctx.playerFeet();
        String dimension = "";
        try {
            dimension = ctx.player().level().dimension().identifier().toString();
        } catch (RuntimeException ignored) {
        }
        String note = "Player marked " + key + " at " + pos.x + "," + pos.y + "," + pos.z;
        return MissionMemory.rememberLocation(key, note, "location", dimension, pos.x, pos.y, pos.z, "player");
    }

    private void scopeMissionMemory() {
        try {
            if (!(ctx.worldData() instanceof WorldData)) {
                return;
            }
            Path dimensionDir = ((WorldData) ctx.worldData()).directory;
            Path namespaceDir = dimensionDir == null ? null : dimensionDir.getParent();
            Path worldDir = namespaceDir == null ? dimensionDir : namespaceDir.getParent();
            if (worldDir != null || dimensionDir != null) {
                MissionMemory.useStorageFile((worldDir == null ? dimensionDir : worldDir).resolve("mission-memory.json"));
            }
        } catch (RuntimeException ignored) {
        }
    }
}
