package com.qiqi.li.living.domain.portal;

import java.util.Map;

import com.qiqi.li.living.api.LivingMod;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/** 门域持久数据注册总线；总线绑定与 Mover 接线由领域注册入口负责。 */
public final class PortalComponents {
    public static final DeferredRegister<DataComponentType<?>> REG =
        DeferredRegister.create(Registries.DATA_COMPONENT_TYPE, LivingMod.ID);

    public static final DeferredRegister<AttachmentType<?>> ATTACH_REG =
        DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, LivingMod.ID);

    public static final DeferredHolder<AttachmentType<?>, AttachmentType<ContainerPortalData>>
            CONTAINER_PORTAL_DATA = ATTACH_REG.register("container_portal_data", () ->
                AttachmentType.builder(() -> ContainerPortalData.EMPTY)
                    .serialize(ContainerPortalData.CODEC)
                    .build());

    /** 玩家库存与末影箱分别按真实容器键保存；死亡不复制附件。 */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<Map<String, ContainerPortalData>>>
            CONTAINER_PORTAL_DATA_PLAYER = ATTACH_REG.register("container_portal_data_player", () ->
                AttachmentType.<Map<String, ContainerPortalData>>builder(Map::of)
                    .serialize(ContainerPortalData.KEYED_CODEC)
                    .build());

    /** 无容器 UUID 时的跨形态交接快照；不是门面占槽物品。 */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<ContainerPortalData>>
            CONTAINER_PORTAL_DATA_ITEM = REG.register("container_portal_data_item", () ->
                DataComponentType.<ContainerPortalData>builder()
                    .persistent(ContainerPortalData.CODEC)
                    .build());

    private PortalComponents() {}
}
