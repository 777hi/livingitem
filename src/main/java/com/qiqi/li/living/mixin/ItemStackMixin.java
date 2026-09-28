package com.qiqi.li.living.mixin;

import java.util.Optional;
import java.util.Set;

import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.domain.chest.LivingChestTooltipComponent;
import com.qiqi.li.living.domain.chest.LivingChestFunction;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ItemStack.class)
public abstract class ItemStackMixin {

    @Inject(method = "getTooltipImage", at = @At("RETURN"), cancellable = true)
    private void onGetTooltipImage(CallbackInfoReturnable<Optional<TooltipComponent>> cir) {
        ItemStack self = (ItemStack) (Object) this;
        if (!LivingChestFunction.isLivingChest(self)) return;

        if (LivingChestFunction.isStorageEmpty(self)) return;

        cir.setReturnValue(Optional.of(new LivingChestTooltipComponent(
            LivingChestFunction.getItems(self),
            9,
            LivingChestFunction.CHEST_SLOTS / 9
        )));
    }

    /**
     * 活物品堆叠比较：忽略运行时状态组件的差异。
     *
     * <p>两个同类活物品比较是否能堆叠时，收集所有适用功能的
     * {@link LivingItemFunction#getIgnoredComponentTypes()}，
     * 在比较时跳过这些组件。</p>
     *
     * <p>组件化设计：活物品只需在 {@link com.qiqi.li.living.core.LivingFunctionConfig}
     * 中通过 {@code withIgnoreComponentTypes()} 声明即可，
     * 无需在此 Mixin 中硬编码物品类型。</p>
     */
    @Inject(method = "isSameItemSameComponents", at = @At("HEAD"), cancellable = true)
    private static void onIsSameItemSameComponents(ItemStack stack1, ItemStack stack2,
                                                    CallbackInfoReturnable<Boolean> cir) {
        if (!LivingItemManager.isLivingItem(stack1) || !LivingItemManager.isLivingItem(stack2)) {
            return;
        }
        if (stack1.getItem() != stack2.getItem()) {
            cir.setReturnValue(false);
            return;
        }

        Set<DataComponentType<?>> ignoredTypes = LivingItemManager.getIgnoredComponentTypes(stack1);
        if (ignoredTypes.isEmpty()) return;

        DataComponentMap map1 = stack1.getComponents();
        DataComponentMap map2 = stack2.getComponents();
        Set<DataComponentType<?>> types1 = map1.keySet();
        Set<DataComponentType<?>> types2 = map2.keySet();
        for (DataComponentType<?> type : types1) {
            if (ignoredTypes.contains(type)) continue;
            if (!types2.contains(type)) {
                cir.setReturnValue(false);
                return;
            }
            if (!java.util.Objects.equals(map1.get(type), map2.get(type))) {
                cir.setReturnValue(false);
                return;
            }
        }
        for (DataComponentType<?> type : types2) {
            if (ignoredTypes.contains(type)) continue;
            if (!types1.contains(type)) {
                cir.setReturnValue(false);
                return;
            }
        }
        cir.setReturnValue(true);
    }
}