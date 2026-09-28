package com.qiqi.li.living.domain.runtime;

import com.qiqi.li.living.domain.hopper.ResolvedSlotData;
import com.qiqi.li.living.domain.furnace.TransformData;
import com.qiqi.li.living.domain.power.LivingWaxedGeneratorData;

import javax.annotation.Nullable;

/**
 * 活物品的运行时数据快照 —— 仅包含展示/瞬态字段，不参与物品堆叠判定。
 *
 * <p>此类数据存在服务端 {@link ContainerRuntimeCache} 中，通过
 * {@link com.qiqi.li.network.LivingItemSyncPacket} 同步到客户端
 * {@link LivingItemClientCache}，供 Tooltip 渲染使用。</p>
 *
 * <p>数据按物品类型分为三组，每组均为可选：</p>
 * <ul>
 *   <li><b>发电机</b>：全部 13 个遥测字段（{@link LivingWaxedGeneratorData}）</li>
 *   <li><b>漏斗</b>：{@code cooldown} + {@code slotInfo}</li>
 *   <li><b>熔炉</b>：{@code progress} + {@code total} + {@code burnTime} + {@code transform}</li>
 * </ul>
 */
public record LivingItemRuntimeData(
    @Nullable LivingWaxedGeneratorData generatorTelemetry,
    @Nullable HopperRuntime hopper,
    @Nullable FurnaceRuntime furnace
) {
    public static final LivingItemRuntimeData EMPTY = new LivingItemRuntimeData(null, null, null);

    public static LivingItemRuntimeData forGenerator(LivingWaxedGeneratorData telemetry) {
        return new LivingItemRuntimeData(telemetry, null, null);
    }

    public static LivingItemRuntimeData forHopper(int cooldown, @Nullable ResolvedSlotData slotInfo) {
        return new LivingItemRuntimeData(null, new HopperRuntime(cooldown, slotInfo), null);
    }

    public static LivingItemRuntimeData forFurnace(int progress, int total, int burnTime,
                                                    @Nullable TransformData transform) {
        return new LivingItemRuntimeData(null, null,
            new FurnaceRuntime(progress, total, burnTime, transform));
    }

    public boolean isGenerator() { return generatorTelemetry != null; }
    public boolean isHopper()   { return hopper != null; }
    public boolean isFurnace()  { return furnace != null; }

    // ─── 子记录 ───────────────────────────────────────────────

    /** 活漏斗运行时：仅冷却与槽位解析信息。 */
    public record HopperRuntime(int cooldown, @Nullable ResolvedSlotData slotInfo) {
        public static final HopperRuntime EMPTY = new HopperRuntime(0, null);
    }

    /** 活熔炉运行时：烧制进度、燃料、配方缓存。 */
    public record FurnaceRuntime(int progress, int total, int burnTime,
                                  @Nullable TransformData transform) {
        public static final FurnaceRuntime EMPTY = new FurnaceRuntime(0, 0, 0, null);
    }
}