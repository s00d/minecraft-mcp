package io.izzel.minecraftmcp.forge;

import io.izzel.minecraftmcp.compat.MinecraftCompat;
import io.izzel.minecraftmcp.serverlink.RemoteMcpChannels;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** The four plugin-channel messages; on the wire the same custom payloads Fabric and NeoForge send. */
record ForgeStringPayload(CustomPacketPayload.Type<ForgeStringPayload> type, String text) implements CustomPacketPayload {
    static final CustomPacketPayload.Type<ForgeStringPayload> REQUEST = MinecraftCompat.payloadType(RemoteMcpChannels.REQUEST);
    static final CustomPacketPayload.Type<ForgeStringPayload> RESPONSE = MinecraftCompat.payloadType(RemoteMcpChannels.RESPONSE);
    static final CustomPacketPayload.Type<ForgeStringPayload> CLIENT_REQUEST = MinecraftCompat.payloadType(RemoteMcpChannels.CLIENT_REQUEST);
    static final CustomPacketPayload.Type<ForgeStringPayload> CLIENT_RESPONSE = MinecraftCompat.payloadType(RemoteMcpChannels.CLIENT_RESPONSE);

    static StreamCodec<RegistryFriendlyByteBuf, ForgeStringPayload> codec(CustomPacketPayload.Type<ForgeStringPayload> type) {
        return StreamCodec.of((buf, payload) -> buf.writeUtf(payload.text), buf -> new ForgeStringPayload(type, buf.readUtf(32767)));
    }
}
