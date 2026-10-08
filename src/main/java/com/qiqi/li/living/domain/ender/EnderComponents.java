package com.qiqi.li.living.domain.ender;

import com.qiqi.li.living.api.LivingMod;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 末影领域的组件注册站（2026-10-08：从 {@code LivingComponents} 拆回本领域）。
 *
 * <p>⚠️ <b>为什么组件定义放这里，而不是放 {@code LivingEnderChestData}</b>：
 * {@code DeferredRegister.register(...)} 必须在 {@code RegisterEvent} <b>之前</b>调用。
 * 本类的 {@code <clinit>} 由 {@code LivingItem} 构造阶段的 {@code EnderComponents.REG.register(bus)}
 * 触发 ⇒ 时机正确；若定义在 Data 类里，其 {@code <clinit>} 由首次使用触发（可能在 RegisterEvent 之后）
 * ⇒ 抛 {@code Cannot register new entries after RegisterEvent has been fired}。</p>
 */
public final class EnderComponents {

    public static final DeferredRegister<DataComponentType<?>> REG =
        DeferredRegister.create(Registries.DATA_COMPONENT_TYPE, LivingMod.ID);

    /** 活末影箱数据组件。 */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<LivingEnderChestData>> LIVING_ENDER_CHEST_DATA =
        REG.register("living_ender_chest_data", () ->
            DataComponentType.<LivingEnderChestData>builder()
                .persistent(LivingEnderChestData.CODEC)
                .networkSynchronized(LivingEnderChestData.STREAM_CODEC)
                .build());

    private EnderComponents() {}
}
