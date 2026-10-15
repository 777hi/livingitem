package com.qiqi.li.living.domain.beacon;

import java.util.List;

import net.minecraft.resources.ResourceLocation;

/**
 * 客户端「哪些箱子里有活信标」缓存（照 {@code LivingToolHostClientCache} 模式）。
 *
 * <p>由 {@link BeaconHostPacket#handle} 更新，供 {@code LivingBeamRenderer}
 * 渲染箱子宿主的垂直光束。</p>
 *
 * <ul>
 *   <li><b>整体替换</b>：每次收到包就整表替换 —— 箱子被破坏 / 信标被取走 /
 *       玩家走远，都会因为"不再出现在包里"而自然消失。</li>
 *   <li><b>维度校验</b>：包里带维度，读取时若与当前维度不符则返回空。</li>
 * </ul>
 *
 * <p>⚠️ 网络包在 Netty 线程解码、渲染在主线程 —— 故字段用 {@code volatile}
 * 且写入不可变 {@code List}。</p>
 */
public final class BeaconHostClientCache {

    private static volatile ResourceLocation dimension;
    private static volatile List<BeaconHostPacket.Entry> entries = List.of();

    private BeaconHostClientCache() {
    }

    public static void update(ResourceLocation dim, List<BeaconHostPacket.Entry> list) {
        dimension = dim;
        entries = List.copyOf(list);
    }

    /** 当前维度下的条目（维度不匹配返回空列表）。 */
    public static List<BeaconHostPacket.Entry> get(ResourceLocation currentDim) {
        return currentDim != null && currentDim.equals(dimension) ? entries : List.of();
    }

    /** 清空（客户端退出存档时调用，避免跨存档残留）。 */
    public static void clear() {
        dimension = null;
        entries = List.of();
    }
}
