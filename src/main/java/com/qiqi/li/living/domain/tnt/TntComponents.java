package com.qiqi.li.living.domain.tnt;

import com.qiqi.li.living.api.LivingMod;
import com.mojang.serialization.Codec;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/**
 * 活 TNT 领域的组件注册总线（2026-10-08：组件定义从 {@code LivingComponents} 拆回本领域）。
 *
 * <p>本类<b>只放总线</b> —— 具体组件定义在各 {@code XxxData} 类里，与使用它的
 * {@code of()} / {@code set()} 同属一个 {@code <clinit>} ⇒ 从结构上避免循环静态初始化。</p>
 */
public final class TntComponents {

    public static final DeferredRegister<DataComponentType<?>> REG =
        DeferredRegister.create(Registries.DATA_COMPONENT_TYPE, LivingMod.ID);

    /** 活 TNT 数据（引信/爆炸状态）。 */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<LivingTntData>> LIVING_TNT_DATA =
        REG.register("living_tnt_data", () ->
            DataComponentType.<LivingTntData>builder()
                .persistent(LivingTntData.CODEC)
                .networkSynchronized(LivingTntData.STREAM_CODEC)
                .build());

    /** 区块水印附件总线；只在服务端保存，不同步客户端。 */
    public static final DeferredRegister<AttachmentType<?>> ATTACH_REG =
        DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, LivingMod.ID);

    /** 区块已完整处理的 SUPER 爆炸纪元。 */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<Long>> EXPLOSION_EPOCH =
        ATTACH_REG.register("tnt_explosion_epoch", () ->
            AttachmentType.builder(() -> 0L).serialize(Codec.LONG).build());

    private TntComponents() {}
}
