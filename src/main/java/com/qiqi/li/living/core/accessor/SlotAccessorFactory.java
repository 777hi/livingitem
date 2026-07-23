package com.qiqi.li.living.core.accessor;

import java.util.Set;
import java.util.UUID;

import net.neoforged.neoforge.items.IItemHandler;

import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import com.qiqi.li.living.LivingItemManager;
import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.core.ComponentState;
import com.qiqi.li.living.function.LivingChestFunction;
import com.qiqi.li.living.function.LivingEnderChestFunction;
import org.slf4j.Logger;

/**
 * SlotAccessor 工厂 —— 根据槽位内容创建对应的访问器。
 *
 * <p>判断逻辑：</p>
 * <ol>
 *   <li>活箱子 → {@link LivingChestAccessor}</li>
 *   <li>活末影箱 → {@link LivingEnderChestAccessor}</li>
 *   <li>其他活物品 → 返回 null（隔离，不允许传输）</li>
 *   <li>普通物品 → {@link PlainSlotAccessor}</li>
 * </ol>
 *
 * <p>新增存储类型时，只需在此方法中添加一行判断。</p>
 */
public final class SlotAccessorFactory {

    private static final Logger LOGGER = LogUtils.getLogger();

    private SlotAccessorFactory() {}

    /**
     * 为指定槽位创建访问器。
     *
     * @param server Minecraft 服务器实例
     * @param containerCtx 容器上下文
     * @param slot 槽位索引
     * @param filterState 过滤组件状态（用于活漏斗黑白名单过滤，可为 null）
     * @param transferredTargetSlots 级联防护集合
     * @return 访问器实例，如果槽位包含不应被传输的活物品则返回 null
     */
    public static SlotAccessor create(MinecraftServer server, ContainerContext containerCtx, int slot,
                                       ComponentState filterState, Set<Integer> transferredTargetSlots) {
        ItemStack stack = containerCtx.getItem(slot);

        SlotAccessor raw;

        if (LivingChestFunction.isLivingChest(stack)) {
            int capacity = LivingChestFunction.getCapacity(containerCtx);
            raw = new LivingChestAccessor(server, containerCtx, slot, stack, capacity,
                transferredTargetSlots);
        } else if (LivingEnderChestFunction.isLivingEnderChest(stack)) {
            int ch = stack.getCount();
            UUID boundUuid = LivingEnderChestFunction.getBoundPlayerUuid(stack);
            LOGGER.debug("SlotAccessorFactory: creating LivingEnderChestAccessor, channel={}, slot={}, direct={}", ch, slot, boundUuid != null);
            if (boundUuid != null) {
                raw = new LivingEnderChestAccessor(server, ch,
                    transferredTargetSlots, boundUuid);
            } else {
                raw = new LivingEnderChestAccessor(server, ch, filterState,
                    transferredTargetSlots);
            }
        } else if (LivingItemManager.isLivingItem(stack)) {
            return null;
        } else {
            raw = new PlainSlotAccessor(containerCtx, slot, transferredTargetSlots);
        }

        // 自动包装过滤装饰器
        return new FilteredSlotAccessor(raw, filterState);
    }

    /**
     * 为邻居容器的指定槽位创建访问器。
     *
     * <p>用于跨容器传输场景，将邻居容器的 {@link IItemHandler} 槽位
     * 包装为 {@link NeighborSlotAccessor}，并自动添加过滤装饰器。</p>
     *
     * @param handler 邻居容器的 IItemHandler
     * @param slot 槽位索引
     * @param filterState 过滤组件状态（可为 null）
     * @return 访问器实例
     */
    public static SlotAccessor createForNeighbor(IItemHandler handler, int slot,
                                                  ComponentState filterState) {
        SlotAccessor raw = new NeighborSlotAccessor(handler, slot);
        return new FilteredSlotAccessor(raw, filterState);
    }
}