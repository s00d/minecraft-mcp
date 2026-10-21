package io.izzel.minecraftmcp.mcp;

import io.izzel.minecraftmcp.json.Json;

import java.util.*;

/**
 * MCP over JSON-RPC, for both protocol eras an MCP client may speak:
 * <ul>
 *   <li>2026-07-28: every request carries its protocol version in {@code params._meta} (and, over HTTP, in the
 *   {@code MCP-Protocol-Version} header); there is no {@code initialize}, {@code server/discover} describes the
 *   server, and results carry {@code resultType}.</li>
 *   <li>2024-11-05 to 2025-11-25: an {@code initialize} handshake negotiates the version; notifications get no
 *   reply; a 2025-03-26 client may send a batch.</li>
 * </ul>
 * This server is stateless in both: it mints no session ids and sends no server-initiated messages.
 */
public final class JsonRpcHandler {
    public static final String MODERN = "2026-07-28";
    /** Versions negotiated through initialize, newest first. */
    public static final List<String> LEGACY = List.of("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05");
    public static final List<String> SUPPORTED;
    static {
        List<String> all = new ArrayList<>(List.of(MODERN));
        all.addAll(LEGACY);
        SUPPORTED = List.copyOf(all);
    }
    private static final String VERSION_META = "io.modelcontextprotocol/protocolVersion";
    static final String INSTRUCTIONS = """
        Drives a running Minecraft client or server. mc.client.* needs a client, mc.server.* a server \
        (a singleplayer world counts). Start with mc.client.state. Every tool lists its arguments; a wrong one \
        is refused with the valid names. Prefer wait:true on world.join/world.leave/connection.connect over \
        polling. Command feedback arrives as chat: run mc.client.command.run, then mc.client.chat.wait with the \
        last value of a previous mc.client.chat.read as since. mc.client.screenshot.take with inline:true \
        returns the picture.""";

    private final ToolRegistry registry;
    private final String serverVersion;

    public JsonRpcHandler(ToolRegistry registry) {
        this(registry, "dev");
    }

    public JsonRpcHandler(ToolRegistry registry, String serverVersion) {
        this.registry = registry;
        this.serverVersion = serverVersion;
    }

    /** One HTTP POST body plus the MCP request headers that came with it (null when absent). */
    public record Request(String body, String protocolVersionHeader, String methodHeader, String nameHeader) {
        public static Request of(String body) {
            return new Request(body, null, null, null);
        }
    }

    /** status 202 has no body. */
    public record Reply(int status, String body) {
        static final Reply ACCEPTED = new Reply(202, null);
    }

    /** The JSON-RPC reply body alone, for callers that are not HTTP. */
    public String handle(String requestJson) {
        Reply reply = handle(Request.of(requestJson));
        return reply.body() == null ? "" : reply.body();
    }

    @SuppressWarnings("unchecked")
    public Reply handle(Request request) {
        Object parsed;
        try {
            parsed = Json.parse(request.body());
        } catch (Exception e) {
            return new Reply(400, error(null, -32700, "Parse error: " + e.getMessage(), null));
        }
        if (parsed instanceof List<?> batch) {
            // 2025-03-26 allowed batches; answer every request in it, in order
            List<Object> replies = new ArrayList<>();
            for (Object message : batch) {
                Reply reply = message instanceof Map<?, ?> map
                    ? message((Map<String, Object>) map, Request.of(null)) : new Reply(400, error(null, -32600, "Invalid Request", null));
                if (reply.body() != null) replies.add(Json.parse(reply.body()));
            }
            return replies.isEmpty() ? Reply.ACCEPTED : new Reply(200, Json.stringify(replies));
        }
        if (!(parsed instanceof Map<?, ?> map)) return new Reply(400, error(null, -32600, "Invalid Request", null));
        return message((Map<String, Object>) map, request);
    }

    @SuppressWarnings("unchecked")
    private Reply message(Map<String, Object> message, Request request) {
        Object id = message.get("id");
        if (!(message.get("method") instanceof String method)) {
            // a response from the client, or garbage: nothing to answer
            return message.containsKey("result") || message.containsKey("error")
                ? Reply.ACCEPTED : new Reply(400, error(id, -32600, "Invalid Request", null));
        }
        if (!message.containsKey("id")) return Reply.ACCEPTED; // notifications need no reply
        Map<String, Object> params = message.get("params") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        Map<?, ?> meta = params.get("_meta") instanceof Map<?, ?> m ? m : Map.of();
        String version = meta.get(VERSION_META) instanceof String v ? v : null;
        try {
            return version != null ? modern(id, method, params, version, request) : legacy(id, method, params, request);
        } catch (McpException e) {
            return new Reply(e.status, error(id, e.code, e.getMessage(), e.data));
        } catch (Exception e) {
            return new Reply(500, error(id, -32603, e.getMessage() == null ? e.getClass().getName() : e.getMessage(), null));
        }
    }

