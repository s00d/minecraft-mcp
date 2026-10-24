package io.izzel.minecraftmcp.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * The crafting recipes the client knows, and placing one in a crafting grid. 1.21.2 moved recipes to the server:
 * from then on the client only knows the recipes unlocked in its recipe book, as displays. Before, it had them all
 * and the book only marked the unlocked ones. Client thread only.
 */
public final class CraftingCompat {
    private CraftingCompat() {}

    /**
     * One crafting recipe: what it makes, whether it fits the 2x2 inventory grid, whether the player unlocked it
     * (the server only places unlocked recipes), and each ingredient slot as the item ids that fit it.
     */
    public record Recipe(Object handle, String result, int resultCount, boolean small, boolean unlocked, List<List<String>> slots) {}

    private static String id(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    public static List<Recipe> recipes(Minecraft mc) {
        List<Recipe> recipes = new ArrayList<>();
        //? if >=1.21.2 {
        var context = net.minecraft.world.item.crafting.display.SlotDisplayContext.fromLevel(mc.level);
        for (var collection : mc.player.getRecipeBook().getCollections()) {
            for (var entry : collection.getRecipes()) {
                var display = entry.display();
                List<net.minecraft.world.item.crafting.display.SlotDisplay> ingredients;
                boolean small;
                if (display instanceof net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay shaped) {
                    ingredients = shaped.ingredients();
                    small = shaped.width() <= 2 && shaped.height() <= 2;
                } else if (display instanceof net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay shapeless) {
                    ingredients = shapeless.ingredients();
                    small = ingredients.size() <= 4;
                } else {
                    continue;
                }
                ItemStack result = display.result().resolveForFirstStack(context);
                if (result.isEmpty()) continue;
                List<List<String>> slots = new ArrayList<>();
                for (var slot : ingredients) {
                    List<String> fits = slot.resolveForStacks(context).stream().filter(s -> !s.isEmpty()).map(CraftingCompat::id).distinct().toList();
                    if (!fits.isEmpty()) slots.add(fits);
                }
                recipes.add(new Recipe(entry.id(), id(result), result.getCount(), small, true, slots));
            }
        }
        //?} else {
        /*var access = mc.level.registryAccess();
        for (var holder : mc.level.getRecipeManager().getRecipes()) {
            var recipe = holder.value();
            if (recipe.getType() != net.minecraft.world.item.crafting.RecipeType.CRAFTING || recipe.isSpecial()) continue;
            ItemStack result = recipe.getResultItem(access);
            if (result.isEmpty()) continue;
            List<List<String>> slots = new ArrayList<>();
            for (var ingredient : recipe.getIngredients()) {
                if (ingredient.isEmpty()) continue;
                slots.add(java.util.Arrays.stream(ingredient.getItems()).map(CraftingCompat::id).distinct().toList());
            }
            recipes.add(new Recipe(holder, id(result), result.getCount(), recipe.canCraftInDimensions(2, 2),
                mc.player.getRecipeBook().contains(holder), slots));
        }
        *///?}
        return recipes;
    }

    /** Asks the server to fill the grid of container {@code containerId} with one set of the recipe's ingredients. */
    public static void place(Minecraft mc, int containerId, Recipe recipe) {
        //? if >=1.21.2 {
        mc.gameMode.handlePlaceRecipe(containerId, (net.minecraft.world.item.crafting.display.RecipeDisplayId) recipe.handle(), false);
        //?} else {
        /*mc.gameMode.handlePlaceRecipe(containerId, (net.minecraft.world.item.crafting.RecipeHolder<?>) recipe.handle(), false);
        *///?}
    }
}
