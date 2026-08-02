package com.qiqi.li.living.mixin;

import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.domain.map.MapCoordHelper;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.EmptyMapItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EmptyMapItem.class)
public class MapItemMixin {

    @Inject(method = "use", at = @At("HEAD"), cancellable = true)
    private void onUse(Level level, Player player, InteractionHand hand, CallbackInfoReturnable<InteractionResultHolder<ItemStack>> cir) {
        if (level.isClientSide) return;

        ItemStack stack = player.getItemInHand(hand);
        if (!LivingItemManager.isLivingItem(stack)) return;

        int count = stack.getCount();
        double distance = 128.0 * count * count;

        float yaw = player.getYRot();
        double dx = -Math.sin(Math.toRadians(yaw));
        double dz = Math.cos(Math.toRadians(yaw));

        double targetX = player.getX() + dx * distance;
        double targetZ = player.getZ() + dz * distance;

        int centerX = MapCoordHelper.calculateMapCenterCoord(targetX, 0);
        int centerZ = MapCoordHelper.calculateMapCenterCoord(targetZ, 0);

        stack.consume(1, player);
        player.awardStat(Stats.ITEM_USED.get(stack.getItem()));
        player.level().playSound(null, player, SoundEvents.UI_CARTOGRAPHY_TABLE_TAKE_RESULT, player.getSoundSource(), 1.0F, 1.0F);

        ItemStack newMap = MapItem.create(level, centerX, centerZ, (byte)0, true, false);
        LivingItemManager.setLiving(newMap, true);

        if (stack.isEmpty()) {
            cir.setReturnValue(InteractionResultHolder.consume(newMap));
        } else {
            if (!player.getInventory().add(newMap.copy())) {
                player.drop(newMap, false);
            }
            cir.setReturnValue(InteractionResultHolder.consume(stack));
        }
    }
}