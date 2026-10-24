package io.izzel.minecraftmcp.script;

import io.izzel.minecraftmcp.bridge.RecordingClientBridge;
import io.izzel.minecraftmcp.mcp.ToolRegistry;
import io.izzel.minecraftmcp.tools.BuiltinClientTools;
import io.izzel.minecraftmcp.tools.BuiltinCommonTools;
import io.izzel.minecraftmcp.scenario.ScenarioEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class JsScriptTest {
    @TempDir
    Path gameDirectory;

    final AtomicLong ticks = new AtomicLong();
    Bridge bridge;
    ToolRegistry registry;

    /** A recording bridge whose ticks pass at once and whose player gets hungrier with every state read. */
    final class Bridge extends RecordingClientBridge {
        int food = 20;

        @Override public void waitTicks(long count) { ticks.addAndGet(count); }
        /** Like the real one: it finishes ticks later. */
        @Override public io.izzel.minecraftmcp.mcp.FutureResult<Map<String, Object>> mouseHold(String button, long t, Double x, Double y) {
            record("mouseHold", button, t);
            return new io.izzel.minecraftmcp.mcp.FutureResult<>(Runnable::run);
        }
        @Override public Map<String, Object> playerState() {
            record("playerState");
            return Map.of("food", food--, "health", 20.0, "effects", List.of());
        }
    }

    @BeforeEach
    void setUp() {
        bridge = new Bridge();
        bridge.gameDirectory = gameDirectory;
        registry = new ToolRegistry();
        BuiltinCommonTools.register(registry, bridge, new ScenarioEngine(registry));
        BuiltinClientTools.register(registry, bridge);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> run(String code) throws Exception {
        return (Map<String, Object>) registry.call("mc.script.run", Map.of("code", code, "timeoutMs", 5000));
    }

    @Test
    void callsToolsThroughTheNamespaceAndReturnsPlainValues() throws Exception {
        Map<String, Object> report = run("""
            const seen = [];
            for (const i of [0, 1, 2]) {
              const p = mc.client.player.state();
              seen.push(p.food);
              if (p.food < 19) mc.client.mouse.click({button: "right"});
            }
            wait(5);
            return {seen, last: seen[seen.length - 1], half: 1.5, nothing: null};""");
        assertEquals(true, report.get("ok"), String.valueOf(report));
        assertEquals(Map.of("seen", List.of(20L, 19L, 18L), "last", 18L, "half", 1.5), withoutNulls(report.get("result")));
        assertEquals(List.of("playerState[]", "playerState[]", "playerState[]", "mouseClick[right, null, null]"), bridge.calls);
        assertEquals(5, ticks.get());
        assertEquals(4L, report.get("calls"));
    }

    @Test
    void aFailingToolIsAnErrorTheScriptCanCatch() throws Exception {
        Map<String, Object> report = run("""
            try { mc.client.hotbar.select({slot: "x"}); } catch (e) { log("caught", e.message); }
            mc.call("mc.no.such.tool", {});""");
        assertEquals(false, report.get("ok"));
        assertTrue(String.valueOf(report.get("error")).contains("mc.no.such.tool"), String.valueOf(report));
        assertEquals(2, report.get("line"));
        assertTrue(String.valueOf(report.get("log")).contains("caught mc.client.hotbar.select"), String.valueOf(report));
    }

    @Test
    void printedLinesAndTheReturnValueReachTheReport() throws Exception {
        Map<String, Object> report = run("const found = {count: 2, names: ['a', 'b']}; print('found', found); console.log('done'); return found;");
        assertEquals(List.of("found {\"count\":2,\"names\":[\"a\",\"b\"]}", "done"), report.get("log"));
        assertEquals(Map.of("count", 2L, "names", List.of("a", "b")), report.get("result"));
    }

    @Test
    void endlessRecursionFailsTheScriptNotTheWorker() throws Exception {
        Map<String, Object> report = run("const f = n => f(n + 1); return f(0);");
        assertEquals(false, report.get("ok"), String.valueOf(report));
    }

    @Test
    void syncRunsAWholeBlockOnTheGameThread() throws Exception {
        Map<String, Object> report = run("""
            const r = sync(() => {
              const a = mc.client.player.state().food;
              mc.client.player.turn({yaw: 10});
              return [a, mc.client.player.state().food];
            });
            let held = "none";
            try { sync(() => mc.client.mouse.hold({ticks: 5})); } catch (e) { held = e.message; }
            return {r, held};""");
        assertEquals(true, report.get("ok"), String.valueOf(report));
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) report.get("result");
        assertEquals(List.of(20L, 19L), result.get("r"));
        assertTrue(String.valueOf(result.get("held")).contains("cannot pass inside sync"), String.valueOf(result));
        assertTrue(bridge.calls.contains("turn[10.0, 0.0]"), String.valueOf(bridge.calls));
    }

    @Test
    void aLongSyncBlockIsStopped() throws Exception {
        Map<String, Object> report = run("sync(() => { while (true) {} });");
        assertEquals(false, report.get("ok"));
        assertTrue(String.valueOf(report.get("error")).contains("sync()"), String.valueOf(report));
    }

    @Test
    void assertionsAndStepsReportWhereAStoryFailed() throws Exception {
        Map<String, Object> report = run("""
            step("arrive", () => assertEquals({a: [1, 2]}, {a: [1, 2.0]}));
            step("talk", () => assert(mc.client.player.state().food > 30, "the guide is not fed"));
            step("never", () => 1);""");
        assertEquals(false, report.get("ok"));
        assertTrue(String.valueOf(report.get("error")).contains("assertion failed: the guide is not fed"), String.valueOf(report));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps = (List<Map<String, Object>>) report.get("steps");
        assertEquals(List.of("arrive", "talk"), steps.stream().map(s -> s.get("name")).toList());
        assertEquals(List.of("passed", "failed"), steps.stream().map(s -> s.get("status")).toList());
    }

    @Test
    @SuppressWarnings("unchecked")
    void javascriptFilesRunAsScenarios() throws Exception {
        java.nio.file.Path dir = gameDirectory.resolve("story");
        java.nio.file.Files.createDirectories(dir);
        java.nio.file.Files.writeString(dir.resolve("a_pass.js"), "// @name greeting\n// @tags story\nstep('hello', () => assert(true));");
        java.nio.file.Files.writeString(dir.resolve("b_fail.js"), "// @tags story\nassertEquals(1, 2, 'counted');");
        java.nio.file.Files.writeString(dir.resolve("c_server.js"), "// @sides server\nassert(false);");
        Map<String, Object> report = (Map<String, Object>) registry.call("mc.scenario.batch.run", Map.of("directory", dir.toString()));
        List<Map<String, Object>> scenarios = (List<Map<String, Object>>) report.get("scenarios");
        assertEquals(List.of("greeting", "b_fail.js", "c_server.js"), scenarios.stream().map(s -> s.get("name")).toList());
        assertEquals(List.of("passed", "failed", "skipped"), scenarios.stream().map(s -> s.get("status")).toList());
        assertEquals("counted: expected 2 but was 1", scenarios.get(1).get("error"));
        assertEquals(1, ((List<?>) scenarios.get(0).get("steps")).size());
    }

    @Test
    void syntaxErrorsReportTheLine() throws Exception {
        Map<String, Object> report = run("const a = 1;\nconst b = ;");
        assertEquals(false, report.get("ok"));
        assertEquals(2, report.get("line"));
    }

    @Test
    void anEndlessLoopEndsAtTheTimeout() throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> report = (Map<String, Object>) registry.call("mc.script.run", Map.of("code", "while (true) {}", "timeoutMs", 300));
        assertEquals(false, report.get("ok"));
        assertTrue(String.valueOf(report.get("error")).contains("timeoutMs"), String.valueOf(report));
    }

    @Test
    void maxCallsStopsARunawayLoopOfToolCalls() throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> report = (Map<String, Object>) registry.call("mc.script.run",
            Map.of("code", "for (;;) mc.client.state();", "maxCalls", 10));
        assertEquals(false, report.get("ok"));
        assertTrue(String.valueOf(report.get("error")).contains("maxCalls"), String.valueOf(report));
    }

    @Test
    void savedScriptsRunByNameAndFromOtherScripts() throws Exception {
        registry.call("mc.script.save", Map.of("name", "twice", "code", "// doubles args.n\nreturn args.n * 2;"));
        assertEquals(List.of(Map.of("name", "twice", "description", "doubles args.n")),
            ((Map<?, ?>) registry.call("mc.script.list", Map.of())).get("scripts"));
        Map<String, Object> report = run("return run('twice', {n: 21}) + 1;");
        assertEquals(43L, report.get("result"), String.valueOf(report));
        @SuppressWarnings("unchecked")
        Map<String, Object> byName = (Map<String, Object>) registry.call("mc.script.run", Map.of("name", "twice", "args", Map.of("n", 4)));
        assertEquals(8L, byName.get("result"));
    }

    @Test
    void scriptsCannotNestThemselvesForever() throws Exception {
        registry.call("mc.script.save", Map.of("name", "loop", "code", "return run('loop');"));
        Map<String, Object> report = run("return run('loop');");
        assertEquals(false, report.get("ok"));
        assertTrue(String.valueOf(report.get("error")).contains("nested"), String.valueOf(report));
    }

    @Test
    void scriptToolsAreNotCallableFromScripts() throws Exception {
        Map<String, Object> report = run("return typeof mc.script;");
        assertEquals("undefined", report.get("result"));
        report = run("mc.call('mc.script.run', {code: '1'});");
        assertEquals(false, report.get("ok"));
    }

    @Test
    void untilPollsEveryTicksAndGivesUp() throws Exception {
        Map<String, Object> report = run("return [until(() => mc.client.player.state().food < 18, 5000, 2), until(() => false, 50)];");
        assertEquals(List.of(true, false), report.get("result"), String.valueOf(report));
    }

    @Test
    @SuppressWarnings("unchecked")
    void backgroundRunsReportAndStop() throws Exception {
        Map<String, Object> started = (Map<String, Object>) registry.call("mc.script.run",
            Map.of("code", "let n = 0; while (true) { n++; sleep(5); }", "background", true));
        String id = (String) started.get("id");
        assertEquals("running", ((Map<String, Object>) registry.call("mc.script.status", Map.of("id", id))).get("state"));
        assertEquals(List.of(id), ((Map<String, Object>) registry.call("mc.script.stop", Map.of())).get("stopped"));
        Map<String, Object> done = (Map<String, Object>) registry.call("mc.script.status", Map.of("id", id, "waitMs", 5000));
        assertEquals("failed", done.get("state"), String.valueOf(done));
        assertTrue(String.valueOf(done.get("report")).contains("stopped"), String.valueOf(done));
    }

    @Test
    void constBecomesLetOnlyAsAKeyword() {
        assertEquals("let a = 'const'; // const x\nlet b = `const ${ {const: 1}.const + (() => { let c = 1; return c; })() } const`; /* const */ x.const",
            JsScript.constToLet("const a = 'const'; // const x\nconst b = `const ${ {const: 1}.const + (() => { const c = 1; return c; })() } const`; /* const */ x.const"));
    }

    @Test
    void regexLiteralsAndMethodsNamedConstAreLeftAlone() {
        assertEquals("let r = /'`[/]\\//g.test(s); let a = 1; x = a / 2; let b = o.const(); ({const() { return 1 }})",
            JsScript.constToLet("const r = /'`[/]\\//g.test(s); const a = 1; x = a / 2; const b = o.const(); ({const() { return 1 }})"));
    }

    @Test
    void scriptsCannotReachJava() throws Exception {
        Map<String, Object> report = run("""
            let leaked = [];
            try { null.x } catch (e) { leaked.push(typeof e.rhinoException, typeof e.javaException); }
            leaked.push(typeof Packages, typeof java, typeof JavaAdapter);
            try { leaked.push(String(mc.client.state.getClass)); } catch (e) { leaked.push("threw"); }
            return leaked;""");
        assertEquals(true, report.get("ok"), String.valueOf(report));
        assertEquals(List.of("undefined", "undefined", "undefined", "undefined", "undefined", "undefined"), report.get("result"));
    }

    @Test
    void constInsideLoopsGetsANewValueEachIteration() throws Exception {
        Map<String, Object> report = run("const out = []; for (const x of [1, 2]) { const y = x * 10; out.push(y); } let i = 0; "
            + "while (i < 2) { const z = i++; out.push(z); } return out;");
        assertEquals(List.of(10L, 20L, 0L, 1L), report.get("result"), String.valueOf(report));
    }

    private static Object withoutNulls(Object value) {
        if (value instanceof Map<?, ?> map) {
            java.util.Map<Object, Object> out = new java.util.LinkedHashMap<>();
            map.forEach((k, v) -> { if (v != null) out.put(k, withoutNulls(v)); });
            return out;
        }
        return value;
    }
}
