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

package baritone.ai.planner;

import baritone.ai.AgentTelemetry;
import baritone.ai.BaritoneTools;
import baritone.ai.GoalTracker;
import baritone.ai.MissionMemory;
import baritone.ai.MistralAgent;
import baritone.ai.OpenAiChatClient;
import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.Settings;
import baritone.api.utils.Helper;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.ChatFormatting;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The hierarchical mission planner — the "main agent" that makes "get full diamond armor"
 * actually climb the tech ladder instead of YOLOing at diamonds with a wooden pickaxe:
 *
 * <ol>
 *   <li><b>Decompose:</b> one think-first LLM call (forced {@code submit_plan} tool) turns the
 *       goal into ordered {@link SubGoal}s with machine-checkable criteria.</li>
 *   <li><b>Execute:</b> each sub-goal runs as a fresh, focused {@link MistralAgent} sub-agent
 *       with a small iteration budget.</li>
 *   <li><b>Verify:</b> the sub-agent's "done" is checked against the REAL inventory via
 *       {@link BaritoneTools#snapshotForPlanner()} + {@link CriteriaEvaluator}; unmet → the
 *       sub-agent is bounced back with what is missing.</li>
 *   <li><b>Adapt:</b> deaths run the {@link DeathPolicy} (recover drops when reachable,
 *       replan-and-regear after too many deaths); failed steps trigger an LLM replan of the
 *       remaining work.</li>
 * </ol>
 *
 * Progress is surfaced through the existing GoalTracker plan/step telemetry (the launcher and
 * phone checkbox UI need zero changes) and persisted via {@link PlannerStore} for `ai recover`.
 */
public final class PlannerAgent implements Helper {

    /** The planner currently running a mission, for `ai stop` / busy checks. */
    public static final AtomicReference<PlannerAgent> ACTIVE = new AtomicReference<>();

    /** Iteration budget for a death-recovery interlude (sprint there, pick up, done). */
    private static final int RECOVERY_ITERATIONS = 20;

    private final IBaritone baritone;
    private final BaritoneTools tools;
    private volatile boolean cancelled;
    private volatile Thread worker;
    private volatile MistralAgent currentSubAgent;
    private long handledDeathSeq;

    public PlannerAgent(IBaritone baritone) {
        this.baritone = baritone;
        this.tools = new BaritoneTools(baritone);
    }

    public void cancel() {
        cancelled = true;
        MistralAgent sub = currentSubAgent;
        if (sub != null) {
            sub.cancel();
        }
        Thread t = worker;
        if (t != null) {
            t.interrupt();
        }
    }

    /** Top-level entry: runs the whole mission; returns when it is done, failed, or cancelled. */
    public void runMission(String mainGoal) {
        worker = Thread.currentThread();
        Settings settings = BaritoneAPI.getSettings();
        if (!GoalTracker.snapshot().active) {
            GoalTracker.start(mainGoal, true);
        }

        try {
            // ── plan: resume from disk or decompose fresh ─────────────────────────────
            PlanDocument plan = null;
            PlanDocument saved = PlannerStore.load();
            if (saved != null && mainGoal.equals(saved.mainGoal) && !saved.isComplete()) {
                plan = saved;
                logDirect("[AI:planner] resuming saved plan at step " + (plan.cursor + 1)
                        + "/" + plan.subGoals.size(), ChatFormatting.AQUA);
            }
            if (plan == null) {
                GoalTracker.setStatus("Decomposing goal...");
                plan = decompose(mainGoal, settings);
                if (plan == null) {
                    return; // cancelled or hard failure (status already set)
                }
                PlannerStore.save(plan);
            }
            publishPlan(plan);
            if (plan.reasoning != null && !plan.reasoning.isEmpty()) {
                logDirect("[AI:planner] " + truncate(plan.reasoning, 300), ChatFormatting.GRAY);
            }
            handledDeathSeq = DeathWatch.currentSeq(); // deaths before the mission are not ours

            // ── main loop ────────────────────────────────────────────────────────────
            String bounceNote = null;
            while (!cancelled) {
                if (plan.isComplete()) {
                    StateSnapshot snap = tools.snapshotForPlanner();
                    CriteriaEvaluator.Result fin = CriteriaEvaluator.evaluate(plan.finalCriteria, snap);
                    if (fin.met) {
                        GoalTracker.finish("All " + plan.subGoals.size() + " steps verified: " + mainGoal);
                        MissionMemory.recordCheckpointQuietly(mainGoal, "planner_done",
                                plan.subGoals.size() + " steps verified", "done");
                        PlannerStore.clear();
                        return;
                    }
                    plan = replanOrGiveUp(plan, mainGoal, settings,
                            "all steps ran but the final goal is not met: " + String.join("; ", fin.unmet));
                    if (plan == null) {
                        return;
                    }
                    continue;
                }

                SubGoal g = plan.currentSubGoal();
                int stepNo = plan.cursor + 1;
                GoalTracker.setStatus("Step " + stepNo + "/" + plan.subGoals.size() + ": " + g.title);

                String preamble = PlannerPrompts.subGoalPreamble(mainGoal, completedTitles(plan), g);
                if (bounceNote != null) {
                    preamble = preamble + "\n" + bounceNote;
                }
                g.attempts++;
                MistralAgent sub = new MistralAgent(baritone, preamble);
                currentSubAgent = sub;
                MistralAgent.ACTIVE.set(sub); // keep the one-agent-at-a-time invariant visible
                MistralAgent.Outcome outcome;
                try {
                    outcome = sub.runGoalWithOutcome(g.instruction,
                            Math.max(0, settings.aiPlannerSubGoalMaxIterations.value));
                } finally {
                    MistralAgent.ACTIVE.compareAndSet(sub, null);
                    currentSubAgent = null;
                }
                bounceNote = null;

                // ── death handling first: a death invalidates whatever the run claimed ──
                DeathEvent death = DeathWatch.pollNewDeath(handledDeathSeq);
                if (death != null) {
                    handledDeathSeq = DeathWatch.currentSeq();
                    g.deaths++;
                    PlannerStore.save(plan);
                    plan = handleDeath(plan, g, death, mainGoal, settings);
                    if (plan == null) {
                        return;
                    }
                    continue;
                }

                if (cancelled || outcome == MistralAgent.Outcome.CANCELLED) {
                    GoalTracker.fail("Cancelled");
                    PlannerStore.save(plan); // resumable via ai recover
                    return;
                }

                // ── verify against the real inventory — done-claims are never trusted ──
                GoalTracker.setStatus("Verifying step " + stepNo + ": " + g.title);
                StateSnapshot snap = tools.snapshotForPlanner();
                CriteriaEvaluator.Result check = CriteriaEvaluator.evaluate(g.criteria, snap);
                if (check.met) {
                    g.complete = true;
                    plan.cursor++;
                    plan.updatedAt = System.currentTimeMillis();
                    PlannerStore.save(plan);
                    GoalTracker.completeStep(stepNo, "verified");
                    MissionMemory.recordCheckpointQuietly(mainGoal, "subgoal_done", g.title, "done");
                    logDirect("[AI:planner] step " + stepNo + " verified: " + g.title, ChatFormatting.GREEN);
                    continue;
                }

                String unmet = String.join("; ", check.unmet);
                if (outcome == MistralAgent.Outcome.DONE
                        && g.verifyBounces < Math.max(0, settings.aiPlannerVerifyBounces.value)) {
                    g.verifyBounces++;
                    PlannerStore.save(plan);
                    // Tell the (fresh) sub-agent EXACTLY what's still missing, what it currently
                    // holds, and the usual trap: it consumed the target by crafting (e.g. turned
                    // its logs into planks) and must gather/craft more to actually satisfy the check.
                    bounceNote = "VERIFICATION FAILED — you called done() but the goal is NOT met yet.\n"
                            + "STILL MISSING (the game checked your real inventory): " + unmet + "\n"
                            + "YOU CURRENTLY HOLD: " + relevantInventory(snap, check.unmet) + "\n"
                            + "Likely cause: you crafted the required item away (logs->planks, ore->ingots) "
                            + "or never made the target. Get/make EXACTLY what is listed above, verify with "
                            + "get_state, and only THEN call done(). Do not claim done until the counts match.";
                    logDirect("[AI:planner] step " + stepNo + " not verified (" + unmet + ") — bouncing back ("
                            + g.verifyBounces + "/" + settings.aiPlannerVerifyBounces.value + ")", ChatFormatting.YELLOW);
                    continue;
                }

                // step is genuinely stuck → replan the remaining work
                Map<String, Object> failData = new LinkedHashMap<>();
                failData.put("index", stepNo);
                failData.put("title", g.title);
                failData.put("reason", outcome + ": " + sub.lastOutcomeDetail());
                failData.put("unmet", check.unmet);
                AgentTelemetry.emit("subgoal_fail", failData);
                plan = replanOrGiveUp(plan, mainGoal, settings,
                        "step '" + g.title + "' failed (" + outcome + ": " + sub.lastOutcomeDetail()
                                + "); still missing: " + unmet);
                if (plan == null) {
                    return;
                }
            }
            GoalTracker.fail("Cancelled");
            PlannerStore.save(plan);
        } catch (RuntimeException e) {
            logDirect("[AI:planner] crashed: " + e.getClass().getSimpleName() + ": " + e.getMessage(),
                    ChatFormatting.RED);
            GoalTracker.fail("Planner crashed: " + e.getClass().getSimpleName());
        } finally {
            ACTIVE.compareAndSet(this, null);
        }
    }

    // ──────────────────────────────────────────────────────────────────── deaths

    /** Apply the user's death policy. Returns the (possibly replanned) plan, or null to stop. */
    private PlanDocument handleDeath(PlanDocument plan, SubGoal g, DeathEvent death,
                                     String mainGoal, Settings settings) {
        StateSnapshot here = tools.snapshotForPlanner();
        double distance = Math.sqrt(Math.pow(here.x - death.x, 2)
                + Math.pow(here.y - death.y, 2) + Math.pow(here.z - death.z, 2));
        double secondsSince = Math.max(0, (DeathWatch.currentGameTime() - death.gameTime) / 20.0);
        DeathPolicy.Verdict verdict = DeathPolicy.decide(distance, secondsSince, g.deaths,
                Math.max(0, settings.aiPlannerMaxDeathsPerSubGoal.value),
                settings.aiPlannerWalkSpeedBlocksPerSec.value,
                settings.aiPlannerDespawnSeconds.value);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("x", (int) death.x);
        data.put("y", (int) death.y);
        data.put("z", (int) death.z);
        data.put("recoverable", verdict.recoverable);
        data.put("decision", verdict.decision.name());
        data.put("deaths", g.deaths);
        AgentTelemetry.emit("death", data);
        try {
            MissionMemory.rememberLocation("death_drops", "items dropped here after death", "death",
                    death.dimension, (int) death.x, (int) death.y, (int) death.z, "planner");
        } catch (RuntimeException ignored) {}
        logDirect("[AI:planner] death #" + g.deaths + " on '" + g.title + "' — " + verdict.reason,
                ChatFormatting.YELLOW);

        // Death dropped everything carried — re-verify the steps we'd checked off and UN-check the
        // ones whose gear is now gone, rewinding the cursor. Without this the bot keeps "stone
        // pickaxe" checked and tries to mine iron with the wooden pickaxe (or fist) it just lost.
        int unchecked = PlanReverify.afterDeath(plan, tools.snapshotForPlanner());
        if (unchecked > 0) {
            logDirect("[AI:planner] death invalidated " + unchecked + " completed step(s) — re-doing them",
                    ChatFormatting.YELLOW);
            PlannerStore.save(plan);
            publishPlan(plan);
        }

        if (verdict.decision == DeathPolicy.Decision.RECOVER_THEN_CONTINUE && !cancelled) {
            GoalTracker.setStatus("Recovering dropped items (death #" + g.deaths + ")");
            int secondsLeft = (int) Math.max(0, settings.aiPlannerDespawnSeconds.value - secondsSince);
            MistralAgent recovery = new MistralAgent(baritone,
                    PlannerPrompts.recoveryPreamble((int) death.x, (int) death.y, (int) death.z, secondsLeft));
            currentSubAgent = recovery;
            MistralAgent.ACTIVE.set(recovery);
            try {
                recovery.runGoalWithOutcome("Recover your dropped items, then call done.", RECOVERY_ITERATIONS);
            } finally {
                MistralAgent.ACTIVE.compareAndSet(recovery, null);
                currentSubAgent = null;
            }
            // recovery may have brought gear back — re-check so still-satisfied steps stay done
            PlanReverify.afterDeath(plan, tools.snapshotForPlanner());
            PlannerStore.save(plan);
            publishPlan(plan);
            return plan; // retry from the rewound cursor with whatever was recovered
        }

        return replanOrGiveUp(plan, mainGoal, settings,
                "died " + g.deaths + " time(s) pursuing '" + g.title + "' (" + verdict.reason
                        + "). RE-GEAR before retrying: food, armor, torches, safer route.");
    }

    // ─────────────────────────────────────────────────────────────────── replan

    /** One replan round, budget permitting. Returns the updated plan, or null when giving up. */
    private PlanDocument replanOrGiveUp(PlanDocument plan, String mainGoal, Settings settings, String reason) {
        plan.replans++;
        if (plan.replans > Math.max(0, settings.aiPlannerMaxReplans.value) || cancelled) {
            GoalTracker.fail(cancelled ? "Cancelled"
                    : "Gave up after " + (plan.replans - 1) + " replans: " + truncate(reason, 160));
            PlannerStore.save(plan); // kept on disk for diagnostics + ai recover
            return null;
        }
        GoalTracker.setStatus("Replanning (" + plan.replans + "/" + settings.aiPlannerMaxReplans.value + ")...");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("reason", truncate(reason, 300));
        data.put("replans", plan.replans);
        data.put("remaining", plan.subGoals.size() - plan.cursor);
        AgentTelemetry.emit("replan", data);
        logDirect("[AI:planner] replanning (" + plan.replans + "): " + truncate(reason, 200), ChatFormatting.YELLOW);

        JsonObject args = callPlannerLlm(settings, PlannerPrompts.replanSystemPrompt(),
                PlannerPrompts.replanUserPrompt(mainGoal, plan, reason, stateJson()));
        PlanDocument remaining = PlanParser.parse(mainGoal, args, System.currentTimeMillis());

        // splice: verified-complete steps stay; the remaining work is replaced wholesale
        List<SubGoal> spliced = new ArrayList<>();
        for (SubGoal done : plan.subGoals) {
            if (done.complete) {
                spliced.add(done);
            }
        }
        int completed = spliced.size();
        spliced.addAll(remaining.subGoals);
        plan.subGoals = spliced;
        plan.cursor = completed;
        plan.reasoning = remaining.reasoning != null ? remaining.reasoning : plan.reasoning;
        if (!remaining.finalCriteria.isEmpty()) {
            plan.finalCriteria = remaining.finalCriteria;
        }
        plan.updatedAt = System.currentTimeMillis();
        PlannerStore.save(plan);
        publishPlan(plan);
        return plan;
    }

    // ───────────────────────────────────────────────────────────────── LLM call

    /** Decompose the main goal. Returns null only when cancelled / no provider configured. */
    private PlanDocument decompose(String mainGoal, Settings settings) {
        String memory = "";
        try {
            memory = MissionMemory.contextForGoal(mainGoal, 6);
        } catch (RuntimeException ignored) {}
        JsonObject args = callPlannerLlm(settings, PlannerPrompts.decompositionSystemPrompt(),
                PlannerPrompts.decompositionUserPrompt(mainGoal, memory, stateJson()));
        if (cancelled) {
            GoalTracker.fail("Cancelled");
            return null;
        }
        // args == null falls back to a single-sub-goal plan — the mission still runs
        return PlanParser.parse(mainGoal, args, System.currentTimeMillis());
    }

    /**
     * One structured planner call: system + user message, a single forced {@code submit_plan}
     * tool. Returns the tool-call arguments, or null after a retry also fails (callers fall
     * back to {@link PlanParser}'s single-sub-goal plan).
     */
    private JsonObject callPlannerLlm(Settings settings, String systemPrompt, String userPrompt) {
        String provider;
        String apiKey;
        String endpoint;
        String model;
        String mistralKey = settings.mistralApiKey.value;
        if (mistralKey != null && !mistralKey.isEmpty()) {
            provider = "mistral";
            apiKey = mistralKey;
            endpoint = settings.mistralEndpoint.value;
            // planning uses the strong planner model (default mistral-large-latest) even when the
            // per-sub-goal executor runs a cheaper/faster model
            model = effectivePlannerModel(settings.aiPlannerModel.value, settings.mistralModel.value);
            logDirect("[AI:planner] planning with " + model, ChatFormatting.DARK_AQUA);
        } else {
            String ollamaModel = settings.ollamaModel.value == null ? "" : settings.ollamaModel.value.trim();
            if (ollamaModel.isEmpty()) {
                logDirect("[AI:planner] no provider configured (mistral key or ollama model) — running unplanned.",
                        ChatFormatting.YELLOW);
                return null;
            }
            provider = "ollama";
            apiKey = "";
            endpoint = ollamaEndpoint(settings.ollamaBaseUrl.value);
            model = ollamaModel;
        }

        OpenAiChatClient client = new OpenAiChatClient(endpoint, apiKey, provider,
                settings.mistralMaxRetries.value, settings.mistralRetryBackoffMillis.value,
                settings.mistralRequestTimeoutSeconds.value);
        JsonArray toolDefs = submitPlanToolDefs();

        JsonArray messages = new JsonArray();
        messages.add(chatMessage("system", systemPrompt));
        messages.add(chatMessage("user", userPrompt));

        for (int attempt = 0; attempt < 2 && !cancelled; attempt++) {
            try {
                OpenAiChatClient.AssistantMessage am = client.chat(model, messages,
                        toolDefs, 0.2, Math.max(512, settings.aiPlannerMaxTokens.value));
                JsonObject args = extractSubmitPlanArgs(am);
                if (args != null) {
                    return args;
                }
                messages.add(am.raw);
                messages.add(chatMessage("user",
                        "You must respond by CALLING the submit_plan tool with reasoning and sub_goals. No prose."));
            } catch (Exception e) {
                logDirect("[AI:planner] plan call failed (" + truncate(String.valueOf(e.getMessage()), 120)
                        + ")" + (attempt == 0 ? " — retrying" : ""), ChatFormatting.YELLOW);
            }
        }
        return null;
    }

    /** The model the planner should use: its own (Large) model, or the mission model if unset. */
    static String effectivePlannerModel(String plannerModel, String missionModel) {
        if (plannerModel != null && !plannerModel.trim().isEmpty()) {
            return plannerModel.trim();
        }
        return missionModel == null ? "" : missionModel.trim();
    }

    /** Pull the submit_plan arguments out of the assistant message, if it called the tool. */
    static JsonObject extractSubmitPlanArgs(OpenAiChatClient.AssistantMessage am) {
        if (am == null || am.toolCalls == null) {
            return null;
        }
        for (JsonElement el : am.toolCalls) {
            MistralAgent.ParsedToolCall call = MistralAgent.parseToolCall(el);
            if (call.error == null && "submit_plan".equals(call.functionName) && call.arguments != null) {
                return call.arguments;
            }
        }
        return null;
    }

    // ────────────────────────────────────────────────────────────────── helpers

    private String stateJson() {
        try {
            BaritoneTools.ToolResult r = tools.execute("get_state", new JsonObject());
            return r.content == null ? "" : r.content;
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** Push the plan to the HUD/launcher and re-tick already-verified steps. */
    private void publishPlan(PlanDocument plan) {
        List<String> titles = new ArrayList<>();
        for (SubGoal g : plan.subGoals) {
            titles.add(g.title);
        }
        // GoalTracker displays at most MAX_PLAN_STEPS; the PlanDocument remains the source of truth
        GoalTracker.setPlan(titles);
        for (int i = 0; i < plan.subGoals.size(); i++) {
            if (plan.subGoals.get(i).complete) {
                GoalTracker.completeStep(i + 1, "verified");
            }
        }
    }

    /** A short, human inventory summary so a bounced sub-agent sees what it actually has vs. needs. */
    static String relevantInventory(StateSnapshot snap, List<String> unmet) {
        if (snap == null) {
            return "(unknown)";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("food=").append(snap.food);
        if (!"none".equals(snap.bestPickaxe)) {
            sb.append(", best_pickaxe=").append(ToolTiers.strip(snap.bestPickaxe));
        }
        // surface the headline items (logs/planks/tools) so "have 0 logs, have 24 planks" is obvious
        java.util.List<String> keys = new java.util.ArrayList<>(snap.inventoryTotals.keySet());
        java.util.Collections.sort(keys);
        int shown = 0;
        for (String k : keys) {
            int n = snap.inventoryTotals.get(k);
            if (n <= 0) {
                continue;
            }
            sb.append(", ").append(k).append('=').append(n);
            if (++shown >= 14) {
                sb.append(", …");
                break;
            }
        }
        if (shown == 0) {
            sb.append(", inventory empty");
        }
        return sb.toString();
    }

    private static List<String> completedTitles(PlanDocument plan) {
        List<String> titles = new ArrayList<>();
        for (SubGoal g : plan.subGoals) {
            if (g.complete) {
                titles.add(g.title);
            }
        }
        return titles;
    }

    private static JsonObject chatMessage(String role, String content) {
        JsonObject m = new JsonObject();
        m.addProperty("role", role);
        m.addProperty("content", content);
        return m;
    }

    private static String ollamaEndpoint(String baseUrl) {
        String base = baseUrl == null || baseUrl.isBlank() ? "http://localhost:11434" : baseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (base.endsWith("/v1/chat/completions")) {
            return base;
        }
        if (base.endsWith("/v1")) {
            return base + "/chat/completions";
        }
        return base + "/v1/chat/completions";
    }

    /** The single tool offered to the planner model — schema mirrors {@link PlanParser}. */
    static JsonArray submitPlanToolDefs() {
        String criterion = "{\"type\":\"object\",\"properties\":{"
                + "\"type\":{\"type\":\"string\",\"enum\":[\"has_item\",\"food_min\",\"has_station\","
                + "\"best_pickaxe_min\",\"best_axe_min\",\"armor_equipped\",\"reached_y_at_most\"]},"
                + "\"id\":{\"type\":\"string\"},"
                + "\"count\":{\"type\":\"integer\"},"
                + "\"slot\":{\"type\":\"string\",\"enum\":[\"head\",\"chest\",\"legs\",\"feet\"]}},"
                + "\"required\":[\"type\"]}";
        String schema = "{\"type\":\"function\",\"function\":{"
                + "\"name\":\"submit_plan\","
                + "\"description\":\"Submit the decomposed mission plan. Populate reasoning FIRST.\","
                + "\"parameters\":{\"type\":\"object\",\"properties\":{"
                + "\"reasoning\":{\"type\":\"string\",\"description\":\"Step-by-step thinking about the tech ladder, current state, risks and insurance — BEFORE the sub_goals.\"},"
                + "\"sub_goals\":{\"type\":\"array\",\"items\":{\"type\":\"object\",\"properties\":{"
                + "\"title\":{\"type\":\"string\",\"description\":\"Short checkbox label, <= 60 chars\"},"
                + "\"instruction\":{\"type\":\"string\",\"description\":\"Focused order for the executor agent\"},"
                + "\"criteria\":{\"type\":\"array\",\"items\":" + criterion + "}},"
                + "\"required\":[\"title\",\"instruction\"]}},"
                + "\"final_criteria\":{\"type\":\"array\",\"items\":" + criterion + "}},"
                + "\"required\":[\"reasoning\",\"sub_goals\"]}}}";
        JsonArray arr = new JsonArray();
        arr.add(JsonParser.parseString(schema));
        return arr;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
