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
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Drives the configured chat-completions conversation until {@code done} is called, the player
 * runs {@code ai stop}, or an API error occurs. There is no fixed round cap by default
 * ({@link Settings#mistralMaxIterations} {@code <= 0} means unlimited).
 */
public final class MistralAgent implements Helper {

    public static final AtomicReference<MistralAgent> ACTIVE = new AtomicReference<>();

    /** Extra full-window waits when the provider stays rate-limited after the client's own retries. */
    private static final int MAX_RATE_LIMIT_WAITS = 6;

    private static final ThreadLocal<MistralAgent> RUNNING = new ThreadLocal<>();

    private static final String SYSTEM_PROMPT =
              "You are Baritone-AI, an autonomous Minecraft agent built on top of the Baritone pathfinding mod.\n"
            + "You receive a natural-language goal from the player and must accomplish it by calling the "
            + "provided tools. Think step by step but DO NOT narrate -- just call tools.\n\n"
            + "CRITICAL — NEVER use run_command with 'craft ...' — that is NOT a Baritone command and will always fail.\n"
            + "YOU decide which tools to call — there is no fixed script. Do EXACTLY what the goal asks and nothing more. "
            + "If the goal is just to gather blocks (e.g. 'mine 67 logs'), call mine(['minecraft:log'], 67), wait_until_idle, "
            + "verify with get_state, then done — do NOT craft a crafting table, pickaxe, or anything the goal never mentioned.\n"
            + "ONLY craft a tool when the goal needs one AND you do not already have it. Read get_state first: if inventory_totals "
            + "shows a pickaxe/axe of ANY tier (stone/iron/gold/diamond/netherite), or has_wooden_pickaxe/has_wooden_axe is true, "
            + "NEVER craft a wooden one — that is wasteful and wrong. A player with netherite does not need a wooden pickaxe.\n"
            + "Only to obtain a wooden tool FROM SCRATCH (you have no usable pickaxe/axe and no logs): you may call "
            + "make_wooden_tool (mines a few logs then crafts table + tool in one call), or do it step by step "
            + "(mine + make_wood_tool_from_logs). Pass tool as minecraft:wooden_pickaxe or minecraft:wooden_axe. "
            + "If you already have logs, make_wood_tool_from_logs alone suffices.\n"
            + "Undercover mode NEVER disables your tools. It only makes visible looking/clicking calmer. If a tool fails, use another tool.\n\n"
            + "GUIDELINES:\n"
            + "- Always begin with get_state (position, inventory_totals, ender_chest_totals, has_wooden_pickaxe, has_wooden_axe, processes, ai_provider/model).\n"
            + "- You can tune your own configuration: list_settings (optional filter substring like 'allow'), get_setting, "
            + "set_setting, reset_setting. Enable abilities you need BEFORE actions that require them (e.g. set_setting allowBreak true, "
            + "set_setting allowPlace true, set_setting allowInventory true). The Mistral API key is protected and cannot be read or changed.\n"
            + "- When the player asks to change how you move/aim/break ('head not turning', 'be sneaky', 'break faster', 'fix aim'), "
            + "call tune with their words verbatim — it applies the right settings and reports what changed. Only look up setting docs "
            + "(list_settings searches names AND docs; get_setting shows full docs) when the player asks for setting tweaks, not during normal goals.\n"
            + "- Survival reflexes (auto-eat, flee creepers, fight back, anti-lava, anti-drown) run automatically every tick without you; "
            + "get_state shows recent_reflexes. Do not micro-manage danger yourself; tune 'be careful' / 'ignore mobs' toggles them.\n"
            + "- Plan crafting around what you actually have: get_state shows inventory_totals AND ender_chest_totals (last-known). "
            + "Call get_ender_chest to actually open the ender chest and read its REAL contents -- it opens a nearby ender chest or "
            + "PLACES one from inventory if needed. Do not assume materials you have not verified.\n"
            + "- Use has_wooden_pickaxe / has_wooden_axe before mining stone or ore; do not infer tools from earlier messages.\n"
            + "- To HARVEST blocks (ores, stone, logs, etc.) you MUST use mine() — it breaks blocks. "
            + "goto_block / goto_coords only WALK; they never mine. Using goto for 'mine diamond' walks past ore.\n"
            + "- Never ask for coordinates before trying mine() for named block targets like logs, jungle logs, stone, or ore. "
            + "mine(['minecraft:jungle_log']) or mine(['minecraft:log']) can search known/nearby blocks.\n"
            + "- For diamond or overworld ores, mine(['minecraft:diamond_ore']) is enough — deepslate variants are added automatically.\n"
            + "- For other 3x3 table recipes when the table is open, use craft_recipe_at_table(recipe_id) when possible — it tries "
            + "PlacementInfo, shaped pattern fallback, and shapeless search. If you do not know the id, call list_craftable_table_recipes "
            + "with the GUI open (optional filter substring). craft_shaped_at_table accepts item ids or #minecraft:tag per cell.\n"
            + "- Prefer craft_recipe_at_table with a vanilla recipe id (e.g. minecraft:iron_pickaxe) when known.\n"
            + "- For 2x2-only crafting recipes with inventory (E) open: craft_recipe_in_inventory or craft_shaped_in_inventory (4 strings).\n"
            + "- For furnace/smoker/blast furnace with GUI open: furnace_smelt (input item, optional fuel, optional recipe id to validate, wait cap). "
            + "Vanilla campfires have no container GUI.\n"
            + "- If a crafting/station GUI is not open, use open_station first. Use craft_item by output item id when you know what you want. "
            + "For wooden pickaxe/axe, prefer make_wooden_tool or craft_recipe_at_table('minecraft:wooden_pickaxe').\n"
            + "- smithing_recipe, stonecutter_cut, anvil_combine, brewing_load_stand require the matching block GUI open first.\n"
            + "- To make a wooden tool from scratch (no tool, no logs): make_wooden_tool(tool). "
            + "If logs are already in inventory: make_wood_tool_from_logs(tool). "
            + "To GATHER logs (any count): mine(['minecraft:log'], count) — NOT make_wooden_tool.\n"
            + "- For step-by-step crafting instead: set_setting allowInventory true, close_inventory_screens, "
            + "craft_planks_from_logs, craft_sticks, craft_crafting_table, craft_wooden_pickaxe_at_table / "
            + "craft_wooden_axe_at_table (table GUI must be open for the last two).\n"
            + "- Prefer mine / follow_player / farm over run_command. Use goto_block only when the player wants to "
            + "stand near a block without breaking it.\n"
            + "- NEVER call explore unless the player explicitly asks to explore/map terrain. If the player says not to explore, do not call explore. "
            + "Use mine/goto/open_station/craft/farm instead.\n"
            + "- In #goal plan mode, call get_state, then set_goal_plan with concrete steps before movement/mining/crafting/opening actions. "
            + "Use update_goal_status and complete_goal_step as work progresses.\n"
            + "- For separate follow-up work, call mission_enqueue instead of mixing unrelated goals into the current mission. "
            + "Use mission_status to inspect queued missions.\n"
            + "- SURVIVAL PROGRESSION — think and prioritize like a real player. From a fresh start the tech ladder is: "
            + "logs -> wooden pickaxe -> mine cobblestone -> stone pickaxe/axe/sword -> coal+torches -> iron -> better gear. "
            + "Do not skip rungs (you cannot mine iron without a stone pickaxe, nor diamonds without an iron pickaxe). "
            + "get_state now reports time_of_day, ticks_until_night, light_level, mob_spawn_risk, best_pickaxe, best_axe, "
            + "edible_food_count — READ THESE and act on them.\n"
            + "- BEFORE NIGHT (time_of_day=dusk or ticks_until_night small): make sure you have food (edible_food_count>0; "
            + "else kill animals / harvest crops) and safety — either dig a quick 1x1 shelter and wall yourself in, place "
            + "torches so light_level>7, or keep working only if well-lit. Hostiles spawn when mob_spawn_risk is true. "
            + "The survival reflexes will fight/flee for you, but don't pick fights at night with wooden gear.\n"
            + "- REMEMBER LOCATIONS as you explore: the agent auto-saves your 'base' (start) and valuable ores it sees, but "
            + "you should ALSO memory_remember anything important (key='base'/'village'/'iron_spot', include_position=true). "
            + "memory_recall (or the mission_memory_summary in get_state) to return to known spots instead of re-searching.\n"
            + "- Use memory_recall for saved bases, preferences, resource spots, and previous checkpoints. "
            + "Use memory_remember for durable facts and memory_checkpoint after important progress.\n"
            + "- After long-running actions (mine, goto_*, farm, explore), call wait_until_idle (timeout_seconds=0 "
            + "waits until path + mine/farm/explore are idle).\n"
            + "- Re-check state with get_state after each major action (check mine_process_active, inventory_totals).\n"
            + "- If any tool result contains ERROR, WARN, TIMEOUT, failed, issues, or could not, do not call done as success. "
            + "Fix it with another tool call or report impossible. Never call done for 'attempted' or 'tried'.\n"
            + "- Before done for an item/crafting goal, verify the item is actually in inventory with get_state.\n"
            + "- Use Minecraft block ids like 'minecraft:diamond_ore' or just 'diamond_ore'.\n"
            + "- If something is impossible from the current state, call done with an explanation.\n"
            + "- Use say() sparingly for player-visible progress.\n"
            + "- Call done() when the goal is achieved or impossible.\n"
            + "- You may run for many turns; keep calling tools until done. Never answer with ONLY plain text: "
            + "always use tools until finished.";

    private final BaritoneTools tools;
    private JsonArray history = new JsonArray();
    private final boolean planMode;
    private volatile boolean cancelled = false;
    private volatile Thread worker;

    public MistralAgent(IBaritone baritone) {
        this(baritone, false);
    }

    public MistralAgent(IBaritone baritone, boolean planMode) {
        this.tools = new BaritoneTools(baritone);
        this.planMode = planMode;
        history.add(message("system", SYSTEM_PROMPT));
        if (planMode) {
            history.add(message("system",
                    "PLAN MODE IS ON. Call get_state, then before any movement/mining/crafting/opening action call set_goal_plan with 3-8 steps. "
                            + "Then update_goal_status and complete_goal_step as you work. After planning, execute the plan unless impossible."));
        }
    }

    /** True while this agent's worker thread should stop waiting and exit. */
    public static boolean isCancelled() {
        MistralAgent a = RUNNING.get();
        return a != null && a.cancelled;
    }

    public void cancel() {
        cancelled = true;
        Thread t = worker;
        if (t != null) {
            t.interrupt();
        }
    }

    public void runGoal(String userGoal) {
        if (!GoalTracker.snapshot().active) {
            GoalTracker.start(userGoal, planMode);
        }
        GoalTracker.setStatus("Checking provider");

        Settings settings = BaritoneAPI.getSettings();
        String provider = normalizeProvider(settings.aiProvider.value);
        String apiKey;
        String endpoint;
        String model;
        if ("ollama".equals(provider)) {
            apiKey = "";
            endpoint = ollamaChatEndpoint(settings.ollamaBaseUrl.value);
            model = settings.ollamaModel.value == null ? "" : settings.ollamaModel.value.trim();
            if (model.isEmpty()) {
                logDirect("Ollama model is not set. Run: " + settings.prefix.value
                        + "ollama list, then " + settings.prefix.value + "ollama use <number-or-name>", ChatFormatting.RED);
                GoalTracker.fail("Ollama model is not set");
                return;
            }
        } else {
            apiKey = settings.mistralApiKey.value;
            if (apiKey == null || apiKey.isEmpty()) {
                logDirect("Mistral API key is not set. Run: " + settings.prefix.value
                        + "mistral key <YOUR_KEY>, or use " + settings.prefix.value + "ollama use <model>", ChatFormatting.RED);
                GoalTracker.fail("Mistral API key is not set");
                return;
            }
            endpoint = settings.mistralEndpoint.value;
            model = settings.mistralModel.value;
        }
        worker = Thread.currentThread();
        RUNNING.set(this);
        tools.setForbidExplore(goalForbidsExplore(userGoal));
        GoalTracker.setStatus(planMode ? "Planning" : "Starting");
        MissionMemory.recordCheckpointQuietly(userGoal, "agent_started", provider + ":" + model, "running");
        tools.rememberBaseIfUnknown();

        // Fast path: the fine-tuned baritone-brain model answers a tiny schema-free prompt with one
        // tool call (~1s). On escalate / parse failure / tool error we fall through to the full
        // prompt below - preferring Mistral for the big path when an API key is available.
        if (!planMode && "ollama".equals(provider) && BrainProtocol.isBrainModel(model)
                && settings.aiBrainShortPrompt.value) {
            try {
                if (runBrainFastPath(settings, endpoint, model, userGoal)) {
                    RUNNING.remove();
                    return;
                }
            } catch (Exception e) {
                logDirect("[AI] brain fast path failed (" + e.getMessage() + "); using full prompt.",
                        ChatFormatting.YELLOW);
            }
            String bigKey = settings.mistralApiKey.value;
            if (bigKey != null && !bigKey.isEmpty()) {
                provider = "mistral";
                apiKey = bigKey;
                endpoint = settings.mistralEndpoint.value;
                model = settings.mistralModel.value;
                logDirect("[AI] escalating to " + provider + " (" + model + ") with full tools.",
                        ChatFormatting.AQUA);
            } else {
                logDirect("[AI] escalating to full prompt on " + model + " (no Mistral key set).",
                        ChatFormatting.AQUA);
            }
            GoalTracker.setStatus("Escalated to big model");
        }

        // Feature 4: seed the conversation with the most relevant saved memories for this goal.
        if (settings.mistralInjectMemory.value) {
            String memoryContext = MissionMemory.contextForGoal(userGoal, 6);
            if (memoryContext != null && !memoryContext.isEmpty()) {
                history.add(message("system", "Relevant saved memory for this goal: " + memoryContext));
            }
        }
        history.add(message("user", userGoal));

        OpenAiChatClient client = new OpenAiChatClient(endpoint, apiKey, provider,
                settings.mistralMaxRetries.value, settings.mistralRetryBackoffMillis.value,
                settings.mistralRequestTimeoutSeconds.value);
        JsonArray toolDefs = BaritoneTools.toolSchemas();

        int maxIter = settings.mistralMaxIterations.value;
        boolean unlimited = maxIter <= 0;
        boolean verbose = settings.mistralVerbose.value;
        double temp = settings.mistralTemperature.value;
        int maxTok = settings.mistralMaxTokens.value;
        int maxHistory = settings.mistralMaxHistoryMessages.value;
        int keepRecent = settings.mistralKeepRecentMessages.value;
        int maxMissionSeconds = settings.mistralMaxMissionSeconds.value;
        long missionDeadline = maxMissionSeconds > 0
                ? System.currentTimeMillis() + maxMissionSeconds * 1000L : 0L;

        // Bug #3: keep inventory access for the mission, then restore the player's setting afterward.
        boolean prevAllowInventory = settings.allowInventory.value;
        MissionStats stats = new MissionStats(System.currentTimeMillis());

        logDirect(unlimited
                        ? "[AI] starting agent (provider=" + provider + ", model=" + model + ", unlimited rounds until done or ai stop)"
                        : "[AI] starting agent (provider=" + provider + ", model=" + model + ", max " + maxIter + " rounds)",
                ChatFormatting.AQUA);

        int round = 0;
        int rateLimitWaits = 0;
        try {
            while (true) {
                round++;
                // Feature 1: keep the conversation from growing without bound across long missions.
                history = compactHistory(history, maxHistory, keepRecent, MissionMemory.summaryForPrompt());
                if (!unlimited && round > maxIter) {
                    logDirect("[AI] reached max iterations (" + maxIter + "). Use set mistralMaxIterations 0 for unlimited.",
                            ChatFormatting.YELLOW);
                    GoalTracker.fail("Reached max iterations");
                    return;
                }
                // Feature 5: wall-clock watchdog so an unlimited-rounds mission cannot run forever.
                if (missionDeadline > 0L && System.currentTimeMillis() > missionDeadline) {
                    logDirect("[AI] reached time budget (" + maxMissionSeconds + "s). Use set mistralMaxMissionSeconds 0 to disable.",
                            ChatFormatting.YELLOW);
                    GoalTracker.fail("Reached time budget");
                    return;
                }
                if (cancelled) {
                    logDirect("[AI] cancelled.", ChatFormatting.YELLOW);
                    GoalTracker.fail("Cancelled");
                    return;
                }

                OpenAiChatClient.AssistantMessage am;
                GoalTracker.setStatus("Thinking...");
                try {
                    am = client.chat(model, history, toolDefs, temp, maxTok);
                    rateLimitWaits = 0;
                } catch (Exception e) {
                    // Rate limiting outlives even the client's retry budget when two consumers
                    // (launcher orchestrator + this agent) share one key. Don't kill a mission
                    // that is standing safely in-world — wait the window out and try again.
                    String msg = e.getMessage() == null ? "" : e.getMessage();
                    boolean rateLimited = msg.contains("429") || msg.toLowerCase(Locale.ROOT).contains("rate limit");
                    if (rateLimited && !cancelled && rateLimitWaits < MAX_RATE_LIMIT_WAITS) {
                        rateLimitWaits++;
                        logDirect("[AI] rate limited — waiting 30s (" + rateLimitWaits + "/" + MAX_RATE_LIMIT_WAITS + ")…",
                                ChatFormatting.YELLOW);
                        GoalTracker.setStatus("Rate limited — waiting 30s (" + rateLimitWaits + "/" + MAX_RATE_LIMIT_WAITS + ")");
                        AgentTelemetry.emit("status", Map.of("status", "rate_limited", "wait", rateLimitWaits));
                        try {
                            Thread.sleep(30_000L);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            GoalTracker.fail("Cancelled while rate limited");
                            return;
                        }
                        round--; // this round never reached the model; don't burn the iteration budget
                        continue;
                    }
                    logDirect("[AI] API error: " + e.getMessage(), ChatFormatting.RED);
                    GoalTracker.fail("API error: " + e.getMessage());
                    return;
                }
                if (verbose && am.content != null && !am.content.isEmpty()) {
                    logDirect("[AI:thought] " + truncate(am.content, 400), ChatFormatting.GRAY);
                }

                if (am.toolCalls == null || am.toolCalls.size() == 0) {
                    history.add(am.raw);
                    if (cancelled) {
                        GoalTracker.fail("Cancelled");
                        return;
                    }
                    GoalTracker.setStatus("Thinking... waiting for tool call");
                    history.add(message("user",
                            "You replied without tools. Continue the goal using tools only (get_state, craft_*, "
                                    + "mine, goto_*, wait_until_idle, done, etc.). Do not finish with plain text."));
                    continue;
                }

                List<ParsedToolCall> toolCalls = new ArrayList<>();
                boolean hasUnanswerableMalformedCall = false;
                for (JsonElement tcEl : am.toolCalls) {
                    ParsedToolCall parsed = parseToolCall(tcEl);
                    if (!parsed.canRespondAsTool) {
                        hasUnanswerableMalformedCall = true;
                        GoalTracker.setStatus("Thinking... malformed tool call");
                        history.add(message("user",
                                "Your previous response contained a malformed tool call that could not be answered "
                                        + "as a tool result: " + parsed.error
                                        + ". Retry with a valid tool call containing id, function.name, and arguments."));
                        break;
                    }
                    toolCalls.add(parsed);
                }
                if (hasUnanswerableMalformedCall) {
                    continue;
                }

                history.add(am.raw);

                boolean doneCalled = false;
                for (ParsedToolCall tc : toolCalls) {
                    if (cancelled) {
                        logDirect("[AI] cancelled.", ChatFormatting.YELLOW);
                        GoalTracker.fail("Cancelled");
                        return;
                    }
                    if (tc.error != null) {
                        String content = "ERROR: " + tc.error
                                + " Retry with a valid tool call containing function.name and valid JSON arguments.";
                        history.add(toolMessage(tc.callId, tc.functionName, content));
                        tools.observeResult(tc.functionName, content);
                        if (verbose) {
                            logDirect("[AI:result] " + truncate(content, 240), ChatFormatting.RED);
                        }
                        continue;
                    }

                    String fnName = tc.functionName;
                    JsonObject argsObj = tc.arguments;

                    GoalTracker.setStatus("Calling " + fnName);
                    emitToolCall(fnName, argsObj.toString());
                    if (verbose) {
                        logDirect("[AI:call] " + fnName + " " + truncate(argsObj.toString(), 200),
                                ChatFormatting.DARK_AQUA);
                    }

                    BaritoneTools.ToolResult result = tools.execute(fnName, argsObj);
                    tools.observeResult(fnName, result.content);
                    stats.record(fnName, result.error);
                    if (shouldCheckpointTool(fnName, result)) {
                        MissionMemory.recordCheckpointQuietly(userGoal, fnName,
                                result.content == null ? "" : result.content,
                                result.done ? "done" : (result.error ? "error" : "ok"));
                    }

                    String resultContent = result.content == null ? "" : result.content;
                    emitToolResult(fnName, !result.error, resultContent);
                    if (verbose) {
                        logDirect("[AI:result] " + truncate(resultContent, 240),
                                result.error ? ChatFormatting.RED : ChatFormatting.DARK_GRAY);
                    }

                    history.add(toolMessage(tc.callId, fnName, result.content == null ? "" : result.content));

                    if (result.done) {
                        logDirect("[AI] done: " + result.content, ChatFormatting.GREEN);
                        GoalTracker.finish(result.content);
                        MissionMemory.recordCheckpointQuietly(userGoal, "agent_done", result.content, "done");
                        doneCalled = true;
                        // Bug #2: done is terminal; do not run the rest of this batch's tool calls.
                        break;
                    }
                }
                if (doneCalled) {
                    return;
                }
            }
        } finally {
            RUNNING.remove();
            // Bug #3: restore the player's inventory-access setting the mission may have flipped.
            settings.allowInventory.value = prevAllowInventory;
            // Feature 5: emit and persist a one-line telemetry report for the mission.
            if (stats.totalCalls() > 0) {
                String report = stats.report(System.currentTimeMillis());
                logDirect("[AI] mission summary: " + report, ChatFormatting.AQUA);
                MissionMemory.recordCheckpointQuietly(userGoal, "report", report, "report");
            }
        }
    }

    /**
     * One-shot dispatcher for the fine-tuned baritone-brain model: tiny schema-free prompt in, one
     * tool call out, executed immediately. Mirrors the training format (training/train.py), which is
     * single-turn - so the fast path never loops; anything needing conversation escalates.
     *
     * @return true when the mission was fully handled; false to escalate to the full-prompt path
     */
    private boolean runBrainFastPath(Settings settings, String endpoint, String model, String userGoal) throws Exception {
        GoalTracker.setStatus("Brain: thinking");
        OpenAiChatClient client = new OpenAiChatClient(endpoint, "", "ollama",
                settings.mistralMaxRetries.value, settings.mistralRetryBackoffMillis.value,
                settings.mistralRequestTimeoutSeconds.value);
        JsonArray messages = new JsonArray();
        messages.add(message("system", BrainProtocol.SYSTEM_PROMPT));
        messages.add(message("user", userGoal));
        OpenAiChatClient.AssistantMessage am = client.chat(model, messages, null, 0.0, 300);

        String fnName = null;
        JsonObject args = null;
        if (am.toolCalls != null && am.toolCalls.size() > 0) {
            ParsedToolCall parsed = parseToolCall(am.toolCalls.get(0));
            if (parsed.canRespondAsTool && parsed.error == null) {
                fnName = parsed.functionName;
                args = parsed.arguments;
            }
        }
        if (fnName == null) {
            BrainProtocol.Call call = BrainProtocol.extractToolCall(am.content);
            if (call == null) {
                logDirect("[AI:brain] reply had no tool call; escalating.", ChatFormatting.YELLOW);
                return false;
            }
            fnName = call.name;
            args = call.arguments;
        }
        if (BrainProtocol.ESCALATE.equalsIgnoreCase(fnName)) {
            AgentTelemetry.emit("brain_escalate", "reason", "request beyond fast brain");
            logDirect("[AI:brain] escalate: request is beyond the fast brain.", ChatFormatting.YELLOW);
            return false;
        }
        if ("get_state".equalsIgnoreCase(fnName) && !BrainProtocol.looksInformational(userGoal)) {
            // A lone pocket-check cannot complete an action goal ("get wood" -> get_state -> done
            // was observed in-game). Treat it as brain confusion and use the full agent instead.
            logDirect("[AI:brain] answered get_state for an action goal; escalating.", ChatFormatting.YELLOW);
            return false;
        }

        GoalTracker.setStatus("Brain: " + fnName);
        logDirect("[AI:brain] " + fnName + " " + truncate(args.toString(), 160), ChatFormatting.DARK_AQUA);
        boolean prevAllowInventory = settings.allowInventory.value;
        BaritoneTools.ToolResult result;
        try {
            result = tools.execute(fnName, args);
        } finally {
            settings.allowInventory.value = prevAllowInventory;
        }
        tools.observeResult(fnName, result.content);
        if (result.error) {
            logDirect("[AI:brain] tool error: " + truncate(result.content == null ? "" : result.content, 160)
                    + " - escalating.", ChatFormatting.YELLOW);
            return false;
        }
        String summary = result.content == null || result.content.isBlank()
                ? "Dispatched " + fnName : result.content;
        logDirect("[AI] done: " + truncate(summary, 240), ChatFormatting.GREEN);
        GoalTracker.finish(summary);
        MissionMemory.recordCheckpointQuietly(userGoal, "brain_" + fnName, summary, "done");
        return true;
    }

    private static boolean shouldCheckpointTool(String fnName, BaritoneTools.ToolResult result) {
        if (fnName == null || result == null) {
            return false;
        }
        String name = fnName.toLowerCase(java.util.Locale.ROOT);
        if (name.equals("done") || name.equals("memory_checkpoint")) {
            return false;
        }
        if (result.error) {
            return true;
        }
        return !(name.equals("get_state")
                || name.equals("mission_status")
                || name.equals("memory_recall")
                || name.equals("update_goal_status")
                || name.equals("complete_goal_step")
                || name.equals("say"));
    }

    private static boolean goalForbidsExplore(String goal) {
        if (goal == null) {
            return false;
        }
        String g = goal.toLowerCase(java.util.Locale.ROOT)
                .replace("don't", "dont")
                .replace("do not", "dont")
                .replace("dont do", "dont")
                .replaceAll("\\s+", " ");
        return g.contains("dont explore")
                || g.contains("dont explor")
                || g.contains("no explore")
                || g.contains("no explor")
                || g.contains("without exploring")
                || g.contains("not explore")
                || g.contains("not explor")
                || g.contains("dont wander")
                || g.contains("no wandering");
    }

    /**
     * Bounds conversation growth while preserving OpenAI tool-call/tool-result pairing. Keeps the leading
     * system message(s) and the first user message (the original goal) untouched, keeps the most recent
     * {@code keepRecent} messages (cut aligned to a non-tool boundary so no tool result is orphaned), and
     * collapses the middle into one short summary message. Returns the same array when nothing is dropped.
     */
    static JsonArray compactHistory(JsonArray history, int maxMessages, int keepRecent, String summaryTail) {
        if (history == null || maxMessages <= 0 || history.size() <= maxMessages) {
            return history;
        }
        int size = history.size();
        int headerEnd = 0;
        while (headerEnd < size && "system".equals(roleOf(history.get(headerEnd)))) {
            headerEnd++;
        }
        if (headerEnd < size && "user".equals(roleOf(history.get(headerEnd)))) {
            headerEnd++;
        }
        int keepFrom = Math.max(headerEnd, size - Math.max(1, keepRecent));
        // Never let the kept block begin on a tool result whose parent assistant message was dropped.
        while (keepFrom < size && "tool".equals(roleOf(history.get(keepFrom)))) {
            keepFrom++;
        }
        if (keepFrom <= headerEnd) {
            return history;
        }
        int droppedCount = keepFrom - headerEnd;
        JsonArray compacted = new JsonArray();
        for (int i = 0; i < headerEnd; i++) {
            compacted.add(history.get(i));
        }
        String tail = summaryTail == null || summaryTail.isEmpty() ? "none" : summaryTail;
        compacted.add(message("user", "[Earlier progress summarized -- " + droppedCount
                + " older messages omitted. Recent checkpoints: " + tail + "]"));
        for (int i = keepFrom; i < size; i++) {
            compacted.add(history.get(i));
        }
        return compacted;
    }

    private static String roleOf(JsonElement el) {
        if (el == null || !el.isJsonObject()) {
            return "";
        }
        JsonObject obj = el.getAsJsonObject();
        if (!obj.has("role") || obj.get("role").isJsonNull()) {
            return "";
        }
        try {
            return obj.get("role").getAsString();
        } catch (RuntimeException e) {
            return "";
        }
    }

    static ParsedToolCall parseToolCall(JsonElement tcEl) {
        if (tcEl == null || !tcEl.isJsonObject()) {
            return ParsedToolCall.unanswerable("tool call entry is not a JSON object");
        }
        JsonObject tc = tcEl.getAsJsonObject();
        String callId = stringMember(tc, "id");
        if (callId.isEmpty()) {
            return ParsedToolCall.unanswerable("tool call is missing id");
        }
        if (!tc.has("function") || !tc.get("function").isJsonObject()) {
            return ParsedToolCall.error(callId, "invalid_tool_call", "tool call is missing function object");
        }
        JsonObject fn = tc.getAsJsonObject("function");
        String fnName = stringMember(fn, "name");
        if (fnName.isEmpty()) {
            return ParsedToolCall.error(callId, "invalid_tool_call", "tool call function is missing name");
        }
        JsonObject args = parseArgs(fn);
        if (args == null) {
            return ParsedToolCall.error(callId, fnName,
                    "arguments were not a valid JSON object; send arguments as a JSON object");
        }
        return ParsedToolCall.valid(callId, fnName, args);
    }

    /**
     * Parses a tool call's {@code arguments}. Returns an empty object when no arguments are supplied, the
     * parsed object when valid, or {@code null} when the value is malformed (not an object, or an
     * unparseable string) so the caller can surface a corrective error to the model.
     */
    private static JsonObject parseArgs(JsonObject fn) {
        if (!fn.has("arguments") || fn.get("arguments").isJsonNull()) {
            return new JsonObject();
        }
        JsonElement el = fn.get("arguments");
        if (el.isJsonObject()) {
            return el.getAsJsonObject();
        }
        if (el.isJsonPrimitive()) {
            String raw = el.getAsString().trim();
            if (raw.isEmpty()) {
                return new JsonObject();
            }
            try {
                JsonElement parsed = JsonParser.parseString(raw);
                if (parsed.isJsonObject()) {
                    return parsed.getAsJsonObject();
                }
            } catch (RuntimeException ignored) {}
        }
        return null;
    }

    private static String stringMember(JsonObject obj, String member) {
        if (obj == null || !obj.has(member) || obj.get(member).isJsonNull()) {
            return "";
        }
        try {
            return obj.get(member).getAsString().trim();
        } catch (RuntimeException e) {
            return "";
        }
    }

    static final class ParsedToolCall {
        final boolean canRespondAsTool;
        final String callId;
        final String functionName;
        final JsonObject arguments;
        final String error;

        private ParsedToolCall(boolean canRespondAsTool,
                               String callId,
                               String functionName,
                               JsonObject arguments,
                               String error) {
            this.canRespondAsTool = canRespondAsTool;
            this.callId = callId;
            this.functionName = functionName;
            this.arguments = arguments == null ? new JsonObject() : arguments;
            this.error = error;
        }

        static ParsedToolCall valid(String callId, String functionName, JsonObject arguments) {
            return new ParsedToolCall(true, callId, functionName, arguments, null);
        }

        static ParsedToolCall error(String callId, String functionName, String error) {
            return new ParsedToolCall(true, callId, functionName, new JsonObject(), error);
        }

        static ParsedToolCall unanswerable(String error) {
            return new ParsedToolCall(false, "", "invalid_tool_call", new JsonObject(), error);
        }
    }

    private static JsonObject message(String role, String content) {
        JsonObject m = new JsonObject();
        m.addProperty("role", role);
        m.addProperty("content", content);
        return m;
    }

    private static JsonObject toolMessage(String toolCallId, String name, String content) {
        JsonObject m = new JsonObject();
        m.addProperty("role", "tool");
        if (toolCallId != null && !toolCallId.isEmpty()) {
            m.addProperty("tool_call_id", toolCallId);
        }
        m.addProperty("name", name);
        m.addProperty("content", content);
        return m;
    }

    private static String truncate(String s, int n) {
        if (s == null) {
            return "";
        }
        return s.length() <= n ? s : s.substring(0, n) + "...";
    }

    private static void emitToolCall(String name, String args) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", name == null ? "" : name);
        data.put("args", truncate(args == null ? "" : args, 200));
        AgentTelemetry.emit("tool_call", data);
    }

    private static void emitToolResult(String name, boolean ok, String summary) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", name == null ? "" : name);
        data.put("ok", ok);
        data.put("summary", truncate(summary == null ? "" : summary, 200));
        AgentTelemetry.emit("tool_result", data);
    }

    private static String normalizeProvider(String raw) {
        String p = raw == null ? "" : raw.trim().toLowerCase(java.util.Locale.ROOT);
        return p.equals("ollama") ? "ollama" : "mistral";
    }

    private static String ollamaChatEndpoint(String baseUrl) {
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
}
