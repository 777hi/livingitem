package com.qiqi.li.living.transfer;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

/**
 * 槽位交互 —— 传输过程中「目标槽现有物品 + 货物」这对组合的替代语义。
 *
 * <p>活漏斗的通用语义是「把货物插进目标槽」，但对某些特殊目标物品来说，
 * 「插入」没有意义（活耕地是非存储容器，塞不进东西），真正想要的是另一种动作：
 * 骨粉遇到活耕地 = <b>施肥</b>。本接口把这类「货物 × 目标槽」的替代方程
 * 抽象成可注册条目，使新增交互不再需要修改任何传输代码。</p>
 *
 * <h3>为什么需要它</h3>
 * 此前施肥方程硬编码在<b>三处</b>调用点（容器内 {@code TransferPipeline}、
 * 跨容器推送 {@code tryPushToNeighbor}、跨容器拉取 {@code pullFromNeighbor}），
 * 漏掉任何一处就会出现「只在一个方向生效」的 bug（2026-09-15 实测：拉取方向
 * 漏分支，骨粉无法施肥）。注册式分发后，调用点与具体交互解耦——
 * <b>新增交互 = 实现本接口 + 注册一行，零调用点改动</b>。</p>
 *
 * <h3>实现约定（三条，缺一即破坏协议）</h3>
 * <ol>
 *   <li><b>模拟优先</b>：{@code cargo} 是<b>模拟提取的拷贝</b>，实现方可自由改它的
 *       数量（改的是拷贝，不影响真实槽位）；只有 {@link #interact} 返回 true 时，
 *       分发器才会真扣 {@link #consumeAmount()} 个货物。</li>
 *   <li><b>equals 零空转</b>：交互没有产生实际变化时必须返回 false——不扣货、
 *       不设漏斗冷却（对着已冻结的成熟耕地反复右键不烧骨粉即此语义）。</li>
 *   <li><b>只动 target</b>：就地修改 {@code target} 的 DataComponent / 数量，
 *       不要做其它副作用（物品入槽、发网络包等由调用方负责）。</li>
 * </ol>
 *
 * <p>注册入口见 {@link SlotInteractions#register}；内置条目在
 * {@code SlotInteractions} 静态块中注册（参考 {@link SlotAccessorFactory}
 * 的 Provider 注册先例）。</p>
 */
public interface SlotInteraction {

    /**
     * 本交互是否接管这对组合（<b>必须是纯谓词</b>，无副作用——拉取方向会对
     * 邻居容器每个槽位调用它做筛选）。
     *
     * @param cargo  拟传输的货物（真实槽位物品，非拷贝）
     * @param target 目标槽现有物品
     */
    boolean matches(ItemStack cargo, ItemStack target);

    /**
     * 执行交互。
     *
     * @param cargo  模拟提取的货物拷贝（可自由改数量）
     * @param target 目标槽物品（就地修改；是 BE 容器的实时引用，改完即刻生效）
     * @param level  服务端世界（交互需要随机数/战利品表时使用）
     * @return true = 交互生效（调用方真扣 {@link #consumeAmount()} 个货物并结束本轮传输）；
     *         false = 无实际变化（不扣货、不设冷却，调用方继续尝试其它交互/通用插入）
     */
    boolean interact(ItemStack cargo, ItemStack target, ServerLevel level);

    /**
     * 交互生效时消耗的货物数量，默认 1（施肥固定 1 粉/次，不随漏斗堆叠放大——
     * 一次生长 tick 只值一粉）。
     */
    default int consumeAmount() {
        return 1;
    }
}
