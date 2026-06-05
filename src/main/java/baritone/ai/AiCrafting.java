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
import baritone.api.utils.IPlayerContext;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.Direction;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.BrewingStandMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.SmithingMenu;
import net.minecraft.world.inventory.StonecutterMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.item.crafting.PlacementInfo;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.item.crafting.SmithingRecipe;
import net.minecraft.world.item.crafting.StonecutterRecipe;

import it.unimi.dsi.fastutil.ints.IntList;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Client-side crafting and station UIs for the AI agent: 2x2 / 3x3 crafting grids, furnace family, smithing, stonecutter,
 * anvil, and basic brewing slot filling — all via vanilla container clicks (same mechanism as
 * {@link baritone.behavior.InventoryBehavior}).
 */
public final class AiCrafting {

    /** Recipe ids for the automated early-game wood tool chain (never use a boolean axe/pick flag). */
    public static final String RECIPE_WOODEN_PICKAXE = "minecraft:wooden_pickaxe";
    public static final String RECIPE_WOODEN_AXE = "minecraft:wooden_axe";

    /** Last crafting table block we placed (for opening GUI). */
    private static volatile BlockPos lastPlacedCraftingTablePos;
    private static volatile BlockPos lastEnderChestPos;

    private static final class JsonCraftingRecipe {
        final Identifier id;
        final String outputItemId;
        final int outputCount;
        final boolean shaped;
        final int width;
        final int height;
        final Ingredient[] shapedGrid;
        final List<Ingredient> shapelessIngredients;

        JsonCraftingRecipe(
                Identifier id,
                String outputItemId,
                int outputCount,
                int width,
                int height,
                Ingredient[] shapedGrid) {
            this.id = id;
            this.outputItemId = outputItemId;
            this.outputCount = outputCount;
            this.shaped = true;
            this.width = width;
            this.height = height;
            this.shapedGrid = shapedGrid;
            this.shapelessIngredients = List.of();
        }

        JsonCraftingRecipe(Identifier id, String outputItemId, int outputCount, List<Ingredient> ingredients) {
            this.id = id;
            this.outputItemId = outputItemId;
            this.outputCount = outputCount;
            this.shaped = false;
            this.width = 0;
            this.height = 0;
            this.shapedGrid = new Ingredient[9];
            this.shapelessIngredients = ingredients;
        }

        boolean needsCraftingTable() {
            return this.shaped ? this.width > 2 || this.height > 2 : this.shapelessIngredients.size() > 4;
        }

        List<Ingredient> flatIngredients() {
            List<Ingredient> out = new ArrayList<>();
            if (this.shaped) {
                for (Ingredient ing : this.shapedGrid) {
                    if (ing != null && !ing.isEmpty()) {
                        out.add(ing);
                    }
                }
            } else {
                for (Ingredient ing : this.shapelessIngredients) {
                    if (ing != null && !ing.isEmpty()) {
                        out.add(ing);
                    }
                }
            }
            return out;
        }
    }

    /**
     * Vanilla survival menus append the standard 36-slot player inventory at the end of {@link AbstractContainerMenu#slots}.
     */
    private static int menuPlayerSlotStart(AbstractContainerMenu menu) {
        if (menu instanceof InventoryMenu) {
            return INV_FIRST_MAIN;
        }
        int n = menu.slots.size();
        return n >= 36 ? n - 36 : 9;
    }

    private static int menuPlayerSlotEndInclusive(AbstractContainerMenu menu) {
        if (menu instanceof InventoryMenu) {
            return INV_LAST_HOTBAR;
        }
        return menu.slots.size() - 1;
    }

    /** InventoryMenu / CraftingMenu slot indices (vanilla survival). */
    private static final int INV_RESULT = 0;
    private static final int INV_CRAFT_1 = 1;
    private static final int INV_CRAFT_2 = 2;
    private static final int INV_CRAFT_3 = 3;
    private static final int INV_CRAFT_4 = 4;
    private static final int INV_FIRST_MAIN = 9;
    private static final int INV_LAST_HOTBAR = 44;

    private AiCrafting() {}

    /**
     * Runs {@code task} on the Minecraft client thread and waits for the result.
     * Must be called from a non-client thread (e.g. the AI worker thread).
     */
    public static <T> T onClient(IPlayerContext ctx, java.util.concurrent.Callable<T> task) {
        Minecraft mc = ctx.minecraft();
        if (mc.isSameThread()) {
            try {
                return task.call();
            } catch (Throwable t) {
                throw new RuntimeException(t.getMessage(), t);
            }
        }
        CompletableFuture<T> f = new CompletableFuture<>();
        mc.execute(() -> {
            try {
                f.complete(task.call());
            } catch (Throwable t) {
                f.completeExceptionally(t);
            }
        });
        try {
            return f.join();
        } catch (Exception e) {
            Throwable c = e.getCause() != null ? e.getCause() : e;
            throw new RuntimeException(c.getMessage(), c);
        }
    }

