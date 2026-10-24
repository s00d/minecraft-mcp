package io.izzel.minecraftmcp.craft;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CraftPlanTest {
    static final List<String> PLANKS = List.of("minecraft:oak_planks", "minecraft:birch_planks");
    static final List<List<String>> STICKS = List.of(PLANKS, PLANKS);
    static final List<List<String>> PICKAXE = List.of(PLANKS, PLANKS, PLANKS, List.of("minecraft:stick"), List.of("minecraft:stick"));

    @Test
    void timesCountsMixedAlternatives() {
        assertEquals(3, CraftPlan.times(STICKS, Map.of("minecraft:oak_planks", 3, "minecraft:birch_planks", 3), 64));
        assertEquals(2, CraftPlan.times(STICKS, Map.of("minecraft:oak_planks", 3, "minecraft:birch_planks", 3), 2));
        assertEquals(0, CraftPlan.times(STICKS, Map.of("minecraft:oak_planks", 1), 64));
    }

    @Test
    void theScarceIngredientLimits() {
        assertEquals(1, CraftPlan.times(PICKAXE, Map.of("minecraft:oak_planks", 9, "minecraft:stick", 3), 64));
    }

    @Test
    void missingNamesWhatAndHowMany() {
        assertEquals(List.of(Map.of("item", "minecraft:stick", "need", 2, "have", 1)),
            CraftPlan.missing(PICKAXE, Map.of("minecraft:oak_planks", 5, "minecraft:stick", 1)));
        assertEquals(List.of(Map.of("item", "any of " + PLANKS, "need", 3, "have", 1)),
            CraftPlan.missing(PICKAXE, Map.of("minecraft:birch_planks", 1, "minecraft:stick", 2)));
        assertEquals(List.of(), CraftPlan.missing(STICKS, Map.of("minecraft:oak_planks", 2)));
    }
}
