package io.izzel.minecraftmcp.forge;

import io.izzel.minecraftmcp.util.Log;
import io.izzel.minecraftmcp.compat.MinecraftCompat;
import io.izzel.minecraftmcp.config.DefaultOptions;
import io.izzel.minecraftmcp.config.McpConfig;
import io.izzel.minecraftmcp.config.McpConfigs;
import io.izzel.minecraftmcp.config.McpOptions;
import io.izzel.minecraftmcp.config.McpPermissions;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
//? if <1.21.6
/*import net.minecraftforge.common.MinecraftForge;*/
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.server.permission.PermissionAPI;
import net.minecraftforge.server.permission.events.PermissionGatherEvent;
import net.minecraftforge.server.permission.nodes.PermissionNode;
import net.minecraftforge.server.permission.nodes.PermissionTypes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class ForgeMcpConfig {
    static final String FILE_NAME = "minecraft-mcp.toml";

    static final PermissionNode<Boolean> REMOTE_CALL = remoteCallNode();

    // setInformation returns a raw PermissionNode, so it is called for its effect rather than chained.
    private static PermissionNode<Boolean> remoteCallNode() {
        PermissionNode<Boolean> node = new PermissionNode<>(
            McpPermissions.NAMESPACE, McpPermissions.REMOTE_CALL_PATH, PermissionTypes.BOOLEAN,
            (player, uuid, context) -> player != null && MinecraftCompat.isOwner(player));
        node.setInformation(
            Component.literal("Minecraft MCP remote call"),
            Component.literal("Exchange MCP tool calls with this server over the plugin channel, "
                + "in either direction."));
        return node;
    }

    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();
    private static final Map<List<String>, ForgeConfigSpec.ConfigValue<?>> VALUES = new LinkedHashMap<>();
    static final ForgeConfigSpec SPEC;

    static {
        BUILDER.comment("Minecraft MCP configuration.",
                "Every setting can also come from -DminecraftMcp.<path> or MINECRAFT_MCP_<PATH>,",
                "which take priority over this file. The startup log says which one won.",
                "See docs/protocol.md.")
            .push("endpoint");
        define(BUILDER.comment("Address the local HTTP endpoint binds to.",
                "Requires a game restart: the server has already bound.")
            .translation("minecraft_mcp.configuration.endpoint.bind")
            .define("bind", "127.0.0.1"));
        define(BUILDER.comment("Port for the local MCP endpoint; 0 uses 25580 on a client and 25581 on a server,",
                "A taken port leaves the game without an endpoint and logs a warning:",
                "give each game that runs at the same time its own port.",
                "Requires a game restart: the server has already bound.")
            .translation("minecraft_mcp.configuration.endpoint.port")
            .defineInRange("port", 0, 0, 65535));
        define(BUILDER.comment("Bearer token for the local HTTP endpoint.",
                "Blank on a loopback bind means no token: any local program may connect, browsers",
                "are refused by the Origin check. Blank on any other bind mints one into mcp/token.",
                "Requires a game restart: the token is already published in mcp/server.json.")
            .translation("minecraft_mcp.configuration.endpoint.authToken")
            .define("authToken", ""));
        define(BUILDER.comment("Hide the game window once the game started: it keeps rendering, so screenshots",
                "work, and mc.client.window shows it again. For automated runs: no stray clicks.",
                "Requires a game restart: it is applied at startup.")
            .translation("minecraft_mcp.configuration.endpoint.headless")
            .define("headless", false));
        BUILDER.pop();

        BUILDER.push("scenario");
        define(BUILDER.comment("Directory of scenarios to run once at startup; blank runs none.",
                "Requires a game restart: the batch is dispatched during startup.")
            .translation("minecraft_mcp.configuration.scenario.directory")
            .define("directory", ""));
        define(BUILDER.comment("Stop the process once the startup scenario batch finishes.",
                "Requires a game restart: the batch is dispatched during startup.")
            .translation("minecraft_mcp.configuration.scenario.batchExit")
            .define("batchExit", false));
        BUILDER.pop();

        BUILDER.comment("Access policy. These take effect as soon as the file is saved.")
            .push("access");
        define(BUILDER.comment("Serve only tools that read the game (state, screens, chat, inventory,",
                "screenshots, waits); everything that acts on it is absent, as if disabled.")
            .translation("minecraft_mcp.configuration.access.readOnly")
            .define("readOnly", false));
        define(BUILDER.comment("Tools this process refuses to serve, by exact name or a trailing-* prefix.",
                "Applies everywhere, including the local HTTP endpoint: a disabled tool is absent",
                "from tools/list and tools/call answers -32602.",
                "Example: [\"mc.server.log.tail\", \"mc.remote.*\"]")
            .translation("minecraft_mcp.configuration.access.disabledTools")
            // defineListAllowEmpty, not defineList: the latter ends up on ListValueSpec.NON_EMPTY,
            // so an empty default reads as incorrect and FML backs the file up and rewrites it on
            // every start. Empty is exactly what we want to ship.
            .defineListAllowEmpty("disabledTools", List.of(), ForgeMcpConfig::nonBlankString));
        define(BUILDER.comment("Servers this client exchanges MCP messages with, in either direction:",
                "each entry lets that server drive this client, and lets mc.remote.call from",
                "this client reach that server. Calling a server that is not listed fails",
                "immediately rather than timing out.",
                "Matched against the address exactly as typed into the multiplayer screen,",
                "ignoring case. No parsing: \"example.com\" and \"example.com:25565\" are",
                "different entries. A refusal message quotes the exact string to add.",
                "Singleplayer is always trusted. Client-side only; a dedicated server ignores this.",
                "Players are not listed here - a player needs the " + McpPermissions.REMOTE_CALL_NEOFORGE
                    + " permission.")
            .translation("minecraft_mcp.configuration.access.trustedServers")
            .defineListAllowEmpty("trustedServers", List.of(), ForgeMcpConfig::nonBlankString));
        BUILDER.pop();

        SPEC = BUILDER.build();
    }

    private ForgeMcpConfig() {
    }

    private static void define(ForgeConfigSpec.ConfigValue<?> value) {
        VALUES.put(List.copyOf(value.getPath()), value);
    }

    private static boolean nonBlankString(Object value) {
        return value instanceof String text && !text.isBlank();
    }

    private static void verifyCoversEveryOption() {
        List<String> missing = new ArrayList<>();
        for (List<String> path : DefaultOptions.paths()) {
            if (!VALUES.containsKey(path)) {
                missing.add(String.join(".", path));
            }
        }
        if (!missing.isEmpty()) {
            Log.warn(FILE_NAME + " does not define " + missing
                + "; those can only be set from a system property or the environment");
        }
    }

    static void register(FMLJavaModLoadingContext context) {
        verifyCoversEveryOption();
        context.registerConfig(ModConfig.Type.COMMON, SPEC, FILE_NAME);
        McpConfigs.install(new McpConfig(new SpecOptions(), "config/" + FILE_NAME));
        //? if >=1.21.6 {
        ModConfigEvent.Loading.getBus(context.getModBusGroup()).addListener(ForgeMcpConfig::reportFor);
        ModConfigEvent.Reloading.getBus(context.getModBusGroup()).addListener(ForgeMcpConfig::reportFor);
        PermissionGatherEvent.Nodes.BUS.addListener(event -> event.addNodes(REMOTE_CALL));
        //?} else {
        /*context.getModEventBus().addListener(ForgeMcpConfig::reportFor);
        MinecraftForge.EVENT_BUS.addListener((PermissionGatherEvent.Nodes event) -> event.addNodes(REMOTE_CALL));
        *///?}
    }

    private static void reportFor(ModConfigEvent event) {
        if (FILE_NAME.equals(event.getConfig().getFileName())) {
            McpConfigs.report();
        }
    }

    static boolean mayRemoteCall(ServerPlayer player) {
        try {
            return PermissionAPI.getPermission(player, REMOTE_CALL);
        } catch (RuntimeException e) {
            Log.warn("could not resolve " + McpPermissions.REMOTE_CALL_NEOFORGE
                + " for " + MinecraftCompat.profileName(player) + ", refusing: " + e);
            return false;
        }
    }

    private record SpecOptions() implements McpOptions {
        @Override
        public Optional<String> get(List<String> path) {
            Object value = read(path);
            return value == null || value instanceof List ? Optional.empty() : Optional.of(String.valueOf(value));
        }

        @Override
        public Optional<List<String>> getList(List<String> path) {
            if (!(read(path) instanceof List<?> list)) {
                return Optional.empty();
            }
            List<String> items = new ArrayList<>(list.size());
            for (Object item : list) {
                if (item != null) {
                    items.add(String.valueOf(item));
                }
            }
            return Optional.of(items);
        }

        private static Object read(List<String> path) {
            ForgeConfigSpec.ConfigValue<?> value = VALUES.get(path);
            if (value == null) {
                return null;
            }
            try {
                return value.get();
            } catch (IllegalStateException e) {
                Log.warn(String.join(".", path)
                    + " was read before " + FILE_NAME + " loaded; using the built-in default");
                return null;
            }
        }
    }
}
