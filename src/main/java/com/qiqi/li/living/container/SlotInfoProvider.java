package com.qiqi.li.living.container;

import net.minecraft.world.item.ItemStack;

/**
 * 槽位信息提供者 —— 提供槽位能力查询。
 *
 * <p>扩展 {@link LivingContainer}，增加槽位限制、物品验证、模拟插入等能力。
 * 适用于需要精确控制物品放置的功能（如熔炉、漏斗）。</p>
 *
 * <p>典型使用者：
 * <ul>
 *   <li>{@link com.qiqi.li.living.domain.furnace.LivingFurnaceFunction} — 检查输出槽空间</li>
 *   <li>{@link com.qiqi.li.living.domain.hopper.LivingHopperFunction} — 模拟插入目标槽位</li>
 * </ul>
 * </p>
 */
public interface SlotInfoProvider extends LivingContainer {

    /**
     * 获取指定槽位的最大堆叠上限。
     *
     * <p>对于 IItemHandler 容器（抽屉一类的第三方容器），返回 handler.getSlotLimit(slot)，
     * 可能远超 64；对于传统 Container，回退到 {@link #getMaxStackSize()}。</p>
     *
     * @param slot 槽位索引
     * @return 该槽位的最大堆叠数
     */
    default int getSlotLimit(int slot) {
        return getMaxStackSize();
    }

    /**
     * 检查物品是否能放入指定槽位。
     *
     * <p>对于玩家盔甲槽位，非盔甲物品会返回 false，防止物品消失。</p>
     *
     * @param slot 槽位索引
     * @param stack 要检查的物品
     * @return 是否允许放入
     */
    default boolean isItemValid(int slot, ItemStack stack) {
        return true;
    }

    /**
     * 模拟向指定槽位插入物品，返回实际可插入的数量。
     *
     * <p>默认实现是「近似回答」：按 {@code min(槽位上限, 物品上限)} 与槽内已有同类物品
     * 算空间，异类占据返回 0，不含准入。有 handler 后端的实现
     * （SimpleContainerContext / EntityContainerContext）覆写为
     * {@code handler.insertItem(slot, stack, true)} —— 那才是容器自己的回答
     * （含准入 / 升级 / 超堆叠口径，I-C14 ① 的权威）。</p>
     *
     * @param slot 槽位索引
     * @param stack 要插入的物品
     * @return 实际可插入的数量
     */
    default int simulateInsertItem(int slot, ItemStack stack) {
        ItemStack existing = getItem(slot);
        int maxStack = Math.min(getSlotLimit(slot), stack.getMaxStackSize());
        if (existing.isEmpty()) {
            return Math.min(stack.getCount(), maxStack);
        }
        if (ItemStack.isSameItemSameComponents(existing, stack)) {
            return Math.min(stack.getCount(), Math.max(0, maxStack - existing.getCount()));
        }
        return 0;
    }

    /**
     * 模拟向指定槽位插入「任意多」的 prototype，返回该槽对它的<b>总剩余容量</b>。
     *
     * <p>给批量变换算「还能产出多少」用（熔炉产出批量 / 耕地产出）：probe 取
     * {@code slotLimit} 量级，大到足以测出全部剩余空间；容量权威仍是
     * {@link #simulateInsertItem}（含准入、物品上限与超堆叠口径）。</p>
     *
     * @return 剩余可插入数量（0 = 放不下 / 槽位上限非法）
     */
    default int insertableSpace(int slot, ItemStack prototype) {
        int slotLimit = getSlotLimit(slot);
        if (slotLimit <= 0) return 0;
        return Math.max(0, simulateInsertItem(slot, prototype.copyWithCount(slotLimit)));
    }

    /**
     * 模拟从槽位<b>取出</b>：这个槽允不允许被拿走、最多能拿多少。<b>不改任何状态</b>。
     *
     * <p>⭐ 这是 `I-C12` 的<b>第三维「抽取权限」</b> —— 它与「容量」「准入」是三件不同的事：
     * 一个「只进不出」的槽（输出槽 / 过滤槽）容量充足、准入通过，但<b>不允许抽取</b>。
     * 只查前两维就搬 ⇒ 把不该动的货搬走。</p>
     *
     * <p>默认实现退化为「槽里有多少就能拿多少」—— 对没有抽取限制的实现是正确的；
     * 有过滤 / 只进不出的实现（{@code IItemHandler}）必须覆写，走
     * {@code handler.extractItem(slot, n, true)}。</p>
     *
     * @param slot   槽位索引
     * @param amount 期望取出的最大数量
     * @return 实际可取出数量（0 = 不允许抽取）
     */
    default int simulateExtractItem(int slot, int amount) {
        return Math.min(amount, getItem(slot).getCount());
    }

    /**
     * 这个槽是否支持<b>精确写入</b>（把指定内容写到指定槽，不被整库重定向）。
     *
     * <p>用于 {@code moveStack} 的 {@code Unsupported} 判别：后端不支持精确写入时，
     * 移动<b>必然失败</b>，报 {@code Unsupported}（重试永远不会成功）而不是
     * {@code BackendViolation}（那是「说能写却没写对」）。两者混在一起会让排查走错方向。</p>
     *
     * <p>默认 {@code true}（抽象层默认按精确写入对待）；{@code SimpleContainerContext}
     * 覆写为「handler 是否实现 {@code IItemHandlerModifiable}」。</p>
     */
    default boolean supportsExactWrite(int slot) {
        return true;
    }

    /**
     * 获取容器的列数（GUI 宽度）—— **没有默认值，必须由实现者回答**。
     *
     * <p>用于槽位解析时计算行列位置（邻居判定 / 流体 / 应力 / 红石 / 信标列扫描 /
     * 漏斗传输网格全都要它，且都拿它当除数 ⇒ 见不变量 I-C11）。</p>
     *
     * <p>⭐ <b>为什么删掉原来的 {@code default 9}</b>（2026-10-25，S1）：
     * 那个默认值**从来没有任何实现者用到**（三个真实 ctx 全部覆写了它），
     * 却是一颗雷：9 是“看着对”的值 ⇒ 将来哪个新容器上下文忘了覆写，
     * 不会编译报错、不会运行时报错，只会**静默地按 9 列解析**
     * —— 而列数错了的表现是“搬错格子但仍在搬”这类隐形错，极难查。
     * 改成抽象方法后，漏实现是<b>编译期错误</b>。</p>
     *
     * <p>生产容器的布局选择见 {@link ContainerLayoutResolver}：实例来源、方块规则与学习、
     * 内建约定、启发式；来源与列数由同一次解析产生。</p>
     *
     * @return 容器的列数（≥ 1，**不得为 0** —— 它是除数）
     */
    int getWidth();

    /** 未声明来源的实现仍保留其列数，但不得被 UI 当作已核验来源。 */
    default ContainerLayout getLayout() {
        return new ContainerLayout(getWidth(), ContainerLayout.Source.UNKNOWN);
    }
}