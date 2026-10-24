package io.izzel.minecraftmcp.client;

import io.izzel.minecraftmcp.compat.ClientQueries;
import io.izzel.minecraftmcp.compat.CraftingCompat;
import io.izzel.minecraftmcp.compat.MinecraftCompat;
import io.izzel.minecraftmcp.craft.CraftPlan;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Crafts through the recipe book, as a player clicking a recipe does: the server fills the grid from the inventory,
 * then the result is shift-clicked out, one set at a time so exactly the asked amount is made. 2x2 recipes use the
 * inventory grid; 3x3 ones a crafting table within reach (opened, used, closed). Runs on an MCP worker thread.
 */
final class Crafter {
    private static final double REACH = 4.5;

    private final VanillaClientBridge bridge;
    private final Minecraft mc;

    Crafter(VanillaClientBridge bridge, Minecraft mc) {
        this.bridge = bridge;
        this.mc = mc;
    }

    private <T> T onClient(Supplier<T> task) {
        try {
            return bridge.submit(task).get(10, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            if (e.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException(String.valueOf(e.getCause()), e.getCause());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Item id to count, over the main inventory and the hotbar (what the recipe book takes from). */
    private Map<String, Integer> inventory() {
        Map<String, Integer> have = new HashMap<>();
        for (ItemStack stack : MinecraftCompat.inventoryItems(mc.player.getInventory())) {
            if (!stack.isEmpty()) have.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount(), Integer::sum);
        }
        return have;
    }

    /** The known crafting recipes, those making {@code item} only when it is given, with how often each can be made now. */
    Map<String, Object> list(String item, boolean craftableOnly, int limit) {
        return onClient(() -> {
            if (mc.player == null || mc.level == null) return Map.of("inWorld", false, "recipes", List.of());
            Map<String, Integer> have = inventory();
            List<Map<String, Object>> recipes = new ArrayList<>();
            int total = 0;
            for (CraftingCompat.Recipe recipe : CraftingCompat.recipes(mc)) {
                if (item != null && !item.isBlank() && !recipe.result().contains(item)) continue;
                int times = CraftPlan.times(recipe.slots(), have, 64);
                if (craftableOnly && (times == 0 || !recipe.unlocked())) continue;
                total++;
                if (recipes.size() >= Math.max(1, limit)) continue;
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("result", recipe.result());
                entry.put("count", recipe.resultCount());
                entry.put("grid", recipe.small() ? "2x2" : "3x3");
                entry.put("unlocked", recipe.unlocked());
                entry.put("craftable", times);
                entry.put("ingredients", recipe.slots());
                recipes.add(entry);
            }
            return Map.of("inWorld", true, "total", total, "recipes", recipes);
        });
    }

    private record Choice(CraftingCompat.Recipe recipe, int times) {}

    Map<String, Object> craft(String item, int count) {
        bridge.requireOffGameThread("crafting");
        String wanted = item.contains(":") ? item : "minecraft:" + item;
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("item", wanted);
        report.put("requested", count);

        // pick the recipe that can be made most often right now; the 2x2 one when it is a tie (no table needed)
        Object[] picked = onClient(() -> {
            if (mc.player == null || mc.level == null) return null;
            Map<String, Integer> have = inventory();
            List<Choice> choices = new ArrayList<>();
            for (CraftingCompat.Recipe recipe : CraftingCompat.recipes(mc)) {
                if (recipe.result().equals(wanted)) choices.add(new Choice(recipe, recipe.unlocked() ? CraftPlan.times(recipe.slots(), have, 64 * 9) : 0));
            }
            choices.sort(Comparator.comparingInt((Choice c) -> -c.times()).thenComparing(c -> !c.recipe().small()));
            return new Object[] {choices, have};
        });
        if (picked == null) return status(report, "not_in_world", null);
        if (onClient(mc::isPaused)) {
            return status(report, "paused", "the game is paused, so the server does not fill the grid; close the menu, or set "
                + "pauseOnLostFocus false with mc.client.options when the window is in the background");
        }
        @SuppressWarnings("unchecked")
        List<Choice> choices = (List<Choice>) picked[0];
        @SuppressWarnings("unchecked")
        Map<String, Integer> have = (Map<String, Integer>) picked[1];
        if (choices.isEmpty()) {
            return status(report, "no_recipe", "no crafting recipe for " + wanted + " in the recipe book. The client only knows unlocked "
                + "recipes (they unlock as you collect ingredients); /recipe give @s " + wanted + " unlocks it");
        }
        Choice choice = choices.get(0);
        report.put("grid", choice.recipe().small() ? "2x2" : "3x3");
        if (!choice.recipe().unlocked()) {
            return status(report, "locked", "the recipe is not unlocked, so the server will not place it; /recipe give @s " + wanted);
        }
        if (choice.times() == 0) {
            report.put("missing", CraftPlan.missing(choice.recipe().slots(), have));
            return status(report, "missing_ingredients", null);
        }
        int crafts = Math.min(choice.times(), (count + choice.recipe().resultCount() - 1) / choice.recipe().resultCount());

        // the grid: the inventory's for a 2x2 recipe, else an open crafting table or one within reach
        boolean openedTable = false;
        AbstractContainerMenu menu = onClient(() -> mc.player.containerMenu);
        if (!(menu instanceof CraftingMenu)) {
            if (menu != mc.player.inventoryMenu) {
                onClient(bridge::closeContainer);
                bridge.waitTicks(2);
            }
            if (!choice.recipe().small()) {
                Map<String, Object> table = onClient(this::nearestTable);
                if (table == null) {
                    // a table nearby but out of reach: walk up to it first
                    Map<String, Object> far = onClient(() -> ClientQueries.findBlocks(mc, "minecraft:crafting_table", 32, 1));
                    List<?> found = (List<?>) far.get("blocks");
                    if (!found.isEmpty()) {
                        Map<?, ?> spot = (Map<?, ?>) found.get(0);
                        try {
                            Navigator.go(mc, (Integer) spot.get("x"), null, (Integer) spot.get("z"), 2.5, false, 10_000, false).await(30_000);
                        } catch (Exception e) {
                            report.put("walk", String.valueOf(e.getMessage()));
                        }
                        table = onClient(this::nearestTable);
                    }
                    if (table == null) {
                        report.put("nearestTable", found.isEmpty() ? null : found.get(0));
                        return status(report, "needs_crafting_table", found.isEmpty()
                            ? "a 3x3 recipe needs a crafting table and there is none within 32 blocks; craft and place one"
                            : "could not get within " + REACH + " blocks of the nearest crafting table");
                    }
                }
                Map<String, Object> reached = table;
                onClient(() -> bridge.interactBlock((Integer) reached.get("x"), (Integer) reached.get("y"), (Integer) reached.get("z"), (String) reached.get("face"), "main"));
                for (int i = 0; i < 40 && !(onClient(() -> mc.player.containerMenu) instanceof CraftingMenu); i++) bridge.waitTicks(1);
                if (!(onClient(() -> mc.player.containerMenu) instanceof CraftingMenu)) {
                    return status(report, "table_not_opened", "the crafting table at " + table.get("x") + " " + table.get("y") + " " + table.get("z") + " did not open");
                }
                report.put("table", Map.of("x", table.get("x"), "y", table.get("y"), "z", table.get("z")));
                openedTable = true;
            }
            menu = onClient(() -> mc.player.containerMenu);
        }
        AbstractContainerMenu grid = menu;
        int gridSlots = grid instanceof CraftingMenu ? 9 : 4;

        // items left in the grid by an earlier run would be crafted along with this one
        returnGrid(grid, gridSlots);
        bridge.waitTicks(1);
        int before = onClient(this::inventory).getOrDefault(wanted, 0);
        String stopped = null;
        try {
            for (int i = 0; i < crafts; i++) {
                onClient(() -> {
                    CraftingCompat.place(mc, grid.containerId, choice.recipe());
                    return null;
                });
                // the server fills the grid and the result slot a tick or two later
                boolean ready = false;
                for (int t = 0; t < 40 && !ready; t++) {
                    bridge.waitTicks(1);
                    ready = onClient(() -> {
                        ItemStack out = grid.getSlot(0).getItem();
                        return !out.isEmpty() && BuiltInRegistries.ITEM.getKey(out.getItem()).toString().equals(wanted);
                    });
                }
                if (!ready) {
                    stopped = "the server did not fill the grid";
                    break;
                }
                onClient(() -> bridge.clickContainer(0, 0, "QUICK_MOVE"));
                bridge.waitTicks(1);
                // still there: the inventory has no room, and placing again would only stack more sets in the grid
                if (onClient(() -> grid.getSlot(0).hasItem())) {
                    stopped = "inventory_full";
                    break;
                }
            }
        } finally {
            // put back whatever is left in the grid, and close a table opened here
            bridge.waitTicks(1);
            returnGrid(grid, gridSlots);
            if (openedTable) {
                bridge.waitTicks(1);
                onClient(bridge::closeContainer);
            }
        }
        bridge.waitTicks(2);
        int after = onClient(this::inventory).getOrDefault(wanted, 0);
        report.put("crafted", after - before);
        report.put("have", after);
        if (after - before < count) {
            Map<String, Integer> now = onClient(this::inventory);
            List<Map<String, Object>> missing = CraftPlan.missing(choice.recipe().slots(), now);
            if (!missing.isEmpty()) report.put("missing", missing);
        }
        return status(report, after - before >= count ? "crafted" : (after > before ? "partial" : "failed"), stopped);
    }

    /** Shift-clicks every grid slot back into the inventory. */
    private void returnGrid(AbstractContainerMenu grid, int gridSlots) {
        onClient(() -> {
            for (int slot = 1; slot <= gridSlots; slot++) {
                if (grid.getSlot(slot).hasItem()) bridge.clickContainer(slot, 0, "QUICK_MOVE");
            }
            return null;
        });
    }

    /** The nearest crafting table the player can reach, with the face that looks at the player; client thread. */
    private Map<String, Object> nearestTable() {
        Map<String, Object> found = ClientQueries.findBlocks(mc, "minecraft:crafting_table", 5, 8);
        var eye = mc.player.getEyePosition();
        for (Object entry : (List<?>) found.get("blocks")) {
            Map<?, ?> block = (Map<?, ?>) entry;
            int x = (Integer) block.get("x"), y = (Integer) block.get("y"), z = (Integer) block.get("z");
            double dx = eye.x - (x + 0.5), dy = eye.y - (y + 0.5), dz = eye.z - (z + 0.5);
            if (dx * dx + dy * dy + dz * dz > REACH * REACH) continue;
            String face;
            if (Math.abs(dy) >= Math.abs(dx) && Math.abs(dy) >= Math.abs(dz)) face = dy > 0 ? "up" : "down";
            else if (Math.abs(dx) >= Math.abs(dz)) face = dx > 0 ? "east" : "west";
            else face = dz > 0 ? "south" : "north";
            return Map.of("x", x, "y", y, "z", z, "face", face);
        }
        return null;
    }

    private static Map<String, Object> status(Map<String, Object> report, String status, String reason) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", status);
        if (reason != null) out.put("reason", reason);
        out.putAll(report);
        return out;
    }
}
