package com.qiqi.li.living.domain.water;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * 一条派生源的序列化形态（槽位 + 流体类型注册 id）。
 *
 * <p>容器流体落盘（BE 附件 / 玩家附件 / 物品组件）<b>共用同一份条目定义</b> ——
 * 三条轨道的 NBT 形状由它锁定，改动字段名 = 三处轨道同时变形。</p>
 *
 * @param slot    容器内槽位号
 * @param fluidId 流体类型注册 id（字符串形态，未知 id 在还原时安全丢弃）
 */
public record FluidSourceEntry(int slot, String fluidId) {

    public static final Codec<FluidSourceEntry> CODEC = RecordCodecBuilder.create(i -> i.group(
        Codec.INT.fieldOf("slot").forGetter(FluidSourceEntry::slot),
        Codec.STRING.fieldOf("fluid").forGetter(FluidSourceEntry::fluidId)
    ).apply(i, FluidSourceEntry::new));
}
