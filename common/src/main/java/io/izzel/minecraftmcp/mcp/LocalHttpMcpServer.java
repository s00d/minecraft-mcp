package io.izzel.minecraftmcp.mcp;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.izzel.minecraftmcp.config.McpConfig;
import io.izzel.minecraftmcp.json.Json;

import java.io.*;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * The MCP Streamable HTTP endpoint at {@code /mcp}: every JSON-RPC message is one POST, answered with
 * {@code application/json} (or 202 for a notification). There is no event stream, so GET and DELETE are 405.
 *
 * <p>So a client config keeps working across restarts, port 0 (the default) means "the side's usual port"
 * (25580 for a client, 25581 for a server) and only falls back to a random one when that is taken.
 *
 * <p>Authentication: bound to loopback without a configured token, the endpoint takes any local caller, as
 * local MCP servers usually do; browsers are kept out by the Origin check. A configured token is always
 * required, and so is one minted for a non-loopback bind (kept in {@code mcp/token} so it survives
 * restarts). Each start writes {@code mcp/server.json} (discovery) and {@code mcp/mcp.json} (an MCP client
 * config naming this endpoint).
 */
public final class LocalHttpMcpServer implements AutoCloseable {
    public static final int CLIENT_PORT = 25580;
    public static final int SERVER_PORT = 25581;
    private static final Set<String> LOCAL_ORIGINS = Set.of("localhost", "127.0.0.1", "[::1]", "::1");

    private final McpConfig config;
    private final JsonRpcHandler handler;
    private final Executor workers;
    private HttpServer server;
    private String token;

    public LocalHttpMcpServer(McpConfig config, JsonRpcHandler handler, Executor workers) {
        this.config = config;
        this.handler = handler;
        this.workers = Objects.requireNonNull(workers, "workers");
    }

    public void start(Path gameDir, Map<String, Object> descriptor) throws IOException {
        boolean serverSide = "server".equals(descriptor.get("side"));
        server = bind(config.port() != 0 ? config.port() : serverSide ? SERVER_PORT : CLIENT_PORT, config.port() == 0);
        server.createContext("/mcp", this::handle);
        server.setExecutor(workers);
        token = config.authTokenConfigured() ? config.authToken()
            : loopback(config.bindHost()) ? null : persistedToken(gameDir.resolve("mcp/token"));
        server.start();

        int port = server.getAddress().getPort();
        String url = "http://" + (config.bindHost().contains(":") ? "[" + config.bindHost() + "]" : config.bindHost()) + ":" + port + "/mcp";
        Map<String, Object> discovery = new LinkedHashMap<>();
        discovery.put("protocol", "mcp");
        discovery.put("transport", "streamable-http");
        discovery.put("url", url);
        discovery.put("host", config.bindHost());
        discovery.put("port", port);
        discovery.put("path", "/mcp");
        if (token != null) discovery.put("authToken", token);
        discovery.putAll(descriptor);
        write(gameDir.resolve("mcp/server.json"), Json.stringify(discovery));
        // The shape Claude Code's .mcp.json (and most MCP clients) read: drop it in or copy the entry.
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("type", "http");
        entry.put("url", url);
        if (token != null) entry.put("headers", Map.of("Authorization", "Bearer " + token));
        write(gameDir.resolve("mcp/mcp.json"),
            Json.stringify(Map.of("mcpServers", Map.of(serverSide ? "minecraft-server" : "minecraft", entry))));
    }

    private HttpServer bind(int port, boolean fallBackToRandom) throws IOException {
        try {
            return HttpServer.create(new InetSocketAddress(config.bindHost(), port), 0);
        } catch (BindException e) {
            if (!fallBackToRandom) throw e;
            System.out.println("[Minecraft MCP] port " + port + " is taken, using a random one (see mcp/server.json)");
            return HttpServer.create(new InetSocketAddress(config.bindHost(), 0), 0);
        }
    }

    /** The token in {@code file}, or a freshly minted one written there (owner-only where the OS allows). */
    private String persistedToken(Path file) throws IOException {
        if (Files.isRegularFile(file)) {
            String existing = Files.readString(file, StandardCharsets.UTF_8).trim();
            if (!existing.isEmpty()) return existing;
        }
        String minted = config.authToken();
        write(file, minted);
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException e) {
            // not a POSIX file system (Windows): the game directory's own permissions apply
        }
        return minted;
    }

    private static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    private void handle(HttpExchange ex) throws IOException {
        // DNS rebinding: a browser page may reach a local port, but it cannot fake a local Origin
        String origin = ex.getRequestHeaders().getFirst("Origin");
        if (origin != null && !localOrigin(origin)) {
            send(ex, 403, "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32600,\"message\":\"Forbidden origin\"}}");
            return;
        }
        if (!"POST".equals(ex.getRequestMethod())) {
            ex.getResponseHeaders().set("Allow", "POST");
            send(ex, 405, null);
            return;
        }
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if (token != null && !("Bearer " + token).equals(auth)) {
            ex.getResponseHeaders().set("WWW-Authenticate", "Bearer");
            send(ex, 401, "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32600,\"message\":\"Unauthorized: send the authToken from mcp/server.json as a Bearer token\"}}");
            return;
        }
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        var headers = ex.getRequestHeaders();
        JsonRpcHandler.Reply reply = handler.handle(new JsonRpcHandler.Request(body,
            headers.getFirst("MCP-Protocol-Version"), headers.getFirst("Mcp-Method"), headers.getFirst("Mcp-Name")));
        send(ex, reply.status(), reply.body());
    }

    private static boolean loopback(String host) {
        return LOCAL_ORIGINS.contains(host) || host.startsWith("127.");
    }

    private static boolean localOrigin(String origin) {
        try {
            String host = URI.create(origin).getHost();
            return host != null && LOCAL_ORIGINS.contains(host);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private void send(HttpExchange ex, int status, String body) throws IOException {
        if (body == null) {
            ex.sendResponseHeaders(status, -1);
            ex.close();
            return;
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    public int port() {
        return server == null ? -1 : server.getAddress().getPort();
    }

    public void close() {
        if (server != null) {
            server.stop(0);
        }
    }
}
