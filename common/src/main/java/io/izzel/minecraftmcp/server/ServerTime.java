package io.izzel.minecraftmcp.server;

import io.izzel.minecraftmcp.compat.MinecraftCompat;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerTickRateManager;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Time of day, the /tick controls and data pack reloads, all on the server thread. */
public final class ServerTime {
    private ServerTime() {
    }

    public static Map<String, Object> time(MinecraftServer server, String dimension, Long set, Long add) {
        ServerLevel level = ServerWorldTools.level(server, dimension);
        if (set != null) MinecraftCompat.setDayTime(level, set);
        if (add != null) MinecraftCompat.setDayTime(level, MinecraftCompat.dayTime(level) + add);
        long day = MinecraftCompat.dayTime(level);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dimension", MinecraftCompat.id(level.dimension()));
        result.put("dayTime", day);
        result.put("timeOfDay", Math.floorMod(day, 24000L));
        result.put("day", Math.floorDiv(day, 24000L));
        result.put("gameTime", level.getGameTime());
        return result;
    }

    public static Map<String, Object> tickRate(MinecraftServer server, Float rate, Boolean frozen, Integer step, Integer sprint) {
        ServerTickRateManager ticks = server.tickRateManager();
        if (rate != null) {
            if (rate < 1.0F || rate > 10000.0F) throw new IllegalArgumentException("rate must be from 1 to 10000 ticks per second");
            ticks.setTickRate(rate);
        }
        if (frozen != null) ticks.setFrozen(frozen);
        if (step != null && step > 0 && !ticks.stepGameIfPaused(step)) {
            throw new IllegalStateException("step only works while the game is frozen; pass frozen:true first");
        }
        if (sprint != null && sprint > 0) ticks.requestGameToSprint(sprint);
        return tickState(ticks);
    }

    public static Map<String, Object> tickState(ServerTickRateManager ticks) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rate", ticks.tickrate());
        result.put("frozen", ticks.isFrozen());
        result.put("stepping", ticks.isSteppingForward());
        result.put("sprinting", ticks.isSprinting());
        return result;
    }

    public static CompletableFuture<Map<String, Object>> reload(MinecraftServer server) {
        List<String> packs = new ArrayList<>(server.getPackRepository().getSelectedIds());
        long started = System.nanoTime();
        return server.reloadResources(packs).thenApply(ignored -> Map.<String, Object>of(
            "status", "reloaded", "packs", packs, "elapsedMs", (System.nanoTime() - started) / 1_000_000));
    }
}
