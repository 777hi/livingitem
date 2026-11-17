package com.qiqi.li.living.compat.jade;

import java.util.List;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;

import snownee.jade.api.Accessor;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IWailaCommonRegistration;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.WailaPlugin;
import snownee.jade.api.view.IServerExtensionProvider;
import snownee.jade.api.view.ViewGroup;

/** Jade 存量查询入口；公共能力及显示数据仍由服务端真实 provider 提供。 */
@WailaPlugin
public final class LivingItemJadePlugin implements IWailaPlugin {

    @Override
    public void register(IWailaCommonRegistration registration) {
        registration.registerFluidStorage(StorageRequest.SPONGE, BlockEntity.class);
        registration.registerEnergyStorage(StorageRequest.BULB, BlockEntity.class);
    }

    enum StorageRequest implements IServerExtensionProvider<CompoundTag> {
        SPONGE("sponge_storage_request"),
        BULB("bulb_storage_request");

        private final ResourceLocation uid;

        StorageRequest(String path) {
            uid = ResourceLocation.fromNamespaceAndPath("living_item", path);
        }

        @Override
        public ResourceLocation getUid() {
            return uid;
        }

        @Override
        public boolean shouldRequestData(Accessor<?> accessor) {
            return accessor instanceof BlockAccessor block && block.getBlockEntity() != null;
        }

        /** null 继续默认存量扩展；空列表会截断取数链。 */
        @Override
        public List<ViewGroup<CompoundTag>> getGroups(Accessor<?> accessor) {
            return null;
        }
    }
}
