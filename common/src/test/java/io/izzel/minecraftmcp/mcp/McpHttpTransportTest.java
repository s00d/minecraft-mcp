package io.izzel.minecraftmcp.mcp;

import io.izzel.minecraftmcp.config.MapOptions;
import io.izzel.minecraftmcp.config.McpConfig;
import io.izzel.minecraftmcp.json.Json;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

/** The endpoint as an MCP client sees it: real HTTP against LocalHttpMcpServer. */
class McpHttpTransportTest {
    @TempDir Path gameDir;
    private final HttpClient http = HttpClient.newHttpClient();
    private final ExecutorService workers = Executors.newCachedThreadPool();
    private LocalHttpMcpServer server;
    private String url;
    private String token;

    @BeforeEach
    void start() throws Exception {
        start(null);
    }

    /** Binds a free port; retried because a parallel test JVM can take the port between probe and bind. */
    private void start(String configuredToken) throws Exception {
        if (server != null) server.close();
        ToolRegistry registry = new ToolRegistry();
        registry.register(McpTools.tool("mc.echo", "Echo", List.of(Param.string("message", "text to echo").required()),
            args -> Map.of("echo", args.get("message"))));
        for (int attempt = 0; ; attempt++) {
            int port;
            try (ServerSocket free = new ServerSocket(0)) {
                port = free.getLocalPort();
            }
            MapOptions options = MapOptions.of().with(port, "endpoint", "port");
            if (configuredToken != null) options.with(configuredToken, "endpoint", "authToken");
            server = new LocalHttpMcpServer(new McpConfig(options, "config/test.json"), new JsonRpcHandler(registry), workers);
            server.start(gameDir, Map.of("side", "client"));
            if (server.port() > 0) break;
            assertTrue(attempt < 4, "no free port after 5 tries");
        }
        Map<?, ?> discovery = (Map<?, ?>) Json.parse(Files.readString(gameDir.resolve("mcp/server.json")));
        url = (String) discovery.get("url");
        token = (String) discovery.get("authToken");
    }

    @AfterEach
    void stop() {
        server.close();
        workers.shutdownNow();
    }

    @Test
    void legacyHandshakeNegotiatesTheClientsVersionAndAcceptsNotifications() throws Exception {
        var init = post("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\","
            + "\"capabilities\":{},\"clientInfo\":{\"name\":\"t\",\"version\":\"1\"}}}", Map.of());
        assertEquals(200, init.statusCode());
        Map<?, ?> result = (Map<?, ?>) ((Map<?, ?>) Json.parse(init.body())).get("result");
        assertEquals("2025-06-18", result.get("protocolVersion"));
        assertEquals(Map.of("tools", Map.of("listChanged", false)), result.get("capabilities"));
        assertNotNull(result.get("instructions"));

        var notification = post("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", Map.of("MCP-Protocol-Version", "2025-06-18"));
        assertEquals(202, notification.statusCode());
        assertTrue(notification.body().isEmpty());

        var unknownVersion = post("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"1999-01-01\"}}", Map.of());
        assertEquals(JsonRpcHandler.LEGACY.get(0),
            ((Map<?, ?>) ((Map<?, ?>) Json.parse(unknownVersion.body())).get("result")).get("protocolVersion"));
    }

    @Test
    void legacyToolCallReturnsTextContent() throws Exception {
        var call = post("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"mc.echo\",\"arguments\":{\"message\":\"hi\"}}}",
            Map.of("MCP-Protocol-Version", "2025-11-25"));
        assertEquals(200, call.statusCode());
        assertTrue(call.body().contains("\\\"echo\\\":\\\"hi\\\""), call.body());
        assertFalse(call.body().contains("resultType"), "legacy results carry no resultType: " + call.body());
    }

