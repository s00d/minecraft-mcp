package io.izzel.minecraftmcp.forge;

import io.izzel.minecraftmcp.MinecraftMcpBootstrap;
import io.izzel.minecraftmcp.bridge.LoadedMods;
import io.izzel.minecraftmcp.compat.MinecraftCompat;
import io.izzel.minecraftmcp.concurrent.TickCounter;
import io.izzel.minecraftmcp.config.ChannelCaller;
import io.izzel.minecraftmcp.config.McpConfigs;
import io.izzel.minecraftmcp.config.McpPermissions;
import io.izzel.minecraftmcp.server.VanillaServerBridge;
import io.izzel.minecraftmcp.serverlink.McpPluginMessageHandler;
import io.izzel.minecraftmcp.serverlink.RemoteMcpProxies;
import io.izzel.minecraftmcp.tools.BuiltinRemoteTools;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.network.CustomPayloadEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.network.Channel;
import net.minecraftforge.network.ChannelBuilder;
import net.minecraftforge.network.PacketDistributor;
//? if <1.21.6
/*import net.minecraftforge.common.MinecraftForge;*/

import java.util.Comparator;
import java.util.Map;
import java.util.function.BiConsumer;

@Mod("minecraft_mcp")
public final class ForgeMinecraftMcpMod {
    // Set by the client half, so this class names no client-only code.
    static volatile BiConsumer<String, CustomPayloadEvent.Context> clientRequests;
    static volatile BiConsumer<String, CustomPayloadEvent.Context> clientResponses;

    /** Optional on both sides, so a vanilla client or server still connects; the payload ids are the channel names. */
    static final Channel<CustomPacketPayload> CHANNEL = ChannelBuilder.named("minecraft_mcp:mcp")
        .optional()
        .payloadChannel()
        .play()
        .serverbound()
        .add(ForgeStringPayload.REQUEST, ForgeStringPayload.codec(ForgeStringPayload.REQUEST), ForgeMinecraftMcpMod::onServerRequest)
        .add(ForgeStringPayload.CLIENT_RESPONSE, ForgeStringPayload.codec(ForgeStringPayload.CLIENT_RESPONSE), ForgeMinecraftMcpMod::onClientResponse)
        .clientbound()
        .add(ForgeStringPayload.CLIENT_REQUEST, ForgeStringPayload.codec(ForgeStringPayload.CLIENT_REQUEST), (payload, context) -> route(clientRequests, payload, context))
        .add(ForgeStringPayload.RESPONSE, ForgeStringPayload.codec(ForgeStringPayload.RESPONSE), (payload, context) -> route(clientResponses, payload, context))
        .build();

    private static volatile MinecraftMcpBootstrap.McpEndpoint serverEndpoint;
    private static volatile McpPluginMessageHandler pluginHandler;

    public ForgeMinecraftMcpMod(FMLJavaModLoadingContext context) {
        ForgeMcpConfig.register(context);
        //? if >=26.1 {
        /*LoadedMods.install(() -> ModList.getMods().stream()
        *///?} else {
        LoadedMods.install(() -> ModList.get().getMods().stream()
        //?}
            .map(mod -> Map.<String, Object>of("id", mod.getModId(), "version", mod.getVersion().toString(), "name", mod.getDisplayName()))
            .sorted(Comparator.comparing(mod -> (String) mod.get("id"))).toList());
        //? if >=1.21.6 {
        ServerStartedEvent.BUS.addListener(ForgeMinecraftMcpMod::onServerStarted);
        ServerStoppingEvent.BUS.addListener(ForgeMinecraftMcpMod::onServerStopping);
        TickEvent.ServerTickEvent.Post.BUS.addListener(event -> TickCounter.SERVER.onTick());
        PlayerEvent.PlayerLoggedInEvent.BUS.addListener(ForgeMinecraftMcpMod::onLoggedIn);
        PlayerEvent.PlayerLoggedOutEvent.BUS.addListener(event -> RemoteMcpProxies.forgetClient(event.getEntity().getUUID()));
        //?} else {
        /*MinecraftForge.EVENT_BUS.addListener(ForgeMinecraftMcpMod::onServerStarted);
        MinecraftForge.EVENT_BUS.addListener(ForgeMinecraftMcpMod::onServerStopping);
        MinecraftForge.EVENT_BUS.addListener((TickEvent.ServerTickEvent.Post event) -> TickCounter.SERVER.onTick());
        MinecraftForge.EVENT_BUS.addListener(ForgeMinecraftMcpMod::onLoggedIn);
        MinecraftForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent event) -> RemoteMcpProxies.forgetClient(event.getEntity().getUUID()));
        *///?}
        if (FMLEnvironment.dist == Dist.CLIENT) ForgeMinecraftMcpClient.init();
    }

