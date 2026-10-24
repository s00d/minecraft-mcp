package io.izzel.minecraftmcp.tools;

import io.izzel.minecraftmcp.bridge.LoadedMods;
import io.izzel.minecraftmcp.bridge.MinecraftBridge;
import io.izzel.minecraftmcp.compat.RegistryQueries;
import io.izzel.minecraftmcp.util.CrashReports;
import io.izzel.minecraftmcp.mcp.Param;
import io.izzel.minecraftmcp.mcp.ToolRegistry;
import io.izzel.minecraftmcp.scenario.ScenarioEngine;
import io.izzel.minecraftmcp.scenario.ScenarioRunOptions;
import io.izzel.minecraftmcp.schematic.ServerSchematicTools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.izzel.minecraftmcp.mcp.McpTools.destructive;
import static io.izzel.minecraftmcp.mcp.McpTools.read;
import static io.izzel.minecraftmcp.mcp.McpTools.tool;

public final class BuiltinCommonTools {
    private BuiltinCommonTools() {
    }

    public static void register(ToolRegistry registry, MinecraftBridge bridge, ScenarioEngine scenarios) {
        registry.register(read(tool("mc.debug.capabilities", "Return MCP capabilities for this endpoint", args -> bridge.capabilities())));
        registry.register(read(tool("mc.schematic.info", "Read Sponge v3 schematic metadata", List.of(ServerSchematicTools.PATH),
            args -> bridge.schematicInfo(args))));
        Param<String> directory = Param.string("directory", "absolute path of a scenario directory, searched recursively").required();
        Param<List<?>> includeTags = Param.array("includeTags", "only scenarios with one of these tags");
        Param<List<?>> excludeTags = Param.array("excludeTags", "skip scenarios with these tags");
        registry.register(destructive(tool("mc.scenario.batch.run", "Run scenarios from a directory", List.of(directory, includeTags, excludeTags), args -> {
            ScenarioRunOptions.Builder options = ScenarioRunOptions.builder()
                .loader(bridge.loader())
                .side(bridge.side());
            addTags(includeTags.get(args), true, options);
            addTags(excludeTags.get(args), false, options);
            return scenarios.runBatch(directory.get(args), options.build()).toMap();
        })));
        Param<String> modFilter = Param.string("contains", "only mods whose id or name contains this text");
        registry.register(read(tool("mc.mods.list", "List the loaded mods with id, version and name", List.of(modFilter), args -> {
            String filter = modFilter.get(args);
            List<Map<String, Object>> mods = LoadedMods.list().stream().filter(mod -> filter == null
                || String.valueOf(mod.get("id")).contains(filter) || String.valueOf(mod.get("name")).contains(filter)).toList();
            return Map.of("loader", bridge.loader(), "count", mods.size(), "mods", mods);
        })));
        Param<String> registryName = Param.string("registry", "registry id, e.g. item, block, entity_type, sound_event; omit to list registries");
        Param<String> entryFilter = Param.string("contains", "only entries containing this text, e.g. your mod id");
        Param<Integer> limit = Param.integer("limit", "at most this many entries").or(500);
        registry.register(read(tool("mc.registry.list", "List the entries of a built-in registry (items, blocks, entities, ...) "
            + "to check what a mod registered", List.of(registryName, entryFilter, limit),
            args -> RegistryQueries.list(registryName.get(args), entryFilter.get(args), Math.max(1, limit.get(args))))));
        Param<Integer> crashLines = Param.integer("lines", "at most this many lines of the report").or(200);
        registry.register(read(tool("mc.crash.latest", "The newest crash report in crash-reports/, with its description and suspected mods",
            List.of(crashLines), args -> CrashReports.latest(bridge.gameDirectory(), Math.max(1, crashLines.get(args))))));
        Param<List<?>> calls = Param.array("calls", "tool calls to run in order: [{\"tool\": \"mc.client.state\", \"args\": {}}, ...]").required();
        Param<Boolean> keepGoing = Param.bool("keepGoing", "run the remaining calls after one fails").or(false);
        registry.register(destructive(tool("mc.batch", "Run several tool calls in one request, in order, and return every result; "
            + "stops at the first failure unless keepGoing", List.of(calls, keepGoing), args -> batch(registry, calls.get(args), keepGoing.get(args)))));
        io.izzel.minecraftmcp.script.ScriptTools.register(registry, bridge, scenarios);
        registry.register(read(tool("mc.scenario.report", "Return latest scenario report",
            args -> scenarios.latestReport().map(report -> report.toMap()).orElse(Map.of("status", "none")))));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> batch(ToolRegistry registry, List<?> calls, boolean keepGoing) {
        List<Map<String, Object>> results = new ArrayList<>();
        boolean ok = true;
        for (Object entry : calls) {
            if (!(entry instanceof Map<?, ?> call) || !(call.get("tool") instanceof String name)) {
                throw new IllegalArgumentException("each call needs a tool name, got " + entry);
            }
            if (name.equals("mc.batch")) throw new IllegalArgumentException("mc.batch cannot call itself");
            Map<String, Object> callArgs = call.get("args") instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("tool", name);
            try {
                result.put("result", registry.call(name, callArgs));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted during " + name, e);
            } catch (Exception e) {
                result.put("error", e.getMessage() == null ? e.getClass().getName() : e.getMessage());
                ok = false;
            }
            results.add(result);
            if (!ok && !keepGoing) break;
        }
        return Map.of("ok", ok, "results", results);
    }

    private static void addTags(Object value, boolean include, ScenarioRunOptions.Builder options) {
        if (value instanceof Iterable<?> iterable) {
            for (Object tag : iterable) {
                if (include) {
                    options.includeTags(String.valueOf(tag));
                } else {
                    options.excludeTags(String.valueOf(tag));
                }
            }
        } else if (value instanceof String tag && !tag.isBlank()) {
            if (include) {
                options.includeTags(tag);
            } else {
                options.excludeTags(tag);
            }
        }
    }
}
