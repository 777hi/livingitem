package com.qiqi.li.living.domain.brewing;

import java.util.Optional;

import com.qiqi.li.living.runtime.RuntimeSegmentType;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.Item;

/**
 * 活酿造台的运行时数据片段（档 2）—— 承载<b>瞬态</b>批次状态。
 *
 * <p>瞬态数据<b>不进物品组件</b> ⇒ 不影响堆叠（与活熔炉同款，设计稿 §4.2）。两项：</p>
 * <ul>
 *   <li>{@code brewTime} —— 批次倒计时（400 → 0；按 {@code step = count} 递减，D11）。
 *       需要同步到客户端（tooltip 显示实时进度）。</li>
 *   <li>{@code batchIngredient} —— <b>本批次启动时</b>材料的 {@code Item}，
 *       仅用于「中途换材料」检测（对齐原版 BE 字段 {@code ingredient}）。
 *       ⚠️ 只比 <b>Item</b>、不比数量 / 内容（D8）。</li>
 * </ul>
 *
 * <p>⚠️ {@link #id()} 进网络包，<b>发布后不可改名</b>。</p>
 */
public final class BrewingStandSegment implements RuntimeSegmentType<BrewingStandSegment.BrewingRuntime> {

    public static final BrewingStandSegment INSTANCE = new BrewingStandSegment();

    private BrewingStandSegment() {}

    @Override
    public String id() {
        return "brewing";
    }

    @Override
    public StreamCodec<? super RegistryFriendlyByteBuf, BrewingRuntime> codec() {
        return BrewingRuntime.STREAM_CODEC;
    }

    /** 活酿造台运行时：批次倒计时 + 本批次材料的 {@code Item}（空 = 当前无批次）。 */
    public record BrewingRuntime(int brewTime, Optional<Item> batchIngredient) {

        public static final BrewingRuntime EMPTY = new BrewingRuntime(0, Optional.empty());

        public static final StreamCodec<RegistryFriendlyByteBuf, BrewingRuntime> STREAM_CODEC =
            StreamCodec.composite(
                ByteBufCodecs.VAR_INT, BrewingRuntime::brewTime,
                ByteBufCodecs.optional(ByteBufCodecs.<Item>registry(Registries.ITEM)),
                BrewingRuntime::batchIngredient,
                BrewingRuntime::new
            );

        /** 是否处于酿造中（倒计时 > 0）。 */
        public boolean isBrewing() {
            return brewTime > 0;
        }
    }
}
