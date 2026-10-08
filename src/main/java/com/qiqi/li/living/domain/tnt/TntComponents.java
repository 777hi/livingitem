package com.qiqi.li.living.domain.tnt;

import com.qiqi.li.living.api.LivingMod;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

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

    private TntComponents() {}
}
