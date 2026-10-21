package io.izzel.minecraftmcp;

import io.izzel.minecraftmcp.bridge.FakeClientBridge;
import io.izzel.minecraftmcp.bridge.FakeServerBridge;
import io.izzel.minecraftmcp.bridge.MinecraftServerBridge;
import io.izzel.minecraftmcp.mcp.McpTool;
import io.izzel.minecraftmcp.mcp.ToolRegistry;
import io.izzel.minecraftmcp.tools.BuiltinServerTools;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class MinecraftMcpBootstrapTest {
    @Test
    void withoutALocalServerTheToolFailsWithAHint() {
        ToolRegistry registry = new ToolRegistry();
        MinecraftMcpBootstrap.registerIntegratedServerTools(registry, new FakeIntegratedClient());

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> registry.call("mc.server.state", Map.of()));

        assertTrue(error.getMessage().startsWith("no_local_server"), error.getMessage());
        assertTrue(error.getMessage().contains("mc.remote.call"), error.getMessage());
    }

    @Test
    void theMirroredToolsKeepTheirArgumentCheckAndKind() {
        ToolRegistry registry = new ToolRegistry();
        MinecraftMcpBootstrap.registerIntegratedServerTools(registry, new FakeIntegratedClient());

        assertThrows(IllegalArgumentException.class, () -> registry.find("mc.server.block.state").orElseThrow().checkArguments(Map.of("nope", 1)));
        assertEquals(McpTool.Kind.READ, registry.find("mc.server.state").orElseThrow().kind());
        assertEquals(McpTool.Kind.DESTRUCTIVE, registry.find("mc.server.shutdown").orElseThrow().kind());
    }

    @Test
    void theServerIsLookedUpPerCallSoJoiningAndLeavingWorldsJustWorks() throws Exception {
        FakeIntegratedClient client = new FakeIntegratedClient();
        ToolRegistry registry = new ToolRegistry();
        MinecraftMcpBootstrap.registerIntegratedServerTools(registry, client);

        assertThrows(IllegalStateException.class, () -> registry.call("mc.server.state", Map.of()));
        client.server = new FakeIntegratedServer();
        assertEquals(Map.of("running", true, "integrated", true), registry.call("mc.server.state", Map.of()));
        client.server = null;
        assertThrows(IllegalStateException.class, () -> registry.call("mc.server.state", Map.of()));
    }

    @Test
    void everyServerToolIsMirroredOntoTheClientEndpoint() {
        ToolRegistry client = new ToolRegistry();
        MinecraftMcpBootstrap.registerIntegratedServerTools(client, new FakeIntegratedClient());

        ToolRegistry server = new ToolRegistry();
        BuiltinServerTools.register(server, new FakeIntegratedServer());

        Set<String> wrapped = names(client);
        assertEquals(names(server), wrapped);
        wrapped.forEach(name -> assertTrue(name.startsWith("mc.server."), name));
    }

    private static Set<String> names(ToolRegistry registry) {
        return registry.listTools().stream().map(tool -> String.valueOf(tool.get("name")))
            .collect(Collectors.toCollection(TreeSet::new));
    }

    private static final class FakeIntegratedClient extends FakeClientBridge {
        MinecraftServerBridge server;

        @Override public boolean integratedServerAvailable() { return server != null; }
        @Override public MinecraftServerBridge integratedServerBridge() { return server; }
    }

    private static final class FakeIntegratedServer extends FakeServerBridge {
        @Override public Map<String, Object> serverState() { return Map.of("running", true, "integrated", true); }
    }
}
