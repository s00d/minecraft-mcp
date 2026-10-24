package io.izzel.minecraftmcp.mixin;

import io.izzel.minecraftmcp.input.AttackOverride;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vanilla only keeps breaking a block while the attack key is down and the mouse is grabbed; with an unfocused
 * window (an agent driving the game) it aborts the break every tick. While an MCP hold or destroy runs, the
 * MCP tick task does the breaking and this skips vanilla's per-tick abort.
 */
@Mixin(Minecraft.class)
abstract class MinecraftMixin {
    @Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
    private void minecraftMcp$continueAttack(boolean leftClick, CallbackInfo ci) {
        if (AttackOverride.active()) ci.cancel();
    }
}