    private Reply modern(Object id, String method, Map<String, Object> params, String version, Request request) {
        String headerVersion = request.protocolVersionHeader();
        if (headerVersion != null && !headerVersion.equals(version)) {
            throw new McpException(400, -32020, "Header mismatch: MCP-Protocol-Version header value '" + headerVersion
                + "' does not match body value '" + version + "'", null);
        }
        if (request.methodHeader() != null && !request.methodHeader().equals(method)) {
            throw new McpException(400, -32020, "Header mismatch: Mcp-Method header value '" + request.methodHeader()
                + "' does not match body value '" + method + "'", null);
        }
        if ("tools/call".equals(method) && request.nameHeader() != null && !request.nameHeader().equals(params.get("name"))) {
            throw new McpException(400, -32020, "Header mismatch: Mcp-Name header value '" + request.nameHeader()
                + "' does not match body value '" + params.get("name") + "'", null);
        }
        if (!MODERN.equals(version)) {
            throw new McpException(400, -32022, "Unsupported protocol version",
                Map.of("supported", SUPPORTED, "requested", version));
        }
        Map<String, Object> result = switch (method) {
            case "server/discover" -> {
                Map<String, Object> discover = new LinkedHashMap<>();
                discover.put("supportedVersions", SUPPORTED);
                discover.put("capabilities", Map.of("tools", Map.of()));
                discover.put("_meta", Map.of("io.modelcontextprotocol/serverInfo", serverInfo()));
                discover.put("instructions", INSTRUCTIONS);
                discover.put("ttlMs", 0);
                discover.put("cacheScope", "private");
                yield discover;
            }
            case "tools/list" -> {
                Map<String, Object> list = new LinkedHashMap<>();
                list.put("tools", registry.listTools());
                // disabledTools can change while the game runs, and the list depends on this process's config
                list.put("ttlMs", 0);
                list.put("cacheScope", "private");
                yield list;
            }
            case "tools/call" -> callTool(params);
            case "ping" -> new LinkedHashMap<>();
            default -> throw new McpException(404, -32601, "Method not found: " + method, null);
        };
        Map<String, Object> complete = new LinkedHashMap<>();
        complete.put("resultType", "complete");
        complete.putAll(result);
        return new Reply(200, response(id, complete));
    }

    private Reply legacy(Object id, String method, Map<String, Object> params, Request request) {
        String headerVersion = request.protocolVersionHeader();
        if (headerVersion != null && !SUPPORTED.contains(headerVersion)) {
            throw new McpException(400, -32600, "Unsupported MCP-Protocol-Version: " + headerVersion, null);
        }
        Object result = switch (method) {
            case "initialize" -> {
                String requested = String.valueOf(params.get("protocolVersion"));
                Map<String, Object> init = new LinkedHashMap<>();
                init.put("protocolVersion", LEGACY.contains(requested) ? requested : LEGACY.get(0));
                init.put("capabilities", Map.of("tools", Map.of("listChanged", false)));
                init.put("serverInfo", serverInfo());
                init.put("instructions", INSTRUCTIONS);
                yield init;
            }
            case "server/discover" -> Map.of("supportedVersions", SUPPORTED, "capabilities", Map.of("tools", Map.of()),
                "_meta", Map.of("io.modelcontextprotocol/serverInfo", serverInfo()), "instructions", INSTRUCTIONS);
            case "tools/list" -> Map.of("tools", registry.listTools());
            case "tools/call" -> callTool(params);
            case "ping" -> Map.of();
            default -> throw new McpException(200, -32601, "Method not found: " + method, null);
        };
        return new Reply(200, response(id, result));
    }

    private Map<String, Object> serverInfo() {
        return Map.of("name", "minecraft-mcp", "version", serverVersion);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> callTool(Map<String, Object> params) {
        String name = String.valueOf(params.get("name"));
        Map<String, Object> args = params.get("arguments") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        try {
            Object result = registry.call(name, args);
            // A result with an "image" entry ({mimeType, data: base64}) carries it as MCP image content,
            // so a vision-capable client sees the picture; the rest stays the usual JSON text.
            if (result instanceof Map<?, ?> map && map.get("image") instanceof Map<?, ?> image
                    && image.get("data") instanceof String data && image.get("mimeType") instanceof String mimeType) {
                Map<Object, Object> rest = new LinkedHashMap<>(map);
                rest.remove("image");
                Map<String, Object> withImage = new LinkedHashMap<>();
                withImage.put("content", List.of(
                        Map.of("type", "image", "mimeType", mimeType, "data", data),
                        Map.of("type", "text", "text", Json.stringify(rest))));
                withImage.put("isError", false);
                return withImage;
            }
            return content(Json.stringify(result), false);
        } catch (ToolRegistry.UnknownToolException e) {
            throw new McpException(200, -32602, e.getMessage(), null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            String message = e.getMessage() == null ? e.getClass().getName() : e.getMessage();
            return content(message, true);
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.getClass().getName() : e.getMessage();
            return content(message, true);
        }
    }

    private static Map<String, Object> content(String text, boolean isError) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", List.of(Map.of("type", "text", "text", text)));
        result.put("isError", isError);
        return result;
    }

    private String response(Object id, Object result) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("jsonrpc", "2.0");
        r.put("id", id);
        r.put("result", result);
        return Json.stringify(r);
    }

    private String error(Object id, int code, String message, Object data) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("code", code);
        e.put("message", message);
        if (data != null) e.put("data", data);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("jsonrpc", "2.0");
        if (id != null) r.put("id", id);
        r.put("error", e);
        return Json.stringify(r);
    }

    static final class McpException extends RuntimeException {
        final int status;
        final int code;
        final Object data;

        McpException(int status, int code, String message, Object data) {
            super(message);
            this.status = status;
            this.code = code;
            this.data = data;
        }
    }
}
