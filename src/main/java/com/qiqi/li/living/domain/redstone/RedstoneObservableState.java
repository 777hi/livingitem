package com.qiqi.li.living.domain.redstone;

import net.minecraft.nbt.CompoundTag;
import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.container.ContainerLivingItemHandler;
import com.qiqi.li.living.model.Pos2D;

/** 红石元件公开状态的共同编码：只写稳定配置与本拍结算后的四向出边。 */
final class RedstoneObservableState {

    private RedstoneObservableState() {}

    static CompoundTag create(ContainerContext context, int slot) {
        CompoundTag tag = new CompoundTag();
        ContainerRedstoneData data =
            ContainerLivingItemHandler.peekContainerData(context, ContainerRedstoneData.KEY);
        int[] output = data == null ? new int[ContainerRedstoneData.EDGE_COUNT]
            : data.settledOutput(slot);
        tag.putInt("out_up", output[ContainerRedstoneData.EDGE_UP]);
        tag.putInt("out_down", output[ContainerRedstoneData.EDGE_DOWN]);
        tag.putInt("out_left", output[ContainerRedstoneData.EDGE_LEFT]);
        tag.putInt("out_right", output[ContainerRedstoneData.EDGE_RIGHT]);
        return tag;
    }

    static void putDirection(CompoundTag tag, String key, Pos2D direction) {
        tag.putInt(key + "_x", direction.x());
        tag.putInt(key + "_y", direction.y());
    }
}
