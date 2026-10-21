package io.izzel.minecraftmcp.bridge;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** The loaded mods as the loader reports them ({id, version, name}); each loader installs its source at startup. */
public final class LoadedMods {
    private static volatile Supplier<List<Map<String, Object>>> source = List::of;

    private LoadedMods() {
    }

    public static void install(Supplier<List<Map<String, Object>>> mods) {
        source = mods;
    }

    public static List<Map<String, Object>> list() {
        return source.get();
    }

    /** The version of one mod, or "dev" when the loader does not list it. */
    public static String version(String modId) {
        return list().stream().filter(mod -> modId.equals(mod.get("id"))).map(mod -> String.valueOf(mod.get("version")))
            .findFirst().orElse("dev");
    }
}
