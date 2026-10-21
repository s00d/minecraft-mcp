package io.izzel.minecraftmcp.tools;

import io.izzel.minecraftmcp.bridge.LoadedMods;
import io.izzel.minecraftmcp.bridge.RecordingClientBridge;
import io.izzel.minecraftmcp.bridge.RecordingServerBridge;
import io.izzel.minecraftmcp.concurrent.TickCounter;
import io.izzel.minecraftmcp.mcp.ToolRegistry;
import io.izzel.minecraftmcp.scenario.ScenarioEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

/** Server and shared tools whose body does more than hand its arguments to the bridge. */
class ServerAndCommonToolLogicTest {
    @TempDir
    Path dir;

    @AfterEach
    void resetMods() {
        LoadedMods.install(List::of);
    }

    private static ToolRegistry server(RecordingServerBridge bridge) {
        ToolRegistry registry = new ToolRegistry();
        BuiltinServerTools.register(registry, bridge, new ScenarioEngine(registry));
        return registry;
    }

    private static ToolRegistry client(RecordingClientBridge bridge) {
        ToolRegistry registry = new ToolRegistry();
        BuiltinCommonTools.register(registry, bridge, new ScenarioEngine(registry));
        BuiltinClientTools.register(registry, bridge);
        return registry;
    }

    // --- mc.batch

