package com.qiqi.li.living.domain.power;

import com.qiqi.li.living.api.LivingMod;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/**
 * 红电（电力）领域的组件注册总线（2026-10-08：组件定义从 {@code LivingComponents} 拆回本领域）。
 *
 * <p>本类<b>只放总线</b> —— 具体组件定义在各 {@code XxxData} 类里，与使用它的
 * {@code of()} / {@code set()} 同属一个 {@code <clinit>} ⇒ 从结构上避免循环静态初始化。</p>
 */
public final class PowerComponents {

    public static final DeferredRegister<DataComponentType<?>> REG =
        DeferredRegister.create(Registries.DATA_COMPONENT_TYPE, LivingMod.ID);

    /** 附件总线（相位快照落盘用）。 */
    public static final DeferredRegister<AttachmentType<?>> ATTACH_REG =
        DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, LivingMod.ID);

    /** 活涂蜡雕纹铜块数据。 */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<LivingWaxedChiseledData>> LIVING_WAXED_CHISELED_DATA =
        REG.register("living_waxed_chiseled_data", () ->
            DataComponentType.<LivingWaxedChiseledData>builder()
                .persistent(LivingWaxedChiseledData.CODEC)
                .networkSynchronized(LivingWaxedChiseledData.STREAM_CODEC)
                .build());

    /** 活涂蜡发电机数据。 */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<LivingWaxedGeneratorData>> LIVING_GENERATOR_DATA =
        REG.register("living_generator_data", () ->
            DataComponentType.<LivingWaxedGeneratorData>builder()
                .persistent(LivingWaxedGeneratorData.CODEC)
                .networkSynchronized(LivingWaxedGeneratorData.STREAM_CODEC)
                .build());

    /** 活涂蜡铜灯数据。 */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<LivingWaxedBulbData>> LIVING_WAXED_BULB_DATA =
        REG.register("living_waxed_bulb_data", () ->
            DataComponentType.<LivingWaxedBulbData>builder()
                .persistent(LivingWaxedBulbData.CODEC)
                .networkSynchronized(LivingWaxedBulbData.STREAM_CODEC)
                .build());

    /** 容器相位快照（BE 附件，跨存档持久 —— 退出重进后锁相状态续接）。 */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<PhaseSnapshot>> CONTAINER_PHASE_SNAPSHOT =
        ATTACH_REG.register("container_phase_snapshot", () ->
            AttachmentType.builder(() -> PhaseSnapshot.EMPTY)
                .serialize(PhaseSnapshot.CODEC)
                .build());

    private PowerComponents() {}
}
