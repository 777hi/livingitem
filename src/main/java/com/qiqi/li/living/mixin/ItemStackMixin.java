package com.qiqi.li.living.mixin;

import java.util.Optional;
import java.util.Set;

import com.qiqi.li.living.LivingItemManager;
import com.qiqi.li.living.core.components.InternalStorageComponent;
import com.qiqi.li.living.core.components.LivingChestTooltipComponent;
import com.qiqi.li.living.function.LivingChestFunction;
import com.qiqi.li.living.function.LivingHopperFunction;
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

        if (InternalStorageComponent.isStorageEmpty(self)) return;

        cir.setReturnValue(Optional.of(new LivingChestTooltipComponent(
            InternalStorageComponent.getItems(self),
            9,
            LivingChestFunction.CHEST_SLOTS / 9
        )));
    }

    @Inject(method = "isSameItemSameComponents", at = @At("HEAD"), cancellable = true)
    private static void onIsSameItemSameComponents(ItemStack stack1, ItemStack stack2,
                                                    CallbackInfoReturnable<Boolean> cir) {
        boolean isHopper1 = LivingHopperFunction.isLivingHopper(stack1);
        boolean isFurnace1 = !isHopper1 && stack1.is(net.minecraft.world.item.Items.FURNACE)
                && LivingItemManager.isLivingItem(stack1);
        if (!isHopper1 && !isFurnace1) {
            return;
        }
        if (!LivingItemManager.isLivingItem(stack2)) {
            return;
        }
        if (stack1.getItem() != stack2.getItem()) {
            cir.setReturnValue(false);
            return;
        }
        DataComponentType<com.qiqi.li.living.LivingFunctionData> funcDataType =
                LivingItemManager.LIVING_FUNCTION_DATA.value();
        DataComponentMap map1 = stack1.getComponents();
        DataComponentMap map2 = stack2.getComponents();
        Set<DataComponentType<?>> types1 = map1.keySet();
        Set<DataComponentType<?>> types2 = map2.keySet();
        if (types1.size() != types2.size()) {
            cir.setReturnValue(false);
            return;
        }
        for (DataComponentType<?> type : types1) {
            if (type == funcDataType) continue;
            if (!types2.contains(type)) {
                cir.setReturnValue(false);
                return;
            }
            if (!java.util.Objects.equals(map1.get(type), map2.get(type))) {
                cir.setReturnValue(false);
                return;
            }
        }
        cir.setReturnValue(true);
    }
}