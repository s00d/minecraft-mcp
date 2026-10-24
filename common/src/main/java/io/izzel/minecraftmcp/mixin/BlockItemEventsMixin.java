package io.izzel.minecraftmcp.mixin;

import io.izzel.minecraftmcp.server.ServerEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.LinkedHashMap;
import java.util.Map;

/** Blocks a player placed. */
@Mixin(BlockItem.class)
abstract class BlockItemEventsMixin {
    @Inject(method = "place", at = @At("RETURN"))
    private void minecraftMcp$placed(BlockPlaceContext context, CallbackInfoReturnable<InteractionResult> cir) {
        if (cir.getReturnValue() == null || !cir.getReturnValue().consumesAction() || context.getPlayer() == null
            || !ServerEvents.onServer(context.getPlayer())) return;
        BlockPos pos = context.getClickedPos();
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("player", ServerEvents.who(context.getPlayer()));
        fields.put("block", BuiltInRegistries.BLOCK.getKey(context.getLevel().getBlockState(pos).getBlock()).toString());
        fields.put("x", pos.getX());
        fields.put("y", pos.getY());
        fields.put("z", pos.getZ());
        ServerEvents.record("block_place", fields);
    }
}
