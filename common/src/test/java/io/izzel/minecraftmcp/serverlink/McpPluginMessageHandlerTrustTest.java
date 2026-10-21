package io.izzel.minecraftmcp.serverlink;

import io.izzel.minecraftmcp.concurrent.McpWorkers;
import io.izzel.minecraftmcp.config.McpConfig;
import io.izzel.minecraftmcp.config.TestConfigs;
import io.izzel.minecraftmcp.config.ChannelCaller;
import io.izzel.minecraftmcp.mcp.McpTools;
import io.izzel.minecraftmcp.mcp.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class McpPluginMessageHandlerTrustTest {
    private static final String NODE = "minecraft_mcp:remote.call";

    private static final String MALFORMED = "{";
    private static final String DEPTH_BOMB = "[".repeat(32767);

    private static ToolRegistry registry(McpConfig config) {
        ToolRegistry registry = new ToolRegistry(() -> config);
        registry.register(McpTools.tool("mc.server.state", "state", args -> Map.of("running", true)));
        registry.register(McpTools.tool("mc.server.log.tail", "log", args -> Map.of("lines", List.of())));
        return registry;
    }

    private static String call(McpConfig config, ChannelCaller caller, String tool) {
        StringBuilder out = new StringBuilder();
        new McpPluginMessageHandler(registry(config), () -> config, McpWorkers.direct()).receiveRequest(
            "{\"type\":\"request\",\"id\":7,\"tool\":\"" + tool + "\",\"arguments\":{}}", caller, out::append);
        return out.toString();
    }

    private static McpPluginMessageHandler handler(McpWorkers workers) {
        return new McpPluginMessageHandler(registry(TestConfigs.empty()), TestConfigs::empty, workers);
    }

    @Test
    void aPlayerWithoutThePermissionIsRefused() {
        ChannelCaller caller = new ChannelCaller.Player(UUID.randomUUID(), "Griefer", false, NODE);

        String response = call(TestConfigs.empty(), caller, "mc.server.state");

        assertTrue(response.contains("\"ok\":false"), response);
        assertTrue(response.contains("Griefer"), response);
        assertTrue(response.contains(NODE), response);
        assertFalse(response.contains("running"), "a refused call must not run the tool");
        assertTrue(response.contains("\"id\":7"), response);
    }

    @Test
    void aPlayerWithThePermissionIsServed() {
        ChannelCaller caller = new ChannelCaller.Player(UUID.randomUUID(), "Dev", true, NODE);

        String response = call(TestConfigs.empty(), caller, "mc.server.state");

        assertTrue(response.contains("\"ok\":true"), response);
        assertTrue(response.contains("running"), response);
        assertTrue(response.contains("\"id\":7"), "the response has to correlate with the request");
    }

    @Test
    void anUntrustedServerIsRefusedAndTheMessageNamesTheConfig() {
        ChannelCaller caller = new ChannelCaller.Server("evil.example.com:25565");

        String response = call(TestConfigs.access(List.of(), List.of("good.example.com")), caller, "mc.server.state");

        assertTrue(response.contains("\"ok\":false"), response);
        assertTrue(response.contains("trustedServers"), response);
        assertTrue(response.contains("evil.example.com:25565"), response);
    }

    @Test
    void aTrustedServerIsServed() {
        McpConfig config = TestConfigs.access(List.of(), List.of("good.example.com:25565"));

        String response = call(config, new ChannelCaller.Server("good.example.com:25565"), "mc.server.state");

        assertTrue(response.contains("\"ok\":true"), response);
    }

    @Test
    void theRefusalQuotesTheAddressVerbatimSoItCanBePasted() {
        String response = call(TestConfigs.empty(), new ChannelCaller.Server("mc.example.com"), "mc.server.state");

        assertTrue(response.contains("add \\\"mc.example.com\\\" to trustedServers"), response);
    }

    @Test
    void theIntegratedServerNeedsNoConfiguration() {
        String response = call(TestConfigs.empty(), new ChannelCaller.Local("singleplayer"), "mc.server.state");

        assertTrue(response.contains("\"ok\":true"), response);
    }

    @Test
    void aTrustedPeerStillCannotReachADisabledTool() {
        McpConfig config = TestConfigs.access(List.of("mc.server.log.tail"), List.of());
        ChannelCaller caller = new ChannelCaller.Player(UUID.randomUUID(), "Dev", true, NODE);

        String response = call(config, caller, "mc.server.log.tail");

        assertTrue(response.contains("\"ok\":false"), response);
        assertTrue(response.contains("Unknown tool"), response);
    }

    @Test
    void aMessageThatIsNotARequestDrawsNoAnswer() {
        StringBuilder out = new StringBuilder();
        handler(McpWorkers.direct()).receiveRequest("{\"type\":\"greeting\"}",
            new ChannelCaller.Server("evil.example.com:25565"), out::append);

        assertEquals("", out.toString());
    }

    private static String reply(long id) {
        return "{\"type\":\"response\",\"id\":" + id + ",\"ok\":true,\"result\":{\"running\":true}}";
    }

    @Test
    void anUntrustedServersReplyIsIgnoredEvenWhenTheIdMatches() throws Exception {
        McpConfig config = TestConfigs.access(List.of(), List.of("good.example.com"));
        RemoteMcpProxy proxy = RemoteMcpProxies.toServer();
        try {
            PendingCall call = new PendingCall(proxy, "mc.server.state", 500);

            new McpPluginMessageHandler(registry(config), () -> config, McpWorkers.direct())
                .receiveResponse(reply(call.id()), new ChannelCaller.Server("evil.example.com:25565"));

            assertInstanceOf(java.util.concurrent.TimeoutException.class, call.failure(),
                "an untrusted reply must not complete the call; it should time out instead");
        } finally {
            proxy.failPending();
        }
    }

    @Test
    void aTrustedServersReplyCompletesTheCall() throws Exception {
        McpConfig config = TestConfigs.access(List.of(), List.of("good.example.com"));
        RemoteMcpProxy proxy = RemoteMcpProxies.toServer();
        try {
            PendingCall call = new PendingCall(proxy, "mc.server.state", 5_000);

            new McpPluginMessageHandler(registry(config), () -> config, McpWorkers.direct())
                .receiveResponse(reply(call.id()), new ChannelCaller.Server("good.example.com"));

            assertNull(call.failure());
            assertEquals(Map.of("running", true), call.result.get());
        } finally {
            proxy.failPending();
        }
    }

    @Test
    void aReplyFromAPlayerTheServerNeverCalledIsDropped() {
        ChannelCaller caller = new ChannelCaller.Player(UUID.randomUUID(), "Dev", true, NODE);

        assertDoesNotThrow(() -> handler(McpWorkers.direct()).receiveResponse(
            "{\"type\":\"response\",\"id\":1,\"ok\":true}", caller));
    }

    @Test
    void neitherEntryPointParsesOnTheReceivingThread() {
        List<Runnable> queued = new ArrayList<>();
        McpWorkers deferred = new McpWorkers() {
            @Override
            public void execute(Runnable task) {
                queued.add(task);
            }

            @Override
            public void close() {
            }
        };
        StringBuilder out = new StringBuilder();
        McpPluginMessageHandler handler = handler(deferred);

        assertDoesNotThrow(() -> handler.receiveRequest(MALFORMED, new ChannelCaller.Local("singleplayer"), out::append));
        assertEquals(1, queued.size(), "the request was not handed to a worker");
        assertEquals("", out.toString(), "nothing can be answered before the payload is read");

        assertDoesNotThrow(() -> handler.receiveResponse(MALFORMED, new ChannelCaller.Local("singleplayer")));
        assertEquals(2, queued.size(), "the response was not handed to a worker");

        assertDoesNotThrow(() -> queued.forEach(Runnable::run));
        assertEquals("", out.toString());
    }

    @Test
    void malformedAndOverNestedPayloadsAreDropped() {
        StringBuilder out = new StringBuilder();
        McpPluginMessageHandler handler = handler(McpWorkers.direct());

        for (String payload : List.of(MALFORMED, DEPTH_BOMB, "tru", "")) {
            assertDoesNotThrow(() -> handler.receiveRequest(payload, new ChannelCaller.Local("singleplayer"), out::append),
                () -> "receiveRequest threw on " + payload.substring(0, Math.min(8, payload.length())));
            assertDoesNotThrow(() -> handler.receiveResponse(payload, new ChannelCaller.Local("singleplayer")),
                () -> "receiveResponse threw on " + payload.substring(0, Math.min(8, payload.length())));
        }
        assertEquals("", out.toString(), "an unreadable payload has no id to answer against");
    }

    @Test
    void wellFormedJsonThatIsNotAnObjectIsAlsoDropped() {
        StringBuilder out = new StringBuilder();

        handler(McpWorkers.direct()).receiveRequest("[1,2,3]", new ChannelCaller.Local("singleplayer"), out::append);

        assertEquals("", out.toString());
    }

    @Test
    void theToolBodyRunsOnAWorkerRatherThanTheReceivingThread() throws Exception {
        AtomicReference<Thread> receivedOn = new AtomicReference<>();
        AtomicReference<Thread> calledOn = new AtomicReference<>();
        CountDownLatch answered = new CountDownLatch(1);

        ToolRegistry registry = new ToolRegistry();
        registry.register(McpTools.tool("mc.server.state", "state", args -> {
            calledOn.set(Thread.currentThread());
            return Map.of("running", true);
        }));
        McpWorkers workers = McpWorkers.pooled("test-worker");
        try {
            McpPluginMessageHandler handler =
                new McpPluginMessageHandler(registry, TestConfigs::empty, workers);
            receivedOn.set(Thread.currentThread());
            handler.receiveRequest("{\"type\":\"request\",\"id\":1,\"tool\":\"mc.server.state\",\"arguments\":{}}",
                new ChannelCaller.Local("singleplayer"), response -> answered.countDown());

            assertTrue(answered.await(5, TimeUnit.SECONDS), "the worker never answered");
            assertNotNull(calledOn.get());
            assertNotSame(receivedOn.get(), calledOn.get(),
                "the tool body must not run on the thread that received the payload");
        } finally {
            workers.close();
        }
    }
}