    private static void sleepAi(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private static void click(IPlayerContext ctx, int slot, ClickType type, int button) {
        LocalPlayer p = ctx.player();
        AbstractContainerMenu menu = p.containerMenu;
        ctx.playerController().windowClick(menu.containerId, slot, button, type, p);
    }

    private static boolean ensurePlayerInventoryMenu(IPlayerContext ctx, LocalPlayer p) {
        if (p.containerMenu instanceof InventoryMenu) {
            return true;
        }
        p.closeContainer();
        sleepAi(80);
        return p.containerMenu instanceof InventoryMenu;
    }

    /** Close chest / furnace etc. so crafting can use player or table menu. */
    public static String closeForeignContainers(IPlayerContext ctx) {
        return onClient(ctx, () -> {
            LocalPlayer p = ctx.player();
            if (!(p.containerMenu instanceof InventoryMenu) && !(p.containerMenu instanceof CraftingMenu)) {
                p.closeContainer();
                return "Closed open container.";
            }
            return "No foreign container (already player inventory or crafting table).";
        });
    }

    /**
     * Craft planks from logs in the 2x2 grid, up to {@code maxLogs} conversions (one log -> four planks each).
     */
    public static String craftPlanksFromLogs(IPlayerContext ctx, int maxLogs) {
        return onClient(ctx, () -> {
            LocalPlayer p = ctx.player();
            if (!ensurePlayerInventoryMenu(ctx, p)) {
                return "ERROR: Could not switch to player inventory for 2x2 crafting. Got: "
                        + p.containerMenu.getClass().getSimpleName();
            }
            int crafted = 0;
            for (int n = 0; n < maxLogs; n++) {
                if (MistralAgent.isCancelled()) {
                    return "Cancelled after " + crafted + " log(s).";
                }
                int logSlot = findLogSlot(p.containerMenu);
                if (logSlot < 0) {
                    return crafted == 0 ? "ERROR: No logs in inventory." : "Crafted planks from " + crafted + " log(s). No more logs.";
                }
                clear2x2(ctx);
                // Pick up one log from inventory
                click(ctx, logSlot, ClickType.PICKUP, 0);
                sleepAi(120);
                // Place one into crafting slot 1 (top-left)
                click(ctx, INV_CRAFT_1, ClickType.PICKUP, 1);
                sleepAi(120);
                // Take result (4 planks)
                click(ctx, INV_RESULT, ClickType.QUICK_MOVE, 0);
                sleepAi(120);
                // Return leftover logs if any on cursor
                if (!p.containerMenu.getCarried().isEmpty()) {
                    click(ctx, logSlot, ClickType.PICKUP, 0);
                    sleepAi(80);
                }
                crafted++;
            }
            return "Crafted planks from " + crafted + " log(s).";
        });
    }

    /** Four planks in 2x2 -> one crafting table item. */
    public static String craftCraftingTable(IPlayerContext ctx) {
        return onClient(ctx, () -> {
            LocalPlayer p = ctx.player();
            if (!ensurePlayerInventoryMenu(ctx, p)) {
                return "ERROR: Could not switch to player inventory menu.";
            }
            if (countItem(p, Items.OAK_PLANKS) < 4 && countTag(p, ItemTags.PLANKS) < 4) {
                return "ERROR: Need at least 4 planks (any wood).";
            }
            clear2x2(ctx);
            if (!placePlanks2x2(ctx)) {
                return "ERROR: Could not arrange four planks.";
            }
            sleepAi(120);
            click(ctx, INV_RESULT, ClickType.QUICK_MOVE, 0);
            sleepAi(120);
            clear2x2(ctx);
            return "Crafting table created (or moved to inventory).";
        });
    }

    /** Two planks vertically in 2x2 -> 4 sticks. */
    public static String craftSticks(IPlayerContext ctx, int sets) {
        return onClient(ctx, () -> {
            LocalPlayer p = ctx.player();
            if (!ensurePlayerInventoryMenu(ctx, p)) {
                return "ERROR: Could not switch to player inventory for sticks.";
            }
            int done = 0;
            for (int i = 0; i < sets; i++) {
                if (MistralAgent.isCancelled()) {
                    return "Cancelled after " + done + " stick craft(s).";
                }
                if (countTag(p, ItemTags.PLANKS) < 2) {
                    return done == 0 ? "ERROR: Need 2+ planks for sticks." : "Crafted " + done + " batch(es) of sticks; out of planks.";
                }
                clear2x2(ctx);
                // top column left: slots 1 and 3
                if (!placeOnePlank(ctx, INV_CRAFT_1)) return "ERROR: plank placement 1 failed.";
                sleepAi(80);
                if (!placeOnePlank(ctx, INV_CRAFT_3)) return "ERROR: plank placement 2 failed.";
                sleepAi(120);
                click(ctx, INV_RESULT, ClickType.QUICK_MOVE, 0);
                sleepAi(120);
                clear2x2(ctx);
                done++;
            }
            return "Crafted sticks (" + done + " recipe(s)).";
        });
    }

    /**
     * Craft a wooden pickaxe in an open crafting table GUI — delegates to the vanilla recipe
     * {@value #RECIPE_WOODEN_PICKAXE} so the result always matches the game recipe book.
     */
    public static String craftWoodenPickaxeAtTable(IPlayerContext ctx) {
        return craftRecipeAtTable(ctx, RECIPE_WOODEN_PICKAXE);
    }

    /**
     * Craft a wooden axe in an open crafting table GUI — delegates to {@value #RECIPE_WOODEN_AXE}.
     */
    public static String craftWoodenAxeAtTable(IPlayerContext ctx) {
        return craftRecipeAtTable(ctx, RECIPE_WOODEN_AXE);
    }

    /**
     * Generic shaped crafting at an open crafting table: {@code grid} is 9 item ids in row-major order
     * (indices 0–2 top row, 3–5 middle, 6–8 bottom). Use {@code ""}, {@code air}, or {@code none} for empty cells.
     * Places one item per non-empty cell from player inventory, then shift-clicks the result.
     */
    public static String craftShapedAtTable(IPlayerContext ctx, String[] grid9) {
        if (grid9 == null || grid9.length != 9) {
            return "ERROR: grid must have exactly 9 entries (3x3 row-major).";
        }
        String ready = ensureCraftingTableOpenForCraft(ctx);
        if (ready.startsWith("ERROR:") || ready.startsWith("WARN:")) {
            return ready;
        }
        String fill = onClient(ctx, () -> {
            LocalPlayer p = ctx.player();
            if (!(p.containerMenu instanceof CraftingMenu menu)) {
                return "ERROR: Could not keep crafting table open.";
            }
            clear3x3(ctx, menu);
            for (int i = 0; i < 9; i++) {
                Ingredient ing = parseGridCellToIngredient(grid9[i]);
                if (ing == null || ing.isEmpty()) {
                    continue;
                }
                int dest = i + 1;
                if (!placeOneIngredientInMenu(ctx, menu, ing, dest)) {
                    clear3x3(ctx, menu);
                    return "ERROR: Could not take matching item for grid cell " + i + ".";
                }
                sleepAi(55);
            }
            return "FILLED";
        });
        if (fill.startsWith("ERROR:")) {
            return fill;
        }
        return finishCraftingTableAfterServerUpdate(ctx,
                "Crafted via 3x3 grid (result moved to inventory).",
                "ERROR: No crafting result (wrong pattern, missing items, lag, or unsupported recipe).");
    }

    /**
     * Craft a vanilla crafting-table recipe by registry id. Tries {@link PlacementInfo} first, then a centered
     * {@link ShapedRecipe} pattern, then a bounded search for {@link ShapelessRecipe}.
     */
    public static String craftRecipeAtTable(IPlayerContext ctx, String recipeIdRaw) {
        Identifier rid = Identifier.tryParse(normalizeNamespacedId(recipeIdRaw));
        if (rid == null) {
            return "ERROR: Bad recipe id.";
        }
        String ready = ensureCraftingTableOpenForCraft(ctx);
        if (ready.startsWith("ERROR:") || ready.startsWith("WARN:")) {
            return ready;
        }
        Optional<RecipeHolder<CraftingRecipe>> holderOpt = onClient(ctx, () -> resolveCraftingHolder(ctx, rid));
        if (holderOpt.isEmpty()) {
            String manual = craftKnownTableRecipeWithoutRecipeManager(ctx, rid.toString());
            if (manual != null) {
                return manual;
            }
            Optional<JsonCraftingRecipe> json = onClient(ctx, () -> findJsonCraftingRecipe(ctx, rid));
            if (json.isPresent()) {
                return craftJsonRecipeAtTable(ctx, json.get());
            }
            return "ERROR: Unknown or non-crafting recipe: " + rid
                    + " (no synced recipe or loaded JSON recipe found).";
        }

        RecipeHolder<CraftingRecipe> holder = holderOpt.get();
        CraftingRecipe recipe = holder.value();

        String placementErr = onClient(ctx, () -> {
            if (!(ctx.player().containerMenu instanceof CraftingMenu menu)) {
                return "ERROR: Could not keep crafting table open.";
            }
            clear3x3(ctx, menu);
            String err = fillGridFromPlacementInfo(ctx, menu, recipe);
            if (err != null && err.startsWith("ERROR:")) {
                clear3x3(ctx, menu);
            }
            return err == null ? "FILLED" : err;
        });
        if (placementErr.startsWith("ERROR:")) {
            return placementErr;
        }
        if ("FILLED".equals(placementErr)) {
            String r = finishCraftingTableAfterServerUpdate(ctx,
                    "Crafted recipe " + rid + " (result moved to inventory).",
                    "MISS");
            if (!"MISS".equals(r)) {
                return r;
            }
        }

        if (recipe instanceof ShapedRecipe shaped) {
            String shapedErr = onClient(ctx, () -> {
                if (!(ctx.player().containerMenu instanceof CraftingMenu menu)) {
                    return "ERROR: Could not keep crafting table open.";
                }
                clear3x3(ctx, menu);
                String err = fillGridFromShapedCentered(ctx, menu, shaped);
                if (err != null && err.startsWith("ERROR:")) {
                    clear3x3(ctx, menu);
                }
                return err == null ? "FILLED" : err;
            });
            if (shapedErr.startsWith("ERROR:")) {
                return shapedErr;
            }
            if ("FILLED".equals(shapedErr)) {
                String r = finishCraftingTableAfterServerUpdate(ctx,
                        "Crafted recipe " + rid + " (shaped pattern fallback).",
                        "MISS");
                if (!"MISS".equals(r)) {
                    return r;
                }
            }
        }

        if (recipe instanceof ShapelessRecipe sl) {
            String slErr = onClient(ctx, () -> {
                if (!(ctx.player().containerMenu instanceof CraftingMenu menu)) {
                    return "ERROR: Could not keep crafting table open.";
                }
                clear3x3(ctx, menu);
                Level level = ctx.player().level();
                List<Ingredient> shapelessIngs = shapelessIngredients(sl);
                String err = fillGridFromShapelessSearch(ctx, menu, level, recipe, shapelessIngs);
                if (err != null && err.startsWith("ERROR:")) {
                    clear3x3(ctx, menu);
                }
                return err == null ? "FILLED" : err;
            });
            if (slErr.startsWith("ERROR:")) {
                return slErr;
            }
            if ("FILLED".equals(slErr)) {
                String r = finishCraftingTableAfterServerUpdate(ctx,
                        "Crafted recipe " + rid + " (shapeless placement search).",
                        "MISS");
                if (!"MISS".equals(r)) {
                    return r;
                }
            }
        }

        return "ERROR: No crafting result for " + rid
                + " (placement, shaped, and shapeless strategies failed or ingredients missing).";
    }

    /**
     * Lists crafting recipes the player inventory can likely supply, capped for LLM context. Open crafting table GUI.
     * {@code filter} matches substring on recipe id (case-insensitive).
     */
    public static String listCraftableTableRecipes(IPlayerContext ctx, int maxEntries, String filterRaw) {
        return onClient(ctx, () -> {
            LocalPlayer p = ctx.player();
            if (!(p.containerMenu instanceof CraftingMenu menu)) {
                return "ERROR: Open a crafting table GUI first.";
            }
            int cap = Math.min(200, Math.max(1, maxEntries));
            String f = filterRaw == null ? "" : filterRaw.trim().toLowerCase(Locale.ROOT);
            List<RecipeHolder<CraftingRecipe>> all = allCraftingRecipes(ctx);
            List<String> placeable = new ArrayList<>();
            List<String> fallback = new ArrayList<>();
            for (RecipeHolder<CraftingRecipe> h : all) {
                if (placeable.size() + fallback.size() >= cap) {
                    break;
                }
                String id = h.id().identifier().toString();
                if (!f.isEmpty() && !id.toLowerCase(Locale.ROOT).contains(f)) {
                    continue;
                }
                if (!inventoryCouldSupplyRecipe(menu, h.value())) {
                    continue;
                }
                if (!h.value().placementInfo().isImpossibleToPlace()) {
                    placeable.add(id);
                } else {
                    fallback.add(id);
                }
            }
            if (all.isEmpty()) {
                for (JsonCraftingRecipe r : jsonCraftingRecipes(ctx)) {
                    if (placeable.size() + fallback.size() >= cap) {
                        break;
                    }
                    String id = r.id.toString();
                    if (!f.isEmpty() && !id.toLowerCase(Locale.ROOT).contains(f)) {
                        continue;
                    }
                    if (!inventoryCouldSupplyJsonRecipe(menu, r)) {
                        continue;
                    }
                    placeable.add(id + "->" + r.outputItemId);
                }
            }
            return "placeable_recipes: " + String.join(", ", placeable) + "\n"
                    + "needs_pattern_or_shapeless_fallback: " + String.join(", ", fallback);
        });
    }

    /**
     * List craftable crafting recipes by output item id, using the currently open inventory/table menu.
     */
    public static String listCraftingRecipesForOutput(IPlayerContext ctx, String outputItemIdRaw, int maxEntries) {
        return onClient(ctx, () -> {
            Item target = parseGridItemId(outputItemIdRaw);
            if (target == null || target == Items.AIR) {
                return "ERROR: Bad output item id.";
            }
            LocalPlayer p = ctx.player();
            if (!(p.containerMenu instanceof InventoryMenu) && !(p.containerMenu instanceof CraftingMenu)) {
                return "ERROR: Open player inventory or a crafting table first.";
            }
            int cap = Math.min(100, Math.max(1, maxEntries));
            List<String> ids = new ArrayList<>();
            for (RecipeHolder<CraftingRecipe> h : allCraftingRecipes(ctx)) {
                ItemStack result = craftingResultProbe(ctx, h.value());
                if (result.isEmpty() || !result.is(target)) {
                    continue;
                }
                if (!inventoryCouldSupplyRecipe(p.containerMenu, h.value())) {
                    continue;
                }
                ids.add(h.id().identifier().toString());
                if (ids.size() >= cap) {
                    break;
                }
            }
            if (ids.isEmpty()) {
                String output = BuiltInRegistries.ITEM.getKey(target).toString();
                for (JsonCraftingRecipe r : jsonCraftingRecipes(ctx)) {
                    if (!r.outputItemId.equals(output)) {
                        continue;
                    }
                    if (p.containerMenu instanceof InventoryMenu && r.needsCraftingTable()) {
                        continue;
                    }
                    if (!inventoryCouldSupplyJsonRecipe(p.containerMenu, r)) {
                        continue;
                    }
                    ids.add(r.id.toString());
                    if (ids.size() >= cap) {
                        break;
                    }
                }
            }
            return ids.isEmpty()
                    ? "No craftable recipes found for " + BuiltInRegistries.ITEM.getKey(target) + "."
                    : "craftable_output_recipes: " + String.join(", ", ids);
        });
    }

    /**
     * Craft by desired output item id. Uses the currently open 2x2 inventory or 3x3 table menu.
     */
    public static String craftItemByOutput(IPlayerContext ctx, String outputItemIdRaw, int count) {
        int qty = Math.min(64, Math.max(1, count));
        String outputNorm = normalizeNamespacedId(outputItemIdRaw);
        if (RECIPE_WOODEN_PICKAXE.equals(outputNorm) || RECIPE_WOODEN_AXE.equals(outputNorm)) {
            StringBuilder log = new StringBuilder();
            int done = 0;
            for (int i = 0; i < qty; i++) {
                String r = craftRecipeAtTable(ctx, outputNorm);
                log.append(r);
                if (!r.startsWith("Crafted")) {
                    break;
                }
                done++;
                if (i + 1 < qty) {
                    log.append(" ");
                }
            }
            return "craft_item output=" + outputNorm + ", crafted=" + done + ". " + log;
        }
        List<String> recipeIds = onClient(ctx, () -> {
            Item target = parseGridItemId(outputItemIdRaw);
            if (target == null || target == Items.AIR) {
                return List.of("ERROR: Bad output item id.");
            }
            LocalPlayer p = ctx.player();
            if (!(p.containerMenu instanceof InventoryMenu) && !(p.containerMenu instanceof CraftingMenu)) {
                p.closeContainer();
                sleepAi(80);
            }
            boolean table = p.containerMenu instanceof CraftingMenu;
            boolean inv = p.containerMenu instanceof InventoryMenu;
            if (!table && !inv) {
                return List.of("ERROR: Open player inventory or a crafting table first.");
            }
            List<String> out = new ArrayList<>();
            for (RecipeHolder<CraftingRecipe> h : allCraftingRecipes(ctx)) {
                ItemStack result = craftingResultProbe(ctx, h.value());
                if (result.isEmpty() || !result.is(target)) {
                    continue;
                }
                if (!inventoryCouldSupplyRecipe(p.containerMenu, h.value())) {
                    continue;
                }
                if (inv && recipeNeedsCraftingTable(h.value())) {
                    continue;
                }
                out.add(h.id().identifier().toString());
            }
            return out;
        });
        if (recipeIds.isEmpty()) {
            String json = craftJsonItemByOutput(ctx, outputItemIdRaw, qty);
            if (!json.startsWith("ERROR:")) {
                return json;
            }
            return "ERROR: No craftable recipe found for " + normalizeNamespacedId(outputItemIdRaw)
                    + " in the currently open crafting menu. JSON fallback: " + json;
        }
        if (recipeIds.get(0).startsWith("ERROR:")) {
            String json = craftJsonItemByOutput(ctx, outputItemIdRaw, qty);
            if (!json.startsWith("ERROR:")) {
                return json;
            }
            return recipeIds.get(0);
        }
        String recipeId = recipeIds.get(0);
        StringBuilder log = new StringBuilder();
        int done = 0;
        for (int i = 0; i < qty; i++) {
            if (MistralAgent.isCancelled()) {
                return log.append("Cancelled after ").append(done).append(" craft(s).").toString();
            }
            String result = onClient(ctx, () -> ctx.player().containerMenu instanceof CraftingMenu ? "table" : "inventory");
            String r = "table".equals(result) ? craftRecipeAtTable(ctx, recipeId) : craftRecipeInInventory(ctx, recipeId);
            log.append(r);
            if (!r.startsWith("Crafted")) {
                break;
            }
            done++;
            if (i + 1 < qty) {
                log.append(" ");
            }
        }
        if (done == 0) {
            String json = craftJsonItemByOutput(ctx, outputItemIdRaw, qty);
            if (!json.startsWith("ERROR:")) {
                return json;
            }
            log.append(" JSON fallback: ").append(json);
        }
        return "craft_item output=" + normalizeNamespacedId(outputItemIdRaw)
                + ", recipe=" + recipeId + ", crafted=" + done + ". " + log;
    }

    private static String craftJsonItemByOutput(IPlayerContext ctx, String outputItemIdRaw, int qty) {
        int count = Math.min(64, Math.max(1, qty));
        StringBuilder log = new StringBuilder();
        int done = 0;
        for (int i = 0; i < count; i++) {
            if (MistralAgent.isCancelled()) {
                return log.append("Cancelled after ").append(done).append(" craft(s).").toString();
            }
            String r = onClient(ctx, () -> craftOneJsonItemByOutputOnClient(ctx, outputItemIdRaw));
            if ("NEEDS_CRAFTING_TABLE".equals(r)) {
                String open = openNearbyOrPlaceCraftingTable(ctx);
                log.append(open).append(" ");
                if (open.startsWith("ERROR:") || open.startsWith("WARN:")) {
                    return "ERROR: Need a crafting table for " + normalizeNamespacedId(outputItemIdRaw) + ". " + log;
                }
                r = onClient(ctx, () -> craftOneJsonItemByOutputOnClient(ctx, outputItemIdRaw));
            }
            if (r.startsWith("ASYNC_JSON_TABLE:")) {
                Identifier recipeId = Identifier.tryParse(r.substring("ASYNC_JSON_TABLE:".length()));
                Optional<JsonCraftingRecipe> recipe = recipeId == null
                        ? Optional.empty()
                        : onClient(ctx, () -> findJsonCraftingRecipe(ctx, recipeId));
                r = recipe.map(json -> craftJsonRecipeAtTable(ctx, json))
                        .orElse("ERROR: Could not reload JSON recipe " + recipeId + ".");
            }
            log.append(r);
            if (!r.startsWith("Crafted")) {
                break;
            }
            done++;
            if (i + 1 < count) {
                log.append(" ");
            }
        }
        return done == 0 && log.toString().startsWith("ERROR:")
                ? log.toString()
                : "craft_item output=" + normalizeNamespacedId(outputItemIdRaw)
                + ", crafted=" + done + " via loaded JSON recipes. " + log;
    }

    private static String craftOneJsonItemByOutputOnClient(IPlayerContext ctx, String outputItemIdRaw) {
        Item target = parseGridItemId(outputItemIdRaw);
        if (target == null || target == Items.AIR) {
            return "ERROR: Bad output item id.";
        }
        LocalPlayer p = ctx.player();
        AbstractContainerMenu menu = p.containerMenu;
        boolean table = menu instanceof CraftingMenu;
        boolean inv = menu instanceof InventoryMenu;
        if (!table && !inv) {
            return "ERROR: Open player inventory or a crafting table first.";
        }
        String outputId = BuiltInRegistries.ITEM.getKey(target).toString();
        JsonCraftingRecipe needsTable = null;
        for (JsonCraftingRecipe recipe : jsonCraftingRecipes(ctx)) {
            if (!recipe.outputItemId.equals(outputId)) {
                continue;
            }
            if (!inventoryCouldSupplyJsonRecipe(menu, recipe)) {
                continue;
            }
            if (recipe.needsCraftingTable() && !table) {
                if (needsTable == null) {
                    needsTable = recipe;
                }
                continue;
            }
            return table
                    ? "ASYNC_JSON_TABLE:" + recipe.id
                    : craftJsonRecipeInInventoryOnClient(ctx, recipe);
        }
        if (needsTable != null) {
            return "NEEDS_CRAFTING_TABLE";
        }
        return "ERROR: No loaded JSON crafting recipe can currently make " + outputId + ".";
    }

    private static String craftJsonRecipeAtTable(IPlayerContext ctx, JsonCraftingRecipe recipe) {
        String ready = ensureCraftingTableOpenForCraft(ctx);
        if (ready.startsWith("ERROR:") || ready.startsWith("WARN:")) {
            return ready;
        }
        String fill = onClient(ctx, () -> {
            if (!(ctx.player().containerMenu instanceof CraftingMenu menu)) {
                return "ERROR: Could not keep crafting table open.";
            }
            clear3x3(ctx, menu);
            String err = fillJsonRecipeInCraftingTable(ctx, menu, recipe);
            if (err != null) {
                clear3x3(ctx, menu);
                return err;
            }
            return "FILLED";
        });
        if (fill.startsWith("ERROR:")) {
            return fill;
        }
        return finishCraftingTableAfterServerUpdate(ctx,
                "Crafted recipe " + recipe.id + " (loaded JSON recipe fallback).",
                "ERROR: No crafting result for loaded JSON recipe " + recipe.id + ".");
    }

    private static String craftJsonRecipeAtTableOnClient(IPlayerContext ctx, JsonCraftingRecipe recipe) {
        LocalPlayer p = ctx.player();
        if (!(p.containerMenu instanceof CraftingMenu menu)) {
            return "ERROR: Open a crafting table GUI first (right-click a placed table).";
        }
        clear3x3(ctx, menu);
        String fill = fillJsonRecipeInCraftingTable(ctx, menu, recipe);
        if (fill != null) {
            clear3x3(ctx, menu);
            return fill;
        }
        sleepAi(140);
        if (!craftingResultNonEmpty(menu)) {
            clear3x3(ctx, menu);
            return "ERROR: No crafting result for loaded JSON recipe " + recipe.id + ".";
        }
        finishCraftingTableTakeResult(ctx, menu);
        return "Crafted recipe " + recipe.id + " (loaded JSON recipe fallback).";
    }

    private static String craftJsonRecipeInInventoryOnClient(IPlayerContext ctx, JsonCraftingRecipe recipe) {
        LocalPlayer p = ctx.player();
        if (!ensurePlayerInventoryMenu(ctx, p)) {
            return "ERROR: Open player inventory (E) -- need 2x2 crafting area.";
        }
        if (recipe.needsCraftingTable()) {
            return "ERROR: Recipe needs a crafting table: " + recipe.id + ".";
        }
        clear2x2(ctx);
        String fill = fillJsonRecipeInInventory(ctx, recipe);
        if (fill != null) {
            clear2x2(ctx);
            return fill;
        }
        sleepAi(140);
        if (p.containerMenu.getSlot(0).getItem().isEmpty()) {
            clear2x2(ctx);
            return "ERROR: No crafting result for loaded JSON recipe " + recipe.id + " in 2x2.";
        }
        click(ctx, 0, ClickType.QUICK_MOVE, 0);
        sleepAi(100);
        clear2x2(ctx);
        return "Crafted recipe " + recipe.id + " in 2x2 inventory (loaded JSON recipe fallback).";
    }

    private static String fillJsonRecipeInCraftingTable(IPlayerContext ctx, CraftingMenu menu, JsonCraftingRecipe recipe) {
        if (recipe.shaped) {
            for (int i = 0; i < 9; i++) {
                Ingredient ing = recipe.shapedGrid[i];
                if (ing == null || ing.isEmpty()) {
                    continue;
                }
                if (!placeOneIngredientInMenu(ctx, menu, ing, i + 1)) {
                    return "ERROR: Missing ingredient for " + recipe.id + " grid cell " + i + ".";
                }
                sleepAi(55);
            }
            return null;
        }
        if (recipe.shapelessIngredients.size() > 9) {
            return "ERROR: Too many shapeless ingredients for " + recipe.id + ".";
        }
        for (int i = 0; i < recipe.shapelessIngredients.size(); i++) {
            Ingredient ing = recipe.shapelessIngredients.get(i);
            if (!placeOneIngredientInMenu(ctx, menu, ing, i + 1)) {
                return "ERROR: Missing shapeless ingredient " + i + " for " + recipe.id + ".";
            }
            sleepAi(55);
        }
        return null;
    }

    private static String fillJsonRecipeInInventory(IPlayerContext ctx, JsonCraftingRecipe recipe) {
        LocalPlayer p = ctx.player();
        int[] dests = {INV_CRAFT_1, INV_CRAFT_2, INV_CRAFT_3, INV_CRAFT_4};
        if (recipe.shaped) {
            for (int r = 0; r < recipe.height; r++) {
                for (int c = 0; c < recipe.width; c++) {
                    Ingredient ing = recipe.shapedGrid[r * 3 + c];
                    if (ing == null || ing.isEmpty()) {
                        continue;
                    }
                    int dest = dests[r * 2 + c];
                    int src = findSlotMatchingIngredient(p.containerMenu, ing);
                    if (src < 0 || !moveOnePickupToSlot(ctx, src, dest)) {
                        return "ERROR: Missing ingredient for " + recipe.id + " 2x2 cell " + (r * 2 + c) + ".";
                    }
                    sleepAi(55);
                }
            }
            return null;
        }
        if (recipe.shapelessIngredients.size() > 4) {
            return "ERROR: Too many shapeless ingredients for 2x2 recipe " + recipe.id + ".";
        }
        for (int i = 0; i < recipe.shapelessIngredients.size(); i++) {
            Ingredient ing = recipe.shapelessIngredients.get(i);
            int src = findSlotMatchingIngredient(p.containerMenu, ing);
            if (src < 0 || !moveOnePickupToSlot(ctx, src, dests[i])) {
                return "ERROR: Missing shapeless ingredient " + i + " for " + recipe.id + ".";
            }
            sleepAi(55);
        }
        return null;
    }

    public static String equipItem(IPlayerContext ctx, String itemIdRaw) {
        return onClient(ctx, () -> {
            Item item = parseGridItemId(itemIdRaw);
            if (item == null || item == Items.AIR) {
                return "ERROR: Bad item id.";
            }
            LocalPlayer p = ctx.player();
            AbstractContainerMenu menu = p.containerMenu;
            int src = findItemSlot(menu, item);
            if (src < 0) {
                return "ERROR: Item not found in inventory: " + normalizeNamespacedId(itemIdRaw);
            }
            int selected = p.getInventory().getSelectedSlot();
            int playerStart = menuPlayerSlotStart(menu);
            int hotbarStart = playerStart + 27;
            if (src >= hotbarStart && src < hotbarStart + 9) {
                p.getInventory().setSelectedSlot(src - hotbarStart);
                return "Equipped " + normalizeNamespacedId(itemIdRaw) + " from hotbar slot " + (src - hotbarStart) + ".";
            }
            ctx.playerController().windowClick(menu.containerId, src, selected, ClickType.SWAP, p);
            p.getInventory().setSelectedSlot(selected);
            return "Equipped " + normalizeNamespacedId(itemIdRaw) + " into selected hotbar slot " + selected + ".";
        });
    }

    public static String rightClick(IPlayerContext ctx) {
        return onClient(ctx, () -> {
            Minecraft mc = Minecraft.getInstance();
            LocalPlayer p = ctx.player();
            HitResult hit = ctx.objectMouseOver();
            InteractionResult res;
            if (hit instanceof BlockHitResult bhr && hit.getType() == HitResult.Type.BLOCK) {
                res = mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, bhr);
            } else {
                res = mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
            }
            p.swing(InteractionHand.MAIN_HAND);
            return "Right-click result: " + res + ".";
        });
    }

