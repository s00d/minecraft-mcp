package io.izzel.minecraftmcp.neoforge;

import io.izzel.minecraftmcp.client.VanillaClientBridge;
import io.izzel.minecraftmcp.config.ChannelCaller;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.network.handling.IPayloadContext;

final class NeoForgeClientTrust {
    private NeoForgeClientTrust() {
    }

    static ChannelCaller currentServerCaller(IPayloadContext context) {
        if (context.connection().isMemoryConnection()) {
            return new ChannelCaller.Local("singleplayer");
        }
        return currentServerCaller();
    }

    static ChannelCaller currentServerCaller() {
        return VanillaClientBridge.serverCaller(Minecraft.getInstance());
    }
}
