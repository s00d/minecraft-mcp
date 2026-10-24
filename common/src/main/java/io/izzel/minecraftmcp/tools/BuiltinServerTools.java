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

    private static List<String> types(List<?> values) {
        return values == null ? List.of() : values.stream().map(String::valueOf).toList();
    }

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
        registry.register(read(tool("mc.server.entity.query", "Find entities in a dimension by box, type, tag, name or UUID, with health, tags, AI, target, vehicle, held items and pin state", ServerWorldTools.ENTITY_QUERY_PARAMS,
            args -> bridge.submit(() -> bridge.entityQuery(args)).get(30, TimeUnit.SECONDS))));
        Param<String> who = Param.string("who", "player name or UUID, or an entity UUID (entity.query finds it by tag or name)").required();
        Param<Double> atX = Param.number("x", "X (default: where it is now)");
        Param<Double> atY = Param.number("y", "Y");
        Param<Double> atZ = Param.number("z", "Z");
        Param<Double> yaw = Param.number("yaw", "yaw in degrees (default: as it is)");
        Param<Double> pitch = Param.number("pitch", "pitch in degrees");
        Param<Long> pinTicks = Param.longInt("ticks", "release after this many ticks; 0 holds until entity.unpin").or(0L);
        registry.register(destructive(tool("mc.server.entity.pin", "Hold a player or entity at a spot and rotation every server tick until "
            + "released: nothing moves it, not its AI, knockback or the player's own keys. For staged scenes and repeatable tests",
            List.of(who, atX, atY, atZ, yaw, pitch, pinTicks), args -> bridge.submit(() -> bridge.entityPin(who.get(args), atX.get(args), atY.get(args),
                atZ.get(args), yaw.get(args) == null ? null : yaw.get(args).floatValue(), pitch.get(args) == null ? null : pitch.get(args).floatValue(),
                pinTicks.get(args))).get(10, TimeUnit.SECONDS))));
        Param<String> unpinWho = Param.string("who", "player name or entity UUID; omit or \"all\" to release every pin");
        registry.register(tool("mc.server.entity.unpin", "Release pinned players or entities", List.of(unpinWho),
            args -> bridge.submit(() -> bridge.entityUnpin(unpinWho.get(args))).get(10, TimeUnit.SECONDS)));
        Param<String> lookAt = Param.string("at", "look at this player or entity (its eyes) instead of x/y/z");
        registry.register(destructive(tool("mc.server.entity.look", "Turn a player or entity to look at a point or at another actor; a pinned one keeps looking there",
            List.of(who, atX, atY, atZ, lookAt), args -> bridge.submit(() -> bridge.entityLook(who.get(args), atX.get(args), atY.get(args), atZ.get(args),
                lookAt.get(args))).get(10, TimeUnit.SECONDS))));
        Param<Double> toX = Param.number("x", "target X").required();
        Param<Double> toY = Param.number("y", "target Y").required();
        Param<Double> toZ = Param.number("z", "target Z").required();
        Param<Double> speed = Param.number("speed", "blocks per second (a walking mob does about 4)").or(4.0);
        Param<Long> moveTimeout = Param.longInt("timeoutMs", "give up after this long").or(60_000L);
        registry.register(destructive(tool("mc.server.entity.move", "Walk a mob to a spot along a path from its own navigation, its AI paused "
            + "meanwhile so nothing steers it away (restored after); any other entity moves in a straight line. Players walk with "
            + "mc.client.movement.goto", List.of(who, toX, toY, toZ, speed, moveTimeout),
            args -> bridge.submit(() -> bridge.entityMove(who.get(args), toX.get(args), toY.get(args), toZ.get(args), speed.get(args)))
                .get(10, TimeUnit.SECONDS))));
        Param<String> playerWho = Param.string("player", "player name or UUID").required();
        Param<String> snapshotName = Param.string("name", "snapshot name, per player").or("default");
        registry.register(tool("mc.server.player.save", "Remember a player's state: inventory, position and dimension, health, food, "
            + "experience, effects, game mode, fire and air; player.restore puts it back (kept until the server stops)",
            List.of(playerWho, snapshotName), args -> bridge.submit(() -> bridge.playerSave(playerWho.get(args), snapshotName.get(args))).get(10, TimeUnit.SECONDS)));
        registry.register(destructive(tool("mc.server.player.restore", "Put a player back as player.save remembered them",
            List.of(playerWho, snapshotName), args -> bridge.submit(() -> bridge.playerRestore(playerWho.get(args), snapshotName.get(args))).get(10, TimeUnit.SECONDS))));
        registry.register(read(tool("mc.server.player.snapshots", "List the saved player snapshots", args -> bridge.submit(bridge::playerSnapshots).get(10, TimeUnit.SECONDS))));
        Param<Long> eventsSince = Param.longInt("since", "only events after this sequence number (the last of a previous read); omit for the newest ones");
        Param<List<?>> eventTypes = Param.array("types", "only these: death, damage, attack, interact, block_break, block_place, pickup, "
            + "advancement, chat, join, leave, dimension");
        Param<String> eventContains = Param.string("contains", "only events mentioning this (a name, UUID, item or block id ...)");
        Param<Integer> eventLimit = Param.integer("limit", "max events; with since the oldest after it first, without since the newest").or(100);
        registry.register(read(tool("mc.server.events.read", "Moments on the server, newest 1000 kept: deaths (cause, killer), damage, "
            + "attacks, entity interactions, blocks broken and placed, items picked up, advancements, chat, joins, leaves, dimension "
            + "changes. Pass the returned last as since next time", List.of(eventsSince, eventTypes, eventContains, eventLimit), args -> {
                bridge.submit(() -> {
                    bridge.watchEvents();
                    return null;
                }).get(10, TimeUnit.SECONDS);
                return io.izzel.minecraftmcp.server.ServerEvents.read(eventsSince.get(args) == null ? -1 : eventsSince.get(args), types(eventTypes.get(args)), eventContains.get(args), eventLimit.get(args));
            })));
        Param<Long> eventTimeout = Param.longInt("timeoutMs", "give up after this long").or(30_000L);
        Param<Long> waitSince = Param.longInt("since", "only events after this sequence number; omit to wait for events from now on");
        registry.register(read(tool("mc.server.events.wait", "Wait for a server moment (see events.read): the guide dying, a player "
            + "talking to an NPC, a block broken ...; without since only events from now on count. Returns it, or matched false at the timeout",
            List.of(waitSince, eventTypes, eventContains, eventTimeout), args -> {
                bridge.requireOffGameThread("waiting for a server event");
                bridge.submit(() -> {
                    bridge.watchEvents();
                    return null;
                }).get(10, TimeUnit.SECONDS);
                long since = waitSince.get(args) != null ? waitSince.get(args) : io.izzel.minecraftmcp.server.ServerEvents.last();
                var event = io.izzel.minecraftmcp.server.ServerEvents.await(since, types(eventTypes.get(args)), eventContains.get(args), eventTimeout.get(args));
                long last = io.izzel.minecraftmcp.server.ServerEvents.last();
                return event == null ? Map.of("matched", false, "last", last) : Map.of("matched", true, "event", event, "last", last);
            })));
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
        registry.register(read(tool("mc.server.schematic.diff", "Compare a schematic placed at origin with the world, as text in building "
                + "order (bottom layer first): blocks still to place, to replace and to remove, with positions, and the items "
                + "the missing blocks take. Build from it with mc.client.block.place, check the result by calling it again",
            ServerSchematicTools.DIFF_PARAMS, args -> bridge.submit(() -> bridge.diffSchematic(args)).get(30, TimeUnit.SECONDS))));
    }
}