    // Forge hands payloads over on the network thread; like the other loaders, decide on the game thread.
    private static void onGameThread(CustomPayloadEvent.Context context, Runnable work) {
        context.enqueueWork(work);
        context.setPacketHandled(true);
    }

    private static void route(BiConsumer<String, CustomPayloadEvent.Context> target, ForgeStringPayload payload, CustomPayloadEvent.Context context) {
        onGameThread(context, () -> {
            if (target != null) target.accept(payload.text(), context);
        });
    }

    private static void onServerRequest(ForgeStringPayload payload, CustomPayloadEvent.Context context) {
        onGameThread(context, () -> {
            ServerPlayer player = context.getSender();
            if (pluginHandler == null || player == null) return;
            pluginHandler.receiveRequest(payload.text(), callerFor(player),
                response -> CHANNEL.reply(new ForgeStringPayload(ForgeStringPayload.RESPONSE, response), context));
        });
    }

    private static void onClientResponse(ForgeStringPayload payload, CustomPayloadEvent.Context context) {
        onGameThread(context, () -> {
            ServerPlayer player = context.getSender();
            if (pluginHandler == null || player == null) return;
            pluginHandler.receiveResponse(payload.text(), callerFor(player));
        });
    }

    private static void onLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (pluginHandler != null && event.getEntity() instanceof ServerPlayer player) {
            RemoteMcpProxies.rememberClientName(player.getUUID(), MinecraftCompat.profileName(player));
        }
    }

    private static ChannelCaller.Player callerFor(ServerPlayer player) {
        return new ChannelCaller.Player(player.getUUID(), MinecraftCompat.profileName(player),
            ForgeMcpConfig.mayRemoteCall(player), McpPermissions.REMOTE_CALL_NEOFORGE);
    }

    private static void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        if (!server.isDedicatedServer()) return; // a singleplayer world is reached through the client endpoint
        try {
            VanillaServerBridge bridge = new VanillaServerBridge(server, "forge");
            serverEndpoint = MinecraftMcpBootstrap.start(bridge);
            pluginHandler = new McpPluginMessageHandler(serverEndpoint.registry(), McpConfigs::current, serverEndpoint.workers());
            BuiltinRemoteTools.registerServer(serverEndpoint.registry(), bridge,
                (uuid, text) -> server.execute(() -> {
                    ServerPlayer target = server.getPlayerList().getPlayer(uuid);
                    if (target != null) CHANNEL.send(new ForgeStringPayload(ForgeStringPayload.CLIENT_REQUEST, text), PacketDistributor.PLAYER.with(target));
                }),
                uuid -> {
                    ServerPlayer target = server.getPlayerList().getPlayer(uuid);
                    return target == null ? null
                        : new BuiltinRemoteTools.ClientPeer(callerFor(target), CHANNEL.isRemotePresent(target.connection.getConnection()));
                });
            server.getPlayerList().getPlayers().forEach(player ->
                RemoteMcpProxies.rememberClientName(player.getUUID(), MinecraftCompat.profileName(player)));
        } catch (Exception e) {
            throw new RuntimeException("Failed to start Minecraft MCP dedicated server", e);
        }
    }

    private static void onServerStopping(ServerStoppingEvent event) {
        if (!event.getServer().isDedicatedServer()) return;
        if (serverEndpoint != null) {
            serverEndpoint.close();
            serverEndpoint = null;
        }
        pluginHandler = null;
    }
}
