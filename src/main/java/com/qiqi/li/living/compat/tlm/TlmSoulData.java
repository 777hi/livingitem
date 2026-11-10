package com.qiqi.li.living.compat.tlm;

import com.mojang.serialization.Codec;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/** 所存女仆快照的窄适配入口，不创建实体。 */
public final class TlmSoulData {
    private static final ResourceLocation MAID_INFO_ID =
            ResourceLocation.fromNamespaceAndPath(ModTlm.MOD_ID, "maid_info");
    private static final String MODEL_ID_KEY = "model_id";

    private TlmSoulData() {}

    public static boolean hasStoredMaid(ItemStack stack) {
        CustomData data = maidInfo(stack);
        if (data == null) return false;
        return data.read(Codec.STRING.fieldOf(MODEL_ID_KEY)).result()
                .filter(model -> !model.isBlank()).isPresent();
    }

    public static boolean isHomeMeal(ItemStack stack) {
        return ModTlm.isLoaded() && Loaded.isHomeMeal(stack);
    }

    public static boolean canAddPendingFavor(ItemStack stack) {
        CustomData data = maidInfo(stack);
        return data != null && PendingMaidFavor.canAdd(data.copyTag(), 1);
    }

    public static int pendingFavor(ItemStack stack) {
        CustomData data = maidInfo(stack);
        return data == null ? 0 : PendingMaidFavor.read(data.copyTag());
    }

    public static ItemStack withPendingFavor(ItemStack stack, int amount) {
        ItemStack copy = stack.copy();
        DataComponentType<CustomData> type = maidInfoType();
        if (type == null) return ItemStack.EMPTY;
        CustomData data = copy.get(type);
        if (data == null) return ItemStack.EMPTY;
        CompoundTag tag = data.copyTag();
        if (!PendingMaidFavor.add(tag, amount)) return ItemStack.EMPTY;
        copy.set(type, CustomData.of(tag));
        return copy;
    }

    public static void stripPendingFavor(ItemStack stack) {
        DataComponentType<CustomData> type = maidInfoType();
        if (type == null) return;
        CustomData data = stack.get(type);
        if (data == null) return;
        CompoundTag tag = data.copyTag();
        PendingMaidFavor.strip(tag);
        stack.set(type, CustomData.of(tag));
    }

    private static CustomData maidInfo(ItemStack stack) {
        DataComponentType<CustomData> type = maidInfoType();
        return type == null ? null : stack.get(type);
    }

    @SuppressWarnings("unchecked")
    private static DataComponentType<CustomData> maidInfoType() {
        return (DataComponentType<CustomData>) BuiltInRegistries.DATA_COMPONENT_TYPE.get(MAID_INFO_ID);
    }

    private static final class Loaded {
        private static boolean isHomeMeal(ItemStack stack) {
            return com.github.tartaricacid.touhoulittlemaid.entity.task.meal.DefaultMaidHomeMeal.isHomeMeal(stack);
        }
    }
}
