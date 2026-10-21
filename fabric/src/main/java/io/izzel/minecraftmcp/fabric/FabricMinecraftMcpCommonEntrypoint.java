package io.izzel.minecraftmcp.fabric;

import io.izzel.minecraftmcp.bridge.LoadedMods;
import io.izzel.minecraftmcp.concurrent.TickCounter;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import java.util.Comparator;
import java.util.Map;

public final class FabricMinecraftMcpCommonEntrypoint implements ModInitializer {
    /** "quilt" when Quilt Loader runs this code (it runs Fabric mods and Fabric API), else "fabric". */
    static String loaderName() {
        return FabricLoader.getInstance().isModLoaded("quilt_loader") ? "quilt" : "fabric";
    }

    @Override
    public void onInitialize() {
        ServerTickEvents.END_SERVER_TICK.register(server -> TickCounter.SERVER.onTick());
        LoadedMods.install(() -> FabricLoader.getInstance().getAllMods().stream()
            .map(mod -> Map.<String, Object>of("id", mod.getMetadata().getId(),
                "version", mod.getMetadata().getVersion().getFriendlyString(), "name", mod.getMetadata().getName()))
            .sorted(Comparator.comparing(mod -> (String) mod.get("id"))).toList());
    }
}
