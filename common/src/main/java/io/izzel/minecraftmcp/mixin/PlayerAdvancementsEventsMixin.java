package io.izzel.minecraftmcp.mixin;

import io.izzel.minecraftmcp.server.ServerEvents;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.LinkedHashMap;
import java.util.Map;

/** Advancement criteria a player completed, and whether that finished the advancement. */
@Mixin(PlayerAdvancements.class)
abstract class PlayerAdvancementsEventsMixin {
    @Shadow private ServerPlayer player;

    @Shadow
    public abstract net.minecraft.advancements.AdvancementProgress getOrStartProgress(AdvancementHolder advancement);

    @Inject(method = "award", at = @At("RETURN"))
    private void minecraftMcp$award(AdvancementHolder advancement, String criterion, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ() || player == null) return;
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("player", ServerEvents.who(player));
        fields.put("advancement", String.valueOf(advancement.id()));
        fields.put("criterion", criterion);
        fields.put("done", getOrStartProgress(advancement).isDone());
        ServerEvents.record("advancement", fields);
    }
}
