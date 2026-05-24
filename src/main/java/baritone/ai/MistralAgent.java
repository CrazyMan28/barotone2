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

import java.util.concurrent.atomic.AtomicReference;

/**
 * Drives the configured chat-completions conversation until {@code done} is called, the player
 * runs {@code ai stop}, or an API error occurs. There is no fixed round cap by default
 * ({@link Settings#mistralMaxIterations} {@code <= 0} means unlimited).
 */
public final class MistralAgent implements Helper {

    public static final AtomicReference<MistralAgent> ACTIVE = new AtomicReference<>();

    private static final ThreadLocal<MistralAgent> RUNNING = new ThreadLocal<>();

    private static final String SYSTEM_PROMPT =
              "You are Baritone-AI, an autonomous Minecraft agent built on top of the Baritone pathfinding mod.\n"
            + "You receive a natural-language goal from the player and must accomplish it by calling the "
            + "provided tools. Think step by step but DO NOT narrate -- just call tools.\n\n"
            + "CRITICAL — NEVER use run_command with 'craft ...' — that is NOT a Baritone command and will always fail.\n"
            + "For 'make wooden axe/pick from trees' you MUST call mine_logs_then_make_wood_tool (it mines a FIXED "
            + "number of logs then crafts table + tool). Do NOT chain mine (unlimited) + make_wood_tool — mine will never stop.\n"
            + "For wooden tools from logs, pass tool as exactly minecraft:wooden_pickaxe or minecraft:wooden_axe when possible "
            + "(aliases pickaxe/pick vs axe). The mod logs wood_tool_recipe= in tool results so you can verify which recipe ran.\n"
            + "If the player already has enough logs in inventory, make_wood_tool_from_logs is enough.\n"
            + "Undercover mode NEVER disables your tools. It only makes visible looking/clicking calmer. If a tool fails, use another tool.\n\n"
            + "GUIDELINES:\n"
            + "- Always begin with get_state (position, inventory_totals, has_wooden_pickaxe, has_wooden_axe, processes, ai_provider/model).\n"
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
            + "For wooden pickaxe/axe, prefer mine_logs_then_make_wood_tool or craft_recipe_at_table('minecraft:wooden_pickaxe').\n"
            + "- smithing_recipe, stonecutter_cut, anvil_combine, brewing_load_stand require the matching block GUI open first.\n"
            + "- For wooden tools starting from trees: mine_logs_then_make_wood_tool (tool + optional total_logs). "
            + "Only use make_wood_tool_from_logs if logs are already in inventory.\n"
            + "- For step-by-step crafting instead: set_setting allowInventory true, close_inventory_screens, "
            + "craft_planks_from_logs, craft_sticks, craft_crafting_table, craft_wooden_pickaxe_at_table / "
            + "craft_wooden_axe_at_table (table GUI must be open for the last two).\n"
            + "- Prefer mine / follow_player / farm over run_command. Use goto_block only when the player wants to "
            + "stand near a block without breaking it.\n"
            + "- NEVER call explore unless the player explicitly asks to explore/map terrain. If the player says not to explore, do not call explore. "
            + "Use mine/goto/open_station/craft/farm instead.\n"
            + "- In #goal plan mode, call get_state, then set_goal_plan with concrete steps before movement/mining/crafting/opening actions. "
            + "Use update_goal_status and complete_goal_step as work progresses.\n"
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
    private final JsonArray history = new JsonArray();
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
                return;
            }
        } else {
            apiKey = settings.mistralApiKey.value;
            if (apiKey == null || apiKey.isEmpty()) {
                logDirect("Mistral API key is not set. Run: " + settings.prefix.value
                        + "mistral key <YOUR_KEY>, or use " + settings.prefix.value + "ollama use <model>", ChatFormatting.RED);
                return;
            }
            endpoint = settings.mistralEndpoint.value;
            model = settings.mistralModel.value;
        }
        worker = Thread.currentThread();
        RUNNING.set(this);
        tools.setForbidExplore(goalForbidsExplore(userGoal));
        GoalTracker.start(userGoal, planMode);
        GoalTracker.setStatus(planMode ? "Planning" : "Starting");
        history.add(message("user", userGoal));

        OpenAiChatClient client = new OpenAiChatClient(endpoint, apiKey, provider);
        JsonArray toolDefs = BaritoneTools.toolSchemas();

        int maxIter = settings.mistralMaxIterations.value;
        boolean unlimited = maxIter <= 0;
        boolean verbose = settings.mistralVerbose.value;
        double temp = settings.mistralTemperature.value;
        int maxTok = settings.mistralMaxTokens.value;

        logDirect(unlimited
                        ? "[AI] starting agent (provider=" + provider + ", model=" + model + ", unlimited rounds until done or ai stop)"
                        : "[AI] starting agent (provider=" + provider + ", model=" + model + ", max " + maxIter + " rounds)",
                ChatFormatting.AQUA);

        int round = 0;
        try {
            while (true) {
                round++;
                if (!unlimited && round > maxIter) {
                    logDirect("[AI] reached max iterations (" + maxIter + "). Use set mistralMaxIterations 0 for unlimited.",
                            ChatFormatting.YELLOW);
                    GoalTracker.fail("Reached max iterations");
                    return;
                }
                if (cancelled) {
                    logDirect("[AI] cancelled.", ChatFormatting.YELLOW);
                    GoalTracker.fail("Cancelled");
                    return;
                }

                OpenAiChatClient.AssistantMessage am;
                try {
                    am = client.chat(model, history, toolDefs, temp, maxTok);
                } catch (Exception e) {
                    logDirect("[AI] API error: " + e.getMessage(), ChatFormatting.RED);
                    GoalTracker.fail("API error: " + e.getMessage());
                    return;
                }
                history.add(am.raw);

                if (verbose && am.content != null && !am.content.isEmpty()) {
                    logDirect("[AI:thought] " + truncate(am.content, 400), ChatFormatting.GRAY);
                }

                if (am.toolCalls == null || am.toolCalls.size() == 0) {
                    if (cancelled) {
                        GoalTracker.fail("Cancelled");
                        return;
                    }
                    history.add(message("user",
                            "You replied without tools. Continue the goal using tools only (get_state, craft_*, "
                                    + "mine, goto_*, wait_until_idle, done, etc.). Do not finish with plain text."));
                    continue;
                }

                boolean doneCalled = false;
                for (JsonElement tcEl : am.toolCalls) {
                    if (cancelled) {
                        logDirect("[AI] cancelled.", ChatFormatting.YELLOW);
                        GoalTracker.fail("Cancelled");
                        return;
                    }
                    JsonObject tc = tcEl.getAsJsonObject();
                    String callId = tc.has("id") ? tc.get("id").getAsString() : "";
                    JsonObject fn = tc.getAsJsonObject("function");
                    String fnName = fn.get("name").getAsString();
                    JsonObject argsObj = parseArgs(fn);

                    if (verbose) {
                        logDirect("[AI:call] " + fnName + " " + truncate(argsObj.toString(), 200),
                                ChatFormatting.DARK_AQUA);
                    }

                    BaritoneTools.ToolResult result = tools.execute(fnName, argsObj);
                    tools.observeResult(fnName, result.content);

                    if (verbose) {
                        String c = result.content == null ? "" : result.content;
                        logDirect("[AI:result] " + truncate(c, 240),
                                result.error ? ChatFormatting.RED : ChatFormatting.DARK_GRAY);
                    }

                    history.add(toolMessage(callId, fnName, result.content == null ? "" : result.content));

                    if (result.done) {
                        logDirect("[AI] done: " + result.content, ChatFormatting.GREEN);
                        GoalTracker.finish(result.content);
                        doneCalled = true;
                    }
                }
                if (doneCalled) {
                    return;
                }
            }
        } finally {
            RUNNING.remove();
        }
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

    private static JsonObject parseArgs(JsonObject fn) {
        if (!fn.has("arguments") || fn.get("arguments").isJsonNull()) {
            return new JsonObject();
        }
        JsonElement el = fn.get("arguments");
        if (el.isJsonObject()) {
            return el.getAsJsonObject();
        }
        if (el.isJsonPrimitive()) {
            try {
                JsonElement parsed = JsonParser.parseString(el.getAsString());
                if (parsed.isJsonObject()) {
                    return parsed.getAsJsonObject();
                }
            } catch (RuntimeException ignored) {}
        }
        return new JsonObject();
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
