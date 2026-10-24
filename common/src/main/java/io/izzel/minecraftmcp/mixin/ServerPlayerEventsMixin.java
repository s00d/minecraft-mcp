package io.izzel.minecraftmcp.mixin;

import io.izzel.minecraftmcp.server.ServerEvents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A player's death: ServerPlayer.die does not call LivingEntity.die. */
@Mixin(ServerPlayer.class)
abstract class ServerPlayerEventsMixin {
    @Inject(method = "die", at = @At("HEAD"))
    private void minecraftMcp$die(DamageSource source, CallbackInfo ci) {
        ServerEvents.death((ServerPlayer) (Object) this, source);
    }
}
