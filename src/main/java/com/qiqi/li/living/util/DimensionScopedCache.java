package com.qiqi.li.living.util;

import java.util.List;

import net.minecraft.resources.ResourceLocation;

/**
 * 「某个维度下的一批条目」客户端缓存 —— 四个 {@code XxxClientCache} 共用的存储。
 *
 * <h3>两个设计点（四条渲染通道共用同一条不变量）</h3>
 * <ul>
 *   <li><b>整体替换</b>：每次收到包就整表替换 —— 不需要处理"移除"逻辑，
 *       实体被取走 / 玩家走远，都会因为"不再出现在包里"而自然消失。</li>
 *   <li><b>维度校验</b>：包里带维度，读取时若与当前维度不符则返回空 ——
 *       否则换维度 / 换存档后旧数据会串台（把上一个世界的活物品渲染出来）。</li>
 * </ul>
 *
 * <p>⚠️ 写入经 {@code IPayloadContext#enqueueWork}（主线程），与渲染同线程；{@code volatile}
 * 与不可变 {@code List} 是<b>防御性</b>保留（若将来有人把写入挪回 Netty 线程，不至于读到半截状态）。</p>
 *
 * <p>抽出来之前，上面两条不变量各有四份拷贝，注释已经开始漂移（一份说"包在 Netty 线程解码"、
 * 另一份已更正为不成立）⇒ 口径统一到本类。四个门面类只保留各自的类型与 javadoc，
 * <b>不再各自持有字段</b>。</p>
 */
public final class DimensionScopedCache<T> {

    private volatile ResourceLocation dimension;
    private volatile List<T> entries = List.of();

    public void update(ResourceLocation dim, List<T> list) {
        dimension = dim;
        entries = List.copyOf(list);
    }

    /**
     * 当前维度下的条目。
     *
     * @param currentDim 客户端当前所在维度
     * @return 条目列表；维度不匹配时返回空列表
     */
    public List<T> get(ResourceLocation currentDim) {
        return currentDim != null && currentDim.equals(dimension) ? entries : List.of();
    }

    /** 清空（客户端退出存档时调用，避免跨存档残留）。 */
    public void clear() {
        dimension = null;
        entries = List.of();
    }
}
