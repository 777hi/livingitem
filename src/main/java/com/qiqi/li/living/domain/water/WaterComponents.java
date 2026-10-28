package com.qiqi.li.living.domain.water;

import com.qiqi.li.living.api.LivingMod;

import java.util.Map;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/**
 * 活水领域的组件注册总线（2026-10-08：组件定义从 {@code LivingComponents} 拆回本领域）。
 *
 * <p>本类<b>只放总线</b> —— 具体组件定义在各 {@code XxxData} 类里，与使用它的
 * {@code of()} / {@code set()} 同属一个 {@code <clinit>} ⇒ 从结构上避免循环静态初始化。</p>
 */
public final class WaterComponents {

    public static final DeferredRegister<DataComponentType<?>> REG =
        DeferredRegister.create(Registries.DATA_COMPONENT_TYPE, LivingMod.ID);

    /** 附件总线（容器级流体 / 应力数据落盘用）。 */
    public static final DeferredRegister<AttachmentType<?>> ATTACH_REG =
        DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, LivingMod.ID);

    /** 活水车数据。 */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<LivingWaterWheelData>> LIVING_WATER_WHEEL_DATA =
        REG.register("living_water_wheel_data", () ->
            DataComponentType.<LivingWaterWheelData>builder()
                .persistent(LivingWaterWheelData.CODEC)
                .networkSynchronized(LivingWaterWheelData.STREAM_CODEC)
                .build());

    /** 容器级流体数据（BE 附件，跨存档持久）。 */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<ContainerFluidData>> CONTAINER_FLUID_DATA =
        ATTACH_REG.register("container_fluid_data", () ->
            AttachmentType.builder(() -> ContainerFluidData.EMPTY)
                .serialize(ContainerFluidData.CODEC)
                .build());

    /**
     * 玩家背包 / 末影箱的容器级流体数据（B.5 第三项，2026-10-04）。
     *
     * <p>背包与末影箱<b>没有 BE</b> 可挂 {@link #CONTAINER_FLUID_DATA} ⇒ 落到 <b>Player</b> 上；
     * 一个玩家有背包 + 末影箱<b>两个</b>容器 ⇒ 用「容器键 → 流体数据」映射。默认空 map；
     * {@code copyOnDeath} 默认 false ⇒ 玩家死亡清空（与「背包清空」语义一致）。</p>
     */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<Map<String, ContainerFluidData>>> CONTAINER_FLUID_DATA_PLAYER =
        ATTACH_REG.register("container_fluid_data_player", () ->
            AttachmentType.<Map<String, ContainerFluidData>>builder(() -> Map.of())
                .serialize(ContainerFluidData.KEYED_CODEC)
                .build());

    /**
     * 物品形态的容器级流体数据（2026-10-19）—— <b>方块 ↔ 物品 形态转换的数据载体</b>。
     *
     * <p>问题：容器级流体数据挂在 <b>BE 附件</b> {@link #CONTAINER_FLUID_DATA} 上，
     * 破坏方块时 BE 被移除 ⇒ 附件随之消失；而某些容器（潜影盒等）的<b>物品内容</b>存在别处
     * （按 contents UUID / {@code minecraft:container}）⇒ 保留 ⇒ 真机表现「内容还在，流体没了」。</p>
     *
     * <p>修法（用户 2026-10-19 定案：<b>流体仍是容器属性，不物品化</b>）：属性不变，只换<b>存储介质</b>——
     * 破坏时由 {@code BlockDropsEvent} 把 BE 附件搬进掉落物组件，放置时由
     * {@code BlockEvent.EntityPlaceEvent} 搬回新 BE 附件。数据始终是「容器的」，只是跟着形态走。</p>
     *
     * <p>复用 {@link ContainerFluidData#CODEC}（只序列化派生源；流动表每 tick 由 BFS 重算）。</p>
     */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<ContainerFluidData>> CONTAINER_FLUID_DATA_ITEM =
        REG.register("container_fluid_data_item", () ->
            DataComponentType.<ContainerFluidData>builder()
                .persistent(ContainerFluidData.CODEC)
                .build());

    private WaterComponents() {}
}
