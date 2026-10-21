package io.izzel.minecraftmcp.config;

import io.izzel.minecraftmcp.json.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class McpConfigDocument {
    private McpConfigDocument() {
    }

    public static McpOptions read(String json) {
        Object parsed = Json.parse(json);
        if (!(parsed instanceof Map<?, ?> root)) {
            throw new IllegalArgumentException("expected a JSON object at the top level");
        }
        return new DocumentOptions(root);
    }

    public static McpOptions empty() {
        return new DocumentOptions(Map.of());
    }

    public static String defaultDocument() {
        return """
            {
              "_comment": [
                "Minecraft MCP configuration. Read once at startup; restart the game after editing.",
                "Every setting can also come from -DminecraftMcp.<path> or MINECRAFT_MCP_<PATH>,",
                "which take priority over this file. The startup log says which one won.",
                "See docs/protocol.md for the full reference."
              ],
              "endpoint": {
                "_comment": [
                  "REQUIRES A RESTART. bind/port/authToken: the local MCP endpoint. port 0 uses",
                  "  25580 on a client and 25581 on a server. A taken port leaves the game without",
                  "  an endpoint and logs a warning: give each parallel game its own port.",
                  "  A blank authToken on a loopback bind means no token: any local program may",
                  "  connect, browsers are refused by the Origin check. Set one to require it; a",
                  "  non-loopback bind with a blank one mints a token once into mcp/token.",
                  "  mcp/mcp.json always holds a ready MCP client entry for this endpoint.",
                  "headless: hide the game window once the game started (it keeps rendering, so",
                  "  screenshots work; mc.client.window shows it again) and say so in",
                  "  mc.debug.capabilities. For automated runs: no stray clicks, no focus theft."
                ],
                "bind": "127.0.0.1",
                "port": 0,
                "authToken": "",
                "headless": false
              },
              "scenario": {
                "_comment": [
                  "REQUIRES A RESTART. directory: scenarios to run once at startup; blank runs",
                  "  none. batchExit: stop the process when that batch finishes. Both are",
                  "  consumed during startup."
                ],
                "directory": "",
                "batchExit": false
              },
              "access": {
                "_comment": [
                  "TAKES EFFECT ON RESTART on Fabric; live on NeoForge.",
                  "readOnly: serve only tools that read the game (state, screens, chat, inventory,",
                  "  screenshots, waits); everything that acts on it is absent, as if disabled.",
                  "disabledTools: tools this process refuses to serve, by exact name or a",
                  "  trailing-* prefix. Applies everywhere, including the local HTTP endpoint:",
                  "  a disabled tool is absent from tools/list and tools/call answers -32602.",
                  "  Example: [\\"mc.server.log.tail\\", \\"mc.remote.*\\"]",
                  "trustedServers: servers allowed to drive this client over the plugin channel.",
                  "  Matched against the address exactly as typed into the multiplayer screen,",
                  "  ignoring case. No parsing: \\"example.com\\" and \\"example.com:25565\\" are",
                  "  different entries. A refusal message quotes the exact string to add.",
                  "  Singleplayer is always trusted. Client-side only.",
                  "Players are NOT listed here. A player driving this server over the plugin",
                  "  channel needs the minecraft_mcp:remote.call permission, which defaults to",
                  "  owner level and is granted by a permission mod."
                ],
                "readOnly": false,
                "disabledTools": [],
                "trustedServers": []
              }
            }
            """;
    }

    private record DocumentOptions(Map<?, ?> root) implements McpOptions {
        @Override
        public Optional<String> get(List<String> path) {
            Object value = walk(path);
            return value == null || value instanceof Map || value instanceof List
                ? Optional.empty()
                : Optional.of(String.valueOf(value));
        }

        @Override
        public Optional<List<String>> getList(List<String> path) {
            Object value = walk(path);
            if (value instanceof Iterable<?> iterable) {
                List<String> items = new ArrayList<>();
                for (Object item : iterable) {
                    if (item != null) {
                        items.add(String.valueOf(item));
                    }
                }
                return Optional.of(items);
            }
            if (value instanceof String single && !single.isBlank()) {
                return Optional.of(List.of(single));
            }
            return Optional.empty();
        }

        private Object walk(List<String> path) {
            Object node = root;
            for (String segment : path) {
                if (!(node instanceof Map<?, ?> map)) {
                    return null;
                }
                node = map.get(segment);
            }
            return node;
        }
    }
}
