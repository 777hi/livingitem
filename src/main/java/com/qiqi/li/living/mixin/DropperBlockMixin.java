package com.qiqi.li.living.mixin;

import com.mojang.logging.LogUtils;
import com.qiqi.li.living.function.LivingChestFunction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.DropperBlock;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(DropperBlock.class)
public class DropperBlockMixin {

    private static final Logger LOGGER = LogUtils.getLogger();

    @Redirect(method = "dispenseFrom",
              at = @At(value = "INVOKE",
                       target = "Lnet/minecraft/world/level/block/entity/DispenserBlockEntity;getRandomSlot(Lnet/minecraft/util/RandomSource;)I"))
    private int onGetRandomSlot(DispenserBlockEntity dropper, RandomSource random) {
        int slot = dropper.getRandomSlot(random);
        
        if (slot < 0) return slot;
        
        ItemStack stack = dropper.getItem(slot);
        if (LivingChestFunction.isLivingChest(stack)) {
            LOGGER.info("[DropperBlock] Blocking living chest in slot {} (prevents duping)", slot);
            return -1;  // 返回 -1 让原版认为"无可用槽位"
        }
        
        return slot;  // 正常返回选中的槽位
    }
}