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

package baritone.ai;

import baritone.ai.planner.PlannerAgent;
import baritone.api.IBaritone;
import baritone.api.utils.Helper;
import baritone.command.defaults.AiCommand;
import net.minecraft.ChatFormatting;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Starts — and stands down — the cooperative LLM "survival agent" when the rule-based reflex is in
 * distress (its rule ladder is exhausted and the bot is still endangered). This is the WIRING; the
 * <em>policy</em> ({@link baritone.ai.reflex.SurvivalEscalation}) is pure and unit-tested separately.
 *
 * <p><b>Cooperation invariant (the core requirement):</b> the survival agent NEVER fights the reflex
 * for control. It is just another {@link MistralAgent} run with a survival-only prompt — it acts only
 * through tools (goto/dig/shelter/eat/craft) and {@code wait_until_idle} <em>yields</em> while
 * {@code ReflexProcess.ENGAGED} is true. The reflex is a priority-10 temporary Baritone process that
 * preempts the mission layer at tick granularity, so the reflex always wins control of the body; this
 * coordinator gives the survival agent NO mechanism to override it (no direct movement, no process
 * registration above the reflex). Do not add one.
 *
 * <p>When a normal mission/planner is running, it is requeued for resume (NOT lost) while the survival
 * agent runs; once the survival agent finishes (or the danger resolves), {@code tryStartNextMission}
 * pulls the original mission back. The whole thing happens off the game thread.
 */
public final class SurvivalAgentCoordinator implements Helper {

    /** The running survival agent, or null. Used both for "already running" and to cancel on resolve. */
    public static final AtomicReference<MistralAgent> ACTIVE = new AtomicReference<>();

    private SurvivalAgentCoordinator() {}

    /** A cooperative survival agent is running right now. */
    public static boolean isRunning() {
        return ACTIVE.get() != null;
    }

    /** A provider is configured (Mistral key OR an Ollama model) — the survival agent can actually run. */
    public static boolean providerConfigured() {
        try {
            baritone.api.Settings s = baritone.api.BaritoneAPI.getSettings();
            String provider = s.aiProvider.value == null ? "mistral"
                    : s.aiProvider.value.trim().toLowerCase(java.util.Locale.ROOT);
            if ("ollama".equals(provider)) {
                return s.ollamaModel.value != null && !s.ollamaModel.value.trim().isEmpty();
            }
            return s.mistralApiKey.value != null && !s.mistralApiKey.value.isEmpty();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Begin a survival escalation: pause + requeue any running mission/planner so it resumes later,
     * then launch the cooperative survival agent on a daemon thread. Idempotent — does nothing if a
     * survival agent is already running. Called off the game thread by the reflex adapter.
     *
     * @return true if a survival agent was started this call
     */
    public static boolean escalate(IBaritone baritone) {
        MistralAgent agent = MistralAgent.survival(baritone);
        if (!ACTIVE.compareAndSet(null, agent)) {
            return false; // already running
        }
        // Pause + requeue the running mission/planner so it resumes after — do NOT lose the goal.
        MistralAgent runningMission = MistralAgent.ACTIVE.get();
        PlannerAgent runningPlanner = PlannerAgent.ACTIVE.get();
        MissionQueue.Mission paused = MissionQueue.requeueActiveForResume();
        if (runningPlanner != null) {
            runningPlanner.cancel();
        }
        if (runningMission != null) {
            runningMission.cancel();
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("phase", "escalate_survival");
        if (paused != null) {
            data.put("paused_mission", paused.id);
        }
        AgentTelemetry.emit("reflex", data);

        Helper logger = new SurvivalLog();
        logger.logDirect("[AI] survival escalation: the reflex can't shake this danger — spinning up the "
                + "cooperative survival agent" + (paused != null ? " (mission #" + paused.id
                + " will resume after)" : "") + ".", ChatFormatting.GOLD);

        Thread t = new Thread(() -> {
            try {
                agent.runGoal("EMERGENCY SURVIVAL: get to safety and stop dying. Do NOT pursue the previous "
                        + "goal; cooperate with the survival reflex and give it strategic help (retreat to a "
                        + "safe/known location, dig in and wall off, build a shelter, eat, or gear up if "
                        + "materials are on hand). When you are safe, call done.");
            } catch (Throwable th) {
                logger.logDirect("Survival agent crashed: " + th.getClass().getSimpleName()
                        + ": " + th.getMessage(), ChatFormatting.RED);
            } finally {
                ACTIVE.compareAndSet(agent, null);
                Map<String, Object> done = new LinkedHashMap<>();
                done.put("phase", "survival_resolved");
                AgentTelemetry.emit("reflex", done);
                GoalTracker.setStatus("Survival handled; resuming");
                logger.logDirect("[AI] survival agent done — resuming the original mission.",
                        ChatFormatting.AQUA);
                // Resume the requeued original mission (the queue was never paused).
                AiCommand.tryStartNextMission(baritone, logger);
            }
        }, "baritone-ai-survival");
        t.setDaemon(true);
        t.start();
        return true;
    }

    /**
     * The danger has resolved on its own (distress cleared + no hostiles for the required window).
     * Cancel the survival agent if it's still grinding so the original mission can resume promptly;
     * the agent's own {@code done} is the other resolve path (handled in {@link #escalate}'s finally).
     */
    public static void resolve() {
        MistralAgent agent = ACTIVE.get();
        if (agent != null) {
            agent.cancel();
        }
    }

    static void resetForTests() {
        MistralAgent agent = ACTIVE.getAndSet(null);
        if (agent != null) {
            agent.cancel();
        }
    }

    private static final class SurvivalLog implements Helper {
    }
}
