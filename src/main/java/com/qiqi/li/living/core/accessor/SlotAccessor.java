package com.qiqi.li.living.core.accessor;

import net.minecraft.world.item.ItemStack;

/**
 * 槽位访问器 —— 统一抽象不同存储后端的读写操作。
 *
 * <p>将传输引擎与具体存储类型解耦。传输引擎只调用 {@link #extract(int, ItemStack)}
 * 和 {@link #insert(ItemStack)}，不关心槽位背后是普通物品、活箱子、活末影箱还是跨容器。</p>
 *
 * <h3>设计原则</h3>
 * <ul>
 *   <li>每个 Accessor 实例对应一个具体的槽位（可能跨容器）</li>
 *   <li>extract/insert 内部自行处理类型差异（直接槽位操作 / LivingChestFunction API / 跨容器代理）</li>
 *   <li>rollback 用于插入失败时退回物品</li>
 *   <li>isEmpty/isFull 用于前置判断，避免无效的 extract/insert 调用</li>
 * </ul>
 *
 * <h3>新增存储类型</h3>
 * <p>只需新增一个 Accessor 实现类，无需修改传输引擎代码。</p>
 */
public interface SlotAccessor {

    /**
     * 从槽位提取物品。
     *
     * @param amount 最大提取数量
     * @param filterType 过滤类型（null 表示不限类型），用于活箱子"预查+精确提取"策略
     * @return 提取的物品（可能为 EMPTY）
     */
    ItemStack extract(int amount, ItemStack filterType);

    /**
     * 向槽位插入物品。
     *
     * <p>调用后 {@code stack} 的 count 会被修改为剩余未插入的数量。</p>
     *
     * @param stack 要插入的物品（会被修改）
     * @return 实际插入的数量
     */
    int insert(ItemStack stack);

    /**
     * 回滚：将物品退回源槽位。
     *
     * <p>当 extract 成功但 insert 失败时调用，确保物品不丢失。</p>
     *
     * @param stack 要退回的物品
     */
    void rollback(ItemStack stack);

    boolean isEmpty();

    boolean isFull();

    /**
     * 标记此槽位已被传输到达（用于级联防护）。
     */
    void markTransferred();

    /**
     * 同步此槽位到客户端。
     */
    void sync();

    /**
     * 解包装饰器，获取底层 SlotAccessor。
     *
     * <p>用于需要判断具体类型的场景（如 {@code instanceof LivingEnderChestAccessor}）。
     * 非装饰器实现返回 this，装饰器实现返回被装饰的对象。</p>
     *
     * @return 底层 SlotAccessor
     */
    default SlotAccessor unwrap() {
        return this;
    }

    /**
     * 统一的传输执行逻辑：extract → insert → rollback。
     *
     * <p>适用于所有 SlotAccessor 组合（普通×普通、普通×活箱子、邻居×邻居等）。</p>
     *
     * @param source 源槽位访问器
     * @param target 目标槽位访问器
     * @param amount 最大传输数量
     * @return 是否成功传输
     */
    static boolean transfer(SlotAccessor source, SlotAccessor target, int amount) {
        if (source.isEmpty() || target.isFull()) {
            return false;
        }

        net.minecraft.world.item.ItemStack extracted = source.extract(amount, null);
        if (extracted.isEmpty()) {
            return false;
        }

        int inserted = target.insert(extracted);
        if (inserted <= 0) {
            source.rollback(extracted);
            return false;
        }

        if (!extracted.isEmpty()) {
            source.rollback(extracted);
        }

        target.markTransferred();
        source.sync();
        target.sync();
        return true;
    }
}