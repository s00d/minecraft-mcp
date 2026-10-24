package io.izzel.minecraftmcp.util;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One character per block id, for the ASCII pictures of {@code mc.client.view} and {@code mc.client.map}: air is
 * {@code .}, water {@code ~}, lava {@code %}, every other id gets a letter of its own name (stone {@code s}, sand
 * {@code S} ...) the first time it shows up. Digits and {@code @} stay free for entities and the player.
 */
public final class Glyphs {
    private static final String SPARE = "#$&*+=?!<>^/|:;,'\"`(){}[]_-";
    private final Map<String, Character> chars = new HashMap<>();
    private final Map<String, String> legend = new LinkedHashMap<>();

    public char of(String id) {
        Character known = chars.get(id);
        if (known != null) return known;
        String name = id.substring(id.indexOf(':') + 1);
        char c = switch (name) {
            case "air", "cave_air", "void_air" -> '.';
            case "water", "bubble_column" -> '~';
            case "lava" -> '%';
            default -> pick(name);
        };
        chars.put(id, c);
        legend.merge(String.valueOf(c), name, (a, b) -> a);
        return c;
    }

    /** Character to block name (without the minecraft: namespace), in order of first use. */
    public Map<String, String> legend() {
        return legend;
    }

    private char pick(String name) {
        String letters = name.replaceAll("[^a-z]", "");
        for (char c : letters.toCharArray()) {
            if (free(c)) return c;
            char upper = Character.toUpperCase(c);
            if (free(upper)) return upper;
        }
        for (char c = 'a'; c <= 'z'; c++) {
            if (free(c)) return c;
            if (free(Character.toUpperCase(c))) return Character.toUpperCase(c);
        }
        for (char c : SPARE.toCharArray()) if (free(c)) return c;
        // ponytail: past ~80 kinds in one picture the rest share '?'
        return '?';
    }

    private boolean free(char c) {
        return !legend.containsKey(String.valueOf(c));
    }
}
