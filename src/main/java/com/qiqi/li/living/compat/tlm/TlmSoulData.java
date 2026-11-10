package com.qiqi.li.living.compat.tlm;

import com.github.tartaricacid.touhoulittlemaid.init.InitDataComponent;
import com.mojang.serialization.Codec;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/** 所存女仆快照的只读存在性判据，不创建实体，不修改第三方数据。 */
public final class TlmSoulData {
    private TlmSoulData() {}

    public static boolean hasStoredMaid(ItemStack stack) {
        CustomData data = stack.get(InitDataComponent.MAID_INFO);
        if (data == null) return false;
        return data.read(Codec.STRING.fieldOf(InitDataComponent.MODEL_ID_TAG_NAME)).result()
                .filter(model -> !model.isBlank()).isPresent();
    }
}
