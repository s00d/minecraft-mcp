package io.izzel.minecraftmcp.mixin;

import io.izzel.minecraftmcp.server.ServerEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.LinkedHashMap;
import java.util.Map;

/** A player's attacks and entity interactions, and damage to players (Player.actuallyHurt does not call super). */
@Mixin(Player.class)
abstract class PlayerEventsMixin {
    @Inject(method = "attack", at = @At("HEAD"))
    private void minecraftMcp$attack(Entity target, CallbackInfo ci) {
        Player self = (Player) (Object) this;
        if (ServerEvents.onServer(self)) ServerEvents.record("attack", Map.of("player", ServerEvents.who(self), "target", ServerEvents.who(target)));
    }

    //? if >=26.1 {
    /*@Inject(method = "interactOn", at = @At("RETURN"))
    private void minecraftMcp$interact(Entity target, InteractionHand hand, net.minecraft.world.phys.Vec3 location, CallbackInfoReturnable<InteractionResult> cir) {
        minecraftMcp$interacted(target, hand, cir.getReturnValue());
    }
    *///?} else {
    @Inject(method = "interactOn", at = @At("RETURN"))
    private void minecraftMcp$interact(Entity target, InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir) {
        minecraftMcp$interacted(target, hand, cir.getReturnValue());
    }
    //?}

    @Unique
    private void minecraftMcp$interacted(Entity target, InteractionHand hand, InteractionResult result) {
        Player self = (Player) (Object) this;
        if (!ServerEvents.onServer(self)) return;
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("player", ServerEvents.who(self));
        fields.put("target", ServerEvents.who(target));
        fields.put("hand", hand == InteractionHand.OFF_HAND ? "offhand" : "main");
        fields.put("consumed", result != null && result.consumesAction());
        ServerEvents.record("interact", fields);
    }

    //? if >=1.21.2 {
    @Inject(method = "actuallyHurt", at = @At("TAIL"))
    private void minecraftMcp$hurt(net.minecraft.server.level.ServerLevel level, DamageSource source, float amount, CallbackInfo ci) {
        Player self = (Player) (Object) this;
        if (ServerEvents.onServer(self)) ServerEvents.damage(self, source, amount);
    }
    //?} else {
    /*@Inject(method = "actuallyHurt", at = @At("TAIL"))
    private void minecraftMcp$hurt(DamageSource source, float amount, CallbackInfo ci) {
        Player self = (Player) (Object) this;
        if (ServerEvents.onServer(self)) ServerEvents.damage(self, source, amount);
    }
    *///?}
}