    public static String useItemOnBlock(IPlayerContext ctx, String blockIdRaw, int maxRadius) {
        int radius = Math.min(32, Math.max(1, maxRadius));
        BlockUsePlan plan = onClient(ctx, () -> {
            Identifier bid = Identifier.tryParse(normalizeNamespacedId(blockIdRaw));
            if (bid == null) {
                return BlockUsePlan.error("ERROR: Bad block id.");
            }
            Block block = BuiltInRegistries.BLOCK.get(bid).map(Holder.Reference::value).orElse(null);
            if (block == null || block == Blocks.AIR) {
                return BlockUsePlan.error("ERROR: Unknown block id.");
            }
            LocalPlayer p = ctx.player();
            Level level = p.level();
            BlockPos origin = p.blockPosition();
            BlockPos best = null;
            double bestDist = Double.MAX_VALUE;
            for (int x = -radius; x <= radius; x++) {
                for (int y = -radius; y <= radius; y++) {
                    for (int z = -radius; z <= radius; z++) {
                        BlockPos pos = origin.offset(x, y, z);
                        if (!level.getBlockState(pos).is(block)) {
                            continue;
                        }
                        double d = origin.distSqr(pos);
                        if (d < bestDist && d <= radius * radius) {
                            bestDist = d;
                            best = pos;
                        }
                    }
                }
            }
            if (best == null) {
                return BlockUsePlan.error("ERROR: No " + bid + " found within " + radius + " blocks.");
            }
            return BlockUsePlan.ok(bid.toString(), best);
        });
        if (plan.error != null) {
            return plan.error;
        }
        Vec3 hitVec = Vec3.atCenterOf(plan.pos);
        if (!visiblyLookAt(ctx, hitVec, 32)) {
            return "ERROR: Could not visibly aim at " + plan.blockId + " at " + plan.pos + ".";
        }
        if (!waitCrosshairOnBlock(ctx, plan.pos, 10, 50)) {
            return "ERROR: Crosshair was not on " + plan.blockId + " after visible turn.";
        }
        return onClient(ctx, () -> {
            LocalPlayer p = ctx.player();
            HitResult hover = ctx.objectMouseOver();
            BlockHitResult hit = hover instanceof BlockHitResult bhr && bhr.getBlockPos().equals(plan.pos)
                    ? bhr
                    : new BlockHitResult(hitVec, Direction.UP, plan.pos, false);
            InteractionResult res = Minecraft.getInstance().gameMode.useItemOn(p, InteractionHand.MAIN_HAND, hit);
            p.swing(InteractionHand.MAIN_HAND);
            return "Used held item on " + plan.blockId + " at " + plan.pos + " (" + res + ").";
        });
    }

    public static String furnaceSmeltMany(
            IPlayerContext ctx,
            String inputItemIdRaw,
            String fuelItemIdRawOptional,
            String recipeIdRawOptional,
            int maxWaitSeconds,
            int quantity) {
        int qty = Math.min(64, Math.max(1, quantity));
        StringBuilder out = new StringBuilder();
        int done = 0;
        for (int i = 0; i < qty; i++) {
            if (MistralAgent.isCancelled()) {
                return out.append("Cancelled after ").append(done).append(" item(s).").toString();
            }
            String r = furnaceSmelt(ctx, inputItemIdRaw, fuelItemIdRawOptional, recipeIdRawOptional, maxWaitSeconds);
            out.append(r);
            if (!r.startsWith("Furnace output collected.")) {
                break;
            }
            done++;
            if (i + 1 < qty) {
                out.append(" ");
            }
        }
        return "Smelted outputs collected=" + done + ". " + out;
    }

    public static String brewingBrewAndCollect(IPlayerContext ctx, String ingredientItemIdRaw, int maxWaitSeconds, boolean collect) {
        String loaded = brewingLoadStand(ctx, ingredientItemIdRaw);
        if (loaded.startsWith("ERROR:")) {
            return loaded;
        }
        int capWait = Math.min(600, Math.max(1, maxWaitSeconds));
        long deadline = System.currentTimeMillis() + capWait * 1000L;
        while (System.currentTimeMillis() < deadline) {
            if (MistralAgent.isCancelled()) {
                return "Cancelled waiting for brew.";
            }
            String state = onClient(ctx, () -> {
                if (!(ctx.player().containerMenu instanceof BrewingStandMenu menu)) {
                    return "ERROR: Brewing stand GUI closed before brew completed.";
                }
                return menu.getSlot(3).getItem().isEmpty() ? "DONE" : "WAIT";
            });
            if (state.startsWith("ERROR:")) {
                return loaded + " " + state;
            }
            if ("DONE".equals(state)) {
                if (collect) {
                    return onClient(ctx, () -> {
                        if (!(ctx.player().containerMenu instanceof BrewingStandMenu menu)) {
                            return loaded + " Brew completed, but brewing stand GUI closed before collect.";
                        }
                        int moved = 0;
                        for (int i = 0; i <= 2; i++) {
                            if (!menu.getSlot(i).getItem().isEmpty()) {
                                click(ctx, i, ClickType.QUICK_MOVE, 0);
                                sleepAi(60);
                                moved++;
                            }
                        }
                        return loaded + " Brew completed; collected " + moved + " bottle slot(s).";
                    });
                }
                return loaded + " Brew completed.";
            }
            sleepAi(250);
        }
        return loaded + " TIMEOUT: Brew did not finish after " + capWait + "s.";
    }

    private static void finishCraftingTableTakeResult(IPlayerContext ctx, CraftingMenu menu) {
        click(ctx, 0, ClickType.QUICK_MOVE, 0);
        sleepAi(80);
        clear3x3(ctx, menu);
    }

    private static String finishCraftingTableAfterServerUpdate(IPlayerContext ctx, String success, String failure) {
        for (int i = 0; i < 10; i++) {
            sleepAi(90);
            String r = onClient(ctx, () -> {
                if (!(ctx.player().containerMenu instanceof CraftingMenu menu)) {
                    return "ERROR: Crafting table GUI closed before result could be taken.";
                }
                if (!craftingResultNonEmpty(menu)) {
                    return "WAIT";
                }
                finishCraftingTableTakeResult(ctx, menu);
                return success;
            });
            if (!"WAIT".equals(r)) {
                return r;
            }
        }
        onClient(ctx, () -> {
            if (ctx.player().containerMenu instanceof CraftingMenu menu) {
                clear3x3(ctx, menu);
            }
            return null;
        });
        return failure;
    }

    private static String ensureCraftingTableOpenForCraft(IPlayerContext ctx) {
        Boolean already = onClient(ctx, () -> ctx.player().containerMenu instanceof CraftingMenu);
        if (Boolean.TRUE.equals(already)) {
            return "Already open: crafting table.";
        }
        String opened = openNearbyOrPlaceCraftingTable(ctx);
        if (opened.startsWith("ERROR:") || opened.startsWith("WARN:")) {
            return opened;
        }
        Boolean open = onClient(ctx, () -> ctx.player().containerMenu instanceof CraftingMenu);
        return Boolean.TRUE.equals(open) ? opened : "ERROR: Could not open crafting table GUI. " + opened;
    }

