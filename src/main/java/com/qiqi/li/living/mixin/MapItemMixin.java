package com.qiqi.li.living.mixin;

import com.qiqi.li.living.domain.map.MapUpdateSkipHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MapItem.class)
public class MapItemMixin {

    @Inject(method = "inventoryTick", at = @At("HEAD"), cancellable = true)
    private void onInventoryTick(ItemStack stack, Level level, Entity entity, int itemSlot, boolean isSelected, CallbackInfo ci) {
        if (entity instanceof ServerPlayer player && MapUpdateSkipHelper.shouldSkip(player)) {
            ci.cancel();
        }
    }
}