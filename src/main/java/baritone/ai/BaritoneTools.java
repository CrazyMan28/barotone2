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

import baritone.ai.planner.PlannerStore;
import baritone.ai.planner.StateSnapshot;
import baritone.ai.planner.ToolTiers;
import baritone.ai.reflex.Detectors;
import baritone.process.ReflexProcess;
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
import baritone.command.defaults.UndercoverCommand;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
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
    private volatile boolean planDisplayLocked;
    private volatile String lastProblem = "";
    /** Last `mine ...` command issued, so the wait loop can relocate + re-issue it when mining
     *  gets stuck (e.g. an ice/ocean biome with no reachable stone). */
    private volatile String lastMineCommand;
    private static final long MINE_STUCK_MS = 30_000L;   // no movement + no item gain this long = stuck
    private static final int MINE_MAX_RELOCATES = 5;     // give up after this many moves

    public BaritoneTools(IBaritone baritone) {
        this.baritone = baritone;
        this.ctx = baritone.getPlayerContext();
        this.commands = baritone.getCommandManager();
        scopeMissionMemory();
    }

    public void setForbidExplore(boolean forbidExplore) {
        this.forbidExplore = forbidExplore;
    }

    /** When true (hierarchical-planner sub-agents), the plan-display tools become no-ops:
     *  the planner owns the HUD/launcher checkbox list and a sub-agent must not clobber it. */
    public void setPlanDisplayLocked(boolean locked) {
        this.planDisplayLocked = locked;
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
                "Mine and break blocks (digs ores, logs, etc.) and STOP at quantity. THIS is the tool for 'mine N logs' "
                        + "or 'get N <block>': e.g. mine(['minecraft:log'], 67) gathers 67 logs and stops — it does NOT craft anything. "
                        + "Call it ONCE, then wait_until_idle — calling mine again while it runs does nothing but tell you to wait. "
                        + "For diamond (and other overworld ores), pass the stone ore id: deepslate variants are added automatically.",
                params(
                        param("blocks", "array",
                                "Block ids to mine, e.g. ['minecraft:diamond_ore'] (deepslate_diamond_ore is auto-included).",
                                true, "string"),
                        param("quantity", "integer",
                                "How many MORE to mine (it gathers this many additional, even if you already have some — so "
                                        + "'mine 2 more cobblestone' when you have 6 correctly ends at 8). Omitted defaults to 32. "
                                        + "Mining stone yields cobblestone; mining an ore yields its raw item.", false)
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
        arr.add(fn("tune",
                "PREFERRED when the player describes how Baritone should move/aim/break in plain English. Pass the "
                        + "player's words verbatim; deterministic matching applies the right setting cluster and saves it. "
                        + "Understands: 'head not turning / won't break blocks / fix aim', 'stealthy/undercover/legit', "
                        + "'smoother look', 'snappy aim', 'break faster/slower', 'allow breaking / don't break'. "
                        + "Returns exactly what changed, or a help text if nothing matched (then use set_setting).",
                params(
                        param("request", "string", "The player's tuning request in their own words.", true)
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
                        + "their defaults. With a filter substring, searches every setting's NAME and DOCUMENTATION "
                        + "(e.g. 'break', 'look', 'sprint'), returning each match with a short doc snippet.",
                params(
                        param("filter", "string", "Optional substring to search names and docs for, e.g. 'break'.", false)
                )));
        arr.add(fn("get_setting",
                "Get one setting's full documentation, current value, type, and default value.",
                params(
                        param("name", "string", "Setting name, e.g. allowBreak.", true)
                )));
        arr.add(fn("reset_setting",
                "Reset one setting back to its default value.",
                params(
                        param("name", "string", "Setting name to reset.", true)
                )));
        arr.add(fn("get_ender_chest",
                "Open the player's ender chest and read its REAL contents as item -> count. Opens a reachable ender "
                        + "chest, or PLACES one from inventory if none is nearby, then looks inside. Falls back to last-known "
                        + "contents if it cannot open or place one. Use before planning crafting that relies on stored items.",
                params()));
        arr.add(fn("open_station",
                "Open a station GUI. Opens a reachable one within 6 blocks, ELSE places one from your inventory right next "
                        + "to you and opens that (so it's never far away). Only travels to a distant cached station if you "
                        + "have none nearby and no item to place. Supported: crafting_table, furnace, blast_furnace, smoker, "
                        + "brewing_stand, stonecutter, smithing_table, anvil. (Have the station's item in inventory for the "
                        + "place-here path.)",
                params(
                        param("station", "string", "Station type to open.", true),
                        param("max_wait_seconds", "integer", "Wait cap for pathing/opening (default 90, max 600).", false)
                )));
        arr.add(fn("equip_item",
                "Equip/select an item from inventory or hotbar into the selected hotbar slot.",
                params(
                        param("item_id", "string", "Item id, e.g. minecraft:bucket or diamond_pickaxe.", true)
                )));
        arr.add(fn("equip_armor",
                "PUT ON armor. Reads your inventory and wears the best helmet/chestplate/leggings/boots you own "
                        + "(only upgrades — won't downgrade what you already wear). Call this after crafting/finding armor; "
                        + "get_state's armor_equipped shows what is currently worn.",
                params()));
        arr.add(fn("right_click",
                "Right-click with the currently held item, using the current crosshair target if there is one.",
                params()));
        arr.add(fn("find_entities",
                "Scan for nearby entities (villagers, traders, mobs, animals, players) and list each with its "
                        + "type id, name, position and distance. Use this to locate NPCs before interacting or attacking. "
                        + "Optionally filter by a type/name substring (e.g. 'villager', 'trader').",
                params(
                        param("filter", "string",
                                "Optional case-insensitive substring matched against type id OR name "
                                        + "(e.g. 'villager', 'wandering', 'cow'). Omit to list everything nearby.", false),
                        param("max_radius", "integer", "Search radius in blocks (default 24, max 64).", false)
                )));
        arr.add(fn("interact_entity",
                "Walk to the nearest matching entity and RIGHT-CLICK it (opens villager/trader trades, etc.). "
                        + "Match by type id and/or name. For attacking instead, the survival reflexes handle hostiles; "
                        + "use run_command if you need a different action.",
                params(
                        param("entity_type", "string",
                                "Entity type id or substring, e.g. 'minecraft:villager', 'villager', 'wandering_trader' "
                                        + "(optional if name given).", false),
                        param("entity_name", "string",
                                "Display name / name-tag substring to match, case-insensitive (optional if type given).", false),
                        param("max_wait_seconds", "integer",
                                "Cap for walking to the entity (default 90, max 300).", false)
                )));
        arr.add(fn("hunt",
                "Hunt food animals for meat: finds the nearest cow/pig/chicken/sheep/rabbit (or a specific one), "
                        + "walks to it, ATTACKS until it dies, collects the dropped raw meat, and repeats until it has "
                        + "killed `quantity` animals or none are left nearby. THIS is how you 'get food' from animals — "
                        + "then cook the raw meat with furnace_smelt and call eat. Equip a sword first (equip_item) for faster kills.",
                params(
                        param("animal", "string",
                                "Which food animal: 'cow','pig','chicken','sheep','rabbit', or 'any' (default any nearby food animal).", false),
                        param("quantity", "integer", "How many animals to kill (default 3, max 20).", false),
                        param("max_wait_seconds", "integer", "Overall time cap (default 120, max 300).", false)
                )));
        arr.add(fn("eat",
                "Eat food NOW to refill the hunger bar: selects the best edible food in your inventory, eats it, and "
                        + "repeats until full (food=20) or you run out. Use after cooking meat or when get_state shows low food. "
                        + "Returns what it ate and the resulting food level. (Survival reflexes also auto-eat when starving, "
                        + "but call this to top up on purpose.)",
                params(
                        param("max_items", "integer", "Max food items to eat (default 8). It stops early once food is full.", false)
                )));
        arr.add(fn("use_item_on_block",
                "Equip an optional item, then use the held item on an EXISTING block already in the world within a small "
                        + "radius (e.g. flint_and_steel on a block, bonemeal on crops). NOT for placing/opening a "
                        + "crafting_table/furnace from your inventory — use open_station for that (it places AND opens it).",
                params(
                        param("block_id", "string", "Block id of a block already placed in the world.", true),
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
        arr.add(fn("make_wooden_tool",
                "MAKE a wooden pickaxe or axe FROM SCRATCH, only when you have NONE. Gathers a few logs if needed, then "
                        + "crafts planks, sticks, table, places it and crafts the tool — one call. "
                        + "This is NOT for gathering logs: to get N logs use mine(['minecraft:log'], N). "
                        + "If you already hold any pickaxe/axe (any tier), do NOT call this.",
                params(
                        param("tool", "string",
                                "REQUIRED: exactly minecraft:wooden_pickaxe OR minecraft:wooden_axe (aliases: pickaxe, pick, axe).",
                                true)
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
                "Return a snapshot: position, dimension, health, food + edible_food_count, hotbar, inventory_totals, "
                        + "ender_chest_totals, has_wooden_pickaxe/axe, best_pickaxe/best_axe (tier), time_of_day, "
                        + "ticks_until_night, is_night, light_level, mob_spawn_risk, pathing/goal, mine/farm/explore active, "
                        + "mission_memory_summary (known locations), recent_reflexes. Use time/light/tools/food to plan survival.",
                params()));
        arr.add(fn("look_around",
                "Sense your immediate surroundings BEFORE placing a crafting table/furnace (or when a "
                        + "placement failed). Returns: looking_at (block you're aimed at + how far), "
                        + "block_at_feet, block_below (the floor), headroom (air blocks above your head), "
                        + "standing_on_solid, can_place_station (true if there's an open floored cell with "
                        + "room nearby), and placeable_spot (its x,y,z, or null). If can_place_station is "
                        + "false, goto open ground first instead of trying to place here.",
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
                case "make_wooden_tool":
                case "mine_logs_then_make_wood_tool": // legacy alias
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
                case "tune":
                    return ok(tune(args));
                case "set_setting":
                    return ok(setSetting(args));
                case "list_settings":
                    return ok(listSettings(args));
                case "get_setting":
                    return ok(getSetting(args));
                case "reset_setting":
                    return ok(resetSetting(args));
                case "get_ender_chest":
                    return ok(AiCrafting.openEnderChestAndRead(ctx));
                case "open_station":
                    return ok(openStation(args));
                case "equip_item":
                    return ok(AiCrafting.equipItem(ctx, args.get("item_id").getAsString()));
                case "equip_armor":
                    return ok(AiCrafting.equipBestArmor(ctx));
                case "right_click":
                    return ok(AiCrafting.rightClick(ctx));
                case "find_entities":
                    return ok(findEntities(args));
                case "interact_entity":
                    return ok(interactEntity(args));
                case "hunt":
                    return ok(hunt(args));
                case "eat":
                    return ok(eat(args));
                case "use_item_on_block": {
                    // Redirect station placement: agents wrongly try use_item_on_block to "place" a
                    // crafting_table/furnace from inventory, but that tool only acts on a block ALREADY
                    // in the world (so it loops "no crafting_table found"). open_station places it from
                    // inventory AND opens it — do that instead so the call just works.
                    String blkId = args.has("block_id") && !args.get("block_id").isJsonNull()
                            ? args.get("block_id").getAsString() : "";
                    String itmId = args.has("item_id") && !args.get("item_id").isJsonNull()
                            ? args.get("item_id").getAsString() : "";
                    String station = stationTypeFromId(blkId.isEmpty() ? itmId : blkId);
                    if (station != null) {
                        JsonObject so = new JsonObject();
                        so.addProperty("station", station);
                        return ok("(use_item_on_block on a station -> using open_station) " + openStation(so));
                    }
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
                        // Forgiving: the model often calls this with no logs in hand. If we don't
                        // have enough, gather wood first (the mine+craft path) instead of erroring,
                        // so the wooden-tool bootstrap can't dead-loop on "No logs in inventory".
                        if (countLogsInInventory() < 3) {
                            JsonObject mineArgs = new JsonObject();
                            mineArgs.addProperty("tool", rid);
                            return ok(mineLogsThenMakeWoodTool(mineArgs));
                        }
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
                case "craft_crafting_table": {
                    boolean haveTable = Boolean.TRUE.equals(AiCrafting.onClient(ctx,
                            () -> playerInventoryHas(ctx.player(), Items.CRAFTING_TABLE)));
                    if (haveTable) {
                        return ok("Already have a crafting table in inventory; no craft needed. "
                                + "Use open_station to place/open it.");
                    }
                    return ok(AiCrafting.craftCraftingTable(ctx));
                }
                case "craft_sticks": {
                    int sets = (args.has("sets") && !args.get("sets").isJsonNull())
                            ? args.get("sets").getAsInt() : 1;
                    return ok(AiCrafting.craftSticks(ctx, Math.max(1, Math.min(32, sets))));
                }
                case "craft_wooden_axe_at_table": {
                    String skip = alreadyHaveWoodToolSkip("minecraft:wooden_axe");
                    return ok(skip != null ? skip : AiCrafting.craftWoodenAxeAtTable(ctx));
                }
                case "craft_wooden_pickaxe_at_table": {
                    String skip = alreadyHaveWoodToolSkip("minecraft:wooden_pickaxe");
                    return ok(skip != null ? skip : AiCrafting.craftWoodenPickaxeAtTable(ctx));
                }
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
                case "craft_recipe_at_table": {
                    String rid = args.get("recipe_id").getAsString();
                    String skip = alreadyHaveWoodToolSkip(rid);
                    if (skip != null) {
                        return ok(skip);
                    }
                    String r = AiCrafting.craftRecipeAtTable(ctx, rid);
                    // Tools constantly fail on a missing intermediate (sticks/planks). Auto-supply the
                    // basics from logs/planks and retry ONCE so "no sticks" can't stall a tool craft.
                    // (craftRecipeAtTable already returns an actionable "you still need N more X" message
                    // for the real raw-material shortfall, telling the agent to go mine/smelt it.)
                    if (r != null && (r.contains("Not enough materials") || r.contains("Missing ingredient")
                            || r.contains("Could not take matching"))) {
                        AiCrafting.craftPlanksFromLogs(ctx, 8);
                        AiCrafting.craftSticks(ctx, 2);
                        r = AiCrafting.craftRecipeAtTable(ctx, rid);
                    }
                    return ok(r);
                }
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
                    String outId = args.get("output_item_id").getAsString();
                    String skip = alreadyHaveWoodToolSkip(outId);
                    return ok(skip != null ? skip : AiCrafting.craftItemByOutput(ctx, outId, qty));
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
                    if (planDisplayLocked) {
                        return ok("(plan display is managed by the planner — keep working on your step)");
                    }
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
                    if (planDisplayLocked) {
                        return ok("(plan display is managed by the planner — keep working on your step)");
                    }
                    GoalTracker.setStatus(args.get("status").getAsString());
                    return ok("Goal status updated.");
                case "complete_goal_step": {
                    if (planDisplayLocked) {
                        return ok("(plan display is managed by the planner — keep working on your step)");
                    }
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
                case "look_around":
                    return ok(lookAround());
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
        if (command == null || command.trim().isEmpty()) {
            return false; // never dispatch a blank command
        }
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

    /** A living entity's registry id (e.g. "minecraft:villager") — "unknown" if it can't be resolved. */
    private static String entityTypeId(net.minecraft.world.entity.Entity e) {
        try {
            net.minecraft.resources.Identifier key =
                    net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(e.getType());
            return key != null ? key.toString() : "unknown";
        } catch (RuntimeException ex) {
            return "unknown";
        }
    }

    private static boolean entityMatches(net.minecraft.world.entity.Entity e, String typeNeedle, String nameNeedle) {
        boolean ok = true;
        if (!typeNeedle.isEmpty()) {
            ok = entityTypeId(e).toLowerCase(Locale.ROOT).contains(typeNeedle);
        }
        if (ok && !nameNeedle.isEmpty()) {
            String name = e.getDisplayName() != null ? e.getDisplayName().getString() : "";
            ok = name.toLowerCase(Locale.ROOT).contains(nameNeedle);
        }
        return ok;
    }

    /** List nearby living entities (NPCs/mobs/animals/players) with type, name, position, distance. */
    private String findEntities(JsonObject a) {
        String filter = (a.has("filter") && !a.get("filter").isJsonNull())
                ? a.get("filter").getAsString().trim().toLowerCase(Locale.ROOT) : "";
        int radius = (a.has("max_radius") && !a.get("max_radius").isJsonNull())
                ? Math.min(64, Math.max(1, a.get("max_radius").getAsInt())) : 24;

        JsonObject out = AiCrafting.onClient(ctx, () -> {
            LocalPlayer p = ctx.player();
            if (p == null || ctx.world() == null) {
                return null;
            }
            List<net.minecraft.world.entity.LivingEntity> entities = ctx.world().getEntitiesOfClass(
                    net.minecraft.world.entity.LivingEntity.class,
                    new net.minecraft.world.phys.AABB(p.blockPosition()).inflate(radius),
                    e -> e.isAlive() && e != p && p.distanceTo(e) <= radius);
            entities.sort(java.util.Comparator.comparingDouble(p::distanceTo));

            JsonArray arr = new JsonArray();
            for (net.minecraft.world.entity.LivingEntity e : entities) {
                String type = entityTypeId(e);
                String name = e.getDisplayName() != null ? e.getDisplayName().getString() : type;
                if (!filter.isEmpty()
                        && !type.toLowerCase(Locale.ROOT).contains(filter)
                        && !name.toLowerCase(Locale.ROOT).contains(filter)) {
                    continue;
                }
                JsonObject ent = new JsonObject();
                ent.addProperty("type", type);
                ent.addProperty("name", name);
                ent.addProperty("position", e.blockPosition().getX() + "," + e.blockPosition().getY()
                        + "," + e.blockPosition().getZ());
                ent.addProperty("distance", Math.round(p.distanceTo(e) * 10.0) / 10.0);
                arr.add(ent);
                if (arr.size() >= 30) {
                    break;
                }
            }
            JsonObject o = new JsonObject();
            o.addProperty("count", arr.size());
            o.add("entities", arr);
            return o;
        });

        if (out == null) {
            return "ERROR: Not in a world; cannot scan for entities.";
        }
        if (out.get("count").getAsInt() == 0) {
            return "No" + (filter.isEmpty() ? "" : " matching") + " entities within " + radius + " blocks.";
        }
        return out.toString();
    }

    /** Walk to the nearest matching entity and right-click it (villager/trader trades, etc.). */
    private String interactEntity(JsonObject a) {
        String type = (a.has("entity_type") && !a.get("entity_type").isJsonNull())
                ? a.get("entity_type").getAsString().trim().toLowerCase(Locale.ROOT) : "";
        String name = (a.has("entity_name") && !a.get("entity_name").isJsonNull())
                ? a.get("entity_name").getAsString().trim().toLowerCase(Locale.ROOT) : "";
        int maxWait = (a.has("max_wait_seconds") && !a.get("max_wait_seconds").isJsonNull())
                ? Math.min(300, Math.max(1, a.get("max_wait_seconds").getAsInt())) : 90;
        if (type.isEmpty() && name.isEmpty()) {
            return "ERROR: Provide entity_type and/or entity_name.";
        }

        BaritoneAPI.getSettings().allowInventory.value = true;
        final String fType = type;
        final String fName = name;

        // Find the nearest match (kept as a reference; we re-read its live position each tick).
        net.minecraft.world.entity.LivingEntity target = AiCrafting.onClient(ctx, () -> {
            LocalPlayer p = ctx.player();
            if (p == null || ctx.world() == null) {
                return null;
            }
            return ctx.world().getEntitiesOfClass(
                    net.minecraft.world.entity.LivingEntity.class,
                    new net.minecraft.world.phys.AABB(p.blockPosition()).inflate(48),
                    e -> e.isAlive() && e != p && entityMatches(e, fType, fName))
                    .stream()
                    .min(java.util.Comparator.comparingDouble(p::distanceTo))
                    .orElse(null);
        });
        if (target == null) {
            return "ERROR: No entity found within 48 blocks matching "
                    + (type.isEmpty() ? "" : "type~'" + type + "' ")
                    + (name.isEmpty() ? "" : "name~'" + name + "'") + ". Try find_entities first.";
        }

        final net.minecraft.world.entity.LivingEntity tgt = target;
        String label = AiCrafting.onClient(ctx, () ->
                (tgt.getDisplayName() != null ? tgt.getDisplayName().getString() : entityTypeId(tgt)));

        // Walk to within reach, re-targeting if the entity wanders.
        long deadline = System.currentTimeMillis() + maxWait * 1000L;
        long lastGoal = 0L;
        try {
            while (System.currentTimeMillis() < deadline) {
                if (MistralAgent.isCancelled()) {
                    return "interact_entity to " + label + " cancelled.";
                }
                Double dist = AiCrafting.onClient(ctx, () -> {
                    LocalPlayer p = ctx.player();
                    if (p == null || !tgt.isAlive()) {
                        return -1.0;
                    }
                    return (double) p.distanceTo(tgt);
                });
                if (dist == null || dist < 0) {
                    return "interact_entity: " + label + " is gone (died or unloaded) before reaching it.";
                }
                if (dist <= 3.2) {
                    break;
                }
                // (re)issue the path roughly once a second toward the entity's current block.
                if (System.currentTimeMillis() - lastGoal > 1000L) {
                    lastGoal = System.currentTimeMillis();
                    AiCrafting.onClient(ctx, () -> {
                        baritone.getCustomGoalProcess().setGoalAndPath(
                                new baritone.api.pathing.goals.GoalNear(tgt.blockPosition(), 2));
                        return null;
                    });
                }
                Thread.sleep(300);
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return "interact_entity to " + label + " interrupted.";
        }
        AiCrafting.onClient(ctx, () -> {
            baritone.getCustomGoalProcess().onLostControl();
            return null;
        });

        // Aim at the entity's body, then right-click it (the trade-opening interaction).
        String result = AiCrafting.onClient(ctx, () -> {
            LocalPlayer p = ctx.player();
            if (p == null || !tgt.isAlive()) {
                return "ERROR: Entity gone before interaction.";
            }
            if (p.distanceTo(tgt) > 4.0) {
                return "ERROR: Could not get within reach of " + label + " (still "
                        + Math.round(p.distanceTo(tgt)) + " blocks away).";
            }
            net.minecraft.world.phys.Vec3 aim = tgt.position().add(0, tgt.getBbHeight() * 0.6, 0);
            AiCrafting.visiblyLookAt(ctx, aim, 24);
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            net.minecraft.world.InteractionResult res =
                    mc.gameMode.interact(p, tgt, net.minecraft.world.InteractionHand.MAIN_HAND);
            p.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            return "Right-clicked " + label + " (" + res + ").";
        });
        return result;
    }

    /** Food animals worth hunting for meat (entity type id paths). */
    private static final java.util.Set<String> FOOD_ANIMALS = java.util.Set.of(
            "cow", "pig", "chicken", "sheep", "rabbit", "mooshroom");

    /** Find food animals, walk to each, attack until dead, collect the meat, repeat. */
    private String hunt(JsonObject a) {
        String animal = (a.has("animal") && !a.get("animal").isJsonNull())
                ? a.get("animal").getAsString().trim().toLowerCase(Locale.ROOT) : "";
        if (animal.equals("any") || animal.equals("food") || animal.isEmpty()) {
            animal = "";
        }
        int quantity = (a.has("quantity") && !a.get("quantity").isJsonNull())
                ? Math.min(20, Math.max(1, a.get("quantity").getAsInt())) : 3;
        int maxWait = (a.has("max_wait_seconds") && !a.get("max_wait_seconds").isJsonNull())
                ? Math.min(300, Math.max(10, a.get("max_wait_seconds").getAsInt())) : 120;
        BaritoneAPI.getSettings().allowInventory.value = true;
        final String wanted = animal;
        long deadline = System.currentTimeMillis() + maxWait * 1000L;
        int killed = 0;

        while (killed < quantity && System.currentTimeMillis() < deadline) {
            if (MistralAgent.isCancelled()) {
                return "hunt cancelled after " + killed + " kill(s).";
            }
            // nearest matching live food animal within 48 blocks
            net.minecraft.world.entity.LivingEntity target = AiCrafting.onClient(ctx, () -> {
                LocalPlayer p = ctx.player();
                if (p == null || ctx.world() == null) {
                    return null;
                }
                return ctx.world().getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,
                        new net.minecraft.world.phys.AABB(p.blockPosition()).inflate(48),
                        e -> e.isAlive() && e != p && isHuntableFood(e, wanted))
                        .stream().min(java.util.Comparator.comparingDouble(p::distanceTo)).orElse(null);
            });
            if (target == null) {
                if (killed > 0) {
                    break;
                }
                return "ERROR: No " + (wanted.isEmpty() ? "food animals" : wanted)
                        + " found within 48 blocks. explore() to find a herd, or try a different animal.";
            }
            final net.minecraft.world.entity.LivingEntity tgt = target;
            String label = AiCrafting.onClient(ctx, () -> entityTypeId(tgt));

            // approach, re-pathing toward the (possibly fleeing) animal
            long apprDeadline = Math.min(deadline, System.currentTimeMillis() + 40_000L);
            long lastGoal = 0L;
            boolean reached = false;
            try {
                while (System.currentTimeMillis() < apprDeadline) {
                    if (MistralAgent.isCancelled()) {
                        return "hunt cancelled after " + killed + " kill(s).";
                    }
                    Double dist = AiCrafting.onClient(ctx, () -> {
                        LocalPlayer p = ctx.player();
                        return (p == null || !tgt.isAlive()) ? -1.0 : (double) p.distanceTo(tgt);
                    });
                    if (dist == null || dist < 0) {
                        break; // died/unloaded
                    }
                    if (dist <= 3.0) {
                        reached = true;
                        break;
                    }
                    if (System.currentTimeMillis() - lastGoal > 1000L) {
                        lastGoal = System.currentTimeMillis();
                        AiCrafting.onClient(ctx, () -> {
                            baritone.getCustomGoalProcess().setGoalAndPath(
                                    new baritone.api.pathing.goals.GoalNear(tgt.blockPosition(), 2));
                            return null;
                        });
                    }
                    Thread.sleep(250);
                }
                AiCrafting.onClient(ctx, () -> {
                    baritone.getCustomGoalProcess().onLostControl();
                    return null;
                });
                if (!reached) {
                    continue; // couldn't catch this one; loop picks the next nearest
                }
                // attack until it dies (or we time out / it flees out of reach)
                long killDeadline = Math.min(deadline, System.currentTimeMillis() + 15_000L);
                boolean dead = false;
                while (System.currentTimeMillis() < killDeadline) {
                    if (MistralAgent.isCancelled()) {
                        return "hunt cancelled after " + killed + " kill(s).";
                    }
                    Boolean stillAlive = AiCrafting.onClient(ctx, () -> {
                        LocalPlayer p = ctx.player();
                        if (p == null || !tgt.isAlive()) {
                            return false;
                        }
                        if (p.distanceTo(tgt) > 4.0) {
                            // chase: re-path toward it
                            baritone.getCustomGoalProcess().setGoalAndPath(
                                    new baritone.api.pathing.goals.GoalNear(tgt.blockPosition(), 2));
                            return true;
                        }
                        net.minecraft.world.phys.Vec3 aim = tgt.position().add(0, tgt.getBbHeight() * 0.6, 0);
                        AiCrafting.visiblyLookAt(ctx, aim, 30);
                        net.minecraft.client.Minecraft.getInstance().gameMode.attack(p, tgt);
                        p.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
                        return true;
                    });
                    if (Boolean.FALSE.equals(stillAlive)) {
                        dead = true;
                        break;
                    }
                    Thread.sleep(550); // ~ attack cooldown so each swing lands full damage
                }
                AiCrafting.onClient(ctx, () -> {
                    baritone.getCustomGoalProcess().onLostControl();
                    return null;
                });
                if (dead) {
                    killed++;
                    // walk over the drop spot so Baritone auto-collects the meat
                    AiCrafting.onClient(ctx, () -> {
                        baritone.getCustomGoalProcess().setGoalAndPath(
                                new baritone.api.pathing.goals.GoalNear(ctx.playerFeet(), 1));
                        return null;
                    });
                    Thread.sleep(900);
                    AiCrafting.onClient(ctx, () -> {
                        baritone.getCustomGoalProcess().onLostControl();
                        return null;
                    });
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return "hunt interrupted after " + killed + " kill(s).";
            }
        }
        String inv = AiCrafting.onClient(ctx, this::rawFoodSummaryOnClient);
        return "Hunted " + killed + " animal(s). " + inv
                + (killed > 0 ? " Cook raw meat with furnace_smelt, then call eat." : "");
    }

    private boolean isHuntableFood(net.minecraft.world.entity.LivingEntity e, String wanted) {
        String id = entityTypeId(e);
        String path = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
        if (!wanted.isEmpty()) {
            return path.equals(wanted) || path.contains(wanted);
        }
        return FOOD_ANIMALS.contains(path);
    }

    /** Count raw/cooked meats currently in the inventory, for the hunt report. */
    private String rawFoodSummaryOnClient() {
        LocalPlayer p = ctx.player();
        if (p == null) {
            return "";
        }
        Map<String, Integer> meats = new LinkedHashMap<>();
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            ItemStack st = p.getInventory().getItem(i);
            if (st == null || st.isEmpty()) {
                continue;
            }
            String id = st.getItem().toString();
            if (id.contains("beef") || id.contains("porkchop") || id.contains("chicken")
                    || id.contains("mutton") || id.contains("rabbit") || id.contains("leather")) {
                meats.merge(id, st.getCount(), Integer::sum);
            }
        }
        if (meats.isEmpty()) {
            return "No meat in inventory (drops may have despawned or you missed the kill).";
        }
        StringBuilder sb = new StringBuilder("Inventory now has: ");
        boolean first = true;
        for (Map.Entry<String, Integer> e : meats.entrySet()) {
            if (!first) {
                sb.append(", ");
            }
            sb.append(e.getValue()).append("x ").append(e.getKey());
            first = false;
        }
        return sb.append('.').toString();
    }

    /** Eat the best edible food repeatedly until the hunger bar is full or food runs out. */
    private String eat(JsonObject a) {
        int maxItems = (a.has("max_items") && !a.get("max_items").isJsonNull())
                ? Math.min(36, Math.max(1, a.get("max_items").getAsInt())) : 8;
        BaritoneAPI.getSettings().allowInventory.value = true;
        StringBuilder eaten = new StringBuilder();
        int ateCount = 0;
        int startFood = AiCrafting.onClient(ctx, () -> {
            LocalPlayer p = ctx.player();
            return p == null ? -1 : p.getFoodData().getFoodLevel();
        });
        if (startFood < 0) {
            return "ERROR: Player not in world.";
        }
        if (startFood >= 20) {
            return "Food bar is already full (20/20). No need to eat.";
        }

        try {
            for (int i = 0; i < maxItems; i++) {
                if (MistralAgent.isCancelled()) {
                    break;
                }
                // select the best safe food into the held hotbar slot
                String selected = AiCrafting.onClient(ctx, this::selectBestFoodOnClient);
                if (selected == null) {
                    if (ateCount == 0) {
                        return "ERROR: No edible food in inventory. Hunt animals (hunt) and cook the meat "
                                + "(furnace_smelt), or farm/gather crops first.";
                    }
                    break;
                }
                Integer foodNow = AiCrafting.onClient(ctx, () -> {
                    LocalPlayer p = ctx.player();
                    return p == null ? 20 : p.getFoodData().getFoodLevel();
                });
                if (foodNow != null && foodNow >= 20) {
                    break;
                }
                // Aim at the sky and hold the REAL use key. Vanilla only eats while options.keyUse
                // is physically down (it releases any item-use otherwise), and the input-override
                // right-click can't help because it only fires when the crosshair is on a block.
                AiCrafting.onClient(ctx, () -> {
                    LocalPlayer p = ctx.player();
                    if (p != null) {
                        p.setXRot(-75F); // look up so startUseItem eats instead of interacting
                    }
                    Minecraft.getInstance().options.keyUse.setDown(true);
                    return null;
                });
                Thread.sleep(1900); // eating an item takes ~1.6s; give it a little slack
                AiCrafting.onClient(ctx, () -> {
                    Minecraft.getInstance().options.keyUse.setDown(false);
                    return null;
                });
                ateCount++;
                if (eaten.indexOf(selected) < 0) {
                    eaten.append(eaten.length() == 0 ? "" : ", ").append(selected);
                }
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } finally {
            AiCrafting.onClient(ctx, () -> {
                Minecraft.getInstance().options.keyUse.setDown(false);
                return null;
            });
        }
        int endFood = AiCrafting.onClient(ctx, () -> {
            LocalPlayer p = ctx.player();
            return p == null ? startFood : p.getFoodData().getFoodLevel();
        });
        if (ateCount == 0) {
            return "Did not eat (no usable food).";
        }
        return "Ate " + ateCount + " item(s)" + (eaten.length() > 0 ? " (" + eaten + ")" : "")
                + ". Food: " + startFood + " -> " + endFood + "/20.";
    }

    /** Put the best safe food into the held hotbar slot; returns its id, or null if none. */
    private String selectBestFoodOnClient() {
        LocalPlayer p = ctx.player();
        if (p == null) {
            return null;
        }
        int bestSlot = -1;
        int bestNutrition = -1;
        String bestId = null;
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            ItemStack st = p.getInventory().getItem(i);
            if (st == null || st.isEmpty()) {
                continue;
            }
            net.minecraft.world.food.FoodProperties food =
                    st.get(net.minecraft.core.component.DataComponents.FOOD);
            if (food == null || !Detectors.isSafeFood(st.getItem().toString())) {
                continue;
            }
            if (food.nutrition() > bestNutrition) {
                bestNutrition = food.nutrition();
                bestSlot = i;
                bestId = st.getItem().toString();
            }
        }
        if (bestSlot < 0) {
            return null;
        }
        int held = p.getInventory().getSelectedSlot();
        if (bestSlot < 9) {
            p.getInventory().setSelectedSlot(bestSlot); // already in hotbar
        } else {
            // SWAP it from main inventory into the held hotbar slot
            ctx.playerController().windowClick(p.inventoryMenu.containerId,
                    bestSlot, held, net.minecraft.world.inventory.ClickType.SWAP, p);
        }
        return bestId;
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
        // Cobblestone doesn't occur naturally — it's the DROP from mining stone. Asking Baritone to
        // mine "cobblestone" finds zero blocks (especially in a desert/plains), so ALSO target stone
        // (and deepslate, which drops cobbled_deepslate). This is THE fix for "won't mine down to stone".
        if (ids.contains("minecraft:cobblestone")) {
            ids.add("minecraft:stone");
        }
        if (ids.contains("minecraft:cobbled_deepslate")) {
            ids.add("minecraft:deepslate");
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
        // Validate ids against the real block registry BEFORE claiming success. Without this a
        // hallucinated id (e.g. "minecraft:soulwood") errors in chat while the mission reports
        // "Mining: ..." and finishes having done nothing.
        List<String> unknown = new ArrayList<>();
        for (String id : expanded) {
            net.minecraft.resources.Identifier rl = net.minecraft.resources.Identifier.tryParse(id);
            if (rl == null || net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(rl).isEmpty()) {
                unknown.add(id);
            }
        }
        if (!unknown.isEmpty()) {
            StringBuilder err = new StringBuilder("ERROR: unknown block id(s): ").append(String.join(", ", unknown)).append('.');
            List<String> hints = suggestBlockIds(unknown);
            if (!hints.isEmpty()) {
                err.append(" Did you mean: ").append(String.join(", ", hints)).append('?');
            }
            err.append(" Use real registry ids like minecraft:oak_log or minecraft:diamond_ore.");
            return err.toString();
        }
        int q = (a.has("quantity") && !a.get("quantity").isJsonNull()) ? a.get("quantity").getAsInt() : 0;
        boolean defaultedQuantity = false;
        if (q <= 0) {
            // Unbounded mining is never what an agent wants: seen in the wild, mine(logs) with no
            // quantity blew past 64 logs and never finished. Bound it so the process can complete.
            q = 32;
            defaultedQuantity = true;
        }
        // Count what mining these blocks YIELDS (stone->cobblestone, iron_ore->raw_iron, logs->logs)
        // so the "already have"/top-up logic reflects reality, not the raw block id.
        int have = inventoryCountOf(dropIdsFor(expanded));
        String topUpNote = "";
        if (defaultedQuantity) {
            // No quantity given: treat q as a TOTAL target and skip if we already have a batch.
            if (have >= q) {
                return "Already have " + have + " of " + String.join("/", expanded) + " (>= " + q
                        + " default). Did NOT mine — you already have enough; move to the next step.";
            }
        } else {
            // EXPLICIT quantity means "mine this many MORE". Baritone's mine count is the TOTAL the
            // inventory should reach, so add the current yield: "mine 2 more cobblestone" when you
            // already have 6 must target 8 (not 2, which Baritone treats as already-satisfied and
            // mines nothing). This is the "wouldn't let it mine 2 more" bug.
            int target = have + q;
            topUpNote = " (had " + have + ", mining " + q + " more -> " + target + ")";
            q = target;
        }
        StringBuilder cmd = new StringBuilder("mine");
        cmd.append(' ').append(q);
        for (String id : expanded) {
            cmd.append(' ').append(id);
        }
        // Re-issue guard: calling mine again with the SAME request while the mine process is
        // running RESTARTS it (recalculates paths, drops progress). Seen in the wild as an agent
        // thrash-loop. Point the model at wait_until_idle instead.
        if (cmd.toString().equals(lastMineCommand) && baritone.getMineProcess().isActive()) {
            return "Already mining " + String.join("/", expanded) + " — the process is RUNNING (have "
                    + have + " of " + q + " so far). Did NOT restart it. Call wait_until_idle to let it "
                    + "finish, then get_state to verify the count.";
        }
        // Put the right tool in the HOTBAR first — autoTool only uses the hotbar, so an axe/pickaxe
        // sitting in the main inventory would be ignored and the bot would mine with its bare hand.
        boolean wantAxe = expanded.stream().anyMatch(id ->
                id.contains("log") || id.contains("_wood") || id.contains("stem") || id.contains("bamboo"));
        boolean wantPick = expanded.stream().anyMatch(id ->
                id.contains("stone") || id.contains("ore") || id.contains("cobble") || id.contains("deepslate")
                        || id.contains("obsidian") || id.contains("netherrack") || id.contains("terracotta"));
        if (wantAxe || wantPick) {
            AiCrafting.equipToolsToHotbar(ctx, wantAxe, wantPick);
        }
        lastMineCommand = cmd.toString();   // remembered so wait_until_idle can relocate + retry if stuck
        executeCommand(lastMineCommand);
        return "Mining: " + cmd.substring(5).trim() + topUpNote
                + (expanded.size() > blocks.size() ? " (added deepslate/stone ore pair where applicable)" : "");
    }

    /** The item ids that mining each of these blocks YIELDS (stone->cobblestone, ore->raw_*), so the
     *  mine top-up/idempotency logic counts what you'll actually GET, not the block id. */
    private static java.util.Set<String> dropIdsFor(List<String> blockIds) {
        java.util.Set<String> drops = new java.util.HashSet<>();
        for (String id : blockIds) {
            String p = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
            switch (p) {
                case "stone": drops.add("minecraft:cobblestone"); break;
                case "deepslate": drops.add("minecraft:cobbled_deepslate"); break;
                case "iron_ore": case "deepslate_iron_ore": drops.add("minecraft:raw_iron"); break;
                case "gold_ore": case "deepslate_gold_ore": case "nether_gold_ore": drops.add("minecraft:raw_gold"); break;
                case "copper_ore": case "deepslate_copper_ore": drops.add("minecraft:raw_copper"); break;
                case "coal_ore": case "deepslate_coal_ore": drops.add("minecraft:coal"); break;
                case "diamond_ore": case "deepslate_diamond_ore": drops.add("minecraft:diamond"); break;
                case "emerald_ore": case "deepslate_emerald_ore": drops.add("minecraft:emerald"); break;
                case "redstone_ore": case "deepslate_redstone_ore": drops.add("minecraft:redstone"); break;
                case "lapis_ore": case "deepslate_lapis_ore": drops.add("minecraft:lapis_lazuli"); break;
                default: drops.add(id); break; // logs, cobblestone, dirt, etc. drop themselves
            }
        }
        return drops;
    }

    /** Player feet position read on the client thread (null if not in world). */
    private BetterBlockPos feetSafe() {
        return AiCrafting.onClient(ctx, () -> {
            LocalPlayer p = ctx.player();
            return p == null ? null : ctx.playerFeet();
        });
    }

    /** Total item count across the whole inventory — a cheap "am I still collecting?" progress signal. */
    private int countAllInventoryItems() {
        Integer n = AiCrafting.onClient(ctx, () -> {
            LocalPlayer p = ctx.player();
            if (p == null) {
                return 0;
            }
            int total = 0;
            for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
                total += p.getInventory().getItem(i).getCount();
            }
            return total;
        });
        return n == null ? 0 : n;
    }

    /**
     * Walk to a fresh X,Z a couple dozen blocks away so a stuck mine can find a reachable target.
     * Direction rotates and distance grows with each relocation so we fan out of a bad spot (e.g. a
     * frozen-ocean pocket). The stalled mine is cancelled first; the relocate goal is released after.
     */
    private void relocateForMining(int n) {
        BetterBlockPos pos = feetSafe();
        if (pos == null) {
            return;
        }
        AiCrafting.onClient(ctx, () -> {
            baritone.getMineProcess().cancel();
            return null;
        });
        // Alternate strategies so we escape BOTH common traps:
        //  - DIG DOWN (odd tries): desert/plains where the stone/ore is buried under sand/dirt and
        //    Baritone only paths to *exposed* targets. "goto <y>" tunnels straight down to expose it.
        //  - MOVE SIDEWAYS (even tries): ice/ocean pocket where we must walk to reachable land.
        boolean digDown = (n % 2 == 1);
        final boolean down = digDown;
        final int tx;
        final int tz;
        final int ty;
        String cmd;
        if (digDown) {
            ty = Math.max(8, pos.y - 16 - 8 * ((n - 1) / 2)); // deeper each down-attempt: -16, -24, ...
            tx = pos.x;
            tz = pos.z;
            cmd = "goto " + ty;                       // 1-arg goto = GoalYLevel -> digs a shaft down to stone
        } else {
            int[][] dirs = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}};
            int[] d = dirs[((n / 2) - 1 + dirs.length) % dirs.length];
            int dist = 24 + 8 * n;
            tx = pos.x + d[0] * dist;
            tz = pos.z + d[1] * dist;
            ty = pos.y;
            cmd = "goto " + tx + " " + tz;            // 2-arg goto = GoalXZ -> walk to that column
        }
        executeCommand(cmd);
        long relDeadline = System.currentTimeMillis() + 45_000L; // digging a shaft can take longer than a walk
        IPathingBehavior pb = baritone.getPathingBehavior();
        try {
            while (System.currentTimeMillis() < relDeadline) {
                if (MistralAgent.isCancelled()) {
                    break;
                }
                BetterBlockPos cur = feetSafe();
                if (cur != null) {
                    if (down && cur.y <= ty + 1) {
                        break;                        // reached target depth (stone should be exposed now)
                    }
                    if (!down && Math.abs(cur.x - tx) + Math.abs(cur.z - tz) <= 6) {
                        break;                        // arrived near the relocate target
                    }
                }
                if (!pb.isPathing() && !pb.getInProgress().isPresent()) {
                    break;                            // path finished or itself couldn't progress
                }
                Thread.sleep(400);
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } finally {
            AiCrafting.onClient(ctx, () -> {
                baritone.getCustomGoalProcess().onLostControl();
                baritone.getPathingBehavior().cancelEverything();
                return null;
            });
        }
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

        // Safety net: never craft a wooden tool the player doesn't need. If they
        // already hold a pickaxe/axe of ANY tier, skip the whole mine+craft chain
        // and tell the agent — so "mine some logs" (the agent over-eagerly picking
        // this tool) can't force a pointless wooden pickaxe onto someone holding
        // netherite. The agent decides what to do next from this result.
        boolean wantPick = AiCrafting.RECIPE_WOODEN_PICKAXE.equals(recipeId);
        String existing = existingToolDescription(wantPick);
        if (existing != null) {
            return "Skipped wood-tool craft: you already have " + existing + ". "
                    + "No wooden " + (wantPick ? "pickaxe" : "axe") + " needed. "
                    + "If you only wanted logs, call mine(['minecraft:log'], <count>) instead.";
        }

        // A wooden tool needs only a handful of logs. If the agent asked for many
        // (e.g. "mine 67 logs" mis-routed here), that's a GATHER request, not a
        // make-one-tool request: mine the logs and do NOT craft anything.
        if (a.has("total_logs") && !a.get("total_logs").isJsonNull()
                && a.get("total_logs").getAsInt() > 10) {
            return mineLogsOnly(a.get("total_logs").getAsInt());
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

    /**
     * If the player already holds a pickaxe (or axe) of any tier, return a short
     * human description of the best one; otherwise null. Used to skip redundant
     * wooden-tool crafting (e.g. when the player has a netherite pickaxe).
     */
    private String existingToolDescription(boolean pickaxe) {
        Item[] tiers = pickaxe
                ? new Item[]{Items.NETHERITE_PICKAXE, Items.DIAMOND_PICKAXE, Items.IRON_PICKAXE,
                        Items.GOLDEN_PICKAXE, Items.STONE_PICKAXE, Items.WOODEN_PICKAXE}
                : new Item[]{Items.NETHERITE_AXE, Items.DIAMOND_AXE, Items.IRON_AXE,
                        Items.GOLDEN_AXE, Items.STONE_AXE, Items.WOODEN_AXE};
        return AiCrafting.onClient(ctx, () -> {
            LocalPlayer p = ctx.player();
            if (p == null) {
                return null;
            }
            for (Item tier : tiers) {
                if (playerInventoryHas(p, tier)) {
                    return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(tier).toString();
                }
            }
            return null;
        });
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

    /**
     * Idempotency guard: if {@code id} names a WOODEN pickaxe/axe and the player already holds a
     * pickaxe/axe of ANY tier, return a skip message; else null. Stops the agent re-crafting a wooden
     * tool it already replaced (the "made two wooden pickaxes" regression). Only wooden tools are
     * guarded, so crafting a stone/iron tool — including the iron-axe GOAL — is never blocked.
     */
    private String alreadyHaveWoodToolSkip(String id) {
        if (id == null) {
            return null;
        }
        String low = id.toLowerCase(Locale.ROOT);
        boolean woodPick = low.contains("wooden_pickaxe");
        boolean woodAxe = low.contains("wooden_axe");
        if (!woodPick && !woodAxe) {
            return null;
        }
        String have = existingToolDescription(woodPick);
        return have == null ? null
                : "Skipped: you already have " + have + " — no need to craft a wooden "
                        + (woodPick ? "pickaxe" : "axe") + ". Move on to the next step.";
    }

    /** Total inventory count of items whose registry id is in {@code ids} (client thread). */
    private int inventoryCountOf(java.util.Set<String> ids) {
        Integer n = AiCrafting.onClient(ctx, () -> {
            LocalPlayer p = ctx.player();
            if (p == null) {
                return 0;
            }
            int total = 0;
            for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
                ItemStack st = p.getInventory().getItem(i);
                if (st.isEmpty()) {
                    continue;
                }
                String rid = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(st.getItem()).toString();
                if (ids.contains(rid)) {
                    total += st.getCount();
                }
            }
            return total;
        });
        return n == null ? 0 : n;
    }

    /** Mine up to {@code count} logs and STOP — no crafting. Rescue path for a
     *  gather request the agent mis-routed to the wood-tool tool. */
    private String mineLogsOnly(int count) {
        int target = Math.min(256, Math.max(1, count));
        BaritoneAPI.getSettings().allowInventory.value = true;
        cancelBaritoneWork();
        int start = countLogsInInventory();
        if (start >= target) {
            return "Already have " + start + " logs (>= " + target + " requested); nothing to mine. Did NOT craft a tool.";
        }
        AiCrafting.onClient(ctx, () -> {
            baritone.getMineProcess().mineByName(target, MINE_LOG_BLOCK_NAMES);
            return null;
        });
        long deadline = System.currentTimeMillis() + 300_000L;
        while (System.currentTimeMillis() < deadline) {
            if (MistralAgent.isCancelled()) {
                cancelBaritoneWork();
                return "Cancelled mining at " + countLogsInInventory() + " logs.";
            }
            if (!baritone.getMineProcess().isActive() || countLogsInInventory() >= target) {
                break;
            }
            try {
                Thread.sleep(350);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return "Interrupted mining at " + countLogsInInventory() + " logs.";
            }
        }
        if (baritone.getMineProcess().isActive()) {
            AiCrafting.onClient(ctx, () -> {
                baritone.getMineProcess().cancel();
                return null;
            });
        }
        int after = countLogsInInventory();
        return "Mined logs: now have " + after + " of " + target + " requested. Did NOT craft a tool "
                + "(you asked for logs, not a wooden tool). If you actually want a wooden axe/pick, call make_wood_tool_from_logs.";
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

    private String tune(JsonObject a) {
        String request = a.has("request") && !a.get("request").isJsonNull() ? a.get("request").getAsString() : "";
        if (!BaritoneAPI.getSettings().mistralAllowSelfConfig.value) {
            return "ERROR: AI self-config is disabled (mistralAllowSelfConfig=false). Ask the player to enable it.";
        }
        List<TuneIntents.Intent> intents = TuneIntents.match(request);
        if (intents.isEmpty()) {
            return TuneIntents.help();
        }
        Settings s = BaritoneAPI.getSettings();
        return AiCrafting.onClient(ctx, () -> {
            StringBuilder out = new StringBuilder("Tuned settings:");
            for (TuneIntents.Intent intent : intents) {
                switch (intent) {
                    case FIX_AIM:
                        UndercoverCommand.resetLookSettingsToDefaults(s);
                        s.allowBreak.value = true;
                        out.append("\n- fixed head/aim: look settings reset to defaults (freeLook=")
                                .append(s.freeLook.value).append(", smoothLook=").append(s.smoothLook.value)
                                .append(", strictVisibleBlockInteractions=").append(s.strictVisibleBlockInteractions.value)
                                .append(", blockBreakSpeed=").append(s.blockBreakSpeed.value)
                                .append(") and allowBreak=true");
                        break;
                    case STEALTH:
                        UndercoverCommand.applyUndercoverProfile(s);
                        out.append("\n- stealth: undercover profile applied (smoothLook=").append(s.smoothLook.value)
                                .append(" over ").append(s.smoothLookTicks.value).append(" ticks, blockBreakSpeed=")
                                .append(s.blockBreakSpeed.value).append("); breaking still works");
                        break;
                    case SMOOTH:
                        s.smoothLook.value = true;
                        s.smoothLookTicks.value = 6;
                        s.strictVisibleBlockInteractions.value = false;
                        out.append("\n- smooth look: smoothLook=true over 6 ticks");
                        break;
                    case SNAPPY:
                        s.smoothLook.value = false;
                        s.randomLooking.value = 0D;
                        s.randomLooking113.value = 0D;
                        out.append("\n- snappy aim: smoothLook=false, look jitter off");
                        break;
                    case BREAK_FASTER:
                        s.blockBreakSpeed.value = 1;
                        out.append("\n- break faster: blockBreakSpeed=1");
                        break;
                    case BREAK_SLOWER:
                        s.blockBreakSpeed.value = 12;
                        out.append("\n- break slower: blockBreakSpeed=12");
                        break;
                    case ALLOW_BREAK:
                        s.allowBreak.value = true;
                        out.append("\n- allowBreak=true");
                        break;
                    case NO_BREAK:
                        s.allowBreak.value = false;
                        out.append("\n- allowBreak=false");
                        break;
                    case REFLEX_ON:
                        s.reflexesEnabled.value = true;
                        out.append("\n- survival reflexes ON (auto-eat, flee creepers, fight back, anti-lava, anti-drown)");
                        break;
                    case REFLEX_OFF:
                        s.reflexesEnabled.value = false;
                        out.append("\n- survival reflexes OFF");
                        break;
                }
            }
            try {
                SettingsUtil.save(s);
                out.append("\nSaved (persists across restarts).");
            } catch (Exception e) {
                out.append("\nWARNING: could not save settings to disk: ").append(e.getMessage());
            }
            return out.toString();
        });
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
                // Booleans (allowPlace/allowBreak/allowInventory/...) are set DIRECTLY —
                // SettingsUtil.parseAndApply goes through name reflection that ProGuard can
                // break in the shipped jar, which is what produced "Could not set allowPlace".
                if (setting.value instanceof Boolean) {
                    String v = value.trim().toLowerCase(java.util.Locale.US);
                    Boolean parsed = (v.equals("true") || v.equals("1") || v.equals("yes") || v.equals("on")) ? Boolean.TRUE
                            : (v.equals("false") || v.equals("0") || v.equals("no") || v.equals("off")) ? Boolean.FALSE
                            : null;
                    if (parsed == null) {
                        return "ERROR: " + setting.getName() + " is a true/false setting; got '" + value + "'.";
                    }
                    @SuppressWarnings("unchecked")
                    Settings.Setting<Boolean> bs = (Settings.Setting<Boolean>) setting;
                    bs.value = parsed;
                    return "Set " + setting.getName() + " = " + parsed + ".";
                }
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
        String doc = SettingsDocs.describe(setting.getName());
        return describeSetting(setting) + (doc.isEmpty() ? "" : "\nDocs: " + doc);
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
        out.append("Settings matching '").append(filter).append("' (name or docs):");
        int total = 0;
        for (Settings.Setting<?> setting : settings.allSettings) {
            if (!setting.getName().toLowerCase(Locale.ROOT).contains(filter)
                    && !SettingsDocs.matches(setting.getName(), filter)) {
                continue;
            }
            total++;
            if (shown < cap) {
                out.append("\n- ").append(describeSetting(setting)).append(docSnippet(setting.getName()));
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

    /**
     * Short doc snippet appended to list_settings results; full text comes from get_setting.
     */
    private static String docSnippet(String settingName) {
        String doc = SettingsDocs.describe(settingName);
        if (doc.isEmpty()) {
            return "";
        }
        return " — " + (doc.length() > 100 ? doc.substring(0, 97) + "..." : doc);
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

    /** If an item/block id names a station open_station supports, return its type; else null. */
    private static String stationTypeFromId(String id) {
        if (id == null) {
            return null;
        }
        String p = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
        p = p.trim().toLowerCase(Locale.ROOT);
        switch (p) {
            case "crafting_table":
            case "furnace":
            case "blast_furnace":
            case "smoker":
            case "brewing_stand":
            case "stonecutter":
            case "smithing_table":
            case "anvil":
            case "chipped_anvil":
            case "damaged_anvil":
                return p.endsWith("anvil") ? "anvil" : p;
            default:
                return null;
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
        // Make sure placement is allowed before we try to put a station down.
        BaritoneAPI.getSettings().allowPlace.value = true;

        // Try LOCAL first for every station: open a reachable one, else PLACE one
        // from inventory right next to the player. This is the re-plan/re-make path —
        // we never walk to a far cached station while we can make one here.
        String local;
        if (station.kind == StationKind.CRAFTING_TABLE) {
            local = AiCrafting.openNearbyOrPlaceCraftingTable(ctx);
        } else {
            net.minecraft.world.level.block.Block block = net.minecraft.core.registries.BuiltInRegistries.BLOCK
                    .get(net.minecraft.resources.Identifier.tryParse(station.blockId))
                    .map(net.minecraft.core.Holder.Reference::value).orElse(null);
            net.minecraft.world.item.Item item = block == null ? null : block.asItem();
            if (block == null || item == null || item == net.minecraft.world.item.Items.AIR) {
                local = "WARN: No reachable " + station.displayName + " and no item to place one.";
            } else {
                local = AiCrafting.openNearbyOrPlaceStation(ctx, block, item, station.displayName,
                        () -> menuMatchesOnClient(station));
            }
        }
        if (local.startsWith("Opened") || local.startsWith("Already")) {
            return local;
        }
        // We placed one (or one is nearby) but the GUI didn't open this instant —
        // do NOT path to a distant cached station. Let the agent retry locally.
        if (local.startsWith("Placed")) {
            return local + " Call open_station again to open it.";
        }
        // Only when there is genuinely nothing local (none within reach AND no item to
        // place) do we travel to a known cached station as a last resort.
        boolean noLocalOption = local.contains("no item to place") || local.contains("no crafting table item");

        if (!noLocalOption) {
            // local attempt failed for another reason (e.g. couldn't open GUI after placing) —
            // surface it rather than wandering off to a far block.
            return local;
        }
        // No station nearby and nothing to place. For a crafting table, don't burn 90s
        // walking to a far cached one — tell the agent to make the item first.
        if (station.kind == StationKind.CRAFTING_TABLE) {
            return "ERROR: No crafting table nearby and none in inventory. Craft one first "
                    + "(craft_planks_from_logs, then craft_crafting_table), then call open_station again.";
        }
        // Craftable station (furnace/blast_furnace/smoker/...) with no item to place: CRAFT it from
        // its materials (furnace = 8 cobblestone) and place+open it, instead of wandering to a far one.
        {
            String craft = AiCrafting.craftRecipeAtTable(ctx, station.blockId);
            if (craft.startsWith("Crafted") || craft.contains("crafted=") || craft.contains("result moved to inventory")) {
                net.minecraft.world.level.block.Block sblock = net.minecraft.core.registries.BuiltInRegistries.BLOCK
                        .get(net.minecraft.resources.Identifier.tryParse(station.blockId))
                        .map(net.minecraft.core.Holder.Reference::value).orElse(null);
                net.minecraft.world.item.Item sitem = sblock == null ? null : sblock.asItem();
                if (sblock != null && sitem != null && sitem != net.minecraft.world.item.Items.AIR) {
                    String retry = AiCrafting.openNearbyOrPlaceStation(ctx, sblock, sitem, station.displayName,
                            () -> menuMatchesOnClient(station));
                    if (retry.startsWith("Opened") || retry.startsWith("Already")) {
                        return retry;
                    }
                    if (retry.startsWith("Placed")) {
                        return retry + " Call open_station again to open it.";
                    }
                }
            } else {
                // Couldn't craft it — usually missing the material. Tell the agent what to get.
                return "No " + station.displayName + " nearby/in inventory and couldn't craft one ("
                        + craft + "). A furnace needs 8 cobblestone (mine minecraft:stone). Get the material, "
                        + "then call open_station again.";
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
            return "TIMEOUT: Could not reach a " + station.displayName + " within " + seconds + "s "
                    + "(none nearby). Don't keep retrying open_station — craft and place a fresh "
                    + station.displayName + " here instead (you likely have the materials).";
        } finally {
            settings.rightClickContainerOnArrival.value = prevRightClick;
            // Always stop the goto: a finished OR abandoned far-path must not keep dragging the agent
            // across the map after this call returns (that stranded it 400+ blocks away mid-mission).
            AiCrafting.onClient(ctx, () -> {
                baritone.getCustomGoalProcess().onLostControl();
                baritone.getPathingBehavior().cancelEverything();
                return null;
            });
        }
    }

    private String runRawArg(JsonObject a) {
        String cmd = a.get("command").getAsString();
        return runRaw(cmd);
    }

    private String runRaw(String cmd) {
        String trimmed = cmd == null ? "" : cmd.trim();
        // Never dispatch an empty/blank command — keeps a stray run_command("") from
        // doing anything and avoids confusing "Unknown or incomplete command" noise.
        if (trimmed.isEmpty()) {
            return "ERROR: empty command — nothing to run.";
        }
        String low = trimmed.toLowerCase(java.util.Locale.US);
        if (low.startsWith("craft ") || low.equals("craft")) {
            return "ERROR: There is no Baritone 'craft' command. Use make_wooden_tool for axe/pick from scratch, "
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
        // Stuck-mining watchdog: if a mine is active but the player neither moves nor gains items for
        // MINE_STUCK_MS, it can't reach any target (classic ice/ocean biome: cobblestone is all under
        // water/ice, so Baritone just blacklists "unreachable" forever). Relocate and retry the mine.
        int relocations = 0;
        BetterBlockPos anchor = feetSafe();
        int anchorItems = countAllInventoryItems();
        long anchorTime = System.currentTimeMillis();
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
                // progress = moved horizontally >5 blocks OR picked anything up (resets the stuck timer).
                // Horizontal only, so the anti-drown "surfacing for air" bob doesn't count as progress.
                BetterBlockPos cur = feetSafe();
                int items = countAllInventoryItems();
                boolean moved = cur != null && anchor != null
                        && Math.abs(cur.x - anchor.x) + Math.abs(cur.z - anchor.z) > 5;
                if (moved || items > anchorItems) {
                    anchor = cur;
                    anchorItems = items;
                    anchorTime = System.currentTimeMillis();
                }
                if (lastMineCommand != null && baritone.getMineProcess().isActive()
                        && System.currentTimeMillis() - anchorTime > MINE_STUCK_MS) {
                    if (relocations >= MINE_MAX_RELOCATES) {
                        cancelBaritoneWork();
                        lastMineCommand = null;
                        return "Mining stuck: couldn't reach the target block after relocating "
                                + relocations + "x (tried digging down AND moving sideways). The target may "
                                + "be a block that doesn't occur naturally — to get COBBLESTONE, mine "
                                + "minecraft:stone (it drops cobblestone). Make sure you have a pickaxe, "
                                + "then mine minecraft:stone; or goto a different area.";
                    }
                    relocations++;
                    String resume = lastMineCommand;
                    relocateForMining(relocations);
                    if (MistralAgent.isCancelled()) {
                        return "Wait cancelled (ai stop).";
                    }
                    executeCommand(resume);   // start mining again from the new spot
                    anchor = feetSafe();
                    anchorItems = countAllInventoryItems();
                    anchorTime = System.currentTimeMillis();
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

    /** Move the listed items (a finished mission's deliverables) into the hotbar so they show. */
    public String showcaseInHotbar(java.util.List<String> itemIds) {
        return AiCrafting.arrangeItemsInHotbar(ctx, itemIds);
    }

    /** Put on the best armor the player owns (used by equip_armor + planner completion). */
    public String equipBestArmor() {
        return AiCrafting.equipBestArmor(ctx);
    }

    /**
     * Typed state extraction for the hierarchical planner's criteria verification — the same
     * facts as get_state but filled directly on the client thread, no JSON round-trip. The
     * planner trusts THIS, not the sub-agent's "done" claim.
     */
    public StateSnapshot snapshotForPlanner() {
        return AiCrafting.onClient(ctx, () -> {
            StateSnapshot snap = new StateSnapshot();
            LocalPlayer p = ctx.player();
            if (p == null) {
                return snap;
            }
            snap.x = ctx.playerFeet().x;
            snap.y = ctx.playerFeet().y;
            snap.z = ctx.playerFeet().z;
            try {
                snap.food = p.getFoodData().getFoodLevel();
            } catch (RuntimeException ignored) {}
            try {
                snap.bestPickaxe = bestToolTier(p, true);
                snap.bestAxe = bestToolTier(p, false);
            } catch (RuntimeException ignored) {}
            try {
                for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
                    ItemStack stack = p.getInventory().getItem(i);
                    if (stack == null || stack.isEmpty()) {
                        continue;
                    }
                    snap.inventoryTotals.merge(
                            ToolTiers.strip(itemRegistryId(stack)),
                            stack.getCount(), Integer::sum);
                }
            } catch (RuntimeException ignored) {}
            try {
                putSnapshotArmor(snap, p, net.minecraft.world.entity.EquipmentSlot.HEAD, "head");
                putSnapshotArmor(snap, p, net.minecraft.world.entity.EquipmentSlot.CHEST, "chest");
                putSnapshotArmor(snap, p, net.minecraft.world.entity.EquipmentSlot.LEGS, "legs");
                putSnapshotArmor(snap, p, net.minecraft.world.entity.EquipmentSlot.FEET, "feet");
            } catch (RuntimeException ignored) {}
            try {
                // same source as get_state's known_stations ("crafting_table@10,64,20; furnace@…")
                String stations = MissionMemory.stationsForPrompt();
                if (stations != null && !stations.isEmpty()) {
                    for (String entry : stations.split(";")) {
                        int at = entry.indexOf('@');
                        if (at > 0) {
                            snap.stationTypes.add(ToolTiers.strip(entry.substring(0, at).trim()));
                        }
                    }
                }
            } catch (RuntimeException ignored) {}
            return snap;
        });
    }

    private static void putSnapshotArmor(StateSnapshot snap, LocalPlayer p,
                                         net.minecraft.world.entity.EquipmentSlot slot, String key) {
        ItemStack stack = p.getItemBySlot(slot);
        if (stack != null && !stack.isEmpty()) {
            snap.armorEquipped.put(key, itemRegistryId(stack));
        }
    }

    private static void putWornArmor(JsonObject out, LocalPlayer p,
                                     net.minecraft.world.entity.EquipmentSlot slot, String key) {
        ItemStack stack = p.getItemBySlot(slot);
        if (stack != null && !stack.isEmpty()) {
            out.addProperty(key, itemRegistryId(stack));
        }
    }

    /** Full registry id ("minecraft:iron_pickaxe") for an item stack. */
    private static String itemRegistryId(ItemStack stack) {
        try {
            net.minecraft.resources.Identifier id =
                    net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (id != null) {
                return id.toString();
            }
        } catch (RuntimeException ignored) {}
        return stack.getItem().toString();
    }

    /** Placement-awareness snapshot: what I'm aimed at, my feet/head/floor, headroom, and whether
     *  there's a usable spot to place a station nearby — so the agent checks BEFORE trying to place. */
    private String lookAround() {
        return AiCrafting.onClient(ctx, () -> {
            JsonObject s = new JsonObject();
            LocalPlayer p = ctx.player();
            if (p == null) {
                s.addProperty("error", "Player not in world");
                return s.toString();
            }
            net.minecraft.world.level.Level level = p.level();
            net.minecraft.core.BlockPos feet = ctx.playerFeet();
            // What we're aimed at — manual raycast from the eyes (Minecraft.hitResult is stale on the AI thread).
            net.minecraft.world.phys.HitResult hr = p.pick(5.5D, 0F, false);
            if (hr instanceof net.minecraft.world.phys.BlockHitResult bhr
                    && hr.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) {
                net.minecraft.core.BlockPos lp = bhr.getBlockPos();
                s.addProperty("looking_at", blockIdAt(level, lp));
                s.addProperty("looking_at_pos", lp.getX() + "," + lp.getY() + "," + lp.getZ());
                s.addProperty("looking_at_face", bhr.getDirection().getName());
                double dist = Math.sqrt(p.getEyePosition(1F).distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(lp)));
                s.addProperty("looking_at_distance", Math.round(dist * 10) / 10.0);
            } else {
                s.addProperty("looking_at", "air (nothing solid within 5.5 blocks)");
            }
            s.addProperty("block_at_feet", blockIdAt(level, feet));
            s.addProperty("block_at_head", blockIdAt(level, feet.above()));
            s.addProperty("block_below", blockIdAt(level, feet.below()));
            s.addProperty("standing_on_solid",
                    level.getBlockState(feet.below()).isFaceSturdy(level, feet.below(), net.minecraft.core.Direction.UP));
            int headroom = 0;
            for (int i = 1; i <= 6; i++) {
                if (level.getBlockState(feet.above(i)).isAir()) {
                    headroom++;
                } else {
                    break;
                }
            }
            s.addProperty("headroom", headroom);
            net.minecraft.core.BlockPos spot = AiCrafting.nearestPlaceableSpotOnClient(p, 12);
            s.addProperty("can_place_station", spot != null);
            s.addProperty("placeable_spot", spot == null
                    ? "none within 12 blocks — goto open ground first, then place"
                    : spot.getX() + "," + spot.getY() + "," + spot.getZ());
            return s.toString();
        });
    }

    private static String blockIdAt(net.minecraft.world.level.Level level, net.minecraft.core.BlockPos pos) {
        net.minecraft.world.level.block.state.BlockState st = level.getBlockState(pos);
        if (st.isAir()) {
            return "air";
        }
        return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(st.getBlock()).toString();
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

        // ── situational awareness (for survival progression decisions) ──
        try {
            long dayCycle = p.level().getDayTime() % 24000L;
            String tod;
            long untilNight;
            if (dayCycle < 12000) { tod = dayCycle < 1000 ? "dawn" : "day"; untilNight = 12000 - dayCycle; }
            else if (dayCycle < 13000) { tod = "dusk"; untilNight = 13000 - dayCycle; }
            else if (dayCycle < 23000) { tod = "night"; untilNight = 0; }
            else { tod = "dawn"; untilNight = 24000 - dayCycle + 12000; }
            s.addProperty("time_of_day", tod);
            s.addProperty("ticks_until_night", untilNight);
            s.addProperty("is_night", dayCycle >= 13000 && dayCycle < 23000);
        } catch (RuntimeException ignored) {}
        try {
            int light = p.level().getMaxLocalRawBrightness(p.blockPosition());
            s.addProperty("light_level", light);
            s.addProperty("mob_spawn_risk", light <= 7); // hostiles spawn at light <= 7
        } catch (RuntimeException ignored) {}
        try {
            s.addProperty("best_pickaxe", bestToolTier(p, true));
            s.addProperty("best_axe", bestToolTier(p, false));
        } catch (RuntimeException ignored) {}
        try {
            // worn armor — the planner verifies armor_equipped criteria against this,
            // and the decompose prompt needs to see what is already worn
            JsonObject armor = new JsonObject();
            putWornArmor(armor, p, net.minecraft.world.entity.EquipmentSlot.HEAD, "head");
            putWornArmor(armor, p, net.minecraft.world.entity.EquipmentSlot.CHEST, "chest");
            putWornArmor(armor, p, net.minecraft.world.entity.EquipmentSlot.LEGS, "legs");
            putWornArmor(armor, p, net.minecraft.world.entity.EquipmentSlot.FEET, "feet");
            s.add("armor_equipped", armor);
        } catch (RuntimeException ignored) {}
        try {
            s.addProperty("food_saturation", Math.round(p.getFoodData().getSaturationLevel() * 10f) / 10f);
            int edible = 0;
            for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
                ItemStack st = p.getInventory().getItem(i);
                if (st == null || st.isEmpty()) continue;
                if (st.get(net.minecraft.core.component.DataComponents.FOOD) != null
                        && Detectors.isSafeFood(st.getItem().toString())) {
                    edible += st.getCount();
                }
            }
            s.addProperty("edible_food_count", edible);
        } catch (RuntimeException ignored) {}

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
        try {
            // Homestead: the stations the agent built — open_station returns to these instead of
            // placing/crafting new ones. The agent should reuse them, not re-build.
            s.addProperty("known_stations", MissionMemory.stationsForPrompt());
        } catch (RuntimeException ignored) {
            s.addProperty("known_stations", "");
        }
        try {
            s.addProperty("reflexes_enabled", BaritoneAPI.getSettings().reflexesEnabled.value);
            String liveThreat = ReflexProcess.ACTIVE_STATUS;
            if (liveThreat != null && !"idle".equals(liveThreat)) {
                // a reflex is handling a danger RIGHT NOW — the agent should not fight it for control
                s.addProperty("active_threat", liveThreat);
            }
            List<String> reflexes = ReflexLog.recent(4);
            if (!reflexes.isEmpty()) {
                JsonArray recent = new JsonArray();
                for (String line : reflexes) {
                    recent.add(line);
                }
                s.add("recent_reflexes", recent);
            }
        } catch (RuntimeException ignored) {
        }
        // Autonomously remember valuable ores/structures the agent can currently see,
        // so it builds a map of where things are WITHOUT being told to.
        try {
            autoRememberNearbyResources(p);
        } catch (RuntimeException ignored) {
        }
        return s.toString();
    }

    /** Best pickaxe/axe tier the player is carrying, e.g. "minecraft:iron_pickaxe" or "none". */
    private String bestToolTier(LocalPlayer p, boolean pickaxe) {
        Item[] tiers = pickaxe
                ? new Item[]{Items.NETHERITE_PICKAXE, Items.DIAMOND_PICKAXE, Items.IRON_PICKAXE,
                        Items.GOLDEN_PICKAXE, Items.STONE_PICKAXE, Items.WOODEN_PICKAXE}
                : new Item[]{Items.NETHERITE_AXE, Items.DIAMOND_AXE, Items.IRON_AXE,
                        Items.GOLDEN_AXE, Items.STONE_AXE, Items.WOODEN_AXE};
        for (Item tier : tiers) {
            if (playerInventoryHas(p, tier)) {
                net.minecraft.resources.Identifier id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(tier);
                return id != null ? id.toString() : "unknown";
            }
        }
        return "none";
    }

    /** High-value blocks the agent auto-records the location of when it sees them. */
    private static final String[] NOTABLE_BLOCKS = {
            "diamond_ore", "deepslate_diamond_ore", "ancient_debris",
            "emerald_ore", "deepslate_emerald_ore", "gold_ore", "deepslate_gold_ore",
            "nether_gold_ore", "raw_iron_block"
    };

    /** Scan a small radius for valuable blocks and persist any new finds to mission memory. */
    private void autoRememberNearbyResources(LocalPlayer p) {
        net.minecraft.world.level.Level level = p.level();
        net.minecraft.core.BlockPos feet = p.blockPosition();
        String dim;
        try {
            dim = level.dimension().identifier().toString();
        } catch (RuntimeException e) {
            dim = "";
        }
        int radius = 6;
        int recorded = 0;
        for (int y = -radius; y <= radius && recorded < 4; y++) {
            for (int x = -radius; x <= radius && recorded < 4; x++) {
                for (int z = -radius; z <= radius && recorded < 4; z++) {
                    net.minecraft.core.BlockPos pos = feet.offset(x, y, z);
                    String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK
                            .getKey(level.getBlockState(pos).getBlock()).toString();
                    String path = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
                    boolean notable = false;
                    for (String n : NOTABLE_BLOCKS) {
                        if (path.equals(n)) { notable = true; break; }
                    }
                    if (!notable) continue;
                    // one memory per exact position (re-seeing overwrites, never duplicates)
                    String key = "ore_" + path + "_" + pos.getX() + "_" + pos.getY() + "_" + pos.getZ();
                    try {
                        MissionMemory.rememberLocation(key, "saw " + path + " here",
                                "resource", dim, pos.getX(), pos.getY(), pos.getZ(), "auto");
                        recorded++;
                    } catch (RuntimeException ignored) {
                    }
                }
            }
        }
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

    /** Stamp the current position as "base" once, the first time the agent runs in this world. */
    public void rememberBaseIfUnknown() {
        try {
            for (MissionMemory.MemoryRecord m : MissionMemory.snapshot().memories) {
                if ("base".equals(m.key) && m.hasPosition) {
                    return; // already know where home is
                }
            }
            MissionMemory.Location loc = currentMemoryLocation();
            if (loc != null) {
                MissionMemory.rememberLocation("base", "Starting position for this mission session",
                        "location", loc.dimension, loc.x, loc.y, loc.z, "auto");
            }
        } catch (RuntimeException ignored) {
        }
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
            // the planner's active-plan.json lives beside the per-world mission memory
            PlannerStore.useStorageFile(file.resolveSibling("active-plan.json"));
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
        // Tools signal failure by convention with an "ERROR:" prefix; reflect that in the flag so
        // callers (telemetry, the brain fast path's escalation) see the failure instead of success.
        r.error = s != null && s.startsWith("ERROR:");
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

    /** Fuzzy block-id suggestions for typo'd/hallucinated ids ("soulwood" -> oak_wood, soul_sand...). */
    private static List<String> suggestBlockIds(List<String> unknownIds) {
        List<String> hints = new ArrayList<>();
        for (String id : unknownIds) {
            String path = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
            for (String fragment : path.toLowerCase(Locale.ROOT).split("[_\\s]+")) {
                if (fragment.length() < 3) {
                    continue;
                }
                for (net.minecraft.resources.Identifier key
                        : net.minecraft.core.registries.BuiltInRegistries.BLOCK.keySet()) {
                    if (key.toString().contains(fragment) && !hints.contains(key.toString())) {
                        hints.add(key.toString());
                        if (hints.size() >= 6) {
                            return hints;
                        }
                    }
                }
            }
        }
        return hints;
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
