package com.qiqi.li.living.core.accessor;

import java.util.Set;

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
     * @param filterState 过滤组件状态（用于活箱子预查过滤，可为 null）
     * @param transferredTargetSlots 级联防护集合
     * @return 访问器实例，如果槽位包含不应被传输的活物品则返回 null
     */
    public static SlotAccessor create(MinecraftServer server, ContainerContext containerCtx, int slot,
                                       ComponentState filterState, Set<Integer> transferredTargetSlots) {
        ItemStack stack = containerCtx.getItem(slot);

        if (LivingChestFunction.isLivingChest(stack)) {
            int capacity = LivingChestFunction.getCapacity(containerCtx);
            return new LivingChestAccessor(server, containerCtx, slot, stack, capacity,
                filterState, transferredTargetSlots);
        }

        if (LivingEnderChestFunction.isLivingEnderChest(stack)) {
            int ch = stack.getCount();
            LOGGER.debug("SlotAccessorFactory: creating LivingEnderChestAccessor, channel={}, slot={}", ch, slot);
            return new LivingEnderChestAccessor(server, ch, filterState,
                transferredTargetSlots);
        }

        if (LivingItemManager.isLivingItem(stack)) {
            return null;
        }

        return new PlainSlotAccessor(containerCtx, slot, transferredTargetSlots);
    }
}