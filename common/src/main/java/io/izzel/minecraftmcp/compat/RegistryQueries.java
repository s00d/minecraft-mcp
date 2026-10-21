package io.izzel.minecraftmcp.compat;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Lookups in the built-in registries, which hold everything a mod registers in code. */
public final class RegistryQueries {
    private RegistryQueries() {
    }

    public static Map<String, Object> list(String registry, String contains, int limit) {
        List<String> registries = new ArrayList<>();
        for (Registry<?> candidate : BuiltInRegistries.REGISTRY) registries.add(MinecraftCompat.id(candidate.key()));
        registries.sort(null);
        if (registry == null || registry.isBlank()) return Map.of("count", registries.size(), "registries", registries);
        String id = registry.contains(":") ? registry : "minecraft:" + registry;
        for (Registry<?> candidate : BuiltInRegistries.REGISTRY) {
            if (!MinecraftCompat.id(candidate.key()).equals(id)) continue;
            List<String> entries = new ArrayList<>();
            for (Object key : candidate.keySet()) {
                String entry = key.toString();
                if (contains == null || entry.contains(contains)) entries.add(entry);
            }
            entries.sort(null);
            int matched = entries.size();
            return Map.of("registry", id, "total", candidate.keySet().size(), "matched", matched,
                "truncated", matched > limit, "entries", entries.subList(0, Math.min(limit, matched)));
        }
        throw new IllegalArgumentException("no registry " + id + "; known: " + registries);
    }
}