    private static boolean craftingResultNonEmpty(CraftingMenu menu) {
        try {
            return !menu.getSlot(0).getItem().isEmpty();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * @return {@code null} if grid was filled from placement; empty string to skip (impossible / no layout);
     *     {@code ERROR:...} on missing item while placing.
     */
    private static String fillGridFromPlacementInfo(IPlayerContext ctx, CraftingMenu menu, CraftingRecipe recipe) {
        PlacementInfo pi = recipe.placementInfo();
        if (pi.isImpossibleToPlace()) {
            return "";
        }
        IntList slotMap = pi.slotsToIngredientIndex();
        if (slotMap.isEmpty()) {
            return "";
        }
        LocalPlayer p = ctx.player();
        int cells = Math.min(9, slotMap.size());
        for (int i = 0; i < cells; i++) {
            int dest = i + 1;
            int ingIdx = slotMap.getInt(i);
            if (ingIdx == PlacementInfo.EMPTY_SLOT) {
                continue;
            }
            if (ingIdx < 0 || ingIdx >= pi.ingredients().size()) {
                continue;
            }
            Ingredient ing = pi.ingredients().get(ingIdx);
            if (ing.isEmpty()) {
                continue;
            }
            int src = findSlotMatchingIngredient(p.containerMenu, ing);
            if (src < 0) {
                return "ERROR: Missing ingredient for recipe (placement cell " + i + ").";
            }
            if (!moveOnePickupToSlot(ctx, src, dest)) {
                return "ERROR: Could not move ingredient into crafting cell " + i + ".";
            }
            sleepAi(55);
        }
        return null;
    }

    private static String fillGridFromShapedCentered(IPlayerContext ctx, CraftingMenu menu, ShapedRecipe shaped) {
        Ingredient[] grid = expandShapedToGrid3x3(shaped);
        LocalPlayer p = ctx.player();
        for (int i = 0; i < 9; i++) {
            Ingredient ing = grid[i];
            if (ing == null || ing.isEmpty()) {
                continue;
            }
            int dest = i + 1;
            int src = findSlotMatchingIngredient(p.containerMenu, ing);
            if (src < 0) {
                return "ERROR: Missing ingredient for shaped cell " + i + ".";
            }
            if (!moveOnePickupToSlot(ctx, src, dest)) {
                return "ERROR: Could not move ingredient into shaped cell " + i + ".";
            }
            sleepAi(55);
        }
        return null;
    }

    private static Ingredient[] expandShapedToGrid3x3(ShapedRecipe shaped) {
        Ingredient[] g = new Ingredient[9];
        int w = shaped.getWidth();
        int h = shaped.getHeight();
        List<Optional<Ingredient>> pat = shaped.getIngredients();
        int offR = (3 - h) / 2;
        int offC = (3 - w) / 2;
        for (int r = 0; r < h; r++) {
            for (int c = 0; c < w; c++) {
                int pidx = r * w + c;
                if (pidx < 0 || pidx >= pat.size()) {
                    continue;
                }
                Optional<Ingredient> o = pat.get(pidx);
                int gr = offR + r;
                int gc = offC + c;
                if (gr < 0 || gr >= 3 || gc < 0 || gc >= 3) {
                    continue;
                }
                int gix = gr * 3 + gc;
                if (o.isPresent() && !o.get().isEmpty()) {
                    g[gix] = o.get();
                }
            }
        }
        return g;
    }

    private static String fillGridFromShapelessSearch(
            IPlayerContext ctx,
            CraftingMenu menu,
            Level level,
            CraftingRecipe recipe,
            List<Ingredient> ings) {
        if (ings.isEmpty()) {
            return "ERROR: Shapeless recipe has no ingredients.";
        }
        if (ings.size() > 9) {
            return "ERROR: Too many shapeless ingredients.";
        }
        int[] slotForIng = new int[ings.size()];
        int[] budget = new int[]{80000};
        ItemStack[] grid = new ItemStack[9];
        Arrays.fill(grid, ItemStack.EMPTY);
        if (!shapelessAssignSlots(level, recipe, ings, 0, grid, slotForIng, budget)) {
            return "ERROR: No valid shapeless layout (ambiguous recipe or missing items).";
        }
        LocalPlayer p = ctx.player();
        for (int i = 0; i < ings.size(); i++) {
            int dest = slotForIng[i] + 1;
            int src = findSlotMatchingIngredient(p.containerMenu, ings.get(i));
            if (src < 0) {
                return "ERROR: Missing ingredient for shapeless slot " + i + ".";
            }
            if (!moveOnePickupToSlot(ctx, src, dest)) {
                return "ERROR: Could not move ingredient for shapeless slot " + i + ".";
            }
            sleepAi(55);
        }
        return null;
    }

    private static boolean shapelessAssignSlots(
            Level level,
            CraftingRecipe recipe,
            List<Ingredient> ings,
            int depth,
            ItemStack[] grid,
            int[] outSlotPerIng,
            int[] budget) {
        if (budget[0]-- <= 0) {
            return false;
        }
        if (depth == ings.size()) {
            List<ItemStack> nine = Arrays.asList(Arrays.copyOf(grid, 9));
            return recipe.matches(CraftingInput.of(3, 3, nine), level);
        }
        Ingredient need = ings.get(depth);
        for (int s = 0; s < 9; s++) {
            if (!grid[s].isEmpty()) {
                continue;
            }
            for (ItemStack probe : probeStacksForIngredient(need)) {
                if (probe.isEmpty() || !need.test(probe)) {
                    continue;
                }
                ItemStack one = probe.copyWithCount(1);
                grid[s] = one;
                outSlotPerIng[depth] = s;
                if (shapelessAssignSlots(level, recipe, ings, depth + 1, grid, outSlotPerIng, budget)) {
                    return true;
                }
                grid[s] = ItemStack.EMPTY;
            }
        }
        return false;
    }

    private static List<ItemStack> probeStacksForIngredient(Ingredient ing) {
        List<ItemStack> list = new ArrayList<>();
        ing.items().forEach(h -> list.add(new ItemStack(h, 1)));
        return list.isEmpty() ? List.of(ItemStack.EMPTY) : list;
    }

    @SuppressWarnings("unchecked")
    private static List<Ingredient> shapelessIngredients(ShapelessRecipe r) {
        try {
            java.lang.reflect.Field f = ShapelessRecipe.class.getDeclaredField("ingredients");
            f.setAccessible(true);
            return new ArrayList<>((List<Ingredient>) f.get(r));
        } catch (ReflectiveOperationException e) {
            return List.of();
        }
    }

    private static boolean inventoryCouldSupplyRecipe(AbstractContainerMenu menu, CraftingRecipe recipe) {
        List<Ingredient> needs = flatIngredientsForRecipe(recipe);
        if (needs.isEmpty()) {
            return false;
        }
        List<ItemStack> pool = copyPlayerInvStacks(menu);
        for (Ingredient ing : needs) {
            if (!consumeOneMatchingFromPool(pool, ing)) {
                return false;
            }
        }
        return true;
    }

    private static List<RecipeHolder<CraftingRecipe>> allCraftingRecipes(IPlayerContext ctx) {
        List<RecipeHolder<CraftingRecipe>> all = new ArrayList<>();
        RecipeManager rm = recipeManager(ctx);
        if (rm == null) {
            return all;
        }
        for (RecipeHolder<?> h : rm.getRecipes()) {
            if (h.value() instanceof CraftingRecipe cr) {
                all.add(new RecipeHolder<>(h.id(), cr));
            }
        }
        return all;
    }

    private static ItemStack craftingResultProbe(IPlayerContext ctx, CraftingRecipe recipe) {
        try {
            return recipe.assemble(CraftingInput.EMPTY, ctx.player().registryAccess());
        } catch (RuntimeException e) {
            return ItemStack.EMPTY;
        }
    }

    private static boolean recipeNeedsCraftingTable(CraftingRecipe recipe) {
        if (recipe instanceof ShapedRecipe shaped) {
            return shaped.getWidth() > 2 || shaped.getHeight() > 2;
        }
        if (recipe instanceof ShapelessRecipe sl) {
            return shapelessIngredients(sl).size() > 4;
        }
        PlacementInfo pi = recipe.placementInfo();
        return pi.slotsToIngredientIndex().size() > 4 || pi.ingredients().size() > 4;
    }

    private static List<Ingredient> flatIngredientsForRecipe(CraftingRecipe recipe) {
        if (recipe instanceof ShapedRecipe shaped) {
            List<Ingredient> out = new ArrayList<>();
            for (Ingredient ing : expandShapedToGrid3x3(shaped)) {
                if (ing != null && !ing.isEmpty()) {
                    out.add(ing);
                }
            }
            return out;
        }
        if (recipe instanceof ShapelessRecipe sl) {
            return new ArrayList<>(shapelessIngredients(sl));
        }
        List<Ingredient> out = new ArrayList<>();
        for (Ingredient ing : recipe.placementInfo().ingredients()) {
            if (!ing.isEmpty()) {
                out.add(ing);
            }
        }
        return out;
    }

    private static boolean inventoryCouldSupplyJsonRecipe(AbstractContainerMenu menu, JsonCraftingRecipe recipe) {
        List<Ingredient> needs = recipe.flatIngredients();
        if (needs.isEmpty()) {
            return false;
        }
        List<ItemStack> pool = copyPlayerInvStacks(menu);
        for (Ingredient ing : needs) {
            if (!consumeOneMatchingFromPool(pool, ing)) {
                return false;
            }
        }
        return true;
    }

    private static Optional<JsonCraftingRecipe> findJsonCraftingRecipe(IPlayerContext ctx, Identifier rid) {
        for (JsonCraftingRecipe recipe : jsonCraftingRecipes(ctx)) {
            if (recipe.id.equals(rid)) {
                return Optional.of(recipe);
            }
        }
        return Optional.empty();
    }

    private static List<JsonCraftingRecipe> jsonCraftingRecipes(IPlayerContext ctx) {
        Map<Identifier, JsonCraftingRecipe> recipesById = new LinkedHashMap<>();
        Minecraft mc = ctx.minecraft();
        ResourceManager rm = mc.getResourceManager();
        if (rm != null) {
            Map<Identifier, Resource> resources;
            try {
                resources = rm.listResources("recipe", id -> id.getPath().endsWith(".json"));
            } catch (RuntimeException e) {
                resources = Map.of();
            }
            for (Map.Entry<Identifier, Resource> entry : resources.entrySet()) {
                Identifier recipeId = recipeIdFromResource(entry.getKey());
                if (recipeId == null) {
                    continue;
                }
                try (BufferedReader reader = entry.getValue().openAsReader()) {
                    JsonCraftingRecipe parsed = parseJsonCraftingRecipe(reader, recipeId);
                    if (parsed != null) {
                        recipesById.put(recipeId, parsed);
                    }
                } catch (IOException | RuntimeException ignored) {
                }
            }
        }
        for (JsonCraftingRecipe recipe : classpathMinecraftCraftingRecipes()) {
            recipesById.putIfAbsent(recipe.id, recipe);
        }
        return new ArrayList<>(recipesById.values());
    }

    private static List<JsonCraftingRecipe> classpathMinecraftCraftingRecipes() {
        Map<Identifier, JsonCraftingRecipe> recipesById = new LinkedHashMap<>();
        try {
            Enumeration<URL> urls = AiCrafting.class.getClassLoader().getResources("data/minecraft/recipe");
            while (urls.hasMoreElements()) {
                URL url = urls.nextElement();
                if ("jar".equals(url.getProtocol())) {
                    JarURLConnection conn = (JarURLConnection) url.openConnection();
                    JarFile jar = conn.getJarFile();
                    Enumeration<JarEntry> entries = jar.entries();
                    while (entries.hasMoreElements()) {
                        JarEntry entry = entries.nextElement();
                        String name = entry.getName();
                        if (!name.startsWith("data/minecraft/recipe/") || !name.endsWith(".json")) {
                            continue;
                        }
                        Identifier id = Identifier.tryParse("minecraft:"
                                + name.substring("data/minecraft/recipe/".length(), name.length() - ".json".length()));
                        if (id == null || recipesById.containsKey(id)) {
                            continue;
                        }
                        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                                jar.getInputStream(entry), StandardCharsets.UTF_8))) {
                            JsonCraftingRecipe parsed = parseJsonCraftingRecipe(reader, id);
                            if (parsed != null) {
                                recipesById.put(id, parsed);
                            }
                        } catch (IOException | RuntimeException ignored) {
                        }
                    }
                } else if ("file".equals(url.getProtocol())) {
                    Path root = Paths.get(url.toURI());
                    try (Stream<Path> paths = Files.walk(root)) {
                        paths.filter(p -> p.toString().endsWith(".json")).forEach(p -> {
                            Path rel = root.relativize(p);
                            String relPath = rel.toString().replace('\\', '/');
                            Identifier id = Identifier.tryParse("minecraft:"
                                    + relPath.substring(0, relPath.length() - ".json".length()));
                            if (id == null || recipesById.containsKey(id)) {
                                return;
                            }
                            try (BufferedReader reader = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
                                JsonCraftingRecipe parsed = parseJsonCraftingRecipe(reader, id);
                                if (parsed != null) {
                                    recipesById.put(id, parsed);
                                }
                            } catch (IOException | RuntimeException ignored) {
                            }
                        });
                    }
                }
            }
        } catch (IOException | URISyntaxException | RuntimeException ignored) {
        }
        return new ArrayList<>(recipesById.values());
    }

    private static JsonCraftingRecipe parseJsonCraftingRecipe(BufferedReader reader, Identifier id) {
        JsonElement root = JsonParser.parseReader(reader);
        if (!root.isJsonObject()) {
            return null;
        }
        return parseJsonCraftingRecipe(id, root.getAsJsonObject());
    }
    private static Identifier recipeIdFromResource(Identifier resourceId) {
        String path = resourceId.getPath();
        if (!path.startsWith("recipe/") || !path.endsWith(".json")) {
            return null;
        }
        String idPath = path.substring("recipe/".length(), path.length() - ".json".length());
        return Identifier.tryParse(resourceId.getNamespace() + ":" + idPath);
    }

    private static JsonCraftingRecipe parseJsonCraftingRecipe(Identifier id, JsonObject obj) {
        String type = obj.has("type") ? obj.get("type").getAsString() : "";
        if (!type.equals("minecraft:crafting_shaped") && !type.equals("minecraft:crafting_shapeless")) {
            return null;
        }
        String output = jsonRecipeOutputItem(obj);
        if (output == null || parseGridItemId(output) == null) {
            return null;
        }
        int count = jsonRecipeOutputCount(obj);
        if (type.equals("minecraft:crafting_shaped")) {
            JsonObject key = obj.has("key") && obj.get("key").isJsonObject() ? obj.getAsJsonObject("key") : null;
            if (key == null || !obj.has("pattern") || !obj.get("pattern").isJsonArray()) {
                return null;
            }
            JsonArray pat = obj.getAsJsonArray("pattern");
            int height = Math.min(3, pat.size());
            int width = 0;
            List<String> rows = new ArrayList<>();
            for (int r = 0; r < height; r++) {
                String row = pat.get(r).getAsString();
                rows.add(row);
                width = Math.max(width, Math.min(3, row.length()));
            }
            if (width <= 0 || height <= 0) {
                return null;
            }
            Ingredient[] grid = new Ingredient[9];
            for (int r = 0; r < height; r++) {
                String row = rows.get(r);
                for (int c = 0; c < Math.min(3, row.length()); c++) {
                    char ch = row.charAt(c);
                    if (ch == ' ') {
                        continue;
                    }
                    JsonElement ingJson = key.get(String.valueOf(ch));
                    Ingredient ing = ingredientFromJson(ingJson);
                    if (ing == null || ing.isEmpty()) {
                        return null;
                    }
                    grid[r * 3 + c] = ing;
                }
            }
            return new JsonCraftingRecipe(id, normalizeNamespacedId(output), Math.max(1, count), width, height, grid);
        }
        if (!obj.has("ingredients") || !obj.get("ingredients").isJsonArray()) {
            return null;
        }
        List<Ingredient> ingredients = new ArrayList<>();
        for (JsonElement el : obj.getAsJsonArray("ingredients")) {
            Ingredient ing = ingredientFromJson(el);
            if (ing == null || ing.isEmpty()) {
                return null;
            }
            ingredients.add(ing);
        }
        if (ingredients.isEmpty()) {
            return null;
        }
        return new JsonCraftingRecipe(id, normalizeNamespacedId(output), Math.max(1, count), ingredients);
    }

    private static String jsonRecipeOutputItem(JsonObject obj) {
        if (!obj.has("result")) {
            return null;
        }
        JsonElement result = obj.get("result");
        if (result.isJsonPrimitive()) {
            return result.getAsString();
        }
        if (result.isJsonObject()) {
            JsonObject ro = result.getAsJsonObject();
            if (ro.has("id")) {
                return ro.get("id").getAsString();
            }
            if (ro.has("item")) {
                return ro.get("item").getAsString();
            }
        }
        return null;
    }

    private static int jsonRecipeOutputCount(JsonObject obj) {
        try {
            JsonElement result = obj.get("result");
            if (result != null && result.isJsonObject() && result.getAsJsonObject().has("count")) {
                return result.getAsJsonObject().get("count").getAsInt();
            }
        } catch (RuntimeException ignored) {
        }
        return 1;
    }

    private static Ingredient ingredientFromJson(JsonElement el) {
        if (el == null || el.isJsonNull()) {
            return null;
        }
        List<Item> items = new ArrayList<>();
        if (el.isJsonPrimitive()) {
            items.addAll(itemsForIngredientToken(el.getAsString()));
        } else if (el.isJsonArray()) {
            for (JsonElement part : el.getAsJsonArray()) {
                if (part != null && part.isJsonPrimitive()) {
                    items.addAll(itemsForIngredientToken(part.getAsString()));
                } else if (part != null && part.isJsonObject()) {
                    items.addAll(itemsForIngredientObject(part.getAsJsonObject()));
                }
            }
        } else if (el.isJsonObject()) {
            items.addAll(itemsForIngredientObject(el.getAsJsonObject()));
        }
        if (items.isEmpty()) {
            return null;
        }
        return Ingredient.of(items.stream());
    }

    private static List<Item> itemsForIngredientObject(JsonObject obj) {
        if (obj.has("item")) {
            return itemsForIngredientToken(obj.get("item").getAsString());
        }
        if (obj.has("tag")) {
            return itemsForIngredientToken("#" + obj.get("tag").getAsString());
        }
        if (obj.has("items") && obj.get("items").isJsonArray()) {
            List<Item> out = new ArrayList<>();
            for (JsonElement el : obj.getAsJsonArray("items")) {
                if (el.isJsonPrimitive()) {
                    out.addAll(itemsForIngredientToken(el.getAsString()));
                }
            }
            return out;
        }
        return Collections.emptyList();
    }

    private static List<Item> itemsForIngredientToken(String raw) {
        if (raw == null) {
            return Collections.emptyList();
        }
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) {
            return Collections.emptyList();
        }
        if (s.startsWith("#")) {
            Identifier tid = Identifier.tryParse(s.substring(1));
            if (tid == null) {
                return Collections.emptyList();
            }
            TagKey<Item> tag = TagKey.create(Registries.ITEM, tid);
            List<Item> out = new ArrayList<>();
            BuiltInRegistries.ITEM.getTagOrEmpty(tag).forEach(h -> out.add(h.value()));
            return out;
        }
        Item item = parseGridItemId(s);
        return item == null || item == Items.AIR ? Collections.emptyList() : List.of(item);
    }

    private static List<ItemStack> copyPlayerInvStacks(AbstractContainerMenu menu) {
        List<ItemStack> list = new ArrayList<>();
        int a = menuPlayerSlotStart(menu);
        int b = menuPlayerSlotEndInclusive(menu);
        for (int i = a; i <= b; i++) {
            ItemStack st = menu.getSlot(i).getItem();
            if (!st.isEmpty()) {
                list.add(st.copy());
            }
        }
        return list;
    }

    private static boolean consumeOneMatchingFromPool(List<ItemStack> pool, Ingredient ing) {
        for (ItemStack st : pool) {
            if (!st.isEmpty() && ing.test(st)) {
                st.shrink(1);
                return true;
            }
        }
        return false;
    }

    private static Ingredient parseGridCellToIngredient(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty() || s.equals("air") || s.equals("none") || s.equals("empty") || s.equals("_")) {
            return null;
        }
        if (s.startsWith("#")) {
            Identifier tid = Identifier.tryParse(s.substring(1));
            if (tid == null) {
                return null;
            }
            TagKey<Item> tag = TagKey.create(Registries.ITEM, tid);
            if (!BuiltInRegistries.ITEM.getTagOrEmpty(tag).iterator().hasNext()) {
                return null;
            }
            Stream<Item> stream = StreamSupport.stream(BuiltInRegistries.ITEM.getTagOrEmpty(tag).spliterator(), false)
                    .map(Holder::value);
            return Ingredient.of(stream);
        }
        Item it = parseGridItemId(s);
        if (it == null || it == Items.AIR) {
            return null;
        }
        return Ingredient.of(it);
    }

    private static boolean placeOneIngredientInMenu(IPlayerContext ctx, CraftingMenu menu, Ingredient ing, int destCraftSlot) {
        if (ing == null || ing.isEmpty()) {
            return true;
        }
        int src = findSlotMatchingIngredient(menu, ing);
        if (src < 0) {
            return false;
        }
        return moveOnePickupToSlot(ctx, src, destCraftSlot);
    }

    /**
     * Like {@link #craftRecipeAtTable} but uses the survival inventory (E) 2x2 crafting grid.
     * Recipes whose placement needs more than four cells must use a crafting table instead.
     */
    public static String craftRecipeInInventory(IPlayerContext ctx, String recipeIdRaw) {
        return onClient(ctx, () -> {
            Identifier rid = Identifier.tryParse(normalizeNamespacedId(recipeIdRaw));
            if (rid == null) {
                return "ERROR: Bad recipe id.";
            }
            Optional<RecipeHolder<CraftingRecipe>> holderOpt = resolveCraftingHolder(ctx, rid);
            if (holderOpt.isEmpty()) {
                Optional<JsonCraftingRecipe> json = findJsonCraftingRecipe(ctx, rid);
                if (json.isPresent()) {
                    return craftJsonRecipeInInventoryOnClient(ctx, json.get());
                }
                return "ERROR: Unknown or non-crafting recipe: " + rid + ".";
            }
            RecipeHolder<CraftingRecipe> holder = holderOpt.get();
            LocalPlayer p = ctx.player();
            if (!ensurePlayerInventoryMenu(ctx, p)) {
                return "ERROR: Open player inventory (E) — need 2x2 crafting area.";
            }
            InventoryMenu menu = (InventoryMenu) p.containerMenu;
            PlacementInfo pi = holder.value().placementInfo();
            if (pi.isImpossibleToPlace()) {
                return "ERROR: Recipe cannot be auto-placed: " + rid;
            }
            IntList slotMap = pi.slotsToIngredientIndex();
            if (slotMap.isEmpty()) {
                return "ERROR: Recipe has empty placement layout: " + rid;
            }
            if (slotMap.size() > 4) {
                return "ERROR: Recipe needs more than 2x2 — open a crafting table and use craft_recipe_at_table: " + rid;
            }
            clear2x2(ctx);
            for (int i = 0; i < slotMap.size(); i++) {
                int dest = INV_CRAFT_1 + i;
                int ingIdx = slotMap.getInt(i);
                if (ingIdx == PlacementInfo.EMPTY_SLOT) {
                    continue;
                }
                if (ingIdx < 0 || ingIdx >= pi.ingredients().size()) {
                    continue;
                }
                Ingredient ing = pi.ingredients().get(ingIdx);
                if (ing.isEmpty()) {
                    continue;
                }
                int src = findSlotMatchingIngredient(p.containerMenu, ing);
                if (src < 0) {
                    clear2x2(ctx);
                    return "ERROR: Missing ingredient for recipe " + rid + " (cell " + i + ").";
                }
                if (!moveOnePickupToSlot(ctx, src, dest)) {
                    clear2x2(ctx);
                    return "ERROR: Could not move ingredient into 2x2 cell " + i + ".";
                }
                sleepAi(55);
            }
            sleepAi(120);
            try {
                if (menu.getSlot(0).getItem().isEmpty()) {
                    clear2x2(ctx);
                    return "ERROR: No crafting result (wrong layout or need 3x3): " + rid + ".";
                }
            } catch (RuntimeException e) {
                return "ERROR: Could not read crafting result slot.";
            }
            click(ctx, 0, ClickType.QUICK_MOVE, 0);
            sleepAi(100);
            clear2x2(ctx);
            return "Crafted " + rid + " in 2x2 inventory crafting.";
        });
    }

    /**
     * 2x2 shaped crafting in open inventory: {@code grid4} is four item ids row-major (top row then bottom; maps to slots 1–4).
     */
    public static String craftShapedInInventory(IPlayerContext ctx, String[] grid4) {
        return onClient(ctx, () -> {
            if (grid4 == null || grid4.length != 4) {
                return "ERROR: grid must have exactly 4 strings (2x2 row-major).";
            }
            LocalPlayer p = ctx.player();
            if (!ensurePlayerInventoryMenu(ctx, p)) {
                return "ERROR: Open player inventory (E) first.";
            }
            clear2x2(ctx);
            int[] dests = {INV_CRAFT_1, INV_CRAFT_2, INV_CRAFT_3, INV_CRAFT_4};
            for (int i = 0; i < 4; i++) {
                Item item = parseGridItemId(grid4[i]);
                if (item == null || item == Items.AIR) {
                    continue;
                }
                int src = findItemSlot(p.containerMenu, item);
                if (src < 0) {
                    clear2x2(ctx);
                    return "ERROR: Missing item for 2x2 cell " + i + ".";
                }
                if (!moveOnePickupToSlot(ctx, src, dests[i])) {
                    clear2x2(ctx);
                    return "ERROR: Could not place into 2x2 cell " + i + ".";
                }
                sleepAi(55);
            }
            sleepAi(120);
            try {
                if (p.containerMenu.getSlot(0).getItem().isEmpty()) {
                    clear2x2(ctx);
                    return "ERROR: No result from 2x2 pattern.";
                }
            } catch (RuntimeException e) {
                return "ERROR: Could not read result slot.";
            }
            click(ctx, 0, ClickType.QUICK_MOVE, 0);
            sleepAi(100);
            clear2x2(ctx);
            return "Crafted via 2x2 grid.";
        });
    }

    /**
     * Deposit one input (and optionally one fuel) into an open furnace / smoker / blast furnace, wait for output, then shift-click result.
     * Vanilla campfires have no container screen. If {@code recipe_id} is non-blank, validates the input stack against that cooking recipe.
     */
    public static String furnaceSmelt(
            IPlayerContext ctx,
            String inputItemIdRaw,
            String fuelItemIdRawOptional,
            String recipeIdRawOptional,
            int maxWaitSeconds) {
        int capWait = Math.min(600, Math.max(1, maxWaitSeconds));
        String setup = onClient(ctx, () -> {
            LocalPlayer p = ctx.player();
            AbstractContainerMenu raw = p.containerMenu;
            if (!(raw instanceof AbstractFurnaceMenu menu)) {
                return "ERROR: Open a furnace, smoker, or blast furnace GUI (vanilla campfire has no GUI).";
            }
            Identifier inputId = Identifier.tryParse(normalizeNamespacedId(inputItemIdRaw));
            if (inputId == null) {
                return "ERROR: Bad input_item id.";
            }
            Item inputItem = BuiltInRegistries.ITEM.getOptional(inputId).orElse(null);
            if (inputItem == null || inputItem == Items.AIR) {
                return "ERROR: Unknown input item.";
            }
            String recipeOpt = recipeIdRawOptional == null ? "" : recipeIdRawOptional.trim();
            if (!recipeOpt.isEmpty()) {
                Identifier rid = Identifier.tryParse(normalizeNamespacedId(recipeOpt));
                if (rid == null) {
                    return "ERROR: Bad recipe_id.";
                }
                Optional<RecipeHolder<?>> ro = resolveCookingHolder(ctx, rid);
                if (ro.isEmpty()) {
                    return "ERROR: Unknown smelting-type recipe: " + rid;
                }
                AbstractCookingRecipe recipe = (AbstractCookingRecipe) ro.get().value();
                int ins = findItemSlot(menu, inputItem);
                if (ins < 0) {
                    return "ERROR: No input item in inventory.";
                }
                ItemStack sample = menu.getSlot(ins).getItem();
                if (!recipe.matches(new SingleRecipeInput(sample), p.level())) {
                    return "ERROR: Held input does not match recipe " + rid + ".";
                }
            }
            try {
                if (!menu.getSlot(AbstractFurnaceMenu.RESULT_SLOT).getItem().isEmpty()) {
                    click(ctx, AbstractFurnaceMenu.RESULT_SLOT, ClickType.QUICK_MOVE, 0);
                    sleepAi(80);
                }
            } catch (RuntimeException ignored) {
            }
            if (!menu.getSlot(AbstractFurnaceMenu.INGREDIENT_SLOT).getItem().isEmpty()) {
                return "ERROR: Furnace input slot is not empty — remove items first.";
            }
            int inSrc = findItemSlot(menu, inputItem);
            if (inSrc < 0) {
                return "ERROR: No " + inputId + " in player inventory.";
            }
            if (!moveOnePickupToSlot(ctx, inSrc, AbstractFurnaceMenu.INGREDIENT_SLOT)) {
                return "ERROR: Could not place ingredient into furnace.";
            }
            sleepAi(80);
            String fuelOpt = fuelItemIdRawOptional == null ? "" : fuelItemIdRawOptional.trim();
            if (!fuelOpt.isEmpty()) {
                Identifier fid = Identifier.tryParse(normalizeNamespacedId(fuelOpt));
                if (fid != null) {
                    Item fuelItem = BuiltInRegistries.ITEM.getOptional(fid).orElse(null);
                    if (fuelItem != null && fuelItem != Items.AIR
                            && menu.getSlot(AbstractFurnaceMenu.FUEL_SLOT).getItem().isEmpty()) {
                        int fs = findItemSlot(menu, fuelItem);
                        if (fs < 0) {
                            return "ERROR: Furnace needs fuel (" + fid + ") but none in inventory.";
                        }
                        if (!moveOnePickupToSlot(ctx, fs, AbstractFurnaceMenu.FUEL_SLOT)) {
                            return "ERROR: Could not place fuel into furnace.";
                        }
                        sleepAi(80);
                    }
                }
            }
            return "READY";
        });
        if (!"READY".equals(setup)) {
            return setup;
        }
        long deadline = System.currentTimeMillis() + capWait * 1000L;
        while (System.currentTimeMillis() < deadline) {
            if (MistralAgent.isCancelled()) {
                return "Cancelled waiting for furnace result.";
            }
            String poll = onClient(ctx, () -> {
                if (!(ctx.player().containerMenu instanceof AbstractFurnaceMenu menu)) {
                    return "ERROR: Furnace GUI closed before output could be collected.";
                }
                try {
                    if (!menu.getSlot(AbstractFurnaceMenu.RESULT_SLOT).getItem().isEmpty()) {
                        click(ctx, AbstractFurnaceMenu.RESULT_SLOT, ClickType.QUICK_MOVE, 0);
                        sleepAi(100);
                        return "Furnace output collected.";
                    }
                } catch (RuntimeException e) {
                    return "ERROR: Could not read furnace output slot.";
                }
                return "WAIT";
            });
            if (!"WAIT".equals(poll)) {
                return poll;
            }
            sleepAi(200);
        }
        return "TIMEOUT: No output after " + capWait + "s (add fuel, valid recipe, or wait longer).";
    }

    /** Smithing table: place template/base/addition from a {@link RecipeType#SMITHING} recipe id and take the result. */
    public static String smithingRecipe(IPlayerContext ctx, String recipeIdRaw) {
        return onClient(ctx, () -> {
            Identifier rid = Identifier.tryParse(normalizeNamespacedId(recipeIdRaw));
            if (rid == null) {
                return "ERROR: Bad recipe id.";
            }
            Optional<RecipeHolder<SmithingRecipe>> ho = resolveSmithingHolder(ctx, rid);
            if (ho.isEmpty()) {
                return "ERROR: Unknown smithing recipe: " + rid;
            }
            RecipeHolder<SmithingRecipe> holder = ho.get();
            SmithingRecipe r = holder.value();
            LocalPlayer p = ctx.player();
            if (!(p.containerMenu instanceof SmithingMenu menu)) {
                return "ERROR: Open a smithing table GUI.";
            }
            for (int s : new int[]{SmithingMenu.TEMPLATE_SLOT, SmithingMenu.BASE_SLOT, SmithingMenu.ADDITIONAL_SLOT}) {
                if (!menu.getSlot(s).getItem().isEmpty()) {
                    click(ctx, s, ClickType.QUICK_MOVE, 0);
                    sleepAi(55);
                }
            }
            Optional<Ingredient> templateOpt = r.templateIngredient();
            if (templateOpt.isPresent() && !templateOpt.get().isEmpty()) {
                int src = findSlotMatchingIngredient(menu, templateOpt.get());
                if (src < 0) {
                    return "ERROR: Missing smithing template ingredient.";
                }
                if (!moveOnePickupToSlot(ctx, src, SmithingMenu.TEMPLATE_SLOT)) {
                    return "ERROR: Could not place template.";
                }
                sleepAi(55);
            }
            Ingredient baseIng = r.baseIngredient();
            if (baseIng.isEmpty()) {
                return "ERROR: Invalid smithing recipe (empty base).";
            }
            int bsrc = findSlotMatchingIngredient(menu, baseIng);
            if (bsrc < 0) {
                return "ERROR: Missing smithing base ingredient.";
            }
            if (!moveOnePickupToSlot(ctx, bsrc, SmithingMenu.BASE_SLOT)) {
                return "ERROR: Could not place base.";
            }
            sleepAi(55);
            Optional<Ingredient> addOpt = r.additionIngredient();
            if (addOpt.isPresent() && !addOpt.get().isEmpty()) {
                int asrc = findSlotMatchingIngredient(menu, addOpt.get());
                if (asrc < 0) {
                    return "ERROR: Missing smithing addition ingredient.";
                }
                if (!moveOnePickupToSlot(ctx, asrc, SmithingMenu.ADDITIONAL_SLOT)) {
                    return "ERROR: Could not place addition.";
                }
                sleepAi(55);
            }
            sleepAi(120);
            try {
                if (menu.getSlot(SmithingMenu.RESULT_SLOT).getItem().isEmpty()) {
                    return "ERROR: No smithing result (wrong materials).";
                }
            } catch (RuntimeException e) {
                return "ERROR: Could not read smithing result.";
            }
            click(ctx, SmithingMenu.RESULT_SLOT, ClickType.QUICK_MOVE, 0);
            sleepAi(100);
            return "Smithing " + rid + " (result moved to inventory).";
        });
    }

    /** Stonecutter: place recipe input, select recipe by id, shift-click output. */
    public static String stonecutterCut(IPlayerContext ctx, String recipeIdRaw) {
        return onClient(ctx, () -> {
            Identifier rid = Identifier.tryParse(normalizeNamespacedId(recipeIdRaw));
            if (rid == null) {
                return "ERROR: Bad recipe id.";
            }
            Optional<RecipeHolder<StonecutterRecipe>> ho = resolveStonecutterHolder(ctx, rid);
            if (ho.isEmpty()) {
                return "ERROR: Unknown stonecutter recipe: " + rid;
            }
            LocalPlayer p = ctx.player();
            if (!(p.containerMenu instanceof StonecutterMenu scm)) {
                return "ERROR: Open a stonecutter GUI.";
            }
            StonecutterRecipe sr = ho.get().value();
            Ingredient in = sr.input();
            if (in.isEmpty()) {
                return "ERROR: Bad stonecutter recipe.";
            }
            for (int s : new int[]{StonecutterMenu.INPUT_SLOT, StonecutterMenu.RESULT_SLOT}) {
                if (!scm.getSlot(s).getItem().isEmpty()) {
                    click(ctx, s, ClickType.QUICK_MOVE, 0);
                    sleepAi(55);
                }
            }
            int src = findSlotMatchingIngredient(scm, in);
            if (src < 0) {
                return "ERROR: No stonecutter input item in inventory.";
            }
            if (!moveOnePickupToSlot(ctx, src, StonecutterMenu.INPUT_SLOT)) {
                return "ERROR: Could not place stonecutter input.";
            }
            sleepAi(100);
            int idx = -1;
            int i = 0;
            for (var entry : scm.getVisibleRecipes().entries()) {
                Optional<RecipeHolder<StonecutterRecipe>> opt = entry.recipe().recipe();
                if (opt.isPresent() && opt.get().id().equals(ResourceKey.create(Registries.RECIPE, rid))) {
                    idx = i;
                    break;
                }
                i++;
            }
            if (idx < 0) {
                return "ERROR: Recipe not listed for current input (wrong block variant?).";
            }
            if (!scm.clickMenuButton(p, idx)) {
                return "ERROR: Could not select stonecutter recipe.";
            }
            sleepAi(120);
            if (scm.getSlot(StonecutterMenu.RESULT_SLOT).getItem().isEmpty()) {
                return "ERROR: No stonecutter output after selection.";
            }
            click(ctx, StonecutterMenu.RESULT_SLOT, ClickType.QUICK_MOVE, 0);
            sleepAi(80);
            return "Stonecutter: " + rid + ".";
        });
    }

    /**
     * Anvil: place left (and optional right) items, optionally set rename text, then shift-click result if present.
     * May fail on servers when level cost exceeds player levels.
     */
    public static String anvilCombine(IPlayerContext ctx, String leftItemId, String rightItemIdOpt, String newNameOpt) {
        return onClient(ctx, () -> {
            LocalPlayer p = ctx.player();
            if (!(p.containerMenu instanceof AnvilMenu menu)) {
                return "ERROR: Open an anvil GUI.";
            }
            clearAnvilLikeSlots(ctx, menu, AnvilMenu.INPUT_SLOT, AnvilMenu.ADDITIONAL_SLOT, AnvilMenu.RESULT_SLOT);
            Item left = parseGridItemId(leftItemId);
            if (left == null) {
                return "ERROR: Bad left item id.";
            }
            int lsrc = findItemSlot(menu, left);
            if (lsrc < 0) {
                return "ERROR: No left item in inventory.";
            }
            if (!moveOnePickupToSlot(ctx, lsrc, AnvilMenu.INPUT_SLOT)) {
                return "ERROR: Could not place left stack.";
            }
            sleepAi(70);
            if (rightItemIdOpt != null && !rightItemIdOpt.isBlank()) {
                Item right = parseGridItemId(rightItemIdOpt);
                if (right != null && right != Items.AIR) {
                    int rsrc = findItemSlot(menu, right);
                    if (rsrc < 0) {
                        return "ERROR: Right item not in inventory.";
                    }
                    if (!moveOnePickupToSlot(ctx, rsrc, AnvilMenu.ADDITIONAL_SLOT)) {
                        return "ERROR: Could not place right stack.";
                    }
                    sleepAi(70);
                }
            }
            if (newNameOpt != null && !newNameOpt.isBlank()) {
                menu.setItemName(newNameOpt.trim());
                sleepAi(80);
            }
            sleepAi(120);
            if (menu.getSlot(AnvilMenu.RESULT_SLOT).getItem().isEmpty()) {
                return "ERROR: No anvil result (invalid combine, cost, or rename).";
            }
            click(ctx, AnvilMenu.RESULT_SLOT, ClickType.QUICK_MOVE, 0);
            sleepAi(100);
            return "Anvil: moved result to inventory (if server allowed).";
        });
    }

    /**
     * Brewing stand: ensure blaze powder in fuel slot if empty, place brewing ingredient in ingredient slot,
     * and fill empty potion columns (0–2) with water bottles from inventory.
     */
    public static String brewingLoadStand(IPlayerContext ctx, String ingredientItemIdRaw) {
        return onClient(ctx, () -> {
            if (!(ctx.player().containerMenu instanceof BrewingStandMenu menu)) {
                return "ERROR: Open a brewing stand GUI.";
            }
            if (menu.getSlot(4).getItem().isEmpty()) {
                int fs = findItemSlot(menu, Items.BLAZE_POWDER);
                if (fs >= 0) {
                    if (!moveOnePickupToSlot(ctx, fs, 4)) {
                        return "ERROR: Could not place blaze powder into brewing stand fuel slot.";
                    }
                    sleepAi(70);
                } else if (menu.getFuel() <= 0) {
                    return "ERROR: Brewing stand needs blaze powder fuel but none is available.";
                }
            }
            Identifier ingId = Identifier.tryParse(normalizeNamespacedId(ingredientItemIdRaw));
            if (ingId == null) {
                return "ERROR: Bad ingredient id.";
            }
            Item ingItem = BuiltInRegistries.ITEM.getOptional(ingId).orElse(null);
            if (ingItem == null || ingItem == Items.AIR) {
                return "ERROR: Unknown ingredient.";
            }
            int isrc = findItemSlot(menu, ingItem);
            if (isrc < 0) {
                return "ERROR: No brewing ingredient in inventory.";
            }
            if (!moveOnePickupToSlot(ctx, isrc, 3)) {
                return "ERROR: Could not place brewing ingredient.";
            }
            sleepAi(70);
            for (int bs = 0; bs <= 2; bs++) {
                if (!menu.getSlot(bs).getItem().isEmpty()) {
                    continue;
                }
                int bsrc = findWaterBottleSlot(menu);
                if (bsrc < 0) {
                    return "ERROR: Need water potions (minecraft:potion, water) in inventory for empty bottle slots.";
                }
                if (!moveOnePickupToSlot(ctx, bsrc, bs)) {
                    return "ERROR: Could not place water bottle.";
                }
                sleepAi(55);
            }
            return "Brewing stand loaded (fuel if available, ingredient, water bottles).";
        });
    }

    private static String normalizeNamespacedId(String raw) {
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (!s.contains(":")) {
            s = "minecraft:" + s;
        }
        return s;
    }

    private static Optional<RecipeHolder<CraftingRecipe>> resolveCraftingHolder(IPlayerContext ctx, Identifier rid) {
        ResourceKey<Recipe<?>> key = ResourceKey.create(Registries.RECIPE, rid);
        RecipeManager rm = recipeManager(ctx);
        if (rm == null) {
            return Optional.empty();
        }
        return rm.byKey(key)
                .filter(h -> h.value() instanceof CraftingRecipe)
                .map(h -> new RecipeHolder<>(h.id(), (CraftingRecipe) h.value()));
    }

    private static Optional<RecipeHolder<?>> resolveCookingHolder(IPlayerContext ctx, Identifier rid) {
        ResourceKey<Recipe<?>> key = ResourceKey.create(Registries.RECIPE, rid);
        RecipeManager rm = recipeManager(ctx);
        if (rm == null) {
            return Optional.empty();
        }
        return rm.byKey(key)
                .filter(h -> h.value() instanceof AbstractCookingRecipe);
    }

    private static Optional<RecipeHolder<SmithingRecipe>> resolveSmithingHolder(IPlayerContext ctx, Identifier rid) {
        ResourceKey<Recipe<?>> key = ResourceKey.create(Registries.RECIPE, rid);
        RecipeManager rm = recipeManager(ctx);
        if (rm == null) {
            return Optional.empty();
        }
        return rm.byKey(key)
                .filter(h -> h.value() instanceof SmithingRecipe)
                .map(h -> new RecipeHolder<>(h.id(), (SmithingRecipe) h.value()));
    }

    private static Optional<RecipeHolder<StonecutterRecipe>> resolveStonecutterHolder(IPlayerContext ctx, Identifier rid) {
        ResourceKey<Recipe<?>> key = ResourceKey.create(Registries.RECIPE, rid);
        RecipeManager rm = recipeManager(ctx);
        if (rm == null) {
            return Optional.empty();
        }
        return rm.byKey(key)
                .filter(h -> h.value() instanceof StonecutterRecipe)
                .map(h -> new RecipeHolder<>(h.id(), (StonecutterRecipe) h.value()));
    }

    private static RecipeManager recipeManager(IPlayerContext ctx) {
        Minecraft mc = ctx.minecraft();
        if (mc.hasSingleplayerServer()) {
            return mc.getSingleplayerServer().getRecipeManager();
        }
        return null;
    }

    private static int findSlotMatchingIngredient(AbstractContainerMenu menu, Ingredient ing) {
        int a = menuPlayerSlotStart(menu);
        int b = menuPlayerSlotEndInclusive(menu);
        for (int i = a; i <= b; i++) {
            ItemStack st = menu.getSlot(i).getItem();
            if (!st.isEmpty() && ing.test(st)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean moveOnePickupToSlot(IPlayerContext ctx, int src, int dest) {
        LocalPlayer p = ctx.player();
        AbstractContainerMenu menu = p.containerMenu;
        ItemStack before = ItemStack.EMPTY;
        try {
            before = menu.getSlot(dest).getItem().copy();
        } catch (RuntimeException ignored) {
        }
        click(ctx, src, ClickType.PICKUP, 0);
        sleepAi(60);
        click(ctx, dest, ClickType.PICKUP, 1);
        sleepAi(60);
        if (!p.containerMenu.getCarried().isEmpty()) {
            click(ctx, src, ClickType.PICKUP, 0);
        }
        sleepAi(50);
        try {
            ItemStack after = p.containerMenu.getSlot(dest).getItem();
            return !after.isEmpty() && (before.isEmpty() || after.getCount() > before.getCount()
                    || !ItemStack.isSameItemSameComponents(before, after));
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static void clearAnvilLikeSlots(IPlayerContext ctx, AbstractContainerMenu menu, int a, int b, int c) {
        for (int s : new int[]{a, b, c}) {
            try {
                if (!menu.getSlot(s).getItem().isEmpty()) {
                    click(ctx, s, ClickType.QUICK_MOVE, 0);
                    sleepAi(55);
                }
            } catch (RuntimeException ignored) {
            }
        }
    }

    private static int findWaterBottleSlot(AbstractContainerMenu menu) {
        int a = menuPlayerSlotStart(menu);
        int b = menuPlayerSlotEndInclusive(menu);
        for (int i = a; i <= b; i++) {
            ItemStack st = menu.getSlot(i).getItem();
            if (st.isEmpty()) {
                continue;
            }
            PotionContents pc = st.getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY);
            if (st.is(Items.POTION) && pc.is(Potions.WATER)) {
                return i;
            }
        }
        return -1;
    }

    private static Item parseGridItemId(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty() || s.equals("air") || s.equals("none") || s.equals("empty") || s.equals("_")) {
            return null;
        }
        if (!s.contains(":")) {
            s = "minecraft:" + s;
        }
        Identifier rl = Identifier.tryParse(s);
        if (rl == null) {
            return null;
        }
        Item it = BuiltInRegistries.ITEM.getOptional(rl).orElse(null);
        if (it == null || it == Items.AIR) {
            return null;
        }
        return it;
    }

    private static boolean placeOneItemInMenu(IPlayerContext ctx, CraftingMenu menu, Item item, int destCraftSlot) {
        LocalPlayer p = ctx.player();
        int src = findItemSlot(menu, item);
        if (src < 0) {
            return false;
        }
        click(ctx, src, ClickType.PICKUP, 0);
        sleepAi(60);
        click(ctx, destCraftSlot, ClickType.PICKUP, 1);
        sleepAi(60);
        if (!p.containerMenu.getCarried().isEmpty()) {
            click(ctx, src, ClickType.PICKUP, 0);
        }
        sleepAi(50);
        return true;
    }

    /**
     * Multiplayer 1.21.x clients do not expose the full recipe registry. Keep the early-game wooden tool chain working
     * with vanilla patterns even when RecipeManager is unavailable client-side.
     */
    private static String craftKnownTableRecipeWithoutRecipeManager(IPlayerContext ctx, String recipeId) {
        if (!RECIPE_WOODEN_PICKAXE.equals(recipeId) && !RECIPE_WOODEN_AXE.equals(recipeId)) {
            return null;
        }
        String fill = onClient(ctx, () -> {
            LocalPlayer p = ctx.player();
            if (!(p.containerMenu instanceof CraftingMenu menu)) {
                return "ERROR: Open a crafting table GUI first (right-click a placed table).";
            }
            clear3x3(ctx, menu);
            boolean placed;
            if (RECIPE_WOODEN_PICKAXE.equals(recipeId)) {
                placed = placeOnePlankInMenu(ctx, menu, 1)
                        && placeOnePlankInMenu(ctx, menu, 2)
                        && placeOnePlankInMenu(ctx, menu, 3)
                        && placeOneStick(ctx, menu, 5)
                        && placeOneStick(ctx, menu, 8);
            } else {
                placed = placeOnePlankInMenu(ctx, menu, 1)
                        && placeOnePlankInMenu(ctx, menu, 2)
                        && placeOnePlankInMenu(ctx, menu, 4)
                        && placeOneStick(ctx, menu, 5)
                        && placeOneStick(ctx, menu, 8);
            }
            if (!placed) {
                clear3x3(ctx, menu);
                return "ERROR: Missing planks or sticks for " + recipeId + ".";
            }
            return "FILLED";
        });
        if (fill.startsWith("ERROR:")) {
            return fill;
        }
        return finishCraftingTableAfterServerUpdate(ctx,
                "Crafted recipe " + recipeId + " (manual vanilla pattern fallback).",
                "ERROR: No crafting result for manual pattern " + recipeId + ".");
    }

    /**
     * One-shot: logs &rarr; planks &rarr; sticks &rarr; crafting table &rarr; place &rarr; open &rarr; wooden tool from recipe id.
     * {@code woodenToolRecipeId} must be exactly {@value #RECIPE_WOODEN_PICKAXE} or {@value #RECIPE_WOODEN_AXE}.
     * Works with no inventory screen open (uses {@link InventoryMenu} in the background like Baritone inventory moves).
     */
    public static String makeWoodToolFromLogs(IPlayerContext ctx, String woodenToolRecipeId) {
        if (!RECIPE_WOODEN_PICKAXE.equals(woodenToolRecipeId) && !RECIPE_WOODEN_AXE.equals(woodenToolRecipeId)) {
            return "ERROR: makeWoodToolFromLogs requires recipe " + RECIPE_WOODEN_PICKAXE + " or " + RECIPE_WOODEN_AXE + ".";
        }
        BaritoneAPI.getSettings().allowInventory.value = true;
        BaritoneAPI.getSettings().allowPlace.value = true; // placing the table needs this on
        try {
            IBaritone b = BaritoneAPI.getProvider().getPrimaryBaritone();
            if (b != null) {
                onClient(ctx, () -> {
                    b.getMineProcess().cancel();
                    b.getPathingBehavior().cancelEverything();
                    return null;
                });
            }
        } catch (Throwable ignored) {
        }
        StringBuilder log = new StringBuilder();
        log.append("(wood_tool_recipe=").append(woodenToolRecipeId).append(") ");
        log.append(closeForeignContainers(ctx)).append(" ");
        sleepAi(100);
        if (MistralAgent.isCancelled()) {
            return "Cancelled.";
        }
        log.append(craftPlanksFromLogs(ctx, 5)).append(" ");
        sleepAi(200);
        log.append(craftSticks(ctx, 1)).append(" ");
        sleepAi(200);
        log.append(craftCraftingTable(ctx)).append(" ");
        sleepAi(200);
        String placed = placeCraftingTableWithRetry(ctx);
        log.append(placed).append(" ");
        if (placed.startsWith("ERROR:")) {
            // Without a real table there is nothing to open or craft at — burning
            // minutes head-swiveling at a phantom position froze whole missions.
            // Fail FAST with advice so the model can move somewhere open and retry.
            return log + "ERROR: Crafting table could not be placed here. "
                    + "Move a few blocks to open, flat ground (e.g. goto_coords) and call this tool again.";
        }
        sleepAi(450);
        boolean tableOk = Boolean.TRUE.equals(onClient(ctx, () -> {
            BlockPos pos = lastPlacedCraftingTablePos;
            if (pos == null) {
                return false;
            }
            return ctx.player().level().getBlockState(pos).is(Blocks.CRAFTING_TABLE);
        }));
        if (!tableOk) {
            log.append("(WARN: crafting table block not detected at remembered pos) ");
        }
        log.append(openPlacedCraftingTable(ctx)).append(" ");
        sleepAi(350);
        log.append(craftRecipeAtTable(ctx, woodenToolRecipeId)).append(" ");
        sleepAi(150);
        Item want = RECIPE_WOODEN_AXE.equals(woodenToolRecipeId) ? Items.WOODEN_AXE : Items.WOODEN_PICKAXE;
        if (!playerInventoryContainsItem(ctx, want)) {
            log.append("ERROR: Expected ").append(BuiltInRegistries.ITEM.getKey(want))
                    .append(" not in inventory after recipe craft (check craft message, materials, or lag). ");
        }
        sleepAi(150);
        onClient(ctx, () -> {
            ctx.player().closeContainer();
            return "";
        });
        return log.toString().trim();
    }

    public static String openNearbyOrPlaceCraftingTable(IPlayerContext ctx) {
        String nearby = openReachableCraftingTable(ctx);
        if (nearby.startsWith("Opened") || nearby.startsWith("Already")) {
            return nearby;
        }
        // None in immediate reach: walk to a table we placed earlier (e.g. stepped off it while
        // mining) and re-use it before placing a second one. Same fix as the furnace re-open path.
        BlockPos walked = walkToNearbyStation(ctx, Blocks.CRAFTING_TABLE, 32);
        if (walked != null) {
            String r = openTableAtPositionVisible(ctx, walked);
            if (r.startsWith("Opened") || r.startsWith("Already")) {
                return r;
            }
        }
        boolean hasTable = Boolean.TRUE.equals(onClient(ctx, () -> findItemSlot(ctx.player().containerMenu, Items.CRAFTING_TABLE) >= 0));
        if (!hasTable) {
            return "ERROR: No reachable crafting table and no crafting table item in inventory.";
        }
        String placed = placeCraftingTableWithRetry(ctx);
        sleepAi(350);
        if (placed.startsWith("ERROR:")) {
            return placed;
        }
        String opened = openPlacedCraftingTable(ctx);
        return opened.startsWith("WARN:") ? placed + " " + opened : opened;
    }

    /**
     * Generic "ensure a usable station is open" for non-crafting-table stations
     * (furnace, blast_furnace, smoker, brewing_stand, stonecutter, smithing_table,
     * anvil). Each call: open a reachable one (radius 6) if present, else place one
     * from inventory RIGHT NEXT to the player and open it. Never walks to a distant
     * cached block — that's the caller's last-resort goto. {@code menuOpen} returns
     * true when the correct station GUI is showing.
     */
    public static String openNearbyOrPlaceStation(IPlayerContext ctx,
            net.minecraft.world.level.block.Block block,
            net.minecraft.world.item.Item item,
            String displayName,
            java.util.concurrent.Callable<Boolean> menuOpen) {
        if (stationMenuOpen(menuOpen)) {
            return "Already open: " + displayName + ".";
        }
        // 1) open a reachable existing one
        BlockPos near = onClient(ctx, () -> findReachableStation(ctx.player(), 6, block));
        if (near != null) {
            String r = openStationAtPositionVisible(ctx, near, displayName, menuOpen);
            if (r.startsWith("Opened") || r.startsWith("Already")) {
                return r;
            }
        }
        // 1b) None in immediate reach: walk to the nearest one we can see nearby (e.g. a furnace we
        // placed then stepped away from) and RE-USE it. Critical for furnaces — placing a second one
        // would strand the smelting output in the first. Bounded radius so we never trek across the map.
        BlockPos walked = walkToNearbyStation(ctx, block, 32);
        if (walked != null) {
            String r = openStationAtPositionVisible(ctx, walked, displayName, menuOpen);
            if (r.startsWith("Opened") || r.startsWith("Already")) {
                return r;
            }
        }
        // 2) place one from inventory next to the player, then open it
        boolean hasItem = Boolean.TRUE.equals(onClient(ctx,
                () -> findItemSlot(ctx.player().inventoryMenu, item) >= 0));
        if (!hasItem) {
            return "WARN: No reachable " + displayName + " within 6 blocks and no item to place one.";
        }
        BlockPos placed = placeStationBlock(ctx, item, block, displayName);
        for (int attempt = 0; attempt < 3 && placed == null && !MistralAgent.isCancelled(); attempt++) {
            // walk to a known-good open spot before retrying (don't just look-down-and-hope)
            if (!walkToPlaceableSpot(ctx)) {
                nudgePlayer(ctx);
            }
            placed = placeStationBlock(ctx, item, block, displayName);
        }
        if (placed == null) {
            return "ERROR: Could not place " + displayName + " — no clear spot nearby (try moving to open ground).";
        }
        sleepAi(350);
        String opened = openStationAtPositionVisible(ctx, placed, displayName, menuOpen);
        if (opened.startsWith("Opened") || opened.startsWith("Already")) {
            return opened;
        }
        return "Placed " + displayName + " at " + placed + " but could not open its GUI yet (" + opened + ").";
    }

    private static boolean stationMenuOpen(java.util.concurrent.Callable<Boolean> menuOpen) {
        try {
            return Boolean.TRUE.equals(menuOpen.call());
        } catch (Exception e) {
            return false;
        }
    }

    /** Nearest matching station block within {@code radius} (NOT limited to immediate reach). */
    private static BlockPos findNearestStationBlock(LocalPlayer p, int radius,
            net.minecraft.world.level.block.Block block) {
        Level level = p.level();
        BlockPos origin = p.blockPosition();
        double best = Double.MAX_VALUE;
        BlockPos bestPos = null;
        for (int y = -4; y <= 4; y++) {
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    BlockPos pos = origin.offset(x, y, z);
                    if (!level.getBlockState(pos).is(block)) {
                        continue;
                    }
                    double dist = origin.distSqr(pos);
                    if (dist < best) {
                        best = dist;
                        bestPos = pos;
                    }
                }
            }
        }
        return bestPos;
    }

    /**
     * Path to a station block we can see within {@code radius} but isn't in immediate reach (e.g. a
     * furnace we placed then stepped off of), so we re-use it instead of placing a second one. Returns
     * the station pos once we're within reach of it, or null if none found / couldn't get there.
     * Always releases the temporary goal so a failed attempt never leaves a goto running in the
     * background (that strands the agent walking off across the map).
     */
    private static BlockPos walkToNearbyStation(IPlayerContext ctx,
            net.minecraft.world.level.block.Block block, int radius) {
        IBaritone b = BaritoneAPI.getProvider().getPrimaryBaritone();
        if (b == null) {
            return null;
        }
        BlockPos target = onClient(ctx, () -> findNearestStationBlock(ctx.player(), radius, block));
        if (target == null) {
            return null;
        }
        boolean already = Boolean.TRUE.equals(onClient(ctx, () -> withinReach(ctx.player(), target)));
        if (already) {
            return target;
        }
        try {
            onClient(ctx, () -> {
                b.getCustomGoalProcess().setGoalAndPath(new baritone.api.pathing.goals.GoalGetToBlock(target));
                return null;
            });
            long deadline = System.currentTimeMillis() + 30_000L;
            while (System.currentTimeMillis() < deadline) {
                if (MistralAgent.isCancelled()) {
                    break;
                }
                if (Boolean.TRUE.equals(onClient(ctx, () -> withinReach(ctx.player(), target)))) {
                    return target;
                }
                if (!b.getPathingBehavior().isPathing() && !b.getPathingBehavior().getInProgress().isPresent()) {
                    break; // path completed or couldn't progress
                }
                sleepAi(300);
            }
            return Boolean.TRUE.equals(onClient(ctx, () -> withinReach(ctx.player(), target))) ? target : null;
        } finally {
            onClient(ctx, () -> {
                b.getCustomGoalProcess().onLostControl();
                return null;
            });
        }
    }

    private static boolean withinReach(LocalPlayer p, BlockPos pos) {
        return p.getEyePosition(1f).distanceToSqr(Vec3.atCenterOf(pos)) <= 5.5D * 5.5D;
    }

    private static BlockPos findReachableStation(LocalPlayer p, int radius,
            net.minecraft.world.level.block.Block block) {
        Level level = p.level();
        BlockPos origin = p.blockPosition();
        double reach = 5.25D;
        double best = Double.MAX_VALUE;
        BlockPos bestPos = null;
        Vec3 eye = p.getEyePosition(1f);
        for (int y = -2; y <= 2; y++) {
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    BlockPos pos = origin.offset(x, y, z);
                    if (!level.getBlockState(pos).is(block)) {
                        continue;
                    }
                    double dist = eye.distanceToSqr(Vec3.atCenterOf(pos));
                    if (dist <= reach * reach && dist < best) {
                        best = dist;
                        bestPos = pos;
                    }
                }
            }
        }
        return bestPos;
    }

    /** Snap-aim settings so placement reliably lands on its target (stealth/smoothLook makes it miss). */
    private static void snappyAimForPlacement() {
        BaritoneAPI.getSettings().smoothLook.value = false;
        BaritoneAPI.getSettings().strictVisibleBlockInteractions.value = false;
        BaritoneAPI.getSettings().freeLook.value = true;
    }

    /** Place {@code item} against a support block adjacent to the player; returns the placed pos or null. */
    private static BlockPos placeStationBlock(IPlayerContext ctx,
            net.minecraft.world.item.Item item,
            net.minecraft.world.level.block.Block block,
            String displayName) {
        snappyAimForPlacement();
        TablePlacementPlan plan = onClient(ctx, () -> {
            LocalPlayer p = ctx.player();
            p.closeContainer();
            if (!(p.containerMenu instanceof InventoryMenu)) {
                return TablePlacementPlan.error("ERROR: expected player inventory menu.");
            }
            int slot = findItemSlot(p.inventoryMenu, item);
            if (slot < 0) {
                return TablePlacementPlan.error("ERROR: no " + displayName + " item.");
            }
            int hotbar = p.getInventory().getSelectedSlot();
            ctx.playerController().windowClick(p.inventoryMenu.containerId, slot, hotbar, ClickType.SWAP, p);
            p.getInventory().setSelectedSlot(hotbar);
            BlockHitResult hit = findTablePlacementHit(p);
            if (hit == null) {
                return TablePlacementPlan.error("ERROR: no spot to place against.");
            }
            return TablePlacementPlan.ok(hit, hit.getBlockPos().relative(hit.getDirection()));
        });
        if (plan.error != null) {
            return null;
        }
        // Aim best-effort; don't bail on a slightly-off crosshair — waitForBlock verifies.
        visiblyLookAt(ctx, plan.hit.getLocation(), 36);
        waitCrosshairOnBlock(ctx, plan.hit.getBlockPos(), 12, 50);
        BlockPos actualPlaced = onClient(ctx, () -> {
            Minecraft mc = Minecraft.getInstance();
            LocalPlayer p = ctx.player();
            if (!p.getInventory().getSelectedItem().is(item)) {
                int slot = findItemSlot(p.inventoryMenu, item);
                if (slot >= 0) {
                    int hotbar = p.getInventory().getSelectedSlot();
                    ctx.playerController().windowClick(p.inventoryMenu.containerId, slot, hotbar, ClickType.SWAP, p);
                    p.getInventory().setSelectedSlot(hotbar);
                }
            }
            mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, plan.hit);
            p.swing(InteractionHand.MAIN_HAND);
            return plan.placed;
        });
        return waitForBlock(ctx, actualPlaced, block, 30, 100) ? actualPlaced : null;
    }

    /** Visible look at the block on each face and right-click until the station GUI opens. */
    private static String openStationAtPositionVisible(IPlayerContext ctx, BlockPos pos, String displayName,
            java.util.concurrent.Callable<Boolean> menuOpen) {
        if (stationMenuOpen(menuOpen)) {
            return "Already open: " + displayName + ".";
        }
        for (int attempt = 0; attempt < 3; attempt++) {
            for (Direction face : Direction.values()) {
                Vec3 hitVec = Vec3.atCenterOf(pos).add(face.getStepX() * 0.52, face.getStepY() * 0.52, face.getStepZ() * 0.52);
                if (!visiblyLookAt(ctx, hitVec, 24) || !waitCrosshairOnBlock(ctx, pos, 8, 50)) {
                    continue;
                }
                onClient(ctx, () -> {
                    Minecraft mc = Minecraft.getInstance();
                    LocalPlayer p = ctx.player();
                    p.closeContainer();
                    BlockHitResult hit = new BlockHitResult(hitVec, face.getOpposite(), pos, false);
                    mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, hit);
                    p.swing(InteractionHand.MAIN_HAND);
                    return null;
                });
                for (int wait = 0; wait < 6; wait++) {
                    if (stationMenuOpen(menuOpen)) {
                        return "Opened " + displayName + ".";
                    }
                    sleepAi(75);
                }
            }
            sleepAi(120);
        }
        return "WARN: could not open " + displayName + " GUI.";
    }

    private static String openReachableCraftingTable(IPlayerContext ctx) {
        if (Boolean.TRUE.equals(onClient(ctx, () -> ctx.player().containerMenu instanceof CraftingMenu))) {
            return "Already open: crafting table.";
        }
        for (int attempt = 0; attempt < 16; attempt++) {
            if (MistralAgent.isCancelled()) {
                return "Cancelled while opening crafting table.";
            }
            BlockPos pos = onClient(ctx, () -> {
                BlockPos found = findReachableCraftingTable(ctx.player(), 5);
                if (found != null) {
                    lastPlacedCraftingTablePos = found;
                }
                return found;
            });
            if (pos == null) {
                sleepAi(100);
                continue;
            }
            String r = openTableAtPositionVisible(ctx, pos);
            if (r.startsWith("Opened") || r.startsWith("Already") || r.startsWith("ERROR:")) {
                return r;
            }
            sleepAi(100);
        }
        return "WARN: No reachable crafting table opened.";
    }

    private static BlockPos findReachableCraftingTable(LocalPlayer p, int radius) {
        Level level = p.level();
        BlockPos origin = p.blockPosition();
        double reach = 5.25D;
        double best = Double.MAX_VALUE;
        BlockPos bestPos = null;
        Vec3 eye = p.getEyePosition(1f);
        for (int y = -2; y <= 2; y++) {
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    BlockPos pos = origin.offset(x, y, z);
                    if (!level.getBlockState(pos).is(Blocks.CRAFTING_TABLE)) {
                        continue;
                    }
                    double dist = eye.distanceToSqr(Vec3.atCenterOf(pos));
                    if (dist <= reach * reach && dist < best) {
                        best = dist;
                        bestPos = pos;
                    }
                }
            }
        }
        return bestPos;
    }

    private static String placeCraftingTableBlock(IPlayerContext ctx) {
        snappyAimForPlacement(); // stealth/smooth-look would make aiming miss the support block
        TablePlacementPlan plan = onClient(ctx, () -> {
            LocalPlayer p = ctx.player();
            p.closeContainer();
            if (!(p.containerMenu instanceof InventoryMenu)) {
                return TablePlacementPlan.error("ERROR: place table: expected player inventory menu.");
            }
            int slot = findItemSlot(p.inventoryMenu, Items.CRAFTING_TABLE);
            if (slot < 0) {
                return TablePlacementPlan.error("ERROR: No crafting table item (craft one from planks first).");
            }
            int hotbar = p.getInventory().getSelectedSlot();
            ctx.playerController().windowClick(p.inventoryMenu.containerId, slot, hotbar, ClickType.SWAP, p);
            p.getInventory().setSelectedSlot(hotbar);
            BlockHitResult hit = findTablePlacementHit(p);
            if (hit == null) {
                return TablePlacementPlan.error("ERROR: No solid block in front to place crafting table against.");
            }
            BlockPos placed = hit.getBlockPos().relative(hit.getDirection());
            lastPlacedCraftingTablePos = placed;
            return TablePlacementPlan.ok(hit, placed);
        });
        if (plan.error != null) {
            return plan.error;
        }
        // Aim best-effort, but DON'T abort if the crosshair raytrace is slightly off — a
        // hand-built hit still places, and waitForBlock below is the real success check.
        visiblyLookAt(ctx, plan.hit.getLocation(), 36);
        waitCrosshairOnBlock(ctx, plan.hit.getBlockPos(), 12, 50);
        BlockPos[] placedAt = {plan.placed};
        String click = onClient(ctx, () -> {
            Minecraft mc = Minecraft.getInstance();
            LocalPlayer p = ctx.player();
            if (!p.getInventory().getSelectedItem().is(Items.CRAFTING_TABLE)) {
                int slot = findItemSlot(p.inventoryMenu, Items.CRAFTING_TABLE);
                if (slot < 0) {
                    return "ERROR: Crafting table left the hotbar before placement.";
                }
                int hotbar = p.getInventory().getSelectedSlot();
                ctx.playerController().windowClick(p.inventoryMenu.containerId, slot, hotbar, ClickType.SWAP, p);
                p.getInventory().setSelectedSlot(hotbar);
            }
            placedAt[0] = plan.placed;
            InteractionResult res = mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, plan.hit);
            p.swing(InteractionHand.MAIN_HAND);
            return "CLICK: " + res;
        });
        if (click.startsWith("ERROR:")) {
            lastPlacedCraftingTablePos = null;
            return click;
        }
        if (!waitForBlock(ctx, placedAt[0], Blocks.CRAFTING_TABLE, 30, 100)) {
            // Never leave the remembered position pointing at a spot with no table —
            // downstream open/craft loops would chase the phantom for minutes.
            lastPlacedCraftingTablePos = null;
            return "ERROR: Crafting table did not appear at " + placedAt[0] + " after visible right-click (" + click + ").";
        }
        lastPlacedCraftingTablePos = placedAt[0];
        return "Placed crafting table (" + click.substring("CLICK: ".length()) + ") at ~" + lastPlacedCraftingTablePos + ".";
    }

    /**
     * Retries on the AI thread so each {@code useItemOn} runs after ticks can process;
     * tries multiple hit faces because a single ray can miss on laggy servers.
     */
    private static String openPlacedCraftingTable(IPlayerContext ctx) {
        for (int attempt = 0; attempt < 12; attempt++) {
            if (MistralAgent.isCancelled()) {
                return "Cancelled while opening table.";
            }
            BlockPos pos = lastPlacedCraftingTablePos;
            if (pos == null) {
                return "ERROR: No remembered crafting table position.";
            }
            // Cheap sanity check BEFORE the slow visible look-and-click dance:
            // if no table actually exists there, every face is doomed — bail now
            // instead of head-swiveling at a phantom spot for minutes.
            boolean tableThere = Boolean.TRUE.equals(onClient(ctx, () ->
                    ctx.player().level().getBlockState(pos).is(Blocks.CRAFTING_TABLE)));
            if (!tableThere) {
                lastPlacedCraftingTablePos = null;
                return "ERROR: No crafting table exists at remembered position " + pos + ".";
            }
            String r = openTableAtPositionVisible(ctx, pos);
            if (r.startsWith("Opened") || r.startsWith("Already") || r.startsWith("ERROR:")) {
                return r;
            }
            sleepAi(110);
        }
        return "WARN: Could not open crafting table GUI after retries.";
    }

    private static String openTableAtPositionVisible(IPlayerContext ctx, BlockPos pos) {
        if (Boolean.TRUE.equals(onClient(ctx, () -> ctx.player().containerMenu instanceof CraftingMenu))) {
            return "Already open: crafting table.";
        }
        for (Direction face : Direction.values()) {
            Vec3 hitVec = Vec3.atCenterOf(pos).add(
                    face.getStepX() * 0.52,
                    face.getStepY() * 0.52,
                    face.getStepZ() * 0.52);
            if (!visiblyLookAt(ctx, hitVec, 28)) {
                continue;
            }
            if (!waitCrosshairOnBlock(ctx, pos, 8, 50)) {
                continue;
            }
            String r = onClient(ctx, () -> tryOpenTableAtPosWithFace(ctx, pos, face, hitVec));
            if (r.startsWith("OK:")) {
                return r.substring(3).trim();
            }
            if (r.startsWith("ERROR:")) {
                return r;
            }
            for (int wait = 0; wait < 6; wait++) {
                if (Boolean.TRUE.equals(onClient(ctx, () -> ctx.player().containerMenu instanceof CraftingMenu))) {
                    return "Opened crafting table GUI.";
                }
                sleepAi(75);
            }
        }
        // Direct fallback: the crosshair-gated pass above can fail in cramped/awkward terrain (the
        // crosshair never settles on the table, so every face is skipped). We know exactly where the
        // table is, so right-click each face with a hand-built hit WITHOUT the crosshair gate —
        // best-effort aim only. This is what got placement reliable; mirror it for opening.
        for (Direction face : new Direction[]{Direction.UP, Direction.NORTH, Direction.SOUTH,
                Direction.EAST, Direction.WEST, Direction.DOWN}) {
            if (MistralAgent.isCancelled()) {
                return "RETRY";
            }
            Vec3 hitVec = Vec3.atCenterOf(pos).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
            visiblyLookAt(ctx, hitVec, 16);   // aim if we can, but don't bail when the crosshair won't settle
            String r = onClient(ctx, () -> tryOpenTableAtPosWithFace(ctx, pos, face, hitVec));
            if (r.startsWith("OK:")) {
                return r.substring(3).trim();
            }
            if (r.startsWith("ERROR:")) {
                return r;
            }
            for (int wait = 0; wait < 6; wait++) {
                if (Boolean.TRUE.equals(onClient(ctx, () -> ctx.player().containerMenu instanceof CraftingMenu))) {
                    return "Opened crafting table GUI.";
                }
                sleepAi(75);
            }
        }
        return "RETRY";
    }

    private static String tryOpenTableAtPosWithFace(IPlayerContext ctx, BlockPos pos, Direction face, Vec3 hitVec) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = ctx.player();
        if (!p.level().getBlockState(pos).is(Blocks.CRAFTING_TABLE)) {
            return "ERROR: Expected block at " + pos + " is not a crafting table.";
        }
        p.closeContainer();
        BlockHitResult hit = new BlockHitResult(hitVec, face.getOpposite(), pos, false);
        InteractionResult res = mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, hit);
        p.swing(InteractionHand.MAIN_HAND);
        if (p.containerMenu instanceof CraftingMenu) {
            return "OK: Opened crafting table GUI (" + res + ").";
        }
        return "RETRY";
    }

    /**
     * Opens an ender chest and returns its real contents. Opens a reachable ender chest if one is within
     * reach, otherwise places one from the player's inventory and opens that, then reads the synced
     * contents. Falls back to the last-known contents if it cannot open or place one. The placed ender
     * chest is intentionally left in the world (breaking it without silk touch would destroy it).
     */
    public static String openEnderChestAndRead(IPlayerContext ctx) {
        if (Boolean.TRUE.equals(onClient(ctx, () -> !(ctx.player().containerMenu instanceof InventoryMenu)))) {
            String c = readEnderChestContents(ctx);
            onClient(ctx, () -> {
                ctx.player().closeContainer();
                return null;
            });
            return "A container was already open. " + c;
        }
        String opened = openReachableEnderChest(ctx);
        if (opened.startsWith("Cancelled")) {
            return opened;
        }
        if (!opened.startsWith("Opened") && !opened.startsWith("Already")) {
            boolean hasChest = Boolean.TRUE.equals(onClient(ctx,
                    () -> findItemSlot(ctx.player().inventoryMenu, Items.ENDER_CHEST) >= 0));
            if (!hasChest) {
                return "ERROR: No ender chest within reach and no ender chest item to place. Last known "
                        + readEnderChestContents(ctx);
            }
            String placed = placeEnderChestBlock(ctx);
            if (placed.startsWith("ERROR:")) {
                return placed;
            }
            sleepAi(300);
            String openPlaced = openPlacedEnderChest(ctx);
            if (!openPlaced.startsWith("Opened") && !openPlaced.startsWith("Already")) {
                return placed + " " + openPlaced;
            }
            opened = placed + " " + openPlaced;
        }
        sleepAi(300); // let the server sync the chest contents into the menu
        String contents = readEnderChestContents(ctx);
        onClient(ctx, () -> {
            ctx.player().closeContainer();
            return null;
        });
        return opened + " " + contents;
    }

    private static String openReachableEnderChest(IPlayerContext ctx) {
        for (int attempt = 0; attempt < 12; attempt++) {
            if (MistralAgent.isCancelled()) {
                return "Cancelled while opening ender chest.";
            }
            BlockPos pos = onClient(ctx, () -> findReachableBlock(ctx.player(), 5, Blocks.ENDER_CHEST));
            if (pos == null) {
                return "WARN: No reachable ender chest.";
            }
            String r = openEnderChestAt(ctx, pos);
            if (r.startsWith("Opened") || r.startsWith("Already") || r.startsWith("ERROR:")) {
                return r;
            }
            sleepAi(100);
        }
        return "WARN: Could not open a reachable ender chest.";
    }

    private static String openPlacedEnderChest(IPlayerContext ctx) {
        for (int attempt = 0; attempt < 36; attempt++) {
            if (MistralAgent.isCancelled()) {
                return "Cancelled while opening ender chest.";
            }
            BlockPos pos = lastEnderChestPos;
            if (pos == null) {
                return "ERROR: No remembered ender chest position.";
            }
            String r = openEnderChestAt(ctx, pos);
            if (r.startsWith("Opened") || r.startsWith("Already") || r.startsWith("ERROR:")) {
                return r;
            }
            sleepAi(110);
        }
        return "WARN: Could not open the placed ender chest GUI after retries.";
    }

    private static String openEnderChestAt(IPlayerContext ctx, BlockPos pos) {
        if (pos == null) {
            return "ERROR: No ender chest position.";
        }
        if (Boolean.TRUE.equals(onClient(ctx, () -> !(ctx.player().containerMenu instanceof InventoryMenu)))) {
            return "Already open: a container.";
        }
        for (Direction face : Direction.values()) {
            if (face == Direction.DOWN) {
                continue;
            }
            Vec3 hitVec = Vec3.atCenterOf(pos).add(
                    face.getStepX() * 0.52,
                    face.getStepY() * 0.52,
                    face.getStepZ() * 0.52);
            if (!visiblyLookAt(ctx, hitVec, 28)) {
                continue;
            }
            if (!waitCrosshairOnBlock(ctx, pos, 8, 50)) {
                continue;
            }
            String r = onClient(ctx, () -> tryOpenEnderChestAtPosWithFace(ctx, pos, face, hitVec));
            if (r.startsWith("OK:")) {
                return r.substring(3).trim();
            }
            if (r.startsWith("ERROR:")) {
                return r;
            }
            for (int wait = 0; wait < 6; wait++) {
                if (Boolean.TRUE.equals(onClient(ctx, () -> !(ctx.player().containerMenu instanceof InventoryMenu)))) {
                    return "Opened ender chest.";
                }
                sleepAi(75);
            }
        }
        return "RETRY";
    }

    private static String tryOpenEnderChestAtPosWithFace(IPlayerContext ctx, BlockPos pos, Direction face, Vec3 hitVec) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = ctx.player();
        if (!p.level().getBlockState(pos).is(Blocks.ENDER_CHEST)) {
            return "ERROR: Block at " + pos + " is not an ender chest.";
        }
        p.closeContainer();
        BlockHitResult hit = new BlockHitResult(hitVec, face.getOpposite(), pos, false);
        InteractionResult res = mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, hit);
        p.swing(InteractionHand.MAIN_HAND);
        if (!(p.containerMenu instanceof InventoryMenu)) {
            return "OK: Opened ender chest (" + res + ").";
        }
        return "RETRY";
    }

    private static String placeEnderChestBlock(IPlayerContext ctx) {
        TablePlacementPlan plan = onClient(ctx, () -> {
            LocalPlayer p = ctx.player();
            p.closeContainer();
            if (!(p.containerMenu instanceof InventoryMenu)) {
                return TablePlacementPlan.error("ERROR: place ender chest: expected player inventory menu.");
            }
            int slot = findItemSlot(p.inventoryMenu, Items.ENDER_CHEST);
            if (slot < 0) {
                return TablePlacementPlan.error("ERROR: No ender chest item in inventory.");
            }
            int hotbar = p.getInventory().getSelectedSlot();
            ctx.playerController().windowClick(p.inventoryMenu.containerId, slot, hotbar, ClickType.SWAP, p);
            p.getInventory().setSelectedSlot(hotbar);
            BlockHitResult hit = findTablePlacementHit(p);
            if (hit == null) {
                return TablePlacementPlan.error("ERROR: No solid block in front to place the ender chest against.");
            }
            BlockPos placed = hit.getBlockPos().relative(hit.getDirection());
            lastEnderChestPos = placed;
            return TablePlacementPlan.ok(hit, placed);
        });
        if (plan.error != null) {
            return plan.error;
        }
        if (!visiblyLookAt(ctx, plan.hit.getLocation(), 36)) {
            return "ERROR: Could not visibly aim at the ender chest placement support block.";
        }
        if (!waitCrosshairOnBlock(ctx, plan.hit.getBlockPos(), 12, 50)) {
            return "ERROR: Crosshair was not on the ender chest placement support block after the visible turn.";
        }
        String click = onClient(ctx, () -> {
            Minecraft mc = Minecraft.getInstance();
            LocalPlayer p = ctx.player();
            if (!p.getInventory().getSelectedItem().is(Items.ENDER_CHEST)) {
                int slot = findItemSlot(p.inventoryMenu, Items.ENDER_CHEST);
                if (slot < 0) {
                    return "ERROR: Ender chest left the hotbar before placement.";
                }
                int hotbar = p.getInventory().getSelectedSlot();
                ctx.playerController().windowClick(p.inventoryMenu.containerId, slot, hotbar, ClickType.SWAP, p);
                p.getInventory().setSelectedSlot(hotbar);
            }
            InteractionResult res = mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, plan.hit);
            p.swing(InteractionHand.MAIN_HAND);
            return "CLICK: " + res;
        });
        if (click.startsWith("ERROR:")) {
            return click;
        }
        if (!waitForBlock(ctx, plan.placed, Blocks.ENDER_CHEST, 30, 100)) {
            return "ERROR: Ender chest did not appear at " + plan.placed + " after right-click (" + click + ").";
        }
        lastEnderChestPos = plan.placed;
        return "Placed ender chest at ~" + lastEnderChestPos + ".";
    }

    private static String readEnderChestContents(IPlayerContext ctx) {
        return onClient(ctx, () -> {
            LocalPlayer p = ctx.player();
            Container ender = p.getEnderChestInventory();
            java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();
            for (int i = 0; i < ender.getContainerSize(); i++) {
                ItemStack stack = ender.getItem(i);
                if (stack == null || stack.isEmpty()) {
                    continue;
                }
                counts.merge(stack.getItem().toString(), stack.getCount(), Integer::sum);
            }
            if (counts.isEmpty()) {
                return "Ender chest is empty.";
            }
            StringBuilder sb = new StringBuilder("Ender chest contents:");
            for (java.util.Map.Entry<String, Integer> e : counts.entrySet()) {
                sb.append(' ').append(e.getKey()).append(" x").append(e.getValue()).append(';');
            }
            return sb.toString();
        });
    }

    private static BlockPos findReachableBlock(LocalPlayer p, int radius, Block block) {
        Level level = p.level();
        BlockPos origin = p.blockPosition();
        double reach = 5.25D;
        double best = Double.MAX_VALUE;
        BlockPos bestPos = null;
        Vec3 eye = p.getEyePosition(1f);
        for (int y = -2; y <= 2; y++) {
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    BlockPos pos = origin.offset(x, y, z);
                    if (!level.getBlockState(pos).is(block)) {
                        continue;
                    }
                    double dist = eye.distanceToSqr(Vec3.atCenterOf(pos));
                    if (dist <= reach * reach && dist < best) {
                        best = dist;
                        bestPos = pos;
                    }
                }
            }
        }
        return bestPos;
    }

    private static void lookAtBlockCenter(LocalPlayer p, BlockPos pos) {
        Vec3 eye = p.getEyePosition(1f);
        Vec3 target = Vec3.atCenterOf(pos);
        double dx = target.x - eye.x;
        double dy = target.y - eye.y;
        double dz = target.z - eye.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.atan2(-dx, dz) * 57.29577951308232);
        float pitch = (float) (Math.atan2(-dy, Math.max(1.0e-6, horiz)) * 57.29577951308232);
        p.setYRot(yaw);
        p.setXRot(pitch);
    }

    // package-visible so BaritoneTools (entity interaction) can reuse the
    // human-like aim-before-click turn instead of snapping the head.
    static boolean visiblyLookAt(IPlayerContext ctx, Vec3 target, int maxTicks) {
        if (target == null) {
            return false;
        }
        int ticks = Math.max(1, maxTicks);
        for (int i = 0; i < ticks; i++) {
            if (MistralAgent.isCancelled()) {
                return false;
            }
            boolean done = Boolean.TRUE.equals(onClient(ctx, () -> {
                LocalPlayer p = ctx.player();
                float[] want = rotationTo(p.getEyePosition(1f), target);
                float yawDiff = wrapDegrees(want[0] - p.getYRot());
                float pitchDiff = want[1] - p.getXRot();
                if (Math.abs(yawDiff) <= 0.8F && Math.abs(pitchDiff) <= 0.8F) {
                    p.setYRot(want[0]);
                    p.setXRot(clamp(want[1], -90.0F, 90.0F));
                    return true;
                }
                float yawStep = clamp(yawDiff, -visibleYawStep(), visibleYawStep());
                float pitchStep = clamp(pitchDiff, -visiblePitchStep(), visiblePitchStep());
                p.setYRot(p.getYRot() + yawStep);
                p.setXRot(clamp(p.getXRot() + pitchStep, -90.0F, 90.0F));
                return false;
            }));
            if (done) {
                return true;
            }
            sleepAi(50);
        }
        return Boolean.TRUE.equals(onClient(ctx, () -> {
            LocalPlayer p = ctx.player();
            float[] want = rotationTo(p.getEyePosition(1f), target);
            return Math.abs(wrapDegrees(want[0] - p.getYRot())) <= 1.4F
                    && Math.abs(want[1] - p.getXRot()) <= 1.4F;
        }));
    }

    private static boolean waitCrosshairOnBlock(IPlayerContext ctx, BlockPos pos, int attempts, long delayMs) {
        for (int i = 0; i < attempts; i++) {
            if (Boolean.TRUE.equals(onClient(ctx, () -> {
                HitResult hit = ctx.objectMouseOver();
                return hit instanceof BlockHitResult bhr
                        && hit.getType() == HitResult.Type.BLOCK
                        && bhr.getBlockPos().equals(pos);
            }))) {
                return true;
            }
            sleepAi(delayMs);
        }
        return false;
    }

    private static boolean waitForBlock(IPlayerContext ctx, BlockPos pos, Block block, int attempts, long delayMs) {
        for (int i = 0; i < attempts; i++) {
            if (Boolean.TRUE.equals(onClient(ctx, () -> ctx.player().level().getBlockState(pos).is(block)))) {
                return true;
            }
            sleepAi(delayMs);
        }
        return false;
    }

    private static float[] rotationTo(Vec3 eye, Vec3 target) {
        double dx = target.x - eye.x;
        double dy = target.y - eye.y;
        double dz = target.z - eye.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.atan2(-dx, dz) * 57.29577951308232);
        float pitch = (float) (Math.atan2(-dy, Math.max(1.0e-6, horiz)) * 57.29577951308232);
        return new float[] { yaw, pitch };
    }

    private static float visibleYawStep() {
        double configured = BaritoneAPI.getSettings().undercoverLookYawSpeed.value;
        if (configured <= 0.0D) {
            return 180.0F;
        }
        return (float) Math.max(0.6D, Math.min(45.0D, configured));
    }

    private static float visiblePitchStep() {
        double configured = BaritoneAPI.getSettings().undercoverLookPitchSpeed.value;
        if (configured <= 0.0D) {
            return 180.0F;
        }
        return (float) Math.max(0.6D, Math.min(45.0D, configured));
    }

    private static float wrapDegrees(float value) {
        float wrapped = value % 360.0F;
        if (wrapped >= 180.0F) {
            wrapped -= 360.0F;
        }
        if (wrapped < -180.0F) {
            wrapped += 360.0F;
        }
        return wrapped;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static final class TablePlacementPlan {
        final BlockHitResult hit;
        final BlockPos placed;
        final String error;

        private TablePlacementPlan(BlockHitResult hit, BlockPos placed, String error) {
            this.hit = hit;
            this.placed = placed;
            this.error = error;
        }

        static TablePlacementPlan ok(BlockHitResult hit, BlockPos placed) {
            return new TablePlacementPlan(hit, placed, null);
        }

        static TablePlacementPlan error(String error) {
            return new TablePlacementPlan(null, null, error);
        }
    }

    private static final class BlockUsePlan {
        final String blockId;
        final BlockPos pos;
        final String error;

        private BlockUsePlan(String blockId, BlockPos pos, String error) {
            this.blockId = blockId;
            this.pos = pos;
            this.error = error;
        }

        static BlockUsePlan ok(String blockId, BlockPos pos) {
            return new BlockUsePlan(blockId, pos, null);
        }

        static BlockUsePlan error(String error) {
            return new BlockUsePlan("", null, error);
        }
    }

    /** Step the player forward a moment to get off an obstructed/edge spot, then settle. */
    private static void nudgePlayer(IPlayerContext ctx) {
        IBaritone b = BaritoneAPI.getProvider().getPrimaryBaritone();
        if (b == null) {
            return;
        }
        onClient(ctx, () -> {
            b.getInputOverrideHandler().setInputForceState(baritone.api.utils.input.Input.MOVE_FORWARD, true);
            return null;
        });
        sleepAi(550); // ~11 ticks forward
        onClient(ctx, () -> {
            b.getInputOverrideHandler().clearAllKeys();
            return null;
        });
        sleepAi(250);
    }

    /** Place a crafting table; if the spot is blocked, WALK to a known-good open spot and place there. */
    private static String placeCraftingTableWithRetry(IPlayerContext ctx) {
        String r = placeCraftingTableBlock(ctx);
        for (int attempt = 0; attempt < 3 && r.startsWith("ERROR:") && !MistralAgent.isCancelled(); attempt++) {
            // Don't just look-down-and-hope: actively find a flat open cell with room and a place to
            // stand next to it, pathfind there, THEN place. Falls back to a nudge if no spot is found.
            if (!walkToPlaceableSpot(ctx)) {
                nudgePlayer(ctx);
            }
            r = placeCraftingTableBlock(ctx);
        }
        return r;
    }

    /** Find an open, floored cell (with a standing spot beside it) within reach, path to it. */
    private static boolean walkToPlaceableSpot(IPlayerContext ctx) {
        IBaritone b = BaritoneAPI.getProvider().getPrimaryBaritone();
        if (b == null) {
            return false;
        }
        BlockPos[] spot = onClient(ctx, () -> findPlaceableStand(ctx.player(), 12));
        if (spot == null) {
            return false; // nowhere obviously placeable nearby
        }
        BlockPos stand = spot[1];
        boolean alreadyThere = Boolean.TRUE.equals(onClient(ctx,
                () -> ctx.player().blockPosition().closerThan(stand, 1.5)));
        if (!alreadyThere) {
            onClient(ctx, () -> {
                b.getCustomGoalProcess().setGoalAndPath(new baritone.api.pathing.goals.GoalBlock(stand));
                return null;
            });
            long deadline = System.currentTimeMillis() + 25_000L;
            while (System.currentTimeMillis() < deadline) {
                if (MistralAgent.isCancelled()) {
                    break;
                }
                if (Boolean.TRUE.equals(onClient(ctx, () -> ctx.player().blockPosition().closerThan(stand, 1.6)))) {
                    break;
                }
                if (!b.getPathingBehavior().isPathing() && !b.getPathingBehavior().getInProgress().isPresent()) {
                    break; // path completed or couldn't progress
                }
                sleepAi(300);
            }
            onClient(ctx, () -> {
                b.getCustomGoalProcess().onLostControl();
                return null;
            });
        }
        return true;
    }

    /** {placeCell, standCell}: an air cell with a sturdy floor + headroom, beside a standable cell. */
    private static BlockPos[] findPlaceableStand(LocalPlayer p, int radius) {
        Level level = p.level();
        BlockPos origin = p.blockPosition();
        BlockPos bestPlace = null;
        BlockPos bestStand = null;
        double bestDist = Double.MAX_VALUE;
        for (int y = -3; y <= 2; y++) {
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    BlockPos c = origin.offset(x, y, z);
                    if (!isOpenFlooredCell(level, c)) {
                        continue;
                    }
                    for (Direction d : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
                        BlockPos s = c.relative(d);
                        if (!isStandable(level, s)) {
                            continue;
                        }
                        double dist = origin.distSqr(s);
                        if (dist < bestDist) {
                            bestDist = dist;
                            bestPlace = c;
                            bestStand = s;
                        }
                        break;
                    }
                }
            }
        }
        return bestPlace == null ? null : new BlockPos[]{bestPlace, bestStand};
    }

    private static boolean isOpenFlooredCell(Level level, BlockPos c) {
        return level.getBlockState(c).isAir()
                && level.getBlockState(c.above()).isAir()
                && level.getBlockState(c.below()).isFaceSturdy(level, c.below(), Direction.UP);
    }

    private static boolean isStandable(Level level, BlockPos s) {
        return level.getBlockState(s).isAir()
                && level.getBlockState(s.above()).isAir()
                && level.getBlockState(s.below()).isFaceSturdy(level, s.below(), Direction.UP);
    }

    private static BlockHitResult findTablePlacementHit(LocalPlayer p) {
        // Two passes: prefer a PURE-AIR target first (a flower/grass in the cell makes the
        // crosshair raytrace hit the plant instead of the support block, so placement aborts);
        // only fall back to replaceable plants if no clean air spot exists. Both passes reject
        // cells an entity is standing in (a chicken there makes placement physically fail).
        for (boolean airOnly : new boolean[]{true, false}) {
            BlockHitResult hit = findPlacementHitPass(p, airOnly);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    private static BlockHitResult findPlacementHitPass(LocalPlayer p, boolean airOnly) {
        Level level = p.level();
        BlockPos feet = p.blockPosition();
        Direction dir = p.getDirection();
        // 1) straight ahead, closest first
        for (int dist = 1; dist <= 4; dist++) {
            BlockHitResult h = placementHitAt(p, level, feet.relative(dir, dist), airOnly);
            if (h != null) {
                return h;
            }
        }
        // 2) nearest valid cell in a small box around the player
        Vec3 eye = p.getEyePosition(1f);
        double maxDist = 5.25D * 5.25D;
        BlockHitResult bestHit = null;
        double best = Double.MAX_VALUE;
        for (int y = -1; y <= 1; y++) {
            for (int x = -3; x <= 3; x++) {
                for (int z = -3; z <= 3; z++) {
                    BlockPos cell = feet.offset(x, y, z);
                    double dist = eye.distanceToSqr(Vec3.atCenterOf(cell));
                    if (dist > maxDist || dist >= best) {
                        continue;
                    }
                    BlockHitResult h = placementHitAt(p, level, cell, airOnly);
                    if (h != null) {
                        bestHit = h;
                        best = dist;
                    }
                }
            }
        }
        return bestHit;
    }

    /** A valid place-on-FLOOR hit at {@code cell} (place the station on top of the sturdy block
     *  below it), or null if unsuitable. Floor placement is what a look-down reliably hits — far
     *  more robust than wall/ceiling placement. walkToPlaceableSpot guarantees such a cell exists. */
    private static BlockHitResult placementHitAt(LocalPlayer p, Level level, BlockPos cell, boolean airOnly) {
        BlockState st = level.getBlockState(cell);
        if (airOnly ? !st.isAir() : !st.canBeReplaced()) {
            return null;
        }
        // never place where the player is standing
        BlockPos feet = p.blockPosition();
        if (cell.equals(feet) || cell.equals(feet.above())) {
            return null;
        }
        // a sturdy floor must be directly below (place on its top face)
        BlockPos below = cell.below();
        if (!level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) {
            return null;
        }
        // an entity (mob/player) occupying the cell blocks placement
        net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(cell);
        if (!level.getEntities(p, box, e -> e instanceof net.minecraft.world.entity.LivingEntity).isEmpty()) {
            return null;
        }
        Vec3 hitVec = Vec3.atBottomCenterOf(cell).relative(Direction.UP, 0.05);
        return new BlockHitResult(hitVec, Direction.UP, below, false);
    }

    // -------- internals --------

    private static boolean playerInventoryContainsItem(IPlayerContext ctx, Item item) {
        return onClient(ctx, () -> {
            LocalPlayer p = ctx.player();
            for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
                if (p.getInventory().getItem(i).is(item)) {
                    return true;
                }
            }
            return false;
        });
    }

    private static int findLogSlot(AbstractContainerMenu menu) {
        for (int i = INV_FIRST_MAIN; i <= INV_LAST_HOTBAR; i++) {
            ItemStack st = menu.getSlot(i).getItem();
            if (!st.isEmpty() && st.is(ItemTags.LOGS)) {
                return i;
            }
        }
        return -1;
    }

    private static void clear2x2(IPlayerContext ctx) {
        LocalPlayer p = ctx.player();
        for (int s : new int[]{INV_CRAFT_1, INV_CRAFT_2, INV_CRAFT_3, INV_CRAFT_4}) {
            if (!p.containerMenu.getSlot(s).getItem().isEmpty()) {
                click(ctx, s, ClickType.QUICK_MOVE, 0);
                sleepAi(60);
            }
        }
        if (!p.containerMenu.getCarried().isEmpty()) {
            int dump = firstEmptyStorageSlot(p.containerMenu);
            if (dump >= 0) {
                click(ctx, dump, ClickType.PICKUP, 0);
            }
            sleepAi(60);
        }
    }

    private static void clear3x3(IPlayerContext ctx, CraftingMenu menu) {
        LocalPlayer p = ctx.player();
        for (int s = 1; s <= 9; s++) {
            try {
                if (!menu.getSlot(s).getItem().isEmpty()) {
                    click(ctx, s, ClickType.QUICK_MOVE, 0);
                    sleepAi(50);
                }
            } catch (RuntimeException ignored) {
                break;
            }
        }
        if (!p.containerMenu.getCarried().isEmpty()) {
            int dump = firstEmptyStorageSlot(menu);
            if (dump >= 0) {
                click(ctx, dump, ClickType.PICKUP, 0);
            }
        }
    }

    private static int firstEmptyStorageSlot(AbstractContainerMenu menu) {
        int a = menuPlayerSlotStart(menu);
        int b = menuPlayerSlotEndInclusive(menu);
        for (int i = a; i <= b; i++) {
            if (menu.getSlot(i).getItem().isEmpty()) {
                return i;
            }
        }
        return -1;
    }

    private static boolean placePlanks2x2(IPlayerContext ctx) {
        return placeOnePlank(ctx, INV_CRAFT_1) && placeOnePlank(ctx, INV_CRAFT_2)
                && placeOnePlank(ctx, INV_CRAFT_3) && placeOnePlank(ctx, INV_CRAFT_4);
    }

    private static boolean placeOnePlank(IPlayerContext ctx, int destCraftSlot) {
        LocalPlayer p = ctx.player();
        int src = findPlankSlot(p.containerMenu);
        if (src < 0) return false;
        return moveOnePickupToSlot(ctx, src, destCraftSlot);
    }

    private static boolean placeOnePlankInMenu(IPlayerContext ctx, AbstractContainerMenu menu, int destCraftSlot) {
        int src = findPlankSlot(menu);
        if (src < 0) return false;
        return moveOnePickupToSlot(ctx, src, destCraftSlot);
    }

    private static boolean placeOneStick(IPlayerContext ctx, AbstractContainerMenu menu, int dest) {
        int src = findItemSlot(menu, Items.STICK);
        if (src < 0) return false;
        return moveOnePickupToSlot(ctx, src, dest);
    }

    private static int findPlankSlot(AbstractContainerMenu menu) {
        int a = menuPlayerSlotStart(menu);
        int b = menuPlayerSlotEndInclusive(menu);
        for (int i = a; i <= b; i++) {
            ItemStack st = menu.getSlot(i).getItem();
            if (!st.isEmpty() && st.is(ItemTags.PLANKS)) {
                return i;
            }
        }
        return -1;
    }

    private static int findItemSlot(AbstractContainerMenu menu, Item item) {
        int a = menuPlayerSlotStart(menu);
        int b = menuPlayerSlotEndInclusive(menu);
        for (int i = a; i <= b; i++) {
            ItemStack st = menu.getSlot(i).getItem();
            if (!st.isEmpty() && st.is(item)) {
                return i;
            }
        }
        return -1;
    }

    private static int countItem(LocalPlayer p, Item item) {
        int n = 0;
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            ItemStack st = p.getInventory().getItem(i);
            if (!st.isEmpty() && st.is(item)) n += st.getCount();
        }
        return n;
    }

    private static int countTag(LocalPlayer p, net.minecraft.tags.TagKey<Item> tag) {
        int n = 0;
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            ItemStack st = p.getInventory().getItem(i);
            if (!st.isEmpty() && st.is(tag)) n += st.getCount();
        }
        return n;
    }
}
