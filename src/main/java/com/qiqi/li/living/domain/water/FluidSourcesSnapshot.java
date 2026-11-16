package com.qiqi.li.living.domain.water;

import java.util.ArrayList;
import java.util.List;

import com.mojang.serialization.Codec;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

/**
 * 派生源快照 —— 物品形态容器流体数据的<b>不可变</b>组件值（Q6，2026-11-16）。
 *
 * <p>与电力侧 {@code PhaseSnapshot} 同构：record 天然带 equals/hashCode，NeoForge
 * 开发期组件校验（{@code CommonHooks.validateComponent}）对 record 直接放行，
 * 生产侧 {@code ItemStack.matches} 也恢复按值判等。</p>
 *
 * <p><b>只存派生源</b>：流动表每 tick 由 BFS 重算，落盘无正确性价值（与
 * {@link ContainerFluidData#CODEC} 同一口径）。还原运行时对象走
 * {@link ContainerFluidData#fromSnapshot}——每次放置各建一份，多个容器永不共享可变状态。</p>
 *
 * <p>NBT 形状与旧 {@code ContainerFluidData.CODEC} 逐字节一致（共用
 * {@link FluidSourceEntry#CODEC}，字段名 {@code slot}/{@code fluid} 不变）⇒ 已有物品数据照常解析，零迁移。</p>
 *
 * @param sources 派生源列表（槽位 + 流体类型 id），不可变
 */
public record FluidSourcesSnapshot(List<FluidSourceEntry> sources) {

    public static final FluidSourcesSnapshot EMPTY = new FluidSourcesSnapshot(List.of());

    /** 只存派生源，与 {@link ContainerFluidData#CODEC} 同形（字段名锁定在 FluidSourceEntry）。 */
    public static final Codec<FluidSourcesSnapshot> CODEC =
        FluidSourceEntry.CODEC.listOf().xmap(FluidSourcesSnapshot::new, FluidSourcesSnapshot::sources);

    public static final StreamCodec<RegistryFriendlyByteBuf, FluidSourcesSnapshot> STREAM_CODEC =
        StreamCodec.ofMember(FluidSourcesSnapshot::write, FluidSourcesSnapshot::read);

    public boolean isEmpty() {
        return sources.isEmpty();
    }

    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeVarInt(sources.size());
        for (FluidSourceEntry entry : sources) {
            buf.writeVarInt(entry.slot());
            buf.writeUtf(entry.fluidId());
        }
    }

    private static FluidSourcesSnapshot read(RegistryFriendlyByteBuf buf) {
        int size = buf.readVarInt();
        List<FluidSourceEntry> out = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            out.add(new FluidSourceEntry(buf.readVarInt(), buf.readUtf()));
        }
        return new FluidSourcesSnapshot(List.copyOf(out));
    }
}
