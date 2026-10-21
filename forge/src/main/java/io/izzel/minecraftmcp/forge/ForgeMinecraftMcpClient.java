package io.izzel.minecraftmcp.forge;

import io.izzel.minecraftmcp.MinecraftMcpBootstrap;
import io.izzel.minecraftmcp.chat.ChatLog;
import io.izzel.minecraftmcp.client.VanillaClientBridge;
import io.izzel.minecraftmcp.concurrent.TickCounter;
import io.izzel.minecraftmcp.config.ChannelCaller;
import io.izzel.minecraftmcp.config.McpConfigs;
import io.izzel.minecraftmcp.serverlink.McpPluginMessageHandler;
import io.izzel.minecraftmcp.serverlink.RemoteMcpProxies;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.SystemMessageReceivedEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.network.CustomPayloadEvent;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.network.PacketDistributor;
//? if <1.21.6
/*import net.minecraftforge.common.MinecraftForge;*/

import java.nio.file.Path;

/** The client half; loaded only on a client, so the common mod class never touches client classes. */
final class ForgeMinecraftMcpClient {
    private static volatile MinecraftMcpBootstrap.McpEndpoint endpoint;
    private static volatile McpPluginMessageHandler pluginHandler;

    private ForgeMinecraftMcpClient() {
    }

    static void init() {
        //? if >=1.21.6 {
        TickEvent.ClientTickEvent.Post.BUS.addListener(event -> onTick());
        ClientChatReceivedEvent.Player.BUS.addListener(ForgeMinecraftMcpClient::onChat);
        SystemMessageReceivedEvent.BUS.addListener(ForgeMinecraftMcpClient::onSystem);
        ClientPlayerNetworkEvent.LoggingOut.BUS.addListener(event -> RemoteMcpProxies.toServer().failPending());
        //?} else {
        /*MinecraftForge.EVENT_BUS.addListener((TickEvent.ClientTickEvent.Post event) -> onTick());
        MinecraftForge.EVENT_BUS.addListener(ForgeMinecraftMcpClient::onChat);
        MinecraftForge.EVENT_BUS.addListener(ForgeMinecraftMcpClient::onSystem);
        MinecraftForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> RemoteMcpProxies.toServer().failPending());
        *///?}
    }

    // Player chat only; system messages arrive as SystemMessageReceivedEvent.
    private static void onChat(ClientChatReceivedEvent.Player event) {
        ChatLog.CLIENT.add("chat", event.getMessage().getString());
    }

    // Overlay (action bar) messages are left out: they repeat every tick while shown.
    private static void onSystem(SystemMessageReceivedEvent event) {
        if (!event.isOverlay()) ChatLog.CLIENT.add("system", event.getMessage().getString());
    }

    /** The endpoint starts on the first client tick: by then the game is fully constructed on every Forge version. */
    private static void onTick() {
        if (endpoint == null) start();
        TickCounter.CLIENT.onTick();
    }

    private static synchronized void start() {
        if (endpoint != null) return;
        try {
            endpoint = MinecraftMcpBootstrap.start(new ForgeBridge(Minecraft.getInstance()));
            pluginHandler = new McpPluginMessageHandler(endpoint.registry(), McpConfigs::current, endpoint.workers());
            ForgeMinecraftMcpMod.clientRequests = (text, context) -> pluginHandler.receiveRequest(text, caller(context),
                response -> ForgeMinecraftMcpMod.CHANNEL.reply(new ForgeStringPayload(ForgeStringPayload.CLIENT_RESPONSE, response), context));
            ForgeMinecraftMcpMod.clientResponses = (text, context) -> pluginHandler.receiveResponse(text, caller(context));
        } catch (Exception e) {
            throw new RuntimeException("Failed to start Minecraft MCP", e);
        }
    }

    private static ChannelCaller caller(CustomPayloadEvent.Context context) {
        return context.getConnection().isMemoryConnection() ? new ChannelCaller.Local("singleplayer")
            : VanillaClientBridge.serverCaller(Minecraft.getInstance());
    }

    private static final class ForgeBridge extends VanillaClientBridge {
        ForgeBridge(Minecraft mc) {
            super(mc);
        }

        public String loader() { return "forge"; }
        public Path gameDirectory() { return FMLPaths.GAMEDIR.get(); }

        protected boolean remoteChannelOpen() {
            return ForgeMinecraftMcpMod.CHANNEL.isRemotePresent(mc.getConnection().getConnection());
        }

        protected void sendToServer(String text) {
            ForgeMinecraftMcpMod.CHANNEL.send(new ForgeStringPayload(ForgeStringPayload.REQUEST, text), PacketDistributor.SERVER.noArg());
        }
    }
}