    @Test
    void modernRequestsCarryTheirVersionAndGetResultType() throws Exception {
        String meta = ",\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\"}";
        var discover = post("{\"jsonrpc\":\"2.0\",\"id\":\"d\",\"method\":\"server/discover\",\"params\":{" + meta.substring(1) + "}}",
            Map.of("MCP-Protocol-Version", "2026-07-28", "Mcp-Method", "server/discover"));
        assertEquals(200, discover.statusCode());
        Map<?, ?> result = (Map<?, ?>) ((Map<?, ?>) Json.parse(discover.body())).get("result");
        assertEquals("complete", result.get("resultType"));
        assertEquals(JsonRpcHandler.SUPPORTED, result.get("supportedVersions"));

        var call = post("{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":{\"name\":\"mc.echo\",\"arguments\":{\"message\":\"x\"}" + meta + "}}",
            Map.of("MCP-Protocol-Version", "2026-07-28", "Mcp-Method", "tools/call", "Mcp-Name", "mc.echo"));
        assertEquals(200, call.statusCode());
        assertEquals("complete", ((Map<?, ?>) ((Map<?, ?>) Json.parse(call.body())).get("result")).get("resultType"));

        var unknownMethod = post("{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"prompts/list\",\"params\":{" + meta.substring(1) + "}}",
            Map.of("MCP-Protocol-Version", "2026-07-28"));
        assertEquals(404, unknownMethod.statusCode());
        assertEquals(-32601, errorCode(unknownMethod.body()));
    }

    @Test
    void modernVersionAndHeaderProblemsAreBadRequests() throws Exception {
        var mismatch = post("{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"tools/list\",\"params\":{\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\"}}}",
            Map.of("MCP-Protocol-Version", "2025-11-25"));
        assertEquals(400, mismatch.statusCode());
        assertEquals(-32020, errorCode(mismatch.body()));

        var unsupported = post("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/list\",\"params\":{\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"2099-01-01\"}}}",
            Map.of("MCP-Protocol-Version", "2099-01-01"));
        assertEquals(400, unsupported.statusCode());
        Map<?, ?> error = (Map<?, ?>) ((Map<?, ?>) Json.parse(unsupported.body())).get("error");
        assertEquals(-32022, ((Number) error.get("code")).intValue());
        assertEquals(Map.of("supported", JsonRpcHandler.SUPPORTED, "requested", "2099-01-01"), error.get("data"));
    }

    @Test
    void transportRules() throws Exception {
        var get = http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(405, get.statusCode());
        assertEquals("POST", get.headers().firstValue("Allow").orElse(null));

        var foreign = post("{\"jsonrpc\":\"2.0\",\"id\":8,\"method\":\"ping\"}", Map.of("Origin", "https://evil.example"));
        assertEquals(403, foreign.statusCode());
        var local = post("{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"ping\"}", Map.of("Origin", "http://localhost:3000"));
        assertEquals(200, local.statusCode());

        var parseError = post("{not json", Map.of());
        assertEquals(400, parseError.statusCode());
        assertEquals(-32700, errorCode(parseError.body()));
    }

    @Test
    void loopbackNeedsNoTokenAndTheClientConfigNamesTheUrl() throws Exception {
        assertNull(token, "no token is published when none is needed");
        var anonymous = post("{\"jsonrpc\":\"2.0\",\"id\":10,\"method\":\"ping\"}", Map.of());
        assertEquals(200, anonymous.statusCode());
        Map<?, ?> entry = (Map<?, ?>) ((Map<?, ?>) ((Map<?, ?>) Json.parse(Files.readString(gameDir.resolve("mcp/mcp.json"))))
            .get("mcpServers")).get("minecraft");
        assertEquals(Map.of("type", "http", "url", url), entry);
    }

    @Test
    void aTakenPortLeavesTheGameRunningWithoutAnEndpointOrAStaleDiscoveryFile() throws Exception {
        try (ServerSocket taken = new ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))) {
            LocalHttpMcpServer second = new LocalHttpMcpServer(
                new McpConfig(MapOptions.of().with(taken.getLocalPort(), "endpoint", "port"), "config/test.json"),
                new JsonRpcHandler(new ToolRegistry()), workers);
            Files.writeString(gameDir.resolve("mcp/server.json"), "{\"port\": 1}");
            second.start(gameDir, Map.of("side", "client"));
            assertEquals(-1, second.port());
            assertFalse(Files.exists(gameDir.resolve("mcp/server.json")), "a discovery file must not name someone else's port");
            assertFalse(Files.exists(gameDir.resolve("mcp/mcp.json")));
            second.close();
        }
    }

    @Test
    void aConfiguredTokenIsRequiredAndWrittenIntoTheClientConfig() throws Exception {
        start("hunter2");
        assertEquals("hunter2", token);
        var anonymous = http.send(HttpRequest.newBuilder(URI.create(url)).POST(HttpRequest.BodyPublishers.ofString("{}")).build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(401, anonymous.statusCode());
        assertEquals(200, post("{\"jsonrpc\":\"2.0\",\"id\":11,\"method\":\"ping\"}", Map.of()).statusCode());
        Map<?, ?> entry = (Map<?, ?>) ((Map<?, ?>) ((Map<?, ?>) Json.parse(Files.readString(gameDir.resolve("mcp/mcp.json"))))
            .get("mcpServers")).get("minecraft");
        assertEquals(Map.of("Authorization", "Bearer hunter2"), entry.get("headers"));
    }

    private HttpResponse<String> post(String body, Map<String, String> headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) request.header("Authorization", "Bearer " + token);
        headers.forEach(request::header);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static int errorCode(String body) {
        return ((Number) ((Map<?, ?>) ((Map<?, ?>) Json.parse(body)).get("error")).get("code")).intValue();
    }
}
