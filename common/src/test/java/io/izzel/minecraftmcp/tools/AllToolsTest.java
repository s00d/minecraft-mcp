package io.izzel.minecraftmcp.tools;

import io.izzel.minecraftmcp.bridge.RecordingClientBridge;
import io.izzel.minecraftmcp.bridge.RecordingServerBridge;
import io.izzel.minecraftmcp.config.ChannelCaller;
import io.izzel.minecraftmcp.config.McpConfigs;
import io.izzel.minecraftmcp.config.TestConfigs;
import io.izzel.minecraftmcp.json.Json;
import io.izzel.minecraftmcp.mcp.McpTool;
import io.izzel.minecraftmcp.mcp.ToolRegistry;
import io.izzel.minecraftmcp.scenario.ScenarioEngine;
import io.izzel.minecraftmcp.serverlink.RemoteMcpProxies;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Every registered tool, called once with arguments built from its own schema, against recording bridges. */
class AllToolsTest {
    /** Reads net.minecraft's BuiltInRegistries, which needs a bootstrapped game. */
    private static final Set<String> NEEDS_MINECRAFT = Set.of("mc.registry.list");
    private static final UUID PEER = UUID.fromString("00000000-0000-0000-0000-00000000beef");

    @TempDir
    Path gameDirectory;

    @BeforeAll
    static void install() {
        McpConfigs.install(TestConfigs.empty());
        RemoteMcpProxies.rememberClientName(PEER, "Peer");
    }

    @AfterAll
    static void forget() {
        RemoteMcpProxies.forgetClient(PEER);
    }

    @Test
    void everyClientToolRunsWithSchemaArguments() throws Exception {
        RecordingClientBridge bridge = new RecordingClientBridge();
        bridge.gameDirectory = gameDirectory;
        bridge.chat.add("system", "hello");
        ToolRegistry registry = new ToolRegistry();
        BuiltinCommonTools.register(registry, bridge, new ScenarioEngine(registry));
        BuiltinClientTools.register(registry, bridge);
        BuiltinRemoteTools.registerClient(registry, bridge);

        Set<String> ran = runAll(registry, "client");

        assertTrue(ran.size() >= 60, "expected the whole client tool set, ran " + ran);
        assertTrue(bridge.calls.size() >= 40, "tools should reach the bridge, got " + bridge.calls);
    }

    @Test
    void everyServerToolRunsWithSchemaArguments() throws Exception {
        RecordingServerBridge bridge = new RecordingServerBridge();
        bridge.gameDirectory = gameDirectory;
        ToolRegistry registry = new ToolRegistry();
        BuiltinServerTools.register(registry, bridge, new ScenarioEngine(registry));
        BuiltinRemoteTools.registerServer(registry, bridge, (player, payload) -> answer(player, payload),
            player -> new BuiltinRemoteTools.ClientPeer(new ChannelCaller.Player(player, "Peer", true, "mcp.use"), true));

        Set<String> ran = runAll(registry, "server");

        assertTrue(ran.size() >= 30, "expected the whole server tool set, ran " + ran);
        assertTrue(bridge.calls.size() >= 15, "tools should reach the bridge, got " + bridge.calls);
    }

    /** The peer's client answers a request at once, echoing the tool it was asked to run. */
    private static void answer(UUID player, String payload) {
        Map<?, ?> request = (Map<?, ?>) Json.parse(payload);
        RemoteMcpProxies.findClient(player).receiveResponse(Json.stringify(Map.of(
            "type", "response", "id", request.get("id"), "ok", true, "result", Map.of("tool", request.get("tool")))));
    }

    private Set<String> runAll(ToolRegistry registry, String side) throws Exception {
        Set<String> ran = new TreeSet<>();
        List<String> failures = new ArrayList<>();
        for (McpTool tool : registry.allTools()) {
            Map<String, Object> schema = tool.inputSchema();
            assertEquals(false, schema.get("additionalProperties"), tool.name() + " schema must close additionalProperties");
            assertEquals("object", schema.get("type"), tool.name());

            Map<String, Object> args = arguments(schema, side);
            Map<String, Object> withUnknown = new HashMap<>(args);
            withUnknown.put("noSuchArgument", 1);
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> registry.call(tool.name(), withUnknown), tool.name() + " must refuse an unknown argument");
            assertTrue(refused.getMessage().contains("noSuchArgument"), tool.name() + ": " + refused.getMessage());

            if (NEEDS_MINECRAFT.contains(tool.name())) continue;
            try {
                assertNotNull(registry.call(tool.name(), args), tool.name() + " returned null");
                ran.add(tool.name());
            } catch (Exception | AssertionError e) {
                failures.add(tool.name() + " " + args + ": " + e);
            }
        }
        assertTrue(failures.isEmpty(), "tools failed:\n  " + String.join("\n  ", failures));
        return ran;
    }

    /** Required arguments with plausible values, and short timeouts so waits that cannot match end quickly. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> arguments(Map<String, Object> schema, String side) {
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        List<String> required = (List<String>) schema.getOrDefault("required", List.of());
        Map<String, Object> args = new LinkedHashMap<>();
        for (String name : required) args.put(name, value(name, (Map<String, Object>) properties.get(name), side));
        if (properties.containsKey("timeoutMs")) args.put("timeoutMs", 200);
        return args;
    }

    private Object value(String name, Map<String, Object> property, String side) {
        if (property.get("enum") instanceof List<?> choices) return choices.getFirst();
        return switch (String.valueOf(property.get("type"))) {
            case "integer" -> 1;
            case "number" -> 1.5;
            case "boolean" -> true;
            case "array" -> switch (name) {
                case "waypoints" -> List.of(List.of(0, 64, 0));
                case "calls" -> List.of(Map.of("tool", "mc.debug.capabilities"));
                default -> List.of();
            };
            case "object" -> Map.of("x", 0, "y", 64, "z", 0);
            default -> switch (name) {
                case "condition" -> side.equals("client") ? "client.inWorld == true" : "server.running == true";
                case "directory" -> gameDirectory.toString();
                case "tool" -> "mc." + side + ".state";
                case "player" -> PEER.toString();
                case "path" -> "unit.schem";
                case "key" -> "e";
                case "item" -> "minecraft:diamond";
                case "block" -> "minecraft:stone";
                case "address" -> "localhost:25565";
                case "command" -> "list";
                default -> "hello";
            };
        };
    }
}
