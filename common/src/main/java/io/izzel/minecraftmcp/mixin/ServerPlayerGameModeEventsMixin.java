package io.izzel.minecraftmcp.mixin;

import io.izzel.minecraftmcp.server.ServerEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.LinkedHashMap;
import java.util.Map;

/** Blocks a player broke (survival or creative), with what the block was before. */
@Mixin(ServerPlayerGameMode.class)
abstract class ServerPlayerGameModeEventsMixin {
    @Shadow @Final protected ServerPlayer player;
    @Shadow protected ServerLevel level;
    @Unique private BlockState minecraftMcp$breaking;

    @Inject(method = "destroyBlock", at = @At("HEAD"))
    private void minecraftMcp$before(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        minecraftMcp$breaking = level.getBlockState(pos);
    }

    @Inject(method = "destroyBlock", at = @At("RETURN"))
    private void minecraftMcp$after(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        BlockState was = minecraftMcp$breaking;
        minecraftMcp$breaking = null;
        if (!cir.getReturnValueZ() || was == null) return;
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("player", ServerEvents.who(player));
        fields.put("block", BuiltInRegistries.BLOCK.getKey(was.getBlock()).toString());
        fields.put("x", pos.getX());
        fields.put("y", pos.getY());
        fields.put("z", pos.getZ());
        ServerEvents.record("block_break", fields);
    }
}
