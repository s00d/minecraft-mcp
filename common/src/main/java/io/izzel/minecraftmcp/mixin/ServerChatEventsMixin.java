package io.izzel.minecraftmcp.mixin;

import io.izzel.minecraftmcp.server.ServerEvents;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

/** Chat messages players send, as the server broadcasts them. */
@Mixin(ServerGamePacketListenerImpl.class)
abstract class ServerChatEventsMixin {
    @Shadow public ServerPlayer player;

    @Inject(method = "broadcastChatMessage", at = @At("HEAD"))
    private void minecraftMcp$chat(PlayerChatMessage message, CallbackInfo ci) {
        ServerEvents.record("chat", Map.of("player", ServerEvents.who(player), "message", message.signedContent()));
    }
}
