package com.qiqi.li.living.domain.brewing;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import com.mojang.serialization.Codec;
import com.qiqi.li.living.model.Pos2D;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

/**
 * 活酿造台的槽位方向数据。
 *
 * <p>记录 5 个功能槽（3 个瓶子 + 燃料 + 材料）相对酿造台本体的方向。默认布局
 * （用户 2026-10-10 拍板）：</p>
 *
 * <pre>
 * 瓶1 瓶2 瓶3        ↖ ↑ ↗
 * 燃料  台  材料  =   ← ·  →
 * </pre>
 *
 * <p>「槽位名 → 方向」的映射天然支持<b>任意槽位数</b>，本类只负责把默认布局声明出来。
 * 与活熔炉的 {@code DirectionSlotsData} 的差别：酿造台没有「当前激活槽」概念
 * （不需要 {@code activeSlotIndex}），且槽位语义完全不同 ⇒ <b>独立成类、不跨域复用</b>
 * （域自包含是本项目 A1 之后的既定结构）。</p>
 */
public record BrewingDirectionData(Map<String, Pos2D> directions) {

    public static final BrewingDirectionData EMPTY = new BrewingDirectionData(Map.of());

    /** 默认布局的槽位顺序（= 方向输入顺序）：上排 3 瓶 → 燃料 → 材料。 */
    private static final LinkedHashMap<String, Pos2D> BREWING_ORDER = new LinkedHashMap<>() {{
        put("bottle1", Pos2D.UP_LEFT);
        put("bottle2", Pos2D.UP);
        put("bottle3", Pos2D.UP_RIGHT);
        put("fuel", Pos2D.LEFT);
        put("material", Pos2D.RIGHT);
    }};

    public static final BrewingDirectionData DEFAULT_BREWING = new BrewingDirectionData(BREWING_ORDER);

    public static final Codec<BrewingDirectionData> CODEC =
        Codec.unboundedMap(Codec.STRING, Pos2D.CODEC)
            .xmap(BrewingDirectionData::new, BrewingDirectionData::directions);

    /** 复用 {@link Pos2D#MAP_STREAM_CODEC}（「槽位名 → 方向」正是它存在的理由）。 */
    public static final StreamCodec<RegistryFriendlyByteBuf, BrewingDirectionData> STREAM_CODEC =
        Pos2D.MAP_STREAM_CODEC.map(BrewingDirectionData::new, BrewingDirectionData::directions);

    public BrewingDirectionData {
        directions = Collections.unmodifiableMap(new LinkedHashMap<>(directions));
    }

    public BrewingDirectionData withDirection(String slotName, Pos2D direction) {
        Map<String, Pos2D> newMap = new LinkedHashMap<>(directions);
        newMap.put(slotName, direction);
        return new BrewingDirectionData(newMap);
    }

    /** 未配置的槽位返回 {@link Pos2D#NONE}（显示为「·」）。 */
    public Pos2D getDirection(String slotName) {
        return directions.getOrDefault(slotName, Pos2D.NONE);
    }
}
