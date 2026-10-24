package io.izzel.minecraftmcp.craft;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * How often a recipe can be crafted from what the player carries, and what is missing for one craft. A recipe is
 * its ingredient slots, each the item ids that fit it (oak or birch planks ...). Slots take from the alternative
 * the player has most of, the way the recipe book fills a grid.
 */
public final class CraftPlan {
    private CraftPlan() {}

    /** How many times the slots can be filled from {@code have}, up to {@code limit}. */
    public static int times(List<List<String>> slots, Map<String, Integer> have, int limit) {
        if (slots.isEmpty()) return 0;
        Map<String, Integer> left = new HashMap<>(have);
        for (int done = 0; done < limit; done++) {
            for (List<String> slot : slots) {
                String pick = pick(slot, left);
                if (pick == null) return done;
                left.merge(pick, -1, Integer::sum);
            }
        }
        return limit;
    }

    /** For one craft: each kind of ingredient still missing, with how many are needed and how many the player has. */
    public static List<Map<String, Object>> missing(List<List<String>> slots, Map<String, Integer> have) {
        Map<String, Integer> left = new HashMap<>(have);
        Map<List<String>, Integer> short_ = new LinkedHashMap<>();
        Map<List<String>, Integer> needed = new LinkedHashMap<>();
        for (List<String> slot : slots) {
            needed.merge(slot, 1, Integer::sum);
            String pick = pick(slot, left);
            if (pick == null) short_.merge(slot, 1, Integer::sum);
            else left.merge(pick, -1, Integer::sum);
        }
        List<Map<String, Object>> missing = new ArrayList<>();
        for (Map.Entry<List<String>, Integer> entry : short_.entrySet()) {
            List<String> slot = entry.getKey();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("item", slot.size() == 1 ? slot.get(0) : "any of " + slot);
            item.put("need", needed.get(slot));
            item.put("have", slot.stream().mapToInt(id -> have.getOrDefault(id, 0)).sum());
            missing.add(item);
        }
        return missing;
    }

    private static String pick(List<String> slot, Map<String, Integer> left) {
        String best = null;
        int most = 0;
        for (String id : slot) {
            int count = left.getOrDefault(id, 0);
            if (count > most) {
                most = count;
                best = id;
            }
        }
        return best;
    }
}
