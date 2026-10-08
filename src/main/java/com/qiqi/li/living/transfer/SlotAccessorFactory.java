package com.qiqi.li.living.transfer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import com.qiqi.li.living.api.LivingItemManager;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.items.IItemHandler;

import com.mojang.logging.LogUtils;
import net.minecraft.world.item.ItemStack;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.container.ContainerSnapshot;
import com.qiqi.li.living.transfer.FilterData;
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
                           FilterData filterData, Set<Integer> transferredTargetSlots,
                           ContainerSnapshot snapshot);
    }

    /** 注册的 Provider 列表（按优先级排序）。 */
    private static final List<Provider> PROVIDERS = Collections.synchronizedList(new ArrayList<>());

    // ⚠️ 领域 Provider（活箱子 / 活末影箱）**不在这里注册**（2026-10-08：transfer 不再认识领域）——
    // 由各领域在自己的 *Registration 里调 registerProvider 登记。
    // 框架自带的 defaultProvider 改为在 create(...) 的**兜底位置**调用（它匹配一切，必须最后）。

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
                                       FilterData filterData, Set<Integer> transferredTargetSlots,
                                       ContainerSnapshot snapshot) {
        ItemStack stack = containerCtx.getItem(slot);

        // 活物品（非容器类）不参与传输
        if (LivingItemManager.isLivingItem(stack) && !ContainerLikeItems.isContainerLike(stack)) {
            return null;
        }

        // 遍历注册的 Provider（领域 Provider 由 *Registration 登记），找到第一个匹配的
        for (Provider provider : PROVIDERS) {
            SlotAccessor raw = provider.create(server, containerCtx, slot, filterData, transferredTargetSlots, snapshot);
            if (raw != null) {
                return new FilteredSlotAccessor(raw, filterData);
            }
        }

        // 兜底：框架自带的默认 Provider —— **必须在领域之后**（它匹配一切）
        SlotAccessor fallback = defaultProvider(server, containerCtx, slot, filterData,
            transferredTargetSlots, snapshot);
        if (fallback != null) {
            return new FilteredSlotAccessor(fallback, filterData);
        }

        LOGGER.warn("No SlotAccessor provider matched for slot {} in container {}", slot, containerCtx.getContainerKey());
        return null;
    }

    /**
     * 为邻居容器创建 SlotAccessor（不使用注册机制，直接创建）。
     */
    public static SlotAccessor createForNeighbor(IItemHandler handler, int slot,
                                                  FilterData filterData,
                                                  Level level, BlockPos neighborPos) {
        SlotAccessor raw = new NeighborSlotAccessor(handler, slot, level, neighborPos);
        return new FilteredSlotAccessor(raw, filterData);
    }

    /**
     * 默认 Provider —— 处理普通槽位。
     */
    private static SlotAccessor defaultProvider(MinecraftServer server, ContainerContext containerCtx, int slot,
                                                 FilterData filterData, Set<Integer> transferredTargetSlots,
                                                 ContainerSnapshot snapshot) {
        return new PlainSlotAccessor(containerCtx, slot, transferredTargetSlots);
    }
}