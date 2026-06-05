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

import baritone.ai.AgentTelemetry;
import baritone.ai.GoalTracker;
import baritone.ai.MissionMemory;
import baritone.ai.MissionQueue;
import baritone.ai.MistralAgent;
import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.Settings;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;
import baritone.api.utils.Helper;
import net.minecraft.ChatFormatting;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Natural-language goal command. Spawns a Mistral-backed agent on a background
 * thread that will plan and execute Baritone primitives until the goal is met, you run {@code ai stop},
 * or the model calls {@code done}. By default there is no round limit ({@code mistralMaxIterations} 0).
 *
 * <p>Run {@code #ai stop} to cancel a running agent.</p>
 */
public class AiCommand extends Command {

    private static final Helper QUEUE_LOGGER = new QueueLog();
    private static volatile boolean aiRuntimeReady = false;

    public AiCommand(IBaritone baritone) {
        super(baritone, "ai");
    }

    /**
     * One-time wiring of AI runtime state: persists the recent-goal history through {@link MissionMemory}
     * and surfaces a hint if a mission was interrupted (e.g. by closing the game) and can be recovered.
     */
    public static void ensureAiRuntime(Helper logger) {
        if (aiRuntimeReady) {
            return;
        }
        synchronized (AiCommand.class) {
            if (aiRuntimeReady) {
                return;
            }
            GoalTracker.setHistoryStore(new GoalTracker.HistoryStore() {
                @Override
                public void save(java.util.List<String> history) {
                    MissionMemory.saveGoalHistory(history);
                }

                @Override
                public java.util.List<String> load() {
                    return MissionMemory.loadGoalHistory();
                }
            });
            aiRuntimeReady = true;
            try {
                MissionMemory.InFlightMission inflight = MissionMemory.getInFlightMission();
                if (inflight != null && logger != null) {
                    logger.logDirect("[AI] Interrupted mission found. Run `"
                            + BaritoneAPI.getSettings().prefix.value + "ai recover` to resume: " + inflight.goal,
                            ChatFormatting.YELLOW);
                }
            } catch (RuntimeException ignored) {
            }
        }
    }

    public static String recoverInFlightMission(IBaritone baritone, Helper logger) {
        MissionMemory.InFlightMission inflight = MissionMemory.getInFlightMission();
        if (inflight == null) {
            logger.logDirect("No interrupted mission to recover.", ChatFormatting.YELLOW);
            return "No interrupted mission to recover.";
        }
        logger.logDirect("Recovering interrupted mission: " + inflight.goal, ChatFormatting.AQUA);
        startAgent(baritone, inflight.goal, inflight.planMode, logger, "ai recover");
        return inflight.goal;
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        args.requireMin(1);
        ensureAiRuntime(this);
        String first = args.peekString().toLowerCase(Locale.ROOT);
        if (first.equals("recover")) {
            args.getString();
            args.requireMax(0);
            recoverInFlightMission(baritone, this);
            return;
        }
        if (first.equals("stop") || first.equals("cancel")) {
            args.getString();
            MistralAgent active = MistralAgent.ACTIVE.get();
            if (active == null) {
                logDirect("No AI agent is currently running.", ChatFormatting.YELLOW);
                return;
            }
            active.cancel();
            GoalTracker.fail("Cancellation requested");
            logDirect("Cancellation requested. The agent will stop between rounds.", ChatFormatting.YELLOW);
            return;
        }
        if (first.equals("pause")) {
            args.getString();
            args.requireMax(0);
            pauseMissionQueue(baritone, this);
            return;
        }
        if (first.equals("resume")) {
            args.getString();
            args.requireMax(0);
            resumeMissionQueue(baritone, this);
            return;
        }
        if (first.equals("status")) {
            args.getString();
            args.requireMax(0);
            logDirect(GoalTracker.describe() + "\n" + MissionQueue.describe(), ChatFormatting.GRAY);
            return;
        }
        if (first.equals("session")) {
            args.getString();
            String sessionId = args.rawRest().trim();
            AgentTelemetry.setSession(sessionId);
            logDirect(sessionId.isEmpty()
                    ? "Telemetry session cleared."
                    : "Telemetry session set to " + sessionId, ChatFormatting.AQUA);
            return;
        }
        if (first.equals("clearqueue")) {
            args.getString();
            args.requireMax(0);
            int cleared = MissionQueue.clearPending();
            logDirect("Cleared " + cleared + " pending mission(s).", ChatFormatting.GRAY);
            return;
        }
        if (first.equals("queue")) {
            args.getString();
            String queuedGoal = args.rawRest().trim();
            if (queuedGoal.isEmpty()) {
                logDirect(MissionQueue.describe(), ChatFormatting.GRAY);
                return;
            }
            enqueueMission(queuedGoal, false, "ai queue", this);
            return;
        }

        String goal = args.rawRest().trim();
        if (goal.isEmpty()) {
            logDirect("Usage: ai <natural language goal>", ChatFormatting.RED);
            return;
        }

        startAgent(baritone, goal, false, this, "ai");
    }

    public static void startAgent(IBaritone baritone, String goal, boolean planMode, Helper logger, String label) {
        ensureAiRuntime(logger);
        String cleanGoal = goal == null ? "" : goal.trim();
        if (cleanGoal.isEmpty()) {
            logger.logDirect("Usage: " + label + " <natural language goal>", ChatFormatting.RED);
            return;
        }

        if (MistralAgent.ACTIVE.get() != null || MissionQueue.isPaused()) {
            enqueueMission(cleanGoal, planMode, label, logger);
            return;
        }

        startMission(baritone, MissionQueue.create(cleanGoal, planMode, label), false, logger);
    }

    public static void tryStartNextMission(IBaritone baritone, Helper logger) {
        if (baritone == null || logger == null || MistralAgent.ACTIVE.get() != null || MissionQueue.isPaused()) {
            return;
        }
        if (!MissionQueue.hasPending()) {
            return;
        }
        MissionQueue.Mission mission = MissionQueue.pollNext();
        if (mission != null) {
            startMission(baritone, mission, true, logger);
        }
    }

    public static void pauseMissionQueue(IBaritone baritone, Helper logger) {
        MissionQueue.Mission pausedActive = MissionQueue.pauseAndRequeueActive();
        MistralAgent active = MistralAgent.ACTIVE.get();
        if (pausedActive != null && active != null) {
            active.cancel();
            GoalTracker.setStatus("Paused; mission #" + pausedActive.id + " will resume later");
            logger.logDirect("Mission queue paused. Current mission #" + pausedActive.id
                    + " will be requeued when it stops.", ChatFormatting.YELLOW);
            return;
        }
        GoalTracker.showIdle();
        GoalTracker.setStatus("Mission queue paused");
        logger.logDirect("Mission queue paused.", ChatFormatting.YELLOW);
    }

    public static void resumeMissionQueue(IBaritone baritone, Helper logger) {
        boolean changed = MissionQueue.resume();
        GoalTracker.showIdle();
        GoalTracker.setStatus("Mission queue resumed");
        logger.logDirect(changed ? "Mission queue resumed." : "Mission queue was already running.",
                ChatFormatting.AQUA);
        tryStartNextMission(baritone, logger);
    }

    public static String retryLastMission(IBaritone baritone, Helper logger) {
        MissionQueue.Mission retry = MissionQueue.retryLast();
        if (retry == null) {
            return "No finished mission is available to retry.";
        }
        String message = "Queued retry mission #" + retry.id + ": " + retry.goal;
        logger.logDirect(message, ChatFormatting.AQUA);
        tryStartNextMission(baritone, logger);
        return message;
    }

    private static void enqueueMission(String goal, boolean planMode, String label, Helper logger) {
        try {
            MissionQueue.Mission mission = MissionQueue.enqueue(goal, planMode, label);
            GoalTracker.showIdle();
            GoalTracker.setStatus("Queued mission #" + mission.id + " (" + MissionQueue.snapshot().pending.size() + " pending)");
            logger.logDirect("[AI] queued mission #" + mission.id + ": " + mission.goal, ChatFormatting.AQUA);
        } catch (RuntimeException e) {
            logger.logDirect("Could not queue mission: " + e.getMessage(), ChatFormatting.RED);
        }
    }

    private static void startMission(IBaritone baritone, MissionQueue.Mission mission, boolean activeAlreadyMarked, Helper logger) {
        GoalTracker.start(mission.goal, mission.planMode);
        GoalTracker.setStatus("Checking provider");

        Settings settings = BaritoneAPI.getSettings();
        String provider = settings.aiProvider.value == null ? "mistral" : settings.aiProvider.value.trim().toLowerCase(Locale.ROOT);
        if ("ollama".equals(provider)) {
            String model = settings.ollamaModel.value;
            if (model == null || model.trim().isEmpty()) {
                GoalTracker.fail("Ollama model is not set");
                logger.logDirect("Ollama model is not set. Run: "
                                + settings.prefix.value + "ollama list, then " + settings.prefix.value + "ollama use <number-or-name>",
                        ChatFormatting.RED);
                if (activeAlreadyMarked) {
                    MissionQueue.finishActive(mission.id, "Ollama model is not set");
                }
                return;
            }
        } else {
            String key = settings.mistralApiKey.value;
            if (key == null || key.isEmpty()) {
                GoalTracker.fail("Mistral API key is not set");
                logger.logDirect("Mistral API key is not set. Run: "
                                + settings.prefix.value + "mistral key <YOUR_KEY>, or use "
                                + settings.prefix.value + "ollama use <model>",
                        ChatFormatting.RED);
                if (activeAlreadyMarked) {
                    MissionQueue.finishActive(mission.id, "Mistral API key is not set");
                }
                return;
            }
        }

        MistralAgent agent = new MistralAgent(baritone, mission.planMode);
        if (!MistralAgent.ACTIVE.compareAndSet(null, agent)) {
            GoalTracker.fail("Another AI agent just started");
            logger.logDirect("Another AI agent just started; aborting this one.", ChatFormatting.YELLOW);
            MissionQueue.enqueueFront(mission);
            return;
        }
        MissionQueue.Mission runningMission = activeAlreadyMarked ? mission : MissionQueue.markActive(mission);
        // Feature 3: remember the in-flight mission so it can be recovered if the game closes mid-run.
        MissionMemory.recordInFlightMission(runningMission.goal, runningMission.planMode);

        Thread t = new Thread(() -> {
            try {
                agent.runGoal(runningMission.goal);
            } catch (Throwable th) {
                logger.logDirect("AI agent crashed: " + th.getClass().getSimpleName()
                        + ": " + th.getMessage(), ChatFormatting.RED);
                GoalTracker.fail("Agent crashed: " + th.getClass().getSimpleName());
            } finally {
                MistralAgent.ACTIVE.compareAndSet(agent, null);
                GoalTracker.Snapshot snapshot = GoalTracker.snapshot();
                boolean requeuedForPause = MissionQueue.finishActive(runningMission.id, snapshot.status);
                if (requeuedForPause) {
                    MissionMemory.recordCheckpointQuietly(runningMission.goal, "mission_paused",
                            "Requeued for resume", "paused");
                } else if (!snapshot.status.startsWith("Done")) {
                    MissionMemory.recordCheckpointQuietly(runningMission.goal, "mission_finished",
                            snapshot.status, "stopped");
                }
                // Mission is no longer in-flight unless it was paused for later resume.
                if (!requeuedForPause) {
                    MissionMemory.clearInFlightMission();
                }
                tryStartNextMission(baritone, QUEUE_LOGGER);
            }
        }, "baritone-ai-agent");
        t.setDaemon(true);
        t.start();
        logger.logDirect("[AI] started mission #" + runningMission.id + " " + mission.label + ": "
                + runningMission.goal, ChatFormatting.AQUA);
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) {
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Run a natural-language goal via Mistral AI";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "Hands a natural-language goal to a Mistral-backed planning agent, which then drives",
                "Baritone primitives (goto, mine, follow, build, etc.) to accomplish it.",
                "",
                "Requires a Mistral API key. Set it with: mistral key <YOUR_KEY>",
                "",
                "Examples:",
                "> ai go mine 16 diamonds and come back",
                "> ai follow Player1 until they stop",
                "> ai build a small dirt hut next to me",
                "> ai stop                  - cancel the current agent (also stops unlimited waits)",
                "> ai queue <task>          - add a mission behind the current one",
                "> ai pause / ai resume     - pause or resume the mission queue",
                "> ai recover               - resume a mission interrupted by closing the game",
                "> ai status                - show current goal and queued missions",
                "",
                "Tip: `undercover on` before long sessions for gentler movement; set allowInventory true for crafting.",
                "The agent runs in the background; you'll see [AI:call] and [AI:result] log lines as",
                "it works (toggle with the mistralVerbose setting)."
        );
    }

    private static final class QueueLog implements Helper {
    }
}
