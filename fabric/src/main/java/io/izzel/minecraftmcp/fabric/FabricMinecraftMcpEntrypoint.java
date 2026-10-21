package io.izzel.minecraftmcp.fabric;

import io.izzel.minecraftmcp.MinecraftMcpBootstrap;
import io.izzel.minecraftmcp.client.VanillaClientBridge;
import io.izzel.minecraftmcp.config.ChannelCaller;
import io.izzel.minecraftmcp.chat.ChatLog;
import io.izzel.minecraftmcp.config.McpConfigs;
import io.izzel.minecraftmcp.serverlink.McpPluginMessageHandler;
import io.izzel.minecraftmcp.serverlink.RemoteMcpProxies;
import io.izzel.minecraftmcp.concurrent.TickCounter;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.nio.file.Path;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

public final class FabricMinecraftMcpEntrypoint implements ClientModInitializer {
    private static volatile MinecraftMcpBootstrap.McpEndpoint clientEndpoint;
    private static volatile McpPluginMessageHandler clientPluginHandler;
    @Override public void onInitializeClient() {
        try {
            FabricMcpConfig.bootstrap();
            registerRemoteMcpPluginMessages();
            clientEndpoint = MinecraftMcpBootstrap.start(new FabricBridge());
            clientPluginHandler = new McpPluginMessageHandler(clientEndpoint.registry(),
                    McpConfigs::current, clientEndpoint.workers());
            System.out.println("[Minecraft MCP] Fabric MCP server started on port " + clientEndpoint.port());
        } catch (Exception e) { throw new RuntimeException("Failed to start Minecraft MCP", e); }
    }
    private static void registerRemoteMcpPluginMessages() {
        FabricStringPayload.registerTypes();
        ClientPlayNetworking.registerGlobalReceiver(FabricStringPayload.CLIENT_REQUEST, (payload, context) -> {
            if (clientPluginHandler == null) {
                return;
            }
            var responseSender = context.responseSender();
            clientPluginHandler.receiveRequest(payload.text(), FabricClientTrust.currentServerCaller(context.client()),
                    response -> responseSender.sendPacket(new FabricStringPayload(FabricStringPayload.CLIENT_RESPONSE, response)));
        });
        ClientPlayNetworking.registerGlobalReceiver(FabricStringPayload.RESPONSE, (payload, context) -> {
            if (clientPluginHandler == null) {
                return;
            }
            clientPluginHandler.receiveResponse(payload.text(), FabricClientTrust.currentServerCaller(context.client()));
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> TickCounter.CLIENT.onTick());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> RemoteMcpProxies.toServer().failPending());
        // Overlay (action bar) messages are left out: they repeat every tick while shown.
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> { if (!overlay) ChatLog.CLIENT.add("system", message.getString()); });
        ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, time) -> ChatLog.CLIENT.add("chat", message.getString()));
    }
    static final class FabricBridge extends VanillaClientBridge {
        FabricBridge() {
            super(Minecraft.getInstance());
        }
        public String loader() { return "fabric"; }
        public Path gameDirectory() { return FabricLoader.getInstance().getGameDir(); }
        protected boolean remoteChannelOpen() { return ClientPlayNetworking.canSend(FabricStringPayload.REQUEST); }
        protected ChannelCaller currentServerCaller() { return FabricClientTrust.currentServerCaller(mc); }
        protected void sendToServer(String text) {
            ClientPlayNetworking.send(new FabricStringPayload(FabricStringPayload.REQUEST, text));
        }
    }
}
