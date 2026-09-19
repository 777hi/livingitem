package com.qiqi.li.living.domain.tools;

import java.util.List;

import com.qiqi.li.network.LivingToolHostPacket;

import net.minecraft.resources.ResourceLocation;

/**
 * 客户端「哪些容器里有活工具」缓存（{@code K2}）。
 *
 * <p>由 {@link LivingToolHostPacket#handle} 更新，供
 * {@code LivingToolRayRenderer}（射线可视化）与未来的 {@code K} 组悬浮渲染读取。</p>
 *
 * <h3>两个设计点</h3>
 * <ul>
 *   <li><b>整体替换</b>：每次收到包就整表替换 —— 不需要处理"移除"逻辑，
 *       容器被破坏 / 活工具被取走 / 玩家走远，都会因为"不再出现在包里"而自然消失。</li>
 *   <li><b>维度校验</b>：包里带维度，读取时若与当前维度不符则返回空。
 *       否则换维度 / 换存档后旧数据会串台（渲染出上一个世界的活工具）。</li>
 * </ul>
 *
 * <p>⚠️ 网络包在 Netty 线程解码、渲染在主线程 —— 故字段用 {@code volatile}
 * 且写入不可变 {@code List}。</p>
 */
public final class LivingToolHostClientCache {

    private static volatile ResourceLocation dimension;
    private static volatile List<LivingToolHostPacket.Entry> entries = List.of();

    private LivingToolHostClientCache() {
    }

    public static void update(ResourceLocation dim, List<LivingToolHostPacket.Entry> list) {
        dimension = dim;
        entries = List.copyOf(list);
        com.qiqi.li.LivingItem.LOGGER.info("[K2] 收到 {} 个宿主（维度 {}）", list.size(), dim);
    }

    /**
     * 当前维度下的条目。
     *
     * @param currentDim 客户端当前所在维度
     * @return 条目列表；维度不匹配时返回空列表
     */
    public static List<LivingToolHostPacket.Entry> get(ResourceLocation currentDim) {
        return currentDim != null && currentDim.equals(dimension) ? entries : List.of();
    }

    /** 清空（客户端退出存档时调用，避免跨存档残留）。 */
    public static void clear() {
        dimension = null;
        entries = List.of();
    }
}
