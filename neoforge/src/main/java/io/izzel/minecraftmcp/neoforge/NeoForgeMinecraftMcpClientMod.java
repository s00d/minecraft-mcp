package io.izzel.minecraftmcp.neoforge;

import io.izzel.minecraftmcp.MinecraftMcpBootstrap;
import io.izzel.minecraftmcp.client.VanillaClientBridge;
import io.izzel.minecraftmcp.config.ChannelCaller;
import io.izzel.minecraftmcp.chat.ChatLog;
import io.izzel.minecraftmcp.config.McpConfigs;
import io.izzel.minecraftmcp.serverlink.McpPluginMessageHandler;
import io.izzel.minecraftmcp.serverlink.RemoteMcpProxies;
import io.izzel.minecraftmcp.input.KeyAliases;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.loading.FMLPaths;
import io.izzel.minecraftmcp.concurrent.TickCounter;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

import java.nio.file.Path;
import net.minecraft.network.ConnectionProtocol;

@Mod(value = "minecraft_mcp", dist = {Dist.CLIENT})
public final class NeoForgeMinecraftMcpClientMod {
    private static volatile MinecraftMcpBootstrap.McpEndpoint clientEndpoint;
    private static volatile McpPluginMessageHandler clientPluginHandler;

    public NeoForgeMinecraftMcpClientMod(IEventBus modBus) {
        // The earlier mod-lifecycle setup phase runs before Minecraft has constructed its singleton on 1.21.11.
        // Starting there gives NeoForgeBridge a permanently cached null client. ClientStartedEvent
        // is posted on the game bus after construction and supplies the live instance directly.
        // NeoForge before 1.21.6 has no ClientStartedEvent, and its client setup already runs after construction.
        //? if >=1.21.6 {
        NeoForge.EVENT_BUS.addListener(this::onClientStarted);
        //?} else {
        /*modBus.addListener(this::onClientSetup);
        *///?}
        // Overlay (action bar) messages are left out: they repeat every tick while shown.
        NeoForge.EVENT_BUS.addListener(ClientChatReceivedEvent.class, event -> {
            if (event instanceof ClientChatReceivedEvent.System system && system.isOverlay()) return;
            ChatLog.CLIENT.add(event instanceof ClientChatReceivedEvent.System ? "system" : "chat", event.getMessage().getString());
        });
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, event -> TickCounter.CLIENT.onTick());
        NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingOut.class,
                event -> RemoteMcpProxies.toServer().failPending());
    }

    //? if >=1.21.6 {
    private void onClientStarted(net.neoforged.neoforge.client.event.lifecycle.ClientStartedEvent event) {
        start(event.getClient());
    }
    //?} else {
    /*private void onClientSetup(net.neoforged.fml.event.lifecycle.FMLClientSetupEvent event) {
        start(Minecraft.getInstance());
    }
    *///?}

    private void start(Minecraft mc) {
        try {
            clientEndpoint = MinecraftMcpBootstrap.start(new NeoForgeBridge(mc));
            clientPluginHandler = new McpPluginMessageHandler(clientEndpoint.registry(),
                    McpConfigs::current, clientEndpoint.workers());
            ClientPayloadRouter.set(
                    (context, text) -> clientPluginHandler.receiveRequest(text,
                            NeoForgeClientTrust.currentServerCaller(context),
                            response -> context.reply(new NeoForgeStringPayload(NeoForgeStringPayload.CLIENT_RESPONSE, response))),
                    (context, text) -> clientPluginHandler.receiveResponse(text,
                            NeoForgeClientTrust.currentServerCaller(context)));
        } catch (Exception e) { throw new RuntimeException("Failed to start Minecraft MCP", e); }
    }

    static final class NeoForgeBridge extends VanillaClientBridge {
        NeoForgeBridge(Minecraft mc) {
            super(mc);
        }
        public String loader() { return "neoforge"; }
        public Path gameDirectory() { return FMLPaths.GAMEDIR.get(); }
        protected boolean remoteChannelOpen() {
            return NetworkRegistry.hasChannel(mc.getConnection().getConnection(), ConnectionProtocol.PLAY,
                    NeoForgeStringPayload.REQUEST.id());
        }
        protected ChannelCaller currentServerCaller() { return NeoForgeClientTrust.currentServerCaller(); }
        protected void sendToServer(String text) {
            //? if >=1.21.6 {
            net.neoforged.neoforge.client.network.ClientPacketDistributor.sendToServer(new NeoForgeStringPayload(NeoForgeStringPayload.REQUEST, text));
            //?} else {
            /*net.neoforged.neoforge.network.PacketDistributor.sendToServer(new NeoForgeStringPayload(NeoForgeStringPayload.REQUEST, text));
            *///?}
        }
        @Override
        public void pressKey(String key) {
            var keyMapping = InputConstants.getKey(KeyAliases.normalize(key));
            if (keyMapping.getType() == InputConstants.Type.MOUSE) {
                dispatchNativeMousePress(keyMapping, InputConstants.PRESS);
                dispatchNativeMousePress(keyMapping, InputConstants.RELEASE);
            } else {
                dispatchNativeKeyPress(keyMapping, InputConstants.PRESS);
                dispatchNativeKeyPress(keyMapping, InputConstants.RELEASE);
            }
        }
        private void dispatchNativeMousePress(InputConstants.Key key, int action) {
            try {
                //? if >=1.21.9 {
                var method = mc.mouseHandler.getClass().getDeclaredMethod(
                        "onButton", long.class, net.minecraft.client.input.MouseButtonInfo.class, int.class);
                method.setAccessible(true);
                method.invoke(mc.mouseHandler, mc.getWindow().handle(),
                        new net.minecraft.client.input.MouseButtonInfo(key.getValue(), 0), action);
                //?} else {
                /*var method = mc.mouseHandler.getClass().getDeclaredMethod(
                        "onPress", long.class, int.class, int.class, int.class);
                method.setAccessible(true);
                method.invoke(mc.mouseHandler, mc.getWindow().getWindow(), key.getValue(), action, 0);
                *///?}
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Failed to dispatch native mouse input", e);
            }
        }
        private void dispatchNativeKeyPress(InputConstants.Key key, int action) {
            try {
                // NeoForge's KeyMapping lookup filters by the active conflict context and modifier.
                // Go through the vanilla keyboard handler so press/release use that same lookup and
                // run the normal NeoForge screen/input hooks before Minecraft consumes the click.
                //? if >=1.21.9 {
                var method = mc.keyboardHandler.getClass().getDeclaredMethod(
                        "keyPress", long.class, int.class, net.minecraft.client.input.KeyEvent.class);
                method.setAccessible(true);
                method.invoke(mc.keyboardHandler, mc.getWindow().handle(), action,
                        new net.minecraft.client.input.KeyEvent(key.getValue(), 0, 0));
                //?} else {
                /*var method = mc.keyboardHandler.getClass().getDeclaredMethod(
                        "keyPress", long.class, int.class, int.class, int.class, int.class);
                method.setAccessible(true);
                method.invoke(mc.keyboardHandler, mc.getWindow().getWindow(), key.getValue(), 0, action, 0);
                *///?}
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Failed to dispatch native key input", e);
            }
        }
    }
}
