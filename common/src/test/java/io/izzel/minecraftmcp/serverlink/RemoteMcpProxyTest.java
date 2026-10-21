package io.izzel.minecraftmcp.serverlink;

import io.izzel.minecraftmcp.bridge.FakeServerBridge;
import io.izzel.minecraftmcp.concurrent.McpWorkers;
import io.izzel.minecraftmcp.config.TestConfigs;
import io.izzel.minecraftmcp.config.ChannelCaller;
import io.izzel.minecraftmcp.mcp.ToolRegistry;
import io.izzel.minecraftmcp.tools.BuiltinServerTools;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RemoteMcpProxyTest {
    @Test
    void proxyCallsServerToolOverPluginMessageProtocol() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        BuiltinServerTools.register(registry, new TestServerBridge());
        McpPluginMessageHandler server = new McpPluginMessageHandler(registry, TestConfigs::empty, McpWorkers.direct());
        ChannelCaller caller = new ChannelCaller.Player(UUID.randomUUID(), "Tester", true, "minecraft_mcp:remote.call");
        RemoteMcpProxy client = new RemoteMcpProxy();

        Object result = client.call("mc.server.state", Map.of(),
            payload -> server.receiveRequest(payload, caller, client::receiveResponse), 1000);

        Map<?, ?> resultMap = assertInstanceOf(Map.class, result);
        assertEquals(true, resultMap.get("running"));
        assertEquals(2, ((Number) resultMap.get("players")).intValue());
    }

    @Test
    void unreadableRepliesAreDroppedRatherThanThrown() throws Exception {
        RemoteMcpProxy proxy = new RemoteMcpProxy();
        PendingCall call = new PendingCall(proxy, "mc.server.state", 400);
        call.id();

        for (String payload : List.of("{", "[".repeat(32767), "tru", "", "[1,2,3]", "{\"type\":\"other\"}")) {
            assertDoesNotThrow(() -> proxy.receiveResponse(payload),
                () -> "receiveResponse threw on " + payload.substring(0, Math.min(8, payload.length())));
        }

        assertNotNull(call.failure(), "an unreadable reply must not complete a call");
    }

    @Test
    void failPendingUnblocksCallsInsteadOfLettingThemTimeOut() throws Exception {
        RemoteMcpProxy proxy = new RemoteMcpProxy();
        PendingCall call = new PendingCall(proxy, "mc.server.state", 30_000);
        call.id();

        proxy.failPending();

        assertNotNull(call.failure(), "a pending call should fail fast when the peer goes away");
    }

    static class TestServerBridge extends FakeServerBridge {
        @Override
        public Map<String, Object> serverState() {
            return Map.of("running", true, "players", 2);
        }
    }
}
