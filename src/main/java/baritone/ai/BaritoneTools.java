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
import baritone.api.behavior.IPathingBehavior;
import baritone.api.command.manager.ICommandManager;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.SettingsUtil;
import baritone.cache.WorldData;
import baritone.command.defaults.AiCommand;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.BrewingStandMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.SmithingMenu;
import net.minecraft.world.inventory.StonecutterMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Defines the set of tools the Mistral agent can invoke and implements each of them
 * against Baritone's existing process / command APIs.
 *
 * <p>Most tools delegate to the existing chat-control commands by calling
 * {@link ICommandManager#execute(String)}. That means anything Baritone can already
 * do, the AI can do too &mdash; the agent is effectively a natural-language wrapper
 * over Baritone's command surface.</p>
 */
public final class BaritoneTools {

    /** Block names for {@link baritone.api.process.IMineProcess#mineByName(int, String...)} (any overworld/nether log). */
    private static final String[] MINE_LOG_BLOCK_NAMES = {
            "oak_log", "spruce_log", "birch_log", "jungle_log", "acacia_log", "dark_oak_log",
            "mangrove_log", "cherry_log", "pale_oak_log", "crimson_stem", "warped_stem"
    };

    private final IBaritone baritone;
    private final IPlayerContext ctx;
    private final ICommandManager commands;
    private volatile boolean forbidExplore;
    private volatile String lastProblem = "";

    public BaritoneTools(IBaritone baritone) {
        this.baritone = baritone;
        this.ctx = baritone.getPlayerContext();
        this.commands = baritone.getCommandManager();
        scopeMissionMemory();
    }

    public void setForbidExplore(boolean forbidExplore) {
        this.forbidExplore = forbidExplore;
    }

    public void observeResult(String toolName, String content) {
        if (content == null || content.isBlank()) {
            return;
        }
        String c = content.trim();
        if (looksLikeProblem(c)) {
            lastProblem = toolName + ": " + truncateForPrompt(c, 220);
            GoalTracker.setStatus("Tool issue: " + truncateForPrompt(c, 90));
        } else if (!lastProblem.isEmpty() && looksLikeSuccessfulProgress(toolName, c)) {
            lastProblem = "";
        }
    }

    /**
     * Returns the OpenAI/Mistral-style {@code tools} array describing every callable
     * function. Built once per agent run.
     */
    public static JsonArray toolSchemas() {
        JsonArray arr = new JsonArray();
        arr.add(fn("goto_coords",
                "Walk/path to a specific (x, y, z) block coordinate in the current dimension.",
                params(
                        param("x", "integer", "X block coordinate", true),
                        param("y", "integer", "Y block coordinate (height)", true),
                        param("z", "integer", "Z block coordinate", true)
                )));
        arr.add(fn("goto_block",
                "PATH ONLY — walks toward the nearest cached block; does NOT break or mine it. "
                        + "Never use this for ores or trees you want harvested; use mine() with the ore block ids instead.",
                params(
                        param("block", "string",
                                "Block id, e.g. 'minecraft:diamond_ore' or just 'diamond_ore'.", true)
                )));
        arr.add(fn("mine",
                "Mine and break blocks (digs ores, logs, etc.). If quantity is 0 or omitted, mining NEVER STOPS by count "
                        + "— do NOT use that for 'get a few logs then craft'; use mine_logs_then_make_wood_tool instead. "
                        + "For diamond (and other overworld ores), pass the stone ore id: deepslate variants are added automatically.",
                params(
                        param("blocks", "array",
                                "Block ids to mine, e.g. ['minecraft:diamond_ore'] (deepslate_diamond_ore is auto-included).",
                                true, "string"),
                        param("quantity", "integer",
                                "Total stop quantity across all listed blocks. 0 or omit for unlimited.", false)
                )));
        arr.add(fn("follow_player",
                "Follow a player by name. Useful for being escorted.",
                params(
                        param("name", "string", "Player username to follow.", true)
                )));
        arr.add(fn("farm",
                "Automatically farm nearby crops (wheat, carrots, potatoes, beetroots, melons, pumpkins, etc.).",
                params()));
        arr.add(fn("explore",
                "Wander outward from current position, discovering chunks. Do not use unless the player explicitly asks to explore. "
                        + "If the player says not to explore, this tool is blocked for that goal.",
                params()));
        arr.add(fn("build_schematic",
                "Build a schematic from the schematics folder, optionally at given coords.",
                params(
                        param("name", "string", "Schematic file name (with or without extension).", true),
                        param("x", "integer", "Origin X (optional).", false),
                        param("y", "integer", "Origin Y (optional).", false),
                        param("z", "integer", "Origin Z (optional).", false)
                )));
        arr.add(fn("set_setting",
                "Change a Baritone/AI setting at runtime. Validates the name and value, then confirms the applied value, "
                        + "e.g. allowBreak=true. Use list_settings to discover valid names. The API key is protected and cannot be set.",
                params(
                        param("name", "string", "Setting name.", true),
                        param("value", "string", "New value as a string.", true)
                )));
        arr.add(fn("list_settings",
                "List Baritone/AI settings the agent can tune. With no filter, returns settings currently changed from "
                        + "their defaults. With a filter substring, searches every setting name (e.g. 'allow', 'mine', 'mistral').",
                params(
                        param("filter", "string", "Optional name substring to search for, e.g. 'allow'.", false)
                )));
        arr.add(fn("get_setting",
                "Get one setting's current value, type, and default value.",
                params(
                        param("name", "string", "Setting name, e.g. allowBreak.", true)
                )));
        arr.add(fn("reset_setting",
                "Reset one setting back to its default value.",
                params(
                        param("name", "string", "Setting name to reset.", true)
                )));
        arr.add(fn("get_ender_chest",
                "Return the player's last-known ender chest contents as item -> count. Contents sync when an ender "
                        + "chest is opened; check this before planning crafting so you use what you actually have stored.",
                params()));
        arr.add(fn("open_station",
                "Path to and open a nearby station/container GUI. Supported station values: crafting_table, furnace, "
                        + "blast_furnace, smoker, brewing_stand, stonecutter, smithing_table, anvil.",
                params(
                        param("station", "string", "Station type to open.", true),
                        param("max_wait_seconds", "integer", "Wait cap for pathing/opening (default 90, max 600).", false)
                )));
        arr.add(fn("equip_item",
                "Equip/select an item from inventory or hotbar into the selected hotbar slot.",
                params(
                        param("item_id", "string", "Item id, e.g. minecraft:bucket or diamond_pickaxe.", true)
                )));
        arr.add(fn("right_click",
                "Right-click with the currently held item, using the current crosshair target if there is one.",
                params()));
        arr.add(fn("use_item_on_block",
                "Equip an optional item, then use the held item on the nearest matching block within a small radius.",
                params(
                        param("block_id", "string", "Block id to use the held item on.", true),
                        param("item_id", "string", "Optional item id to equip before using.", false),
                        param("max_radius", "integer", "Search radius in blocks (default 6, max 32).", false)
                )));
        arr.add(fn("run_command",
                "Escape hatch: run any raw Baritone chat-control command (without the prefix). "
                        + "Use this for anything not covered by the other tools (e.g. 'cancel', 'thisway 100', "
                        + "'sel 1', 'waypoints save base', etc.).",
                params(
                        param("command", "string",
                                "The raw command, e.g. 'goto 100 64 -200' or 'mine diamond_ore'.", true)
                )));
        arr.add(fn("mine_logs_then_make_wood_tool",
                "PREFERRED for 'make a wooden axe/pick from trees': mines ONLY until you have total_logs (default 6) "
                        + "log items in inventory (Baritone stops automatically), then runs full craft (planks, sticks, "
                        + "table, place, open GUI, tool). Do NOT call mine separately for this — it will mine forever.",
                params(
                        param("tool", "string",
                                "REQUIRED: exactly minecraft:wooden_pickaxe OR minecraft:wooden_axe (aliases: pickaxe, pick, axe).",
                                true),
                        param("total_logs", "integer",
                                "Target number of log ITEMS in inventory before crafting (default 6, min 4, max 32).",
                                false)
                )));
        arr.add(fn("make_wood_tool_from_logs",
                "ONE-SHOT early-game crafting: from logs in inventory, crafts planks, sticks, crafting table, "
                        + "places the table, opens it, and crafts a wooden axe OR pickaxe. "
                        + "Closes chest UIs first. Sets allowInventory true. Prefer this over run_command for tools.",
                params(
                        param("tool", "string",
                                "minecraft:wooden_pickaxe or minecraft:wooden_axe (preferred); or pickaxe / wooden_axe / axe aliases.",
                                true)
                )));
        arr.add(fn("close_inventory_screens",
                "Close chest, hopper, furnace, etc. Leave survival inventory or crafting table when appropriate.",
                params()));
        arr.add(fn("craft_planks_from_logs",
                "Craft planks from logs using the 2x2 player crafting grid. Open inventory (E) first.",
                params(
                        param("max_logs", "integer", "How many logs to convert this call (default 8).", false)
                )));
        arr.add(fn("craft_crafting_table",
                "Craft one crafting table from four planks in the 2x2 grid. Open inventory (E) first.",
                params()));
        arr.add(fn("craft_sticks",
                "Craft sticks from two planks (vertical pattern) in the 2x2 grid. Open inventory (E) first.",
                params(
                        param("sets", "integer", "How many stick recipes to run (default 1).", false)
                )));
        arr.add(fn("craft_wooden_axe_at_table",
                "Craft a wooden axe in an OPEN crafting table GUI (3 planks + 2 sticks pattern).",
                params()));
        arr.add(fn("craft_wooden_pickaxe_at_table",
                "Craft a wooden pickaxe in an OPEN crafting table GUI (right-click a placed table). Needs 3 planks + 2 sticks.",
                params()));
        arr.add(fn("craft_shaped_at_table",
                "Craft any shaped 3x3 recipe at an OPEN crafting table: pass grid as 9 strings in row-major order "
                        + "(top row cells 0-2, then middle, then bottom). Empty cells: \"\" or \"air\". "
                        + "Each cell may be an item id (minecraft:oak_planks), or a tag like #minecraft:planks. "
                        + "Example stone pickaxe: [\"cobblestone\",\"cobblestone\",\"cobblestone\",\"\",\"stick\",\"\",\"\",\"stick\",\"\"].",
                params(
                        param("grid", "array", "Exactly 9 strings: item id, #namespace:tag, or empty for that cell.", true, "string")
                )));
        arr.add(fn("craft_recipe_at_table",
                "Craft using a RECIPE ID at an OPEN crafting table. Tries PlacementInfo first, then centered shaped pattern, "
                        + "then bounded shapeless slot search — many datapack/mod recipes work without manual grids. "
                        + "Singleplayer: full recipe manager; multiplayer: synced registry only.",
                params(
                        param("recipe_id", "string", "Namespaced recipe id, e.g. minecraft:iron_pickaxe.", true)
                )));
        arr.add(fn("list_craftable_table_recipes",
                "With crafting table GUI open: lists recipe ids you likely have materials for (inventory simulation). "
                        + "Returns placeable_recipes (good PlacementInfo) and needs_pattern_or_shapeless_fallback lines. "
                        + "Use before craft_recipe_at_table when the recipe id is unknown. Multiplayer may list fewer recipes.",
                params(
                        param("max_entries", "integer", "Max total ids across both lines (default 50, max 200).", false),
                        param("filter", "string", "Optional substring filter on recipe id (case-insensitive).", false)
                )));
        arr.add(fn("list_crafting_recipes_for_output",
                "With player inventory or crafting table GUI open: list craftable recipe ids that produce an output item.",
                params(
                        param("output_item_id", "string", "Desired output item id, e.g. minecraft:stick.", true),
                        param("max_entries", "integer", "Max recipe ids to return (default 50, max 100).", false)
                )));
        arr.add(fn("craft_item",
                "Craft by desired output item id using the currently open inventory (2x2) or crafting table (3x3). "
                        + "Use open_station(crafting_table) first for 3x3 recipes.",
                params(
                        param("output_item_id", "string", "Desired output item id, e.g. minecraft:iron_pickaxe.", true),
                        param("quantity", "integer", "How many craft operations to attempt (default 1, max 64).", false)
                )));
        arr.add(fn("craft_recipe_in_inventory",
                "Like craft_recipe_at_table but uses the 2x2 grid in OPEN player inventory (E). Only recipes that fit in four cells.",
                params(
                        param("recipe_id", "string", "Vanilla crafting recipe id.", true)
                )));
        arr.add(fn("craft_shaped_in_inventory",
                "2x2 shaped crafting in OPEN inventory: exactly 4 item ids row-major (top row then bottom). Empty: \"\" or air.",
                params(
                        param("grid", "array", "Exactly 4 strings.", true, "string")
                )));
        arr.add(fn("furnace_smelt",
                "With an OPEN furnace, smoker, or blast furnace GUI: put one input item in the input slot, optionally one fuel, "
                        + "wait for output, shift-click result. Vanilla campfires have no GUI. Optional recipe_id validates input "
                        + "against a smelting/blasting/smoking recipe.",
                params(
                        param("input_item_id", "string", "Item id to smelt one of, e.g. minecraft:raw_iron.", true),
                        param("fuel_item_id", "string", "Optional fuel item id (omit or empty if fuel already present).", false),
                        param("recipe_id", "string", "Optional smelting recipe id to validate input (e.g. minecraft:iron_ingot).", false),
                        param("max_wait_seconds", "integer", "Wait cap for each output (default 90, max 600).", false),
                        param("quantity", "integer", "How many outputs to collect (default 1, max 64).", false)
                )));
        arr.add(fn("smithing_recipe",
                "Smithing table with OPEN GUI: resolves a RecipeType smithing recipe id, places template/base/addition, shift-clicks result.",
                params(
                        param("recipe_id", "string", "Smithing recipe id (upgrade or trim), as in /recipe give.", true)
                )));
        arr.add(fn("stonecutter_cut",
                "Stonecutter: OPEN GUI, pass stonecutter recipe id; deposits matching input from inventory, selects recipe, takes output.",
                params(
                        param("recipe_id", "string", "Stonecutter recipe id, e.g. minecraft:stone_bricks_from_stone_stonecutting.", true)
                )));
        arr.add(fn("anvil_combine",
                "Anvil: OPEN GUI. Places left item and optional right item (materials), optional rename string, shift-clicks result.",
                params(
                        param("left_item_id", "string", "Left / first slot item id.", true),
                        param("right_item_id", "string", "Optional second slot item id.", false),
                        param("new_name", "string", "Optional rename text (omit if not renaming).", false)
                )));
        arr.add(fn("brewing_load_stand",
                "Brewing stand: OPEN GUI. Puts blaze powder in fuel slot if empty, ingredient in ingredient slot, water bottles "
                        + "into empty potion columns (0–2). Does not wait for brew ticks.",
                params(
                        param("ingredient_item_id", "string", "Item id for top ingredient slot (e.g. minecraft:nether_wart).", true)
                )));
        arr.add(fn("brewing_brew",
                "Brewing stand: OPEN GUI. Loads the stand, waits for the brew to finish, and optionally shift-clicks bottles out.",
                params(
                        param("ingredient_item_id", "string", "Item id for top ingredient slot (e.g. minecraft:nether_wart).", true),
                        param("max_wait_seconds", "integer", "Wait cap for brew completion (default 90, max 600).", false),
                        param("collect", "boolean", "Whether to shift-click bottle slots after brewing (default true).", false)
                )));
        arr.add(fn("set_goal_plan",
                "For #goal plan mode: set the side-HUD todo plan before doing actions. Use 3-8 concrete steps.",
                params(
                        param("steps", "array", "Todo steps to show on the HUD.", true, "string")
                )));
        arr.add(fn("update_goal_status",
                "Update the side-HUD with the current short status.",
                params(
                        param("status", "string", "Short current status.", true)
                )));
        arr.add(fn("complete_goal_step",
                "Mark a one-based side-HUD plan step complete and optionally update status.",
                params(
                        param("step_index", "integer", "One-based step index to mark complete.", true),
                        param("status", "string", "Optional short current status.", false)
                )));
        arr.add(fn("mission_enqueue",
                "Queue another independent AI mission to run after the current mission finishes.",
                params(
                        param("goal", "string", "Natural-language mission to queue.", true),
                        param("plan_mode", "boolean", "Whether the queued mission should use the side-HUD plan mode (default true).", false)
                )));
        arr.add(fn("mission_status",
                "Show the active mission and pending mission queue.",
                params()));
        arr.add(fn("mission_pause",
                "Pause the mission queue so pending missions do not auto-start. The current agent continues its current turn.",
                params()));
        arr.add(fn("mission_resume",
                "Resume the mission queue and start the next pending mission if no agent is active.",
                params()));
        arr.add(fn("mission_retry",
                "Queue a retry of the last finished mission.",
                params()));
        arr.add(fn("memory_remember",
                "Persist a useful fact for future missions, such as base location, chest contents, player preference, or resource spot.",
                params(
                        param("key", "string", "Stable short key, e.g. base, wood_chest, no_explore_preference.", true),
                        param("value", "string", "Fact to remember.", true),
                        param("category", "string", "Optional category, e.g. location, preference, resource, warning.", false),
                        param("include_position", "boolean", "Attach the current dimension and player block position (default false).", false)
                )));
        arr.add(fn("memory_recall",
                "Recall saved mission memory and optionally recent checkpoints.",
                params(
                        param("query", "string", "Optional search text for key/value/category/dimension.", false),
                        param("category", "string", "Optional exact category filter.", false),
                        param("include_checkpoints", "boolean", "Whether to include recent checkpoint matches (default false).", false)
                )));
        arr.add(fn("memory_forget",
                "Forget one saved memory by key.",
                params(
                        param("key", "string", "Memory key to forget.", true)
                )));
        arr.add(fn("memory_checkpoint",
                "Persist an explicit checkpoint for the current mission.",
                params(
                        param("name", "string", "Short checkpoint name.", true),
                        param("detail", "string", "What changed or what was learned.", false),
                        param("status", "string", "Optional status, e.g. ok, blocked, warning.", false)
                )));
        arr.add(fn("stop",
                "Cancel all current Baritone tasks and pathing.",
                params()));
        arr.add(fn("wait_until_idle",
                "Wait until Baritone is idle: not pathing, no path goal, and mine/farm/explore processes are inactive. "
                        + "Use after mine/goto/farm/explore. timeout_seconds=0 or omit = unlimited (until ai stop).",
                params(
                        param("timeout_seconds", "integer",
                                "0 or omit = wait until idle indefinitely (still respects ai stop). Positive = cap in seconds.",
                                false)
                )));
        arr.add(fn("get_state",
                "Return a snapshot: position, dimension, health, hunger, hotbar, inventory_totals, ender_chest_totals, "
                        + "has_wooden_pickaxe, has_wooden_axe, pathing flag, goal, mine/farm/explore active, legit_mine setting.",
                params()));
        arr.add(fn("say",
                "Print a short status message to the player's chat (visible only to them).",
                params(
                        param("message", "string", "Text to display.", true)
                )));
        arr.add(fn("done",
                "Signal that the original goal is complete or impossible. Pass a final summary. "
                        + "After calling this the agent loop terminates.",
                params(
                        param("summary", "string", "Final message for the player.", true)
                )));
        return arr;
    }

    /**
     * Execute a tool call. Returns the textual result that will be appended to
     * the conversation as a {@code tool} message.
     */
    public ToolResult execute(String name, JsonObject args) {
        try {
            switch (name) {
                case "goto_coords":
                    return ok(gotoCoords(args));
                case "goto_block":
                    return ok(gotoBlock(args));
                case "mine":
                    return ok(mine(args));
                case "mine_logs_then_make_wood_tool":
                    return ok(mineLogsThenMakeWoodTool(args));
                case "follow_player":
                    return ok(followPlayer(args));
                case "farm":
                    return ok(runRaw("farm"));
                case "explore":
                    if (forbidExplore) {
                        return ok("ERROR: This goal explicitly says not to explore. Use mine/goto/farm/open_station/craft tools instead.");
                    }
                    return ok(runRaw("explore"));
                case "build_schematic":
                    return ok(buildSchematic(args));
                case "set_setting":
                    return ok(setSetting(args));
                case "list_settings":
                    return ok(listSettings(args));
                case "get_setting":
                    return ok(getSetting(args));
                case "reset_setting":
                    return ok(resetSetting(args));
                case "get_ender_chest":
                    return ok(AiCrafting.onClient(ctx, this::getEnderChestOnClient));
                case "open_station":
                    return ok(openStation(args));
                case "equip_item":
                    return ok(AiCrafting.equipItem(ctx, args.get("item_id").getAsString()));
                case "right_click":
                    return ok(AiCrafting.rightClick(ctx));
                case "use_item_on_block": {
                    if (args.has("item_id") && !args.get("item_id").isJsonNull()
                            && !args.get("item_id").getAsString().isBlank()) {
                        String equip = AiCrafting.equipItem(ctx, args.get("item_id").getAsString());
                        if (equip.startsWith("ERROR:")) {
                            return ok(equip);
                        }
                    }
                    int radius = (args.has("max_radius") && !args.get("max_radius").isJsonNull())
                            ? args.get("max_radius").getAsInt() : 6;
                    return ok(AiCrafting.useItemOnBlock(ctx, args.get("block_id").getAsString(), radius));
                }
                case "run_command":
                    return ok(runRawArg(args));
                case "close_inventory_screens":
                    return ok(AiCrafting.closeForeignContainers(ctx));
                case "make_wood_tool_from_logs": {
                    try {
                        String rid = parseWoodToolRecipeId(args);
                        return ok(AiCrafting.makeWoodToolFromLogs(ctx, rid));
                    } catch (IllegalArgumentException e) {
                        return err(e.getMessage());
                    }
                }
                case "craft_planks_from_logs": {
                    int maxLogs = (args.has("max_logs") && !args.get("max_logs").isJsonNull())
                            ? args.get("max_logs").getAsInt() : 8;
                    return ok(AiCrafting.craftPlanksFromLogs(ctx, Math.max(1, Math.min(64, maxLogs))));
                }
                case "craft_crafting_table":
                    return ok(AiCrafting.craftCraftingTable(ctx));
                case "craft_sticks": {
                    int sets = (args.has("sets") && !args.get("sets").isJsonNull())
                            ? args.get("sets").getAsInt() : 1;
                    return ok(AiCrafting.craftSticks(ctx, Math.max(1, Math.min(32, sets))));
                }
                case "craft_wooden_axe_at_table":
                    return ok(AiCrafting.craftWoodenAxeAtTable(ctx));
                case "craft_wooden_pickaxe_at_table":
                    return ok(AiCrafting.craftWoodenPickaxeAtTable(ctx));
                case "craft_shaped_at_table": {
                    JsonArray g = args.getAsJsonArray("grid");
                    if (g == null || g.size() != 9) {
                        return err("grid must be a JSON array of exactly 9 strings (use \"\" or \"air\" for empty).");
                    }
                    String[] nine = new String[9];
                    for (int i = 0; i < 9; i++) {
                        nine[i] = g.get(i).getAsString();
                    }
                    return ok(AiCrafting.craftShapedAtTable(ctx, nine));
                }
                case "craft_recipe_at_table":
                    return ok(AiCrafting.craftRecipeAtTable(ctx, args.get("recipe_id").getAsString()));
                case "list_craftable_table_recipes": {
                    int max = (args.has("max_entries") && !args.get("max_entries").isJsonNull())
                            ? args.get("max_entries").getAsInt() : 50;
                    String filter = (args.has("filter") && !args.get("filter").isJsonNull())
                            ? args.get("filter").getAsString() : "";
                    return ok(AiCrafting.listCraftableTableRecipes(ctx, max, filter));
                }
                case "list_crafting_recipes_for_output": {
                    int max = (args.has("max_entries") && !args.get("max_entries").isJsonNull())
                            ? args.get("max_entries").getAsInt() : 50;
                    return ok(AiCrafting.listCraftingRecipesForOutput(
                            ctx,
                            args.get("output_item_id").getAsString(),
                            max));
                }
                case "craft_item": {
                    int qty = (args.has("quantity") && !args.get("quantity").isJsonNull())
                            ? args.get("quantity").getAsInt() : 1;
                    return ok(AiCrafting.craftItemByOutput(ctx, args.get("output_item_id").getAsString(), qty));
                }
                case "craft_recipe_in_inventory":
                    return ok(AiCrafting.craftRecipeInInventory(ctx, args.get("recipe_id").getAsString()));
                case "craft_shaped_in_inventory": {
                    JsonArray g = args.getAsJsonArray("grid");
                    if (g == null || g.size() != 4) {
                        return err("grid must be a JSON array of exactly 4 strings.");
                    }
                    String[] four = new String[4];
                    for (int i = 0; i < 4; i++) {
                        four[i] = g.get(i).getAsString();
                    }
                    return ok(AiCrafting.craftShapedInInventory(ctx, four));
                }
                case "furnace_smelt": {
                    String input = args.get("input_item_id").getAsString();
                    String fuel = (args.has("fuel_item_id") && !args.get("fuel_item_id").isJsonNull())
                            ? args.get("fuel_item_id").getAsString() : "";
                    String rec = (args.has("recipe_id") && !args.get("recipe_id").isJsonNull())
                            ? args.get("recipe_id").getAsString() : "";
                    int wait = (args.has("max_wait_seconds") && !args.get("max_wait_seconds").isJsonNull())
                            ? args.get("max_wait_seconds").getAsInt() : 90;
                    int qty = (args.has("quantity") && !args.get("quantity").isJsonNull())
                            ? args.get("quantity").getAsInt() : 1;
                    return ok(AiCrafting.furnaceSmeltMany(ctx, input, fuel, rec, wait, qty));
                }
                case "smithing_recipe":
                    return ok(AiCrafting.smithingRecipe(ctx, args.get("recipe_id").getAsString()));
                case "stonecutter_cut":
                    return ok(AiCrafting.stonecutterCut(ctx, args.get("recipe_id").getAsString()));
                case "anvil_combine": {
                    String left = args.get("left_item_id").getAsString();
                    String right = (args.has("right_item_id") && !args.get("right_item_id").isJsonNull())
                            ? args.get("right_item_id").getAsString() : "";
                    String renameTo = (args.has("new_name") && !args.get("new_name").isJsonNull())
                            ? args.get("new_name").getAsString() : "";
                    return ok(AiCrafting.anvilCombine(ctx, left, right, renameTo));
                }
                case "brewing_load_stand":
                    return ok(AiCrafting.brewingLoadStand(ctx, args.get("ingredient_item_id").getAsString()));
                case "brewing_brew": {
                    int wait = (args.has("max_wait_seconds") && !args.get("max_wait_seconds").isJsonNull())
                            ? args.get("max_wait_seconds").getAsInt() : 90;
                    boolean collect = !args.has("collect") || args.get("collect").isJsonNull()
                            || args.get("collect").getAsBoolean();
                    return ok(AiCrafting.brewingBrewAndCollect(ctx, args.get("ingredient_item_id").getAsString(), wait, collect));
                }
                case "set_goal_plan": {
                    JsonArray stepsJson = args.getAsJsonArray("steps");
                    List<String> steps = new ArrayList<>();
                    if (stepsJson != null) {
                        for (int i = 0; i < stepsJson.size(); i++) {
                            steps.add(stepsJson.get(i).getAsString());
                        }
                    }
                    GoalTracker.setPlan(steps);
                    StringBuilder msg = new StringBuilder("Goal plan set with ").append(steps.size()).append(" step(s).");
                    for (int i = 0; i < steps.size(); i++) {
                        msg.append("\n").append(i + 1).append(". ").append(steps.get(i));
                    }
                    return ok(msg.toString());
                }
                case "update_goal_status":
                    GoalTracker.setStatus(args.get("status").getAsString());
                    return ok("Goal status updated.");
                case "complete_goal_step": {
                    int step = args.get("step_index").getAsInt();
                    String status = (args.has("status") && !args.get("status").isJsonNull())
                            ? args.get("status").getAsString() : "";
                    GoalTracker.completeStep(step, status);
                    return ok("Goal step " + step + " marked complete.");
                }
                case "mission_enqueue":
                    return ok(missionEnqueue(args));
                case "mission_status":
                    return ok(MissionQueue.describe());
                case "mission_pause":
                    MissionQueue.pause();
                    GoalTracker.setStatus("Mission queue paused");
                    return ok("Mission queue paused. Active mission will continue; pending missions will wait.");
                case "mission_resume":
                    AiCommand.resumeMissionQueue(baritone, new ChatLog());
                    return ok(MissionQueue.describe());
                case "mission_retry":
                    return ok(AiCommand.retryLastMission(baritone, new ChatLog()));
                case "memory_remember":
                    return ok(memoryRemember(args));
                case "memory_recall":
                    return ok(memoryRecall(args));
                case "memory_forget": {
                    String key = args.get("key").getAsString();
                    boolean removed = MissionMemory.forget(key);
                    return ok(removed ? "Forgot memory: " + key : "No memory found for key: " + key);
                }
                case "memory_checkpoint": {
                    String checkpointName = args.get("name").getAsString();
                    String detail = (args.has("detail") && !args.get("detail").isJsonNull())
                            ? args.get("detail").getAsString() : "";
                    String status = (args.has("status") && !args.get("status").isJsonNull())
                            ? args.get("status").getAsString() : "ok";
                    GoalTracker.Snapshot snapshot = GoalTracker.snapshot();
                    MissionMemory.Checkpoint checkpoint = MissionMemory.recordCheckpoint(snapshot.goal, checkpointName, detail, status);
                    return ok("Saved checkpoint #" + checkpoint.id + ": " + checkpoint.name);
                }
                case "stop":
                    cancelBaritoneWork();
                    return ok("Cancelled all Baritone tasks.");
                case "wait_until_idle":
                    return ok(waitUntilIdle(args));
                case "get_state":
                    return ok(getState());
                case "say":
                    return ok(say(args));
                case "done": {
                    String summary = args.has("summary") ? args.get("summary").getAsString() : "Done.";
                    String low = summary.toLowerCase(Locale.ROOT);
                    if (low.contains("attempt") || low.contains("tried") || low.contains("try to")) {
                        return ok("ERROR: Do not call done for an attempt. Verify the requested final state with get_state, then continue or report impossible.");
                    }
                    if (!lastProblem.isEmpty() && !isImpossibleSummary(low)) {
                        return ok("ERROR: Do not call done as successful yet. Previous tool reported a problem: "
                                + lastProblem + ". Continue with get_state and fix it, or call done only if the goal is impossible.");
                    }
                    return ToolResult.done(summary);
                }
                default:
                    return err("Unknown tool: " + name);
            }
        } catch (RuntimeException e) {
            return err(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    // -------- individual tool implementations --------

    private boolean executeCommand(String command) {
        return AiCrafting.onClient(ctx, () -> commands.execute(command));
    }

    private void cancelBaritoneWork() {
        AiCrafting.onClient(ctx, () -> {
            baritone.getMineProcess().cancel();
            baritone.getPathingBehavior().cancelEverything();
            return null;
        });
    }

    private String gotoCoords(JsonObject a) {
        int x = a.get("x").getAsInt();
        int y = a.get("y").getAsInt();
        int z = a.get("z").getAsInt();
        executeCommand(String.format("goto %d %d %d", x, y, z));
        return String.format("Pathing to (%d, %d, %d).", x, y, z);
    }

    private String gotoBlock(JsonObject a) {
        String block = normalizeBlockId(a.get("block").getAsString());
        executeCommand("goto " + block);
        return "Pathing (not mining) toward nearest cached " + block + ".";
    }

    /**
     * Stone + deepslate ore pairs so asking for {@code diamond_ore} still targets deepslate diamond underground.
     */
    private static final String[][] STONE_DEEPSLATE_ORE_PAIRS = {
            {"minecraft:diamond_ore", "minecraft:deepslate_diamond_ore"},
            {"minecraft:emerald_ore", "minecraft:deepslate_emerald_ore"},
            {"minecraft:gold_ore", "minecraft:deepslate_gold_ore"},
            {"minecraft:iron_ore", "minecraft:deepslate_iron_ore"},
            {"minecraft:coal_ore", "minecraft:deepslate_coal_ore"},
            {"minecraft:copper_ore", "minecraft:deepslate_copper_ore"},
            {"minecraft:lapis_ore", "minecraft:deepslate_lapis_ore"},
            {"minecraft:redstone_ore", "minecraft:deepslate_redstone_ore"},
    };

    private static List<String> expandMineBlockIds(JsonArray blocks) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (int i = 0; i < blocks.size(); i++) {
            String id = normalizeBlockId(blocks.get(i).getAsString());
            id = normalizeMineAlias(id);
            if (id.equals("minecraft:log") || id.equals("minecraft:logs") || id.equals("minecraft:tree")
                    || id.equals("#minecraft:logs") || id.equals("#minecraft:logs_that_burn")) {
                for (String log : MINE_LOG_BLOCK_NAMES) {
                    ids.add("minecraft:" + log);
                }
            } else {
                ids.add(id);
            }
        }
        for (String id : new ArrayList<>(ids)) {
            for (String[] pair : STONE_DEEPSLATE_ORE_PAIRS) {
                if (pair[0].equals(id) || pair[1].equals(id)) {
                    ids.add(pair[0]);
                    ids.add(pair[1]);
                    break;
                }
            }
        }
        return new ArrayList<>(ids);
    }

    private static String normalizeMineAlias(String id) {
        if (id == null) {
            return "minecraft:air";
        }
        String s = id.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        if (s.equals("minecraft:jungel_log") || s.equals("minecraft:jungel_logs")
                || s.equals("minecraft:jungle") || s.equals("minecraft:jungle_logs")) {
            return "minecraft:jungle_log";
        }
        if (s.startsWith("minecraft:") && s.endsWith("_logs")) {
            String singular = s.substring(0, s.length() - 1);
            for (String log : MINE_LOG_BLOCK_NAMES) {
                if (singular.equals("minecraft:" + log)) {
                    return singular;
                }
            }
        }
        return s;
    }

    private String mine(JsonObject a) {
        JsonArray blocks = a.getAsJsonArray("blocks");
        if (blocks == null || blocks.size() == 0) {
            return "No blocks specified.";
        }
        List<String> expanded = expandMineBlockIds(blocks);
        StringBuilder cmd = new StringBuilder("mine");
        if (a.has("quantity") && !a.get("quantity").isJsonNull()) {
            int q = a.get("quantity").getAsInt();
            if (q > 0) cmd.append(' ').append(q);
        }
        for (String id : expanded) {
            cmd.append(' ').append(id);
        }
        executeCommand(cmd.toString());
        return "Mining: " + cmd.substring(5).trim()
                + (expanded.size() > blocks.size() ? " (added deepslate/stone ore pair where applicable)" : "");
    }

    /**
     * Mines until {@code total_logs} log items exist in inventory (Baritone quantity semantics), then crafts.
     */
    private String mineLogsThenMakeWoodTool(JsonObject a) {
        final String recipeId;
        try {
            recipeId = parseWoodToolRecipeId(a);
        } catch (IllegalArgumentException e) {
            return "ERROR: " + e.getMessage();
        }
        int target = 6;
        if (a.has("total_logs") && !a.get("total_logs").isJsonNull()) {
            target = Math.min(32, Math.max(4, a.get("total_logs").getAsInt()));
        }

        BaritoneAPI.getSettings().allowInventory.value = true;
        cancelBaritoneWork();

        StringBuilder rep = new StringBuilder();
        int start = countLogsInInventory();
        rep.append("wood_tool_recipe=").append(recipeId).append(". ");
        rep.append("Logs before: ").append(start).append(". Target total: ").append(target).append(". ");

        if (start < target) {
            final int mineTarget = target;
            AiCrafting.onClient(ctx, () -> {
                baritone.getMineProcess().mineByName(mineTarget, MINE_LOG_BLOCK_NAMES);
                return null;
            });
            rep.append("Mining until inventory has ").append(target).append(" logs (auto-stops). ");
            long deadline = System.currentTimeMillis() + 240_000L;
            while (System.currentTimeMillis() < deadline) {
                if (MistralAgent.isCancelled()) {
                    cancelBaritoneWork();
                    return rep + "Cancelled during mining.";
                }
                if (!baritone.getMineProcess().isActive()) {
                    break;
                }
                if (countLogsInInventory() >= target) {
                    break;
                }
                try {
                    Thread.sleep(350);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    AiCrafting.onClient(ctx, () -> {
                        baritone.getMineProcess().cancel();
                        return null;
                    });
                    return rep + "Interrupted during mining.";
                }
            }
            if (baritone.getMineProcess().isActive()) {
                AiCrafting.onClient(ctx, () -> {
                    baritone.getMineProcess().cancel();
                    return null;
                });
                rep.append("(Mine phase timed out; continuing.) ");
            }
            long pathDeadline = System.currentTimeMillis() + 45_000L;
            IPathingBehavior pb = baritone.getPathingBehavior();
            while (System.currentTimeMillis() < pathDeadline) {
                if (MistralAgent.isCancelled()) {
                    return rep + "Cancelled.";
                }
                if (!pb.isPathing() && !pb.getInProgress().isPresent()) {
                    break;
                }
                try {
                    Thread.sleep(300);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return rep + "Interrupted.";
                }
            }
        } else {
            rep.append("Already have enough logs; skipping mine. ");
        }

        int after = countLogsInInventory();
        rep.append("Logs after mine: ").append(after).append(". ");
        if (after < 4) {
            return rep + "ERROR: Need at least 4 logs to craft tools; find trees closer.";
        }

        rep.append(AiCrafting.makeWoodToolFromLogs(ctx, recipeId));
        return rep.toString();
    }

    private static String woodToolArgRaw(JsonObject args) {
        String[] keys = {"tool", "wood_tool", "target_tool", "weapon"};
        for (String k : keys) {
            if (!args.has(k) || args.get(k).isJsonNull()) {
                continue;
            }
            try {
                return args.get(k).getAsString().trim();
            } catch (RuntimeException ignored) {
            }
        }
        return "";
    }

    /**
     * Resolves JSON tool arguments to exactly {@link AiCrafting#RECIPE_WOODEN_PICKAXE} or {@link AiCrafting#RECIPE_WOODEN_AXE}.
     * Never uses a boolean axe flag (too easy to invert or mis-parse).
     */
    private static String parseWoodToolRecipeId(JsonObject args) {
        return parseWoodToolRecipeId(woodToolArgRaw(args));
    }

    private static String parseWoodToolRecipeId(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("tool must not be null.");
        }
        String t = raw.toLowerCase(Locale.US).trim().replace(' ', '_');
        if (t.isEmpty()) {
            throw new IllegalArgumentException("tool must be set (e.g. wooden_pickaxe or wooden_axe).");
        }
        if (AiCrafting.RECIPE_WOODEN_PICKAXE.equals(t) || t.endsWith(":wooden_pickaxe")
                || "wooden_pickaxe".equals(t) || "wood_pickaxe".equals(t)) {
            return AiCrafting.RECIPE_WOODEN_PICKAXE;
        }
        if (AiCrafting.RECIPE_WOODEN_AXE.equals(t) || t.endsWith(":wooden_axe")
                || "wooden_axe".equals(t) || "wood_axe".equals(t)) {
            return AiCrafting.RECIPE_WOODEN_AXE;
        }
        if (t.equals("pic") || t.equals("picax")) {
            return AiCrafting.RECIPE_WOODEN_PICKAXE;
        }
        if (t.equals("pick") || t.contains("pickaxe") || t.contains("pick_") || t.startsWith("pick")) {
            return AiCrafting.RECIPE_WOODEN_PICKAXE;
        }
        if (t.contains("pick")) {
            return AiCrafting.RECIPE_WOODEN_PICKAXE;
        }
        if (t.equals("axe") || t.equals("hatchet")) {
            return AiCrafting.RECIPE_WOODEN_AXE;
        }
        if (t.contains("axe")) {
            return AiCrafting.RECIPE_WOODEN_AXE;
        }
        throw new IllegalArgumentException(
                "Unrecognized tool value '" + raw + "': use wooden_pickaxe / pickaxe / pick, or wooden_axe / axe.");
    }

    private static boolean playerInventoryHas(LocalPlayer p, Item item) {
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            ItemStack st = p.getInventory().getItem(i);
            if (!st.isEmpty() && st.is(item)) {
                return true;
            }
        }
        return false;
    }

    private int countLogsInInventory() {
        return AiCrafting.onClient(ctx, () -> {
            LocalPlayer p = ctx.player();
            if (p == null) {
                return 0;
            }
            int n = 0;
            for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
                ItemStack st = p.getInventory().getItem(i);
                if (!st.isEmpty() && st.is(ItemTags.LOGS)) {
                    n += st.getCount();
                }
            }
            return n;
        });
    }

    private String followPlayer(JsonObject a) {
        String name = a.get("name").getAsString();
        executeCommand("follow player " + name);
        return "Following player " + name + ".";
    }

    private String buildSchematic(JsonObject a) {
        String name = a.get("name").getAsString();
        StringBuilder cmd = new StringBuilder("build ").append(name);
        if (a.has("x") && a.has("y") && a.has("z")) {
            cmd.append(' ').append(a.get("x").getAsInt())
               .append(' ').append(a.get("y").getAsInt())
               .append(' ').append(a.get("z").getAsInt());
        }
        executeCommand(cmd.toString());
        return "Building schematic " + name + ".";
    }

    private String setSetting(JsonObject a) {
        String name = a.get("name").getAsString().trim();
        String value = a.get("value").getAsString();
        if (isProtectedSetting(name)) {
            return "ERROR: Setting '" + name + "' is protected and cannot be changed by the AI.";
        }
        if (!BaritoneAPI.getSettings().mistralAllowSelfConfig.value) {
            return "ERROR: AI self-config is disabled (mistralAllowSelfConfig=false). Ask the player to enable it.";
        }
        Settings settings = BaritoneAPI.getSettings();
        Settings.Setting<?> setting = findSetting(name);
        if (setting == null) {
            return "ERROR: Unknown setting '" + name + "'. Use list_settings to search valid names.";
        }
        return AiCrafting.onClient(ctx, () -> {
            try {
                SettingsUtil.parseAndApply(settings, setting.getName(), value);
            } catch (Exception e) {
                return "ERROR: Could not set " + setting.getName() + " to '" + value + "': "
                        + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            }
            return "Set " + setting.getName() + " = " + safeValue(setting) + ".";
        });
    }

    private String getSetting(JsonObject a) {
        Settings.Setting<?> setting = findSetting(a.get("name").getAsString());
        if (setting == null) {
            return "ERROR: Unknown setting '" + a.get("name").getAsString().trim()
                    + "'. Use list_settings to search valid names.";
        }
        return describeSetting(setting);
    }

    private String resetSetting(JsonObject a) {
        String name = a.get("name").getAsString().trim();
        if (isProtectedSetting(name)) {
            return "ERROR: Setting '" + name + "' is protected and cannot be reset by the AI.";
        }
        if (!BaritoneAPI.getSettings().mistralAllowSelfConfig.value) {
            return "ERROR: AI self-config is disabled (mistralAllowSelfConfig=false). Ask the player to enable it.";
        }
        Settings.Setting<?> setting = findSetting(name);
        if (setting == null) {
            return "ERROR: Unknown setting '" + name + "'. Use list_settings to search valid names.";
        }
        return AiCrafting.onClient(ctx, () -> {
            setting.reset();
            return "Reset " + setting.getName() + " to default = " + safeValue(setting) + ".";
        });
    }

    private String listSettings(JsonObject a) {
        String filter = (a.has("filter") && !a.get("filter").isJsonNull())
                ? a.get("filter").getAsString().trim().toLowerCase(Locale.ROOT) : "";
        Settings settings = BaritoneAPI.getSettings();
        StringBuilder out = new StringBuilder();
        final int cap = 40;
        int shown = 0;
        if (filter.isEmpty()) {
            List<Settings.Setting> modified = SettingsUtil.modifiedSettings(settings);
            out.append("Settings changed from default (").append(modified.size())
                    .append("); pass a filter substring to search all ").append(settings.allSettings.size())
                    .append(" settings:");
            for (Settings.Setting<?> setting : modified) {
                if (shown >= cap) {
                    out.append("\n... ").append(modified.size() - shown).append(" more");
                    break;
                }
                out.append("\n- ").append(describeSetting(setting));
                shown++;
            }
            if (modified.isEmpty()) {
                out.append("\n(all settings are at their defaults)");
            }
            return out.toString();
        }
        out.append("Settings matching '").append(filter).append("':");
        int total = 0;
        for (Settings.Setting<?> setting : settings.allSettings) {
            if (!setting.getName().toLowerCase(Locale.ROOT).contains(filter)) {
                continue;
            }
            total++;
            if (shown < cap) {
                out.append("\n- ").append(describeSetting(setting));
                shown++;
            }
        }
        if (total == 0) {
            out.append("\n(no settings match; try a shorter substring)");
        } else if (total > shown) {
            out.append("\n... ").append(total - shown).append(" more matches; refine the filter");
        }
        return out.toString();
    }

    private String getEnderChestOnClient() {
        LocalPlayer p = ctx.player();
        JsonObject s = new JsonObject();
        if (p == null) {
            s.addProperty("error", "Player not in world");
            return s.toString();
        }
        JsonObject totals = enderChestTotals(p);
        s.add("ender_chest_totals", totals);
        s.addProperty("note", totals.size() == 0
                ? "Ender chest is empty or its contents are not yet known to the client; open an ender chest once to sync."
                : "Last-known ender chest contents (open an ender chest to refresh).");
        return s.toString();
    }

    private static JsonObject enderChestTotals(LocalPlayer p) {
        JsonObject inv = new JsonObject();
        try {
            Container ender = p.getEnderChestInventory();
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (int i = 0; i < ender.getContainerSize(); i++) {
                ItemStack stack = ender.getItem(i);
                if (stack == null || stack.isEmpty()) {
                    continue;
                }
                counts.merge(stack.getItem().toString(), stack.getCount(), Integer::sum);
            }
            for (Map.Entry<String, Integer> e : counts.entrySet()) {
                inv.addProperty(e.getKey(), e.getValue());
            }
        } catch (RuntimeException ignored) {
        }
        return inv;
    }

    static boolean isProtectedSetting(String name) {
        if (name == null) {
            return false;
        }
        return name.trim().toLowerCase(Locale.ROOT).equals("mistralapikey");
    }

    private static Settings.Setting<?> findSetting(String name) {
        if (name == null) {
            return null;
        }
        return BaritoneAPI.getSettings().byLowerName.get(name.trim().toLowerCase(Locale.ROOT));
    }

    private static String describeSetting(Settings.Setting<?> setting) {
        if (isProtectedSetting(setting.getName())) {
            return setting.getName() + " = (hidden) [protected]";
        }
        String type;
        try {
            type = SettingsUtil.settingTypeToString(setting);
        } catch (RuntimeException e) {
            type = "?";
        }
        return setting.getName() + " = " + safeValue(setting)
                + " (type " + type + ", default " + safeDefault(setting) + ")";
    }

    private static String safeValue(Settings.Setting<?> setting) {
        if (isProtectedSetting(setting.getName())) {
            return "(hidden)";
        }
        if (SettingsUtil.javaOnlySetting(setting)) {
            return "(java-only)";
        }
        try {
            return SettingsUtil.settingValueToString(setting);
        } catch (RuntimeException e) {
            return String.valueOf(setting.value);
        }
    }

    private static String safeDefault(Settings.Setting<?> setting) {
        if (SettingsUtil.javaOnlySetting(setting)) {
            return "(java-only)";
        }
        try {
            return SettingsUtil.settingDefaultToString(setting);
        } catch (RuntimeException e) {
            return String.valueOf(setting.defaultValue);
        }
    }

    private String openStation(JsonObject a) {
        StationInfo station = stationInfo(a.get("station").getAsString());
        if (station == null) {
            return "ERROR: Unsupported station. Use crafting_table, furnace, blast_furnace, smoker, brewing_stand, "
                    + "stonecutter, smithing_table, or anvil.";
        }
        if (menuMatchesOnClient(station)) {
            return "Already open: " + station.displayName + ".";
        }
        if (station.kind == StationKind.CRAFTING_TABLE) {
            String local = AiCrafting.openNearbyOrPlaceCraftingTable(ctx);
            if (local.startsWith("Opened") || local.startsWith("Already")) {
                return local;
            }
        }
        int seconds = (a.has("max_wait_seconds") && !a.get("max_wait_seconds").isJsonNull())
                ? Math.min(600, Math.max(1, a.get("max_wait_seconds").getAsInt())) : 90;

        Settings settings = BaritoneAPI.getSettings();
        boolean prevRightClick = settings.rightClickContainerOnArrival.value;
        settings.rightClickContainerOnArrival.value = true;
        try {
            boolean started = executeCommand("goto " + station.blockId);
            if (!started) {
                return "ERROR: Could not start goto for " + station.blockId + ".";
            }
            long deadline = System.currentTimeMillis() + seconds * 1000L;
            while (System.currentTimeMillis() < deadline) {
                if (MistralAgent.isCancelled()) {
                    return "Cancelled while opening " + station.displayName + ".";
                }
                if (menuMatchesOnClient(station)) {
                    return "Opened " + station.displayName + " (" + station.blockId + ").";
                }
                try {
                    Thread.sleep(250);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return "Interrupted while opening " + station.displayName + ".";
                }
            }
            return "TIMEOUT: Did not open " + station.displayName + " after " + seconds + "s.";
        } finally {
            settings.rightClickContainerOnArrival.value = prevRightClick;
        }
    }

    private String runRawArg(JsonObject a) {
        String cmd = a.get("command").getAsString();
        return runRaw(cmd);
    }

    private String runRaw(String cmd) {
        String trimmed = cmd.trim();
        String low = trimmed.toLowerCase(java.util.Locale.US);
        if (low.startsWith("craft ") || low.equals("craft")) {
            return "ERROR: There is no Baritone 'craft' command. Use mine_logs_then_make_wood_tool for axe/pick from trees, "
                    + "make_wood_tool_from_logs if you already have logs, or craft_* tools.";
        }
        boolean ok = executeCommand(trimmed);
        return ok ? "Ran: " + trimmed : "Command failed: " + trimmed;
    }

    private String waitUntilIdle(JsonObject a) {
        int seconds;
        if (!a.has("timeout_seconds") || a.get("timeout_seconds").isJsonNull()
                || a.get("timeout_seconds").getAsInt() <= 0) {
            seconds = 0;
        } else {
            seconds = Math.min(86400, Math.max(1, a.get("timeout_seconds").getAsInt()));
        }
        IPathingBehavior pb = baritone.getPathingBehavior();
        long deadline = seconds == 0 ? Long.MAX_VALUE : System.currentTimeMillis() + seconds * 1000L;
        long start = System.currentTimeMillis();
        try {
            Thread.sleep(300);
            while (System.currentTimeMillis() < deadline) {
                if (MistralAgent.isCancelled()) {
                    return "Wait cancelled (ai stop).";
                }
                if (!pb.isPathing() && !pb.getInProgress().isPresent() && pb.getGoal() == null
                        && !baritoneProcessesBusy()) {
                    return "Idle after " + ((System.currentTimeMillis() - start) / 1000) + "s.";
                }
                Thread.sleep(400);
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return "Wait interrupted.";
        }
        return "Timed out after " + seconds + "s; still working (goal="
                + safeGoal() + ", pathing=" + pb.isPathing()
                + ", mine=" + baritone.getMineProcess().isActive()
                + ", farm=" + baritone.getFarmProcess().isActive()
                + ", explore=" + baritone.getExploreProcess().isActive() + ").";
    }

    private String getState() {
        return AiCrafting.onClient(ctx, this::getStateOnClient);
    }

    private String getStateOnClient() {
        JsonObject s = new JsonObject();
        LocalPlayer p = ctx.player();
        if (p == null) {
            s.addProperty("error", "Player not in world");
            return s.toString();
        }
        BetterBlockPos feet = ctx.playerFeet();
        s.addProperty("position", feet.x + "," + feet.y + "," + feet.z);
        s.addProperty("yaw", Math.round(p.getYRot() * 10.0f) / 10.0f);
        s.addProperty("pitch", Math.round(p.getXRot() * 10.0f) / 10.0f);
        try {
            s.addProperty("dimension", p.level().dimension().identifier().toString());
        } catch (RuntimeException ignored) {}
        s.addProperty("health", p.getHealth());
        s.addProperty("food", p.getFoodData().getFoodLevel());
        s.addProperty("xp_level", p.experienceLevel);

        // hotbar summary
        JsonArray hotbar = new JsonArray();
        try {
            for (int i = 0; i < 9; i++) {
                ItemStack stack = p.getInventory().getItem(i);
                if (stack != null && !stack.isEmpty()) {
                    JsonObject slot = new JsonObject();
                    slot.addProperty("slot", i);
                    slot.addProperty("item", stack.getItem().toString());
                    slot.addProperty("count", stack.getCount());
                    hotbar.add(slot);
                }
            }
        } catch (RuntimeException ignored) {}
        s.add("hotbar", hotbar);

        // inventory (non-hotbar) item counts
        Map<String, Integer> counts = new LinkedHashMap<>();
        try {
            for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
                ItemStack stack = p.getInventory().getItem(i);
                if (stack == null || stack.isEmpty()) continue;
                counts.merge(stack.getItem().toString(), stack.getCount(), Integer::sum);
            }
        } catch (RuntimeException ignored) {}
        JsonObject inv = new JsonObject();
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            inv.addProperty(e.getKey(), e.getValue());
        }
        s.add("inventory_totals", inv);
        s.add("ender_chest_totals", enderChestTotals(p));

        try {
            s.addProperty("has_wooden_pickaxe", playerInventoryHas(p, Items.WOODEN_PICKAXE));
            s.addProperty("has_wooden_axe", playerInventoryHas(p, Items.WOODEN_AXE));
        } catch (RuntimeException ignored) {
        }

        IPathingBehavior pb = baritone.getPathingBehavior();
        s.addProperty("is_pathing", pb.isPathing());
        s.addProperty("goal", safeGoal());
        s.addProperty("mine_process_active", baritone.getMineProcess().isActive());
        s.addProperty("farm_process_active", baritone.getFarmProcess().isActive());
        s.addProperty("explore_process_active", baritone.getExploreProcess().isActive());
        try {
            s.addProperty("legit_mine", BaritoneAPI.getSettings().legitMine.value);
            String provider = BaritoneAPI.getSettings().aiProvider.value;
            s.addProperty("ai_provider", provider);
            s.addProperty("ai_model", "ollama".equalsIgnoreCase(provider)
                    ? BaritoneAPI.getSettings().ollamaModel.value
                    : BaritoneAPI.getSettings().mistralModel.value);
        } catch (RuntimeException ignored) {
        }
        try {
            s.addProperty("mission_memory_summary", MissionMemory.summaryForPrompt());
        } catch (RuntimeException ignored) {
            s.addProperty("mission_memory_summary", "unavailable");
        }
        return s.toString();
    }

    /** True while mine/farm/explore still want control (pathing can pause between segments). */
    private boolean baritoneProcessesBusy() {
        try {
            return baritone.getMineProcess().isActive()
                    || baritone.getFarmProcess().isActive()
                    || baritone.getExploreProcess().isActive();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private String say(JsonObject a) {
        String msg = a.get("message").getAsString();
        new ChatLog().log("[AI] " + msg);
        return "Said: " + msg;
    }

    private String missionEnqueue(JsonObject a) {
        String goal = a.get("goal").getAsString();
        boolean planMode = !a.has("plan_mode") || a.get("plan_mode").isJsonNull() || a.get("plan_mode").getAsBoolean();
        MissionQueue.Mission mission = MissionQueue.enqueue(goal, planMode, "mission tool");
        GoalTracker.setStatus("Queued mission #" + mission.id);
        return "Queued mission #" + mission.id + " (" + MissionQueue.snapshot().pending.size()
                + " pending): " + mission.goal;
    }

    private String memoryRemember(JsonObject a) {
        String key = a.get("key").getAsString();
        String value = a.get("value").getAsString();
        String category = (a.has("category") && !a.get("category").isJsonNull())
                ? a.get("category").getAsString() : "general";
        boolean includePosition = a.has("include_position") && !a.get("include_position").isJsonNull()
                && a.get("include_position").getAsBoolean();
        MissionMemory.MemoryRecord memory = MissionMemory.remember(key, value, category, "agent",
                includePosition ? currentMemoryLocation() : null);
        return "Saved memory " + memory.key + " [" + memory.category + "].";
    }

    private String memoryRecall(JsonObject a) {
        String query = (a.has("query") && !a.get("query").isJsonNull()) ? a.get("query").getAsString() : "";
        String category = (a.has("category") && !a.get("category").isJsonNull()) ? a.get("category").getAsString() : "";
        boolean includeCheckpoints = a.has("include_checkpoints") && !a.get("include_checkpoints").isJsonNull()
                && a.get("include_checkpoints").getAsBoolean();
        return MissionMemory.recall(query, category, includeCheckpoints);
    }

    private MissionMemory.Location currentMemoryLocation() {
        try {
            return AiCrafting.onClient(ctx, () -> {
                BetterBlockPos feet = ctx.playerFeet();
                String dimension = ctx.player().level().dimension().identifier().toString();
                return new MissionMemory.Location(dimension, feet.x, feet.y, feet.z);
            });
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void scopeMissionMemory() {
        Path file = worldMemoryFile();
        if (file != null) {
            MissionMemory.useStorageFile(file);
        }
    }

    private Path worldMemoryFile() {
        try {
            if (!(ctx.worldData() instanceof WorldData)) {
                return null;
            }
            Path dimensionDir = ((WorldData) ctx.worldData()).directory;
            Path namespaceDir = dimensionDir == null ? null : dimensionDir.getParent();
            Path worldDir = namespaceDir == null ? dimensionDir : namespaceDir.getParent();
            Path baseDir = worldDir == null ? dimensionDir : worldDir;
            return baseDir == null ? null : baseDir.resolve("mission-memory.json");
        } catch (RuntimeException e) {
            return null;
        }
    }

    // -------- helpers --------

    private String safeGoal() {
        try {
            return String.valueOf(baritone.getPathingBehavior().getGoal());
        } catch (RuntimeException e) {
            return "null";
        }
    }

    private static String normalizeBlockId(String id) {
        if (id == null) return "minecraft:air";
        String s = id.trim().toLowerCase();
        if (!s.contains(":")) s = "minecraft:" + s;
        return s;
    }

    private boolean menuMatchesOnClient(StationInfo station) {
        return Boolean.TRUE.equals(AiCrafting.onClient(ctx, () -> {
            if (station.kind == StationKind.CRAFTING_TABLE) {
                return ctx.player().containerMenu instanceof CraftingMenu;
            }
            if (station.kind == StationKind.FURNACE) {
                return ctx.player().containerMenu instanceof AbstractFurnaceMenu;
            }
            if (station.kind == StationKind.BREWING_STAND) {
                return ctx.player().containerMenu instanceof BrewingStandMenu;
            }
            if (station.kind == StationKind.STONECUTTER) {
                return ctx.player().containerMenu instanceof StonecutterMenu;
            }
            if (station.kind == StationKind.SMITHING_TABLE) {
                return ctx.player().containerMenu instanceof SmithingMenu;
            }
            if (station.kind == StationKind.ANVIL) {
                return ctx.player().containerMenu instanceof AnvilMenu;
            }
            return false;
        }));
    }

    private static StationInfo stationInfo(String raw) {
        String s = raw == null ? "" : raw.trim().toLowerCase(Locale.US).replace(' ', '_');
        if (s.startsWith("minecraft:")) {
            s = s.substring("minecraft:".length());
        }
        if (s.equals("table") || s.equals("crafting") || s.equals("crafting_table")) {
            return new StationInfo("minecraft:crafting_table", "crafting table", StationKind.CRAFTING_TABLE);
        }
        if (s.equals("furnace")) {
            return new StationInfo("minecraft:furnace", "furnace", StationKind.FURNACE);
        }
        if (s.equals("blast_furnace") || s.equals("blast")) {
            return new StationInfo("minecraft:blast_furnace", "blast furnace", StationKind.FURNACE);
        }
        if (s.equals("smoker")) {
            return new StationInfo("minecraft:smoker", "smoker", StationKind.FURNACE);
        }
        if (s.equals("brewing") || s.equals("brewing_stand")) {
            return new StationInfo("minecraft:brewing_stand", "brewing stand", StationKind.BREWING_STAND);
        }
        if (s.equals("stonecutter") || s.equals("stone_cutter")) {
            return new StationInfo("minecraft:stonecutter", "stonecutter", StationKind.STONECUTTER);
        }
        if (s.equals("smithing") || s.equals("smithing_table")) {
            return new StationInfo("minecraft:smithing_table", "smithing table", StationKind.SMITHING_TABLE);
        }
        if (s.equals("anvil") || s.equals("chipped_anvil") || s.equals("damaged_anvil")) {
            return new StationInfo("minecraft:" + s, "anvil", StationKind.ANVIL);
        }
        return null;
    }

    private enum StationKind {
        CRAFTING_TABLE,
        FURNACE,
        BREWING_STAND,
        STONECUTTER,
        SMITHING_TABLE,
        ANVIL
    }

    private static final class StationInfo {
        final String blockId;
        final String displayName;
        final StationKind kind;

        StationInfo(String blockId, String displayName, StationKind kind) {
            this.blockId = blockId;
            this.displayName = displayName;
            this.kind = kind;
        }
    }

    private static JsonObject fn(String name, String desc, JsonObject parameters) {
        JsonObject tool = new JsonObject();
        tool.addProperty("type", "function");
        JsonObject f = new JsonObject();
        f.addProperty("name", name);
        f.addProperty("description", desc);
        f.add("parameters", parameters);
        tool.add("function", f);
        return tool;
    }

    private static JsonObject params(JsonObject... fields) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject props = new JsonObject();
        JsonArray required = new JsonArray();
        for (JsonObject p : fields) {
            String pname = p.get("__name").getAsString();
            boolean req = p.get("__required").getAsBoolean();
            p.remove("__name");
            p.remove("__required");
            props.add(pname, p);
            if (req) required.add(pname);
        }
        schema.add("properties", props);
        schema.add("required", required);
        schema.addProperty("additionalProperties", false);
        return schema;
    }

    private static JsonObject param(String name, String type, String desc, boolean required) {
        return param(name, type, desc, required, null);
    }

    private static JsonObject param(String name, String type, String desc, boolean required, String arrayItemType) {
        JsonObject p = new JsonObject();
        p.addProperty("__name", name);
        p.addProperty("__required", required);
        p.addProperty("type", type);
        p.addProperty("description", desc);
        if ("array".equals(type) && arrayItemType != null) {
            JsonObject items = new JsonObject();
            items.addProperty("type", arrayItemType);
            p.add("items", items);
        }
        return p;
    }

    private static ToolResult ok(String s) {
        ToolResult r = new ToolResult();
        r.content = s;
        return r;
    }

    private static ToolResult err(String s) {
        ToolResult r = new ToolResult();
        r.content = "ERROR: " + s;
        r.error = true;
        return r;
    }

    private static boolean looksLikeProblem(String content) {
        String low = content.toLowerCase(Locale.ROOT);
        return low.contains("error:")
                || low.contains("warn:")
                || low.contains("timeout:")
                || low.contains("could not")
                || low.contains("not detected")
                || low.contains("did not")
                || low.contains("failed")
                || low.contains("issues")
                || low.contains("missing ")
                || low.contains("no crafting result");
    }

    private static boolean looksLikeSuccessfulProgress(String toolName, String content) {
        if ("get_state".equals(toolName) || "set_goal_plan".equals(toolName)
                || "update_goal_status".equals(toolName) || "complete_goal_step".equals(toolName)
                || "say".equals(toolName)) {
            return false;
        }
        String low = content.toLowerCase(Locale.ROOT);
        return low.startsWith("opened ")
                || low.startsWith("already open")
                || low.startsWith("placed ")
                || low.startsWith("crafted ")
                || low.startsWith("used ")
                || low.startsWith("equipped ")
                || low.contains(" crafted ")
                || low.contains("crafting table gui")
                || low.contains("ran command")
                || low.contains("pathing");
    }

    private static boolean isImpossibleSummary(String lowSummary) {
        return lowSummary.contains("impossible")
                || lowSummary.contains("cannot")
                || lowSummary.contains("can't")
                || lowSummary.contains("not possible")
                || lowSummary.contains("blocked")
                || lowSummary.contains("unable");
    }

    private static String truncateForPrompt(String s, int n) {
        if (s == null) {
            return "";
        }
        String oneLine = s.replace('\n', ' ').replace('\r', ' ').trim();
        return oneLine.length() <= n ? oneLine : oneLine.substring(0, Math.max(0, n - 3)) + "...";
    }

    /** Result of executing a tool. */
    public static final class ToolResult {
        public String content;
        public boolean error;
        public boolean done;

        public static ToolResult done(String content) {
            ToolResult r = new ToolResult();
            r.content = content;
            r.done = true;
            return r;
        }
    }

    /** Internal helper that defers to Baritone's chat logging via the Helper interface. */
    private static final class ChatLog implements baritone.api.utils.Helper {
        void log(String s) { logDirect(s); }
    }
}
