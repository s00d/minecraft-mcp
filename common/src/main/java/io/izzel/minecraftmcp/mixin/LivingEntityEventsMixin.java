package io.izzel.minecraftmcp.mixin;

import io.izzel.minecraftmcp.server.ServerEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.core.registries.BuiltInRegistries;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.LinkedHashMap;
import java.util.Map;

/** Deaths, damage and picked up items of mobs and players, for mc.server.events (players' own die/damage: see the player mixins). */
@Mixin(LivingEntity.class)
abstract class LivingEntityEventsMixin {
    @Inject(method = "die", at = @At("HEAD"))
    private void minecraftMcp$die(DamageSource source, CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (ServerEvents.onServer(self)) ServerEvents.death(self, source);
    }

    //? if >=1.21.2 {
    @Inject(method = "actuallyHurt", at = @At("TAIL"))
    private void minecraftMcp$hurt(net.minecraft.server.level.ServerLevel level, DamageSource source, float amount, CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (ServerEvents.onServer(self)) ServerEvents.damage(self, source, amount);
    }
    //?} else {
    /*@Inject(method = "actuallyHurt", at = @At("TAIL"))
    private void minecraftMcp$hurt(DamageSource source, float amount, CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (ServerEvents.onServer(self)) ServerEvents.damage(self, source, amount);
    }
    *///?}

    @Inject(method = "take", at = @At("HEAD"))
    private void minecraftMcp$take(Entity taken, int count, CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (!ServerEvents.onServer(self) || !(taken instanceof ItemEntity item)) return;
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("entity", ServerEvents.who(self));
        fields.put("item", BuiltInRegistries.ITEM.getKey(item.getItem().getItem()).toString());
        fields.put("count", count);
        ServerEvents.record("pickup", fields);
    }
}
