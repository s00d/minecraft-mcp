package io.izzel.minecraftmcp.neoforge;

import io.izzel.minecraftmcp.util.Log;
import io.izzel.minecraftmcp.compat.MinecraftCompat;
import io.izzel.minecraftmcp.MinecraftMcpBootstrap;
import io.izzel.minecraftmcp.bridge.LoadedMods;
import io.izzel.minecraftmcp.config.McpConfigs;
import io.izzel.minecraftmcp.config.ChannelCaller;
import io.izzel.minecraftmcp.config.McpPermissions;
import io.izzel.minecraftmcp.serverlink.McpPluginMessageHandler;
import io.izzel.minecraftmcp.serverlink.RemoteMcpProxies;
import io.izzel.minecraftmcp.tools.BuiltinRemoteTools;
import io.izzel.minecraftmcp.concurrent.TickCounter;
import io.izzel.minecraftmcp.server.VanillaServerBridge;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.ModContainer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import net.neoforged.neoforge.common.extensions.ICommonPacketListener;
import net.minecraft.server.level.ServerPlayer;


@Mod("minecraft_mcp")
public final class NeoForgeMinecraftMcpMod {
    private static volatile MinecraftMcpBootstrap.McpEndpoint serverEndpoint;
    private static volatile McpPluginMessageHandler pluginHandler;

    public NeoForgeMinecraftMcpMod(IEventBus modBus, ModContainer container) {
        NeoForgeMcpConfig.register(modBus, container);
        modBus.addListener(this::registerPayloads);
        NeoForge.EVENT_BUS.addListener(this::onServerStarted);
        NeoForge.EVENT_BUS.addListener(this::onServerStopping);
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedInEvent.class, event -> {
            if (pluginHandler == null) {
                return;
            }
            if (!(event.getEntity() instanceof ServerPlayer player)) {
                return;
            }
            RemoteMcpProxies.rememberClientName(player.getUUID(), MinecraftCompat.profileName(player));
        });
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedOutEvent.class,
            event -> RemoteMcpProxies.forgetClient(event.getEntity().getUUID()));
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, event -> TickCounter.SERVER.onTick());
        LoadedMods.install(() -> net.neoforged.fml.ModList.get().getMods().stream()
            .map(mod -> java.util.Map.<String, Object>of("id", mod.getModId(), "version", mod.getVersion().toString(),
                "name", mod.getDisplayName()))
            .sorted(java.util.Comparator.comparing(mod -> (String) mod.get("id"))).toList());
    }

    private void registerPayloads(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("2").optional();
        registrar.playToServer(NeoForgeStringPayload.REQUEST, NeoForgeStringPayload.codec(NeoForgeStringPayload.REQUEST), (payload, context) -> {
            if (pluginHandler == null) {
                return;
            }
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            pluginHandler.receiveRequest(payload.text(), callerFor(player),
                    response -> context.reply(new NeoForgeStringPayload(NeoForgeStringPayload.RESPONSE, response)));
        });
        registrar.playToServer(NeoForgeStringPayload.CLIENT_RESPONSE, NeoForgeStringPayload.codec(NeoForgeStringPayload.CLIENT_RESPONSE),
                (payload, context) -> {
                    if (pluginHandler == null) {
                        return;
                    }
                    if (!(context.player() instanceof ServerPlayer player)) {
                        return;
                    }
                    pluginHandler.receiveResponse(payload.text(), callerFor(player));
                });
        registrar.playToClient(NeoForgeStringPayload.CLIENT_REQUEST, NeoForgeStringPayload.codec(NeoForgeStringPayload.CLIENT_REQUEST),
                (payload, context) -> ClientPayloadRouter.dispatchRequest(context, payload.text()));
        registrar.playToClient(NeoForgeStringPayload.RESPONSE, NeoForgeStringPayload.codec(NeoForgeStringPayload.RESPONSE),
                (payload, context) -> ClientPayloadRouter.dispatchResponse(context, payload.text()));
    }

    private static ChannelCaller.Player callerFor(ServerPlayer player) {
        return new ChannelCaller.Player(player.getUUID(), MinecraftCompat.profileName(player),
                NeoForgeMcpConfig.mayRemoteCall(player), McpPermissions.REMOTE_CALL_NEOFORGE);
    }

    private void onServerStarted(ServerStartedEvent event) {
        if (!event.getServer().isDedicatedServer()) {
            return;
        }
        try {
            VanillaServerBridge bridge = new VanillaServerBridge(event.getServer(), "neoforge");
            serverEndpoint = MinecraftMcpBootstrap.start(bridge);
            pluginHandler = new McpPluginMessageHandler(serverEndpoint.registry(), McpConfigs::current,
                    serverEndpoint.workers());
            BuiltinRemoteTools.registerServer(serverEndpoint.registry(), bridge,
                    (uuid, text) ->
                            event.getServer().execute(() -> {
                                var target = event.getServer().getPlayerList().getPlayer(uuid);
                                if (target != null) {
                                    PacketDistributor.sendToPlayer(target, new NeoForgeStringPayload(NeoForgeStringPayload.CLIENT_REQUEST, text));
                                }
                            }),
                    uuid -> {
                        var target = event.getServer().getPlayerList().getPlayer(uuid);
                        if (target == null) {
                            return null;
                        }
                        return new BuiltinRemoteTools.ClientPeer(callerFor(target),
                                NetworkRegistry.hasChannel((ICommonPacketListener) target.connection,
                                        NeoForgeStringPayload.CLIENT_REQUEST.id()));
                    });
            event.getServer().getPlayerList().getPlayers().forEach(player ->
                    RemoteMcpProxies.rememberClientName(player.getUUID(), MinecraftCompat.profileName(player)));
            Log.info("NeoForge dedicated MCP server started on port " + serverEndpoint.port());
        } catch (Exception e) {
            throw new RuntimeException("Failed to start Minecraft MCP dedicated server", e);
        }
    }

    private void onServerStopping(ServerStoppingEvent event) {
        if (!event.getServer().isDedicatedServer()) {
            return;
        }
        if (serverEndpoint != null) {
            serverEndpoint.close();
            serverEndpoint = null;
        }
        pluginHandler = null;
    }
}
