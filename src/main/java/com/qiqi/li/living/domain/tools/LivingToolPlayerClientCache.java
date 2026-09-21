package com.qiqi.li.living.domain.tools;

import java.util.List;
import java.util.UUID;

import com.qiqi.li.network.LivingToolPlayerPacket;

import net.minecraft.resources.ResourceLocation;

/**
 * 客户端「附近玩家背包里的活工具」缓存（联机可见性 · 最小版）。
 *
 * <p>由 {@link LivingToolPlayerPacket#handle} 更新，供
 * {@code LivingToolModelRenderer} 给<b>其它玩家</b>画背后的待机环。</p>
 *
 * <h3>两个设计点（与 {@link LivingToolHostClientCache} 一致）</h3>
 * <ul>
 *   <li><b>整体替换</b>：每次收到包就整表替换 —— 不需要处理"移除"逻辑，
 *       工具被取走 / 玩家走远，都会因为"不再出现在包里"而自然消失。</li>
 *   <li><b>维度校验</b>：包里带维度，读取时若与当前维度不符则返回空。</li>
 * </ul>
 *
 * <p>⚠️ 网络包在 Netty 线程解码、渲染在主线程 —— 故字段用 {@code volatile}
 * 且写入不可变 {@code List}。</p>
 */
public final class LivingToolPlayerClientCache {

    private static volatile ResourceLocation dimension;
    private static volatile List<LivingToolPlayerPacket.Entry> entries = List.of();

    private LivingToolPlayerClientCache() {
    }

    public static void update(ResourceLocation dim, List<LivingToolPlayerPacket.Entry> list) {
        dimension = dim;
        entries = List.copyOf(list);
    }

    /**
     * 当前维度下，「其它玩家 → 他们的活工具」条目。
     *
     * @param currentDim 客户端当前所在维度
     * @return 条目列表；维度不匹配时返回空列表
     */
    public static List<LivingToolPlayerPacket.Entry> get(ResourceLocation currentDim) {
        return currentDim != null && currentDim.equals(dimension) ? entries : List.of();
    }

    /** 另取一份（渲染遍历时避免和 volatile 读交错）。 */
    public static List<LivingToolPlayerPacket.Entry> snapshot() {
        return entries;
    }

    /** 按玩家查找；没有则返回 {@code null}。 */
    public static List<net.minecraft.world.item.ItemStack> toolsOf(UUID playerId) {
        for (LivingToolPlayerPacket.Entry entry : entries) {
            if (entry.playerId().equals(playerId)) {
                return entry.tools();
            }
        }
        return null;
    }

    /** 清空（客户端退出存档时调用，避免跨存档残留）。 */
    public static void clear() {
        dimension = null;
        entries = List.of();
    }
}
