package com.qiqi.li.living.core.accessor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import net.neoforged.neoforge.items.IItemHandler;

import com.mojang.logging.LogUtils;
import net.minecraft.world.item.ItemStack;
import com.qiqi.li.living.LivingItemManager;
import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.data.FilterData;
import com.qiqi.li.living.function.LivingChestFunction;
import com.qiqi.li.living.function.LivingEnderChestFunction;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;

/**
 * 注册式 SlotAccessor 工厂 —— 通过注册机制解耦类型判断与创建逻辑。
 *
 * <h3>设计原则</h3>
 * <ul>
 *   <li>开闭原则：新增存储类型只需注册 Provider，无需修改工厂代码</li>
 *   <li>优先级机制：按注册顺序匹配，先注册优先级越高</li>
 *   <li>降级策略：所有 Provider 都不匹配时，使用默认的 PlainSlotAccessor</li>
 * </ul>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // 模组初始化时注册
 * SlotAccessorFactory.registerProvider((server, ctx, slot, filterData, transferredSlots) -> {
 *     ItemStack stack = ctx.getItem(slot);
 *     if (MyModItem.isMyItem(stack)) {
 *         return new MyModAccessor(ctx, slot, stack, transferredSlots);
 *     }
 *     return null; // 不匹配，交给下一个 Provider
 * });
 * }</pre>
 */
public final class SlotAccessorFactory {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * SlotAccessor 提供者函数 —— 返回 null 表示不匹配，由下一个 Provider 处理。
     */
    @FunctionalInterface
    public interface Provider {
        SlotAccessor create(MinecraftServer server, ContainerContext containerCtx, int slot,
                           FilterData filterData, Set<Integer> transferredTargetSlots);
    }

    /** 注册的 Provider 列表（按优先级排序）。 */
    private static final List<Provider> PROVIDERS = Collections.synchronizedList(new ArrayList<>());

    static {
        // 注册内置 Provider（按优先级从高到低）
        registerProvider(LivingChestAccessor::tryCreate);
        registerProvider(LivingEnderChestAccessor::tryCreate);
        registerProvider(SlotAccessorFactory::defaultProvider);
    }

    private SlotAccessorFactory() {}

    /**
     * 注册新的 SlotAccessor Provider。
     *
     * @param provider 提供者函数
     */
    public static void registerProvider(Provider provider) {
        PROVIDERS.add(provider);
    }

    /**
     * 创建 SlotAccessor（遍历注册的 Provider，找到第一个匹配的）。
     *
     * @param server 服务器实例
     * @param containerCtx 容器上下文
     * @param slot 槽位索引
     * @param filterData 过滤数据
     * @param transferredTargetSlots 已传输槽位集合
     * @return SlotAccessor（可能为 null，表示该槽位不应被传输）
     */
    public static SlotAccessor create(MinecraftServer server, ContainerContext containerCtx, int slot,
                                       FilterData filterData, Set<Integer> transferredTargetSlots) {
        ItemStack stack = containerCtx.getItem(slot);

        // 活物品（非活箱子/活末影箱）不参与传输
        if (LivingItemManager.isLivingItem(stack) &&
            !LivingChestFunction.isLivingChest(stack) &&
            !LivingEnderChestFunction.isLivingEnderChest(stack)) {
            return null;
        }

        // 遍历注册的 Provider，找到第一个匹配的
        for (Provider provider : PROVIDERS) {
            SlotAccessor raw = provider.create(server, containerCtx, slot, filterData, transferredTargetSlots);
            if (raw != null) {
                return new FilteredSlotAccessor(raw, filterData);
            }
        }

        // 理论上不会到这里（defaultProvider 总会匹配）
        LOGGER.warn("No SlotAccessor provider matched for slot {} in container {}", slot, containerCtx.getContainerKey());
        return null;
    }

    /**
     * 为邻居容器创建 SlotAccessor（不使用注册机制，直接创建）。
     */
    public static SlotAccessor createForNeighbor(IItemHandler handler, int slot,
                                                  FilterData filterData) {
        SlotAccessor raw = new NeighborSlotAccessor(handler, slot);
        return new FilteredSlotAccessor(raw, filterData);
    }

    /**
     * 默认 Provider —— 处理普通槽位。
     */
    private static SlotAccessor defaultProvider(MinecraftServer server, ContainerContext containerCtx, int slot,
                                                 FilterData filterData, Set<Integer> transferredTargetSlots) {
        return new PlainSlotAccessor(containerCtx, slot, transferredTargetSlots);
    }
}