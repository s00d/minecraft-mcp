package io.izzel.minecraftmcp.tools;

import io.izzel.minecraftmcp.mcp.Param;
import io.izzel.minecraftmcp.schematic.ServerSchematicTools;
import io.izzel.minecraftmcp.server.ServerWorldTools;
import java.util.List;

import io.izzel.minecraftmcp.bridge.MinecraftServerBridge;
import io.izzel.minecraftmcp.mcp.ToolRegistry;
import io.izzel.minecraftmcp.scenario.ScenarioEngine;
import io.izzel.minecraftmcp.util.LogTail;

import static io.izzel.minecraftmcp.mcp.McpTools.destructive;
import static io.izzel.minecraftmcp.mcp.McpTools.read;
import static io.izzel.minecraftmcp.mcp.McpTools.tool;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public final class BuiltinServerTools {
    private BuiltinServerTools() {
    }

    public static void register(ToolRegistry registry, MinecraftServerBridge bridge, ScenarioEngine scenarios) {
        BuiltinCommonTools.register(registry, bridge, scenarios);
        register(registry, bridge);
    }

    private static final Param<String> DIMENSION = ServerWorldTools.DIMENSION;
    private static final Param<String> PLAYER = Param.string("player", "player name or UUID").required();
    private static final Param<Integer> X = Param.integer("x", "block X").required();
    private static final Param<Integer> Y = Param.integer("y", "block Y").required();
    private static final Param<Integer> Z = Param.integer("z", "block Z").required();
    private static final Param<Long> TIMEOUT = Param.longInt("timeoutMs", "give up after this many milliseconds").or(30000L);

    public static void register(ToolRegistry registry, MinecraftServerBridge bridge) {
        registry.register(read(tool("mc.server.state", "Get server state", args -> bridge.submit(bridge::serverState).get(10, TimeUnit.SECONDS))));
        Param<String> command = Param.string("command", "command, with or without the leading /").required();
        Param<String> as = Param.string("as", "run as this player (name or UUID) instead of the console");
        registry.register(destructive(tool("mc.server.command.run", "Execute a command on the server and capture its output", List.of(command, as), args -> {
            String asPlayer = as.get(args) == null || as.get(args).isBlank() ? null : as.get(args);
            return bridge.submit(() -> bridge.runCommand(command.get(args), asPlayer)).get(10, TimeUnit.SECONDS);
        })));
        registry.register(destructive(tool("mc.server.shutdown", "Stop the server", args -> {
            bridge.execute(bridge::shutdownServer);
            return Map.of("status", "stopping");
        })));
        registry.register(read(tool("mc.server.players", "List online players with position, health and gamemode",
            args -> bridge.submit(bridge::players).get(10, TimeUnit.SECONDS))));
        registry.register(read(tool("mc.server.player.state", "Get one player's server-authoritative state", List.of(PLAYER),
            args -> bridge.submit(() -> bridge.playerState(PLAYER.get(args))).get(10, TimeUnit.SECONDS))));
        registry.register(read(tool("mc.server.player.inventory", "Get one player's server-authoritative inventory", List.of(PLAYER),
            args -> bridge.submit(() -> bridge.playerInventory(PLAYER.get(args))).get(10, TimeUnit.SECONDS))));
        registry.register(read(tool("mc.server.connection.list", "List player connections with address and latency",
            args -> bridge.submit(bridge::connectionList).get(10, TimeUnit.SECONDS))));
        registry.register(read(tool("mc.server.world.list", "List loaded dimensions",
            args -> bridge.submit(bridge::worldList).get(10, TimeUnit.SECONDS))));
        registry.register(read(tool("mc.server.world.snapshot", "Get time, difficulty and entity counts for a dimension", List.of(DIMENSION),
            args -> bridge.submit(() -> bridge.worldSnapshot(DIMENSION.get(args))).get(10, TimeUnit.SECONDS))));
        registry.register(read(tool("mc.server.block.state", "Get the server-authoritative block state at coordinates", List.of(X, Y, Z, DIMENSION),
            args -> bridge.submit(() -> bridge.blockAt(DIMENSION.get(args), X.get(args), Y.get(args), Z.get(args))).get(10, TimeUnit.SECONDS))));
        Param<String> block = Param.string("block", "block state, e.g. minecraft:stone or minecraft:oak_log[axis=x]").required();
        registry.register(destructive(tool("mc.server.block.set", "Set a block, so a following block.state can assert it", List.of(X, Y, Z, block, DIMENSION),
            args -> bridge.submit(() -> bridge.setBlock(DIMENSION.get(args), X.get(args), Y.get(args), Z.get(args), block.get(args))).get(10, TimeUnit.SECONDS))));
        registry.register(read(tool("mc.server.entity.query", "Query entities in a dimension by box and optional type", ServerWorldTools.ENTITY_QUERY_PARAMS,
            args -> bridge.submit(() -> bridge.entityQuery(args)).get(30, TimeUnit.SECONDS))));
        Param<Integer> chunkX = Param.integer("chunkX", "chunk X (block X >> 4)").required();
        Param<Integer> chunkZ = Param.integer("chunkZ", "chunk Z (block Z >> 4)").required();
        registry.register(read(tool("mc.server.chunk.state", "Report whether a chunk is loaded and ticking", List.of(chunkX, chunkZ, DIMENSION),
            args -> bridge.submit(() -> bridge.chunkState(DIMENSION.get(args), chunkX.get(args), chunkZ.get(args))).get(10, TimeUnit.SECONDS))));
        Param<Long> setTime = Param.longInt("set", "set the day time to this tick (0 sunrise, 6000 noon, 13000 night, 18000 midnight)");
        Param<Long> addTime = Param.longInt("add", "move the day time forward by this many ticks (negative goes back)");
        registry.register(tool("mc.server.time", "Read the day time of a dimension, or set or move it like /time",
            List.of(DIMENSION, setTime, addTime),
            args -> bridge.submit(() -> bridge.time(DIMENSION.get(args), setTime.get(args), addTime.get(args))).get(10, TimeUnit.SECONDS)));
        Param<Double> rate = Param.number("rate", "ticks per second, 1 to 10000 (20 is normal)");
        Param<Boolean> frozen = Param.bool("frozen", "freeze or unfreeze the game (players still move)");
        Param<Integer> step = Param.integer("step", "while frozen, run this many ticks");
        Param<Integer> sprint = Param.integer("sprint", "run this many ticks as fast as the server can, to fast-forward the world");
        Param<Boolean> wait = Param.bool("wait", "with sprint: return once the sprinted ticks have run").or(true);
        Param<Long> sprintTimeout = Param.longInt("timeoutMs", "with sprint and wait: give up waiting after this long").or(120000L);
        registry.register(tool("mc.server.tick", "Control the game speed like /tick: tick rate, freeze, step and sprint; "
            + "with no arguments returns the current state", List.of(rate, frozen, step, sprint, wait, sprintTimeout), args -> {
            Double tps = rate.get(args);
            Integer ticks = sprint.get(args);
            long start = System.nanoTime();
            long before = bridge.ticks().current();
            Map<String, Object> state = new LinkedHashMap<>(bridge.submit(() -> bridge.tickRate(tps == null ? null : tps.floatValue(),
                frozen.get(args), step.get(args), ticks)).get(10, TimeUnit.SECONDS));
            if (ticks != null && ticks > 0 && wait.get(args)) {
                long deadline = System.currentTimeMillis() + sprintTimeout.get(args);
                while (bridge.ticks().current() - before < ticks && System.currentTimeMillis() < deadline) {
                    bridge.ticks().await(1, deadline - System.currentTimeMillis());
                }
                state.put("sprintedTicks", bridge.ticks().current() - before);
                state.put("sprintMs", (System.nanoTime() - start) / 1_000_000);
                state.put("completed", bridge.ticks().current() - before >= ticks);
            }
            return state;
        }));
        registry.register(tool("mc.server.reload", "Reload data packs (recipes, tags, loot tables, functions, advancements) like /reload, "
            + "and return once it finished", List.of(TIMEOUT), args -> {
            var done = bridge.submit(bridge::reload).get(10, TimeUnit.SECONDS);
            return done.get(TIMEOUT.get(args), TimeUnit.MILLISECONDS);
        }));
        registry.register(read(tool("mc.server.tick.stats", "Report tick count and average tick time",
            args -> bridge.submit(bridge::tickStats).get(10, TimeUnit.SECONDS))));
        registry.register(read(tool("mc.server.log.tail", "Return the tail of the server log, optionally only lines containing some text",
            List.of(LogTail.LINES, LogTail.CONTAINS), args -> LogTail.read(bridge.gameDirectory(), args))));
        Param<Long> ticks = Param.longInt("ticks", "server ticks to wait").or(1L);
        registry.register(read(tool("mc.server.ticks.wait", "Wait for real server ticks to elapse", List.of(ticks), args -> {
            long requested = ticks.get(args);
            long observed = bridge.awaitTicks(requested);
            return Map.of("requestedTicks", requested, "waitedTicks", observed, "complete", observed >= requested);
        })));
        Param<String> condition = Param.string("condition", "expression over server state, e.g. server.players > 0").required();
        Param<Long> interval = Param.longInt("intervalTicks", "ticks between checks").or(1L);
        registry.register(read(tool("mc.server.condition.wait", "Wait until a server condition is true", List.of(condition, TIMEOUT, interval), args -> {
            long intervalTicks = interval.get(args);
            boolean matched = bridge.waitUntil(condition.get(args), TIMEOUT.get(args), intervalTicks);
            return Map.of("condition", condition.get(args), "matched", matched, "intervalTicks", Math.max(1, intervalTicks));
        })));
        registry.register(tool("mc.server.schematic.export", "Export a cuboid as a Sponge v3 schematic", ServerSchematicTools.EXPORT_PARAMS,
            args -> bridge.submit(() -> bridge.exportSchematic(args)).get(30, TimeUnit.SECONDS)));
        registry.register(destructive(tool("mc.server.schematic.paste", "Paste a Sponge v3 schematic into the current server world", ServerSchematicTools.PASTE_PARAMS,
            args -> bridge.submit(() -> bridge.pasteSchematic(args)).get(30, TimeUnit.SECONDS))));
    }
}