    @Test
    @SuppressWarnings("unchecked")
    void batchRunsCallsInOrderAndReturnsEveryResult() throws Exception {
        RecordingClientBridge bridge = new RecordingClientBridge();
        Map<String, Object> result = (Map<String, Object>) client(bridge).call("mc.batch", Map.of("calls", List.of(
            Map.of("tool", "mc.client.keyboard.press", "args", Map.of("key", "a")),
            Map.of("tool", "mc.client.player.swing"),
            Map.of("tool", "mc.client.keyboard.press", "args", Map.of("key", "b")))));

        assertEquals(true, result.get("ok"));
        assertEquals(List.of("pressKey[a]", "swing[main]", "pressKey[b]"), bridge.calls);
        List<Map<String, Object>> results = (List<Map<String, Object>>) result.get("results");
        assertEquals(List.of("mc.client.keyboard.press", "mc.client.player.swing", "mc.client.keyboard.press"),
            results.stream().map(entry -> entry.get("tool")).toList());
        assertEquals(Map.of("status", "pressed", "key", "a"), results.get(0).get("result"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void batchStopsAtTheFirstFailureUnlessKeepGoing() throws Exception {
        List<Map<String, Object>> calls = List.of(
            Map.of("tool", "mc.client.keyboard.press", "args", Map.of("key", "a")),
            Map.of("tool", "mc.client.keyboard.press", "args", Map.of()),
            Map.of("tool", "mc.client.keyboard.press", "args", Map.of("key", "c")));

        RecordingClientBridge stopping = new RecordingClientBridge();
        Map<String, Object> stopped = (Map<String, Object>) client(stopping).call("mc.batch", Map.of("calls", calls));
        assertEquals(false, stopped.get("ok"));
        assertEquals(List.of("pressKey[a]"), stopping.calls);
        List<Map<String, Object>> results = (List<Map<String, Object>>) stopped.get("results");
        assertEquals(2, results.size());
        assertEquals("key is required", results.get(1).get("error"));
        assertFalse(results.get(1).containsKey("result"));

        RecordingClientBridge going = new RecordingClientBridge();
        Map<String, Object> kept = (Map<String, Object>) client(going).call("mc.batch", Map.of("calls", calls, "keepGoing", true));
        assertEquals(false, kept.get("ok"), "one failed call makes the batch not ok");
        assertEquals(List.of("pressKey[a]", "pressKey[c]"), going.calls);
        assertEquals(3, ((List<?>) kept.get("results")).size());
    }

    @Test
    @SuppressWarnings("unchecked")
    void batchReportsAnUnknownToolAsAFailedCall() throws Exception {
        Map<String, Object> result = (Map<String, Object>) client(new RecordingClientBridge()).call("mc.batch",
            Map.of("calls", List.of(Map.of("tool", "mc.no.such.tool"))));
        assertEquals(false, result.get("ok"));
        assertEquals("Unknown tool: mc.no.such.tool", ((List<Map<String, Object>>) result.get("results")).getFirst().get("error"));
    }

    @Test
    void batchRefusesToCallItselfAndMalformedCalls() {
        RecordingClientBridge bridge = new RecordingClientBridge();
        ToolRegistry registry = client(bridge);
        IllegalArgumentException recursion = assertThrows(IllegalArgumentException.class, () -> registry.call("mc.batch", Map.of("calls", List.of(
            Map.of("tool", "mc.client.player.swing"),
            Map.of("tool", "mc.batch", "args", Map.of("calls", List.of()))))));
        assertTrue(recursion.getMessage().contains("cannot call itself"), recursion.getMessage());
        assertThrows(IllegalArgumentException.class, () -> registry.call("mc.batch", Map.of("calls", List.of(Map.of("args", Map.of())))));
        assertThrows(IllegalArgumentException.class, () -> registry.call("mc.batch", Map.of("calls", List.of("mc.client.player.swing"))));
        assertThrows(IllegalArgumentException.class, () -> registry.call("mc.batch", Map.of()));
    }

    // --- mc.server.tick, time, reload

    @Test
    @SuppressWarnings("unchecked")
    void tickSprintWaitsForTheSprintedTicks() throws Exception {
        RecordingServerBridge bridge = new RecordingServerBridge() {
            @Override public Map<String, Object> tickRate(Float rate, Boolean frozen, Integer step, Integer sprint) {
                Map<String, Object> state = super.tickRate(rate, frozen, step, sprint);
                for (int i = 0; i < sprint; i++) ticks.onTick(); // the server sprints these ticks
                return state;
            }
        };
        bridge.ticks = new TickCounter();

        Map<String, Object> state = (Map<String, Object>) server(bridge).call("mc.server.tick", Map.of("sprint", 5));

        assertEquals(List.of("tickRate[null, null, null, 5]"), bridge.calls);
        assertEquals(5L, state.get("sprintedTicks"));
        assertEquals(true, state.get("completed"));
        assertEquals("tickRate", state.get("method"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void tickSprintCountsTicksThatArriveWhileItWaits() throws Exception {
        RecordingServerBridge bridge = new RecordingServerBridge(); // FakeTicks: a tick every millisecond
        Map<String, Object> state = (Map<String, Object>) server(bridge).call("mc.server.tick", Map.of("sprint", 3, "timeoutMs", 10000));
        assertEquals(true, state.get("completed"));
        assertTrue((Long) state.get("sprintedTicks") >= 3, state.toString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void tickSprintReportsIncompleteWhenTheServerDoesNotTick() throws Exception {
        RecordingServerBridge bridge = new RecordingServerBridge();
        bridge.ticks = new TickCounter();
        Map<String, Object> state = (Map<String, Object>) server(bridge).call("mc.server.tick", Map.of("sprint", 5, "timeoutMs", 10));
        assertEquals(false, state.get("completed"));
        assertEquals(0L, state.get("sprintedTicks"));
    }

    @Test
    void tickWithoutSprintWaitPassesTheControlsThrough() throws Exception {
        RecordingServerBridge bridge = new RecordingServerBridge();
        bridge.ticks = new TickCounter();
        ToolRegistry registry = server(bridge);

        assertEquals(Map.of("method", "tickRate"), registry.call("mc.server.tick", Map.of()));
        assertEquals(Map.of("method", "tickRate"), registry.call("mc.server.tick", Map.of("rate", 40, "frozen", true, "step", 2)));
        assertEquals(Map.of("method", "tickRate"), registry.call("mc.server.tick", Map.of("sprint", 100, "wait", false)));
        assertEquals(List.of("tickRate[null, null, null, null]", "tickRate[40.0, true, 2, null]", "tickRate[null, null, null, 100]"), bridge.calls);
    }

    @Test
    void timePassesDimensionSetAndAddThrough() throws Exception {
        RecordingServerBridge bridge = new RecordingServerBridge();
        ToolRegistry registry = server(bridge);
        registry.call("mc.server.time", Map.of());
        registry.call("mc.server.time", Map.of("set", 6000));
        registry.call("mc.server.time", Map.of("dimension", "minecraft:the_nether", "add", -1000));
        assertEquals(List.of("time[minecraft:overworld, null, null]", "time[minecraft:overworld, 6000, null]",
            "time[minecraft:the_nether, null, -1000]"), bridge.calls);
    }

    @Test
    void reloadReturnsOnceTheReloadFinished() throws Exception {
        RecordingServerBridge bridge = new RecordingServerBridge();
        bridge.reload = new CompletableFuture<>();
        ToolRegistry registry = server(bridge);

        CompletableFuture<Object> waiting = CompletableFuture.supplyAsync(() -> {
            try {
                return registry.call("mc.server.reload", Map.of("timeoutMs", 10000));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        bridge.reload.complete(Map.of("status", "reloaded", "packs", List.of("vanilla")));

        assertEquals(Map.of("status", "reloaded", "packs", List.of("vanilla")), waiting.get(10, TimeUnit.SECONDS));
    }

    @Test
    void reloadTimesOutWhenTheReloadNeverFinishes() {
        RecordingServerBridge bridge = new RecordingServerBridge();
        bridge.reload = new CompletableFuture<>();
        assertThrows(TimeoutException.class, () -> server(bridge).call("mc.server.reload", Map.of("timeoutMs", 10)));
    }

    // --- logs, crash reports, mods

    @Test
    void serverLogTailFiltersAndReportsAMissingLog() throws Exception {
        RecordingServerBridge bridge = new RecordingServerBridge();
        bridge.gameDirectory = dir;
        ToolRegistry registry = server(bridge);

        assertEquals(Map.of("status", "missing", "path", dir.resolve("logs/latest.log").toString(), "lines", List.of()),
            registry.call("mc.server.log.tail", Map.of()));

        Files.createDirectories(dir.resolve("logs"));
        Files.write(dir.resolve("logs/latest.log"), List.of("[Server] Done", "[Server] WARN x", "[Server] Stopping"));
        Map<?, ?> tail = (Map<?, ?>) registry.call("mc.server.log.tail", Map.of("lines", 2));
        assertEquals(List.of("[Server] WARN x", "[Server] Stopping"), tail.get("lines"));
        Map<?, ?> warn = (Map<?, ?>) registry.call("mc.server.log.tail", Map.of("contains", "WARN"));
        assertEquals(List.of("[Server] WARN x"), warn.get("lines"));
        assertEquals(100, warn.get("requested"));
    }

    @Test
    void crashLatestReadsTheNewestReport() throws Exception {
        RecordingServerBridge bridge = new RecordingServerBridge();
        bridge.gameDirectory = dir;
        ToolRegistry registry = server(bridge);

        assertEquals(Map.of("status", "none", "directory", dir.resolve("crash-reports").toString()), registry.call("mc.crash.latest", Map.of()));

        Path reports = Files.createDirectories(dir.resolve("crash-reports"));
        Path old = Files.write(reports.resolve("crash-old-server.txt"), List.of("Description: old crash"));
        Path newest = Files.write(reports.resolve("crash-new-server.txt"), List.of(
            "---- Minecraft Crash Report ----", "Description: Ticking entity", "",
            "-- Suspected Mods --", "Details:", "\tSuspected Mods: Example Mod (examplemod)", "", "java.lang.NullPointerException"));
        Path notes = Files.write(reports.resolve("notes.log"), List.of("Description: not a report"));
        assertTrue(old.toFile().setLastModified(1_000_000L));
        assertTrue(newest.toFile().setLastModified(2_000_000L));
        assertTrue(notes.toFile().setLastModified(3_000_000L));

        Map<?, ?> crash = (Map<?, ?>) registry.call("mc.crash.latest", Map.of("lines", 2));
        assertEquals("found", crash.get("status"));
        assertEquals(newest.toString(), crash.get("path"));
        assertEquals("Ticking entity", crash.get("description"));
        assertEquals(List.of("-- Suspected Mods --", "Details:", "\tSuspected Mods: Example Mod (examplemod)", "java.lang.NullPointerException"),
            crash.get("suspectedMods"));
        assertEquals(8, crash.get("totalLines"));
        assertEquals(List.of("---- Minecraft Crash Report ----", "Description: Ticking entity"), crash.get("lines"));
    }

    @Test
    void crashLatestFindsTheNeoForgeSuspectedModsLine() throws Exception {
        RecordingServerBridge bridge = new RecordingServerBridge();
        bridge.gameDirectory = dir;
        Path reports = Files.createDirectories(dir.resolve("crash-reports"));
        Files.write(reports.resolve("crash.txt"), List.of("Description: Exception in server tick loop", "Suspected Mods: examplemod"));

        Map<?, ?> crash = (Map<?, ?>) server(bridge).call("mc.crash.latest", Map.of());
        assertEquals(List.of("Suspected Mods: examplemod"), crash.get("suspectedMods"));
        assertEquals(List.of("Description: Exception in server tick loop", "Suspected Mods: examplemod"), crash.get("lines"));
    }

    @Test
    void modsListFiltersByIdOrName() throws Exception {
        LoadedMods.install(() -> List.of(
            Map.of("id", "minecraft", "version", "1.21", "name", "Minecraft"),
            Map.of("id", "examplemod", "version", "1.0", "name", "Example Mod"),
            Map.of("id", "other", "version", "2.0", "name", "Example Helper")));
        ToolRegistry registry = server(new RecordingServerBridge());

        Map<?, ?> all = (Map<?, ?>) registry.call("mc.mods.list", Map.of());
        assertEquals("test", all.get("loader"));
        assertEquals(3, all.get("count"));
        Map<?, ?> byId = (Map<?, ?>) registry.call("mc.mods.list", Map.of("contains", "examplemod"));
        assertEquals(1, byId.get("count"));
        Map<?, ?> byName = (Map<?, ?>) registry.call("mc.mods.list", Map.of("contains", "Example"));
        assertEquals(List.of("examplemod", "other"), ((List<?>) byName.get("mods")).stream().map(mod -> ((Map<?, ?>) mod).get("id")).toList());
    }
}
