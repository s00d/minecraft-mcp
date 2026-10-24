package io.izzel.minecraftmcp.script;

import io.izzel.minecraftmcp.bridge.MinecraftBridge;
import io.izzel.minecraftmcp.mcp.Param;
import io.izzel.minecraftmcp.mcp.ToolRegistry;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static io.izzel.minecraftmcp.mcp.McpTools.destructive;
import static io.izzel.minecraftmcp.mcp.McpTools.read;
import static io.izzel.minecraftmcp.mcp.McpTools.tool;

/** mc.script.*: run JavaScript micro scripts in the game process, save them by name, and run them in the background. */
public final class ScriptTools {
    private static final int KEEP_FINISHED = 20;

    private ScriptTools() {
    }

    private record Run(String id, String name, JsScript script, Thread thread, long startedAt, java.util.concurrent.atomic.AtomicReference<Map<String, Object>> report) {
        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", id);
            if (name != null) map.put("name", name);
            Map<String, Object> done = report.get();
            map.put("state", done == null ? "running" : Boolean.TRUE.equals(done.get("ok")) ? "done" : "failed");
            if (done == null) {
                map.put("calls", script.calls());
                map.put("current", script.current());
                map.put("elapsedMs", System.currentTimeMillis() - startedAt);
                List<String> log = script.log();
                if (!log.isEmpty()) map.put("log", log);
            } else {
                map.put("report", done);
            }
            return map;
        }
    }

    static final String GUIDE = """
        Run a JavaScript micro script inside the game, so a chain of actions with conditions and loops costs one MCP request.
        The code is a function body: `return <any value>` gives the report's "result" (objects and arrays as JSON). Globals:
        mc.<tool>(args) for every tool, e.g. mc.client.player.state(), mc.client.mouse.click({button: "left"}),
        mc.client.blocks.find({block: "minecraft:oak_log"}); mc.call("mc.client.state", {}) does the same by name.
        wait(ticks), sleep(ms), until(() => cond, timeoutMs = 10000, everyTicks = 1) -> bool,
        log(...values) / print / console.log: lines in the report's "log" (objects as JSON; mc.script.status shows them while running),
        run(savedName, args) -> its result, args (the run's args object),
        sync(() => {...}) -> its value: runs the block on the game thread in one go. Every game read outside it waits for the
        next frame (~10 ms); inside, all calls run at once and see the same moment. Nothing inside may wait for ticks
        (wait, mouse.hold, destroy.block ...), and a block may hold the game for at most 250 ms.
        Tool results are plain objects/arrays (0-based, as the tools return them); a failing tool throws an Error you can catch.
        Tools refuse unknown arguments: pass {x: b.x, y: b.y, z: b.z}, not a result object with extra fields.
        Engine: Rhino (ES2015+ mostly): arrow functions, let/const, template strings, destructuring, spread, ?. and ??,
        Map/Set, for-of all work. Not supported: class, async/await, generators' advanced use, rest parameters (...xs).
        Closures created in a loop see the last value of the loop variable: copy it in the body first (let i2 = i).
        Example: const p = mc.client.player.state(); if (p.food < 10) mc.client.mouse.hold({button: "right", ticks: 40});
        for (let i = 0; i < 5; i++) { mc.client.mouse.hold({button: "left", ticks: 30}); mc.client.player.turn({yaw: 90}); }
        return {food: mc.client.player.state().food};""";

    public static void register(ToolRegistry registry, MinecraftBridge bridge) {
        Map<String, Run> runs = new ConcurrentHashMap<>();
        AtomicLong ids = new AtomicLong();
        Path directory = bridge.gameDirectory().resolve("mcp").resolve("scripts");

        Param<String> code = Param.string("code", "JavaScript, run as a function body; omit to run a saved script by name");
        Param<String> name = Param.string("name", "saved script name (mcp/scripts/<name>.js under the game directory)");
        Param<Map<String, Object>> scriptArgs = Param.object("args", "the args object the script sees, e.g. {\"count\": 10}");
        Param<Boolean> background = Param.bool("background", "start it and return an id at once; follow it with mc.script.status").or(false);
        Param<Long> maxCalls = Param.longInt("maxCalls", "fail after this many tool calls (guards against endless loops)").or(100_000L);
        Param<Long> timeout = Param.longInt("timeoutMs", "stop the script once it ran this long").or(300_000L);
        registry.register(destructive(tool("mc.script.run", GUIDE, List.of(code, name, scriptArgs, background, maxCalls, timeout), args -> {
            String source = code.get(args);
            String scriptName = name.get(args);
            if (source == null) {
                if (scriptName == null) throw new IllegalArgumentException("pass code, or the name of a saved script");
                source = JsScript.load(directory, scriptName);
            }
            Map<String, Object> given = scriptArgs.get(args) == null ? Map.of() : scriptArgs.get(args);
            JsScript script = new JsScript(registry, bridge, directory, timeout.get(args), maxCalls.get(args));
            if (!background.get(args)) return script.run(source, scriptName, given);
            String id = "script-" + ids.incrementAndGet();
            java.util.concurrent.atomic.AtomicReference<Map<String, Object>> report = new java.util.concurrent.atomic.AtomicReference<>();
            String finalSource = source;
            Thread thread = new Thread(() -> {
                Map<String, Object> done;
                try {
                    done = script.run(finalSource, scriptName, given);
                } catch (Throwable t) {
                    // an Error (out of memory ...) must still end the run, or it shows as running forever
                    done = Map.of("ok", false, "error", String.valueOf(t));
                }
                report.set(done);
            }, "minecraft-mcp-" + id);
            thread.setDaemon(true);
            prune(runs);
            runs.put(id, new Run(id, scriptName, script, thread, System.currentTimeMillis(), report));
            thread.start();
            return Map.of("id", id, "state", "running");
        })));

        Param<String> runId = Param.string("id", "run id from mc.script.run with background=true; omit to list every run");
        Param<Long> waitMs = Param.longInt("waitMs", "wait up to this long for the run to finish").or(0L);
        registry.register(read(tool("mc.script.status", "State of background script runs: running (current tool call) or finished with its report",
            List.of(runId, waitMs), args -> {
                String id = runId.get(args);
                if (id == null) return Map.of("runs", runs.values().stream().sorted((a, b) -> Long.compare(a.startedAt(), b.startedAt())).map(Run::toMap).toList());
                Run run = runs.get(id);
                if (run == null) throw new IllegalArgumentException("no script run " + id);
                if (waitMs.get(args) > 0) run.thread().join(waitMs.get(args));
                return run.toMap();
            })));
        Param<String> stopId = Param.string("id", "run id; omit to stop every running script");
        registry.register(tool("mc.script.stop", "Stop background script runs; a running tool call is interrupted",
            List.of(stopId), args -> {
                List<String> stopped = new ArrayList<>();
                for (Run run : runs.values()) {
                    if (stopId.get(args) != null && !run.id().equals(stopId.get(args))) continue;
                    if (run.report().get() != null) continue;
                    run.script().stop();
                    run.thread().interrupt();
                    stopped.add(run.id());
                }
                return Map.of("stopped", stopped);
            }));

        Param<String> saveName = Param.string("name", "script name: letters, digits, _ . -").required();
        Param<String> saveCode = Param.string("code", "the JavaScript; a first line // comment is its description").required();
        registry.register(tool("mc.script.save", "Save a script under a name, to run later with mc.script.run name=... or run(name, args) in another script",
            List.of(saveName, saveCode), args -> {
                Path file = JsScript.file(directory, saveName.get(args));
                Files.createDirectories(directory);
                Files.writeString(file, saveCode.get(args));
                return Map.of("status", "saved", "name", saveName.get(args), "path", file.toString());
            }));
        registry.register(read(tool("mc.script.list", "List saved scripts with their first-line description", args -> {
            List<Map<String, Object>> scripts = new ArrayList<>();
            if (Files.isDirectory(directory)) {
                try (Stream<Path> files = Files.list(directory)) {
                    for (Path file : files.filter(f -> f.getFileName().toString().endsWith(".js")).sorted().toList()) {
                        Map<String, Object> entry = new LinkedHashMap<>();
                        entry.put("name", file.getFileName().toString().replaceFirst("\\.js$", ""));
                        String first = Files.readAllLines(file).stream().findFirst().orElse("").trim();
                        if (first.startsWith("//")) entry.put("description", first.substring(2).trim());
                        scripts.add(entry);
                    }
                }
            }
            return Map.of("directory", directory.toString(), "scripts", scripts);
        })));
        Param<String> getName = Param.string("name", "saved script name").required();
        registry.register(read(tool("mc.script.get", "Return a saved script's code", List.of(getName),
            args -> Map.of("name", getName.get(args), "code", JsScript.load(directory, getName.get(args))))));
        registry.register(destructive(tool("mc.script.delete", "Delete a saved script", List.of(getName),
            args -> Map.of("deleted", Files.deleteIfExists(JsScript.file(directory, getName.get(args))), "name", getName.get(args)))));
    }

    /** Keeps the finished runs from piling up: only the newest few stay readable. */
    private static void prune(Map<String, Run> runs) {
        List<Run> finished = runs.values().stream().filter(run -> run.report().get() != null)
            .sorted((a, b) -> Long.compare(a.startedAt(), b.startedAt())).toList();
        for (int i = 0; i < finished.size() - KEEP_FINISHED; i++) runs.remove(finished.get(i).id());
    }
}
