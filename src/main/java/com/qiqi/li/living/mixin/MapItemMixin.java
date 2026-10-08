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

/**
 * 让活地图在玩家处于「跳过更新」场景时停止 tick。
 *
 * <p>两处注入分别拦截 {@code inventoryTick} 的整体调用与 {@code update} 前的实际刷新，
 * 由 {@link MapUpdateSkipHelper} 决定是否需要跳过，避免无谓的地图重绘开销。</p>
 */
@Mixin(MapItem.class)
public class MapItemMixin {

    @Inject(method = "inventoryTick", at = @At("HEAD"), cancellable = true)
    private void onInventoryTick(ItemStack stack, Level level, Entity entity, int itemSlot, boolean isSelected, CallbackInfo ci) {
        if (entity instanceof ServerPlayer player && MapUpdateSkipHelper.shouldSkip(player)) {
            ci.cancel();
        }
    }

    @Inject(method = "inventoryTick",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/world/item/MapItem;update(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/level/saveddata/maps/MapItemSavedData;)V"),
            cancellable = true)
    private void onUpdate(ItemStack stack, Level level, Entity entity, int itemSlot, boolean isSelected, CallbackInfo ci) {
        if (entity instanceof ServerPlayer player && MapUpdateSkipHelper.shouldSkipUpdate(player, stack)) {
            ci.cancel();
        }
    }
}