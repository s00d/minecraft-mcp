package io.izzel.minecraftmcp.fabric;

import io.izzel.minecraftmcp.compat.MinecraftCompat;
import io.izzel.minecraftmcp.MinecraftMcpBootstrap;
import io.izzel.minecraftmcp.config.McpConfigs;
import io.izzel.minecraftmcp.config.ChannelCaller;
import io.izzel.minecraftmcp.config.McpPermissions;
import io.izzel.minecraftmcp.serverlink.McpPluginMessageHandler;
import io.izzel.minecraftmcp.serverlink.RemoteMcpProxies;
import io.izzel.minecraftmcp.tools.BuiltinRemoteTools;
import io.izzel.minecraftmcp.server.VanillaServerBridge;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;


public final class FabricMinecraftMcpServerEntrypoint implements DedicatedServerModInitializer {
    private static volatile MinecraftMcpBootstrap.McpEndpoint endpoint;
    private static volatile McpPluginMessageHandler pluginHandler;

    @Override
    public void onInitializeServer() {
        FabricMcpConfig.bootstrap();
        FabricStringPayload.registerTypes();

        ServerPlayNetworking.registerGlobalReceiver(FabricStringPayload.REQUEST, (payload, context) -> {
            if (pluginHandler == null) {
                return;
            }
            var responseSender = context.responseSender();
            pluginHandler.receiveRequest(payload.text(), callerFor(context.player()),
                    response -> responseSender.sendPacket(new FabricStringPayload(FabricStringPayload.RESPONSE, response)));
        });
        ServerPlayNetworking.registerGlobalReceiver(FabricStringPayload.CLIENT_RESPONSE, (payload, context) -> {
            if (pluginHandler == null) {
                return;
            }
            pluginHandler.receiveResponse(payload.text(), callerFor(context.player()));
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                RemoteMcpProxies.rememberClientName(handler.player.getUUID(), MinecraftCompat.profileName(handler.player)));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                RemoteMcpProxies.forgetClient(handler.player.getUUID()));
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            try {
                VanillaServerBridge bridge = new VanillaServerBridge(server, "fabric");
                endpoint = MinecraftMcpBootstrap.start(bridge);
                pluginHandler = new McpPluginMessageHandler(endpoint.registry(), McpConfigs::current, endpoint.workers());
                BuiltinRemoteTools.registerServer(endpoint.registry(), bridge,
                        (uuid, text) ->
                                server.execute(() -> {
                                    var target = server.getPlayerList().getPlayer(uuid);
                                    if (target != null) {
                                        ServerPlayNetworking.send(target, new FabricStringPayload(FabricStringPayload.CLIENT_REQUEST, text));
                                    }
                                }),
                        uuid -> {
                            var target = server.getPlayerList().getPlayer(uuid);
                            if (target == null) {
                                return null;
                            }
                            return new BuiltinRemoteTools.ClientPeer(callerFor(target),
                                    ServerPlayNetworking.canSend(target, FabricStringPayload.CLIENT_REQUEST));
                        });
                server.getPlayerList().getPlayers().forEach(player ->
                        RemoteMcpProxies.rememberClientName(player.getUUID(), MinecraftCompat.profileName(player)));
                System.out.println("[Minecraft MCP] Fabric dedicated MCP server started on port " + endpoint.port());
            } catch (Exception e) {
                throw new RuntimeException("Failed to start Minecraft MCP dedicated server", e);
            }
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            if (endpoint != null) {
                endpoint.close();
                endpoint = null;
            }
            pluginHandler = null;
        });
    }

    private static ChannelCaller.Player callerFor(ServerPlayer player) {
        return new ChannelCaller.Player(player.getUUID(), MinecraftCompat.profileName(player),
                FabricMcpConfig.mayRemoteCall(player), McpPermissions.REMOTE_CALL_FABRIC);
    }
}
