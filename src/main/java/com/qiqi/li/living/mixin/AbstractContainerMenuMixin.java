package com.qiqi.li.living.mixin;

import com.qiqi.li.living.chest.LivingChestStackFlags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 拦截玩家在容器中的点击操作，设置堆叠标志。
 * 仅当玩家通过鼠标/键盘操作物品时，活箱子才允许跨 UUID 堆叠。
 * 掉落物、漏斗等世界交互不受影响，按原版逻辑处理（不同 NBT 无法堆叠）。
 */
@Mixin(AbstractContainerMenu.class)
public abstract class AbstractContainerMenuMixin {

    @Inject(method = "clicked", at = @At("HEAD"))
    private void onClickedHead(int slotId, int button, ClickType clickType, Player player, CallbackInfo ci) {
        LivingChestStackFlags.ALLOW_STACK.set(true);
    }

    @Inject(method = "clicked", at = @At("RETURN"))
    private void onClickedReturn(int slotId, int button, ClickType clickType, Player player, CallbackInfo ci) {
        LivingChestStackFlags.ALLOW_STACK.remove();
    }
}