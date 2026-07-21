package com.qiqi.li.living.core.accessor;

import java.util.Set;

import com.qiqi.li.living.core.ComponentState;
import com.qiqi.li.living.core.components.ItemFilterComponent;
import com.qiqi.li.living.container.ContainerContext;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.slf4j.Logger;

/**
 * 活末影箱槽位访问器 —— 无线传输路由器。
 *
 * <h3>核心机制</h3>
 * 活末影箱不存储任何物品，只是一个路由器：
 * <ul>
 *   <li><strong>insert（Push）</strong>：不实际存储物品，只注册路由条目到全局路由表</li>
 *   <li><strong>extract（Pull）</strong>：查路由表 → 跳转到源容器 → 提取物品 → 返回</li>
 * </ul>
 *
 * <h3>频道隔离</h3>
 * 堆叠数 = 频道号。不同堆叠数的活末影箱互不干扰。
 *
 * <h3>区块依赖</h3>
 * Push 端和 Pull 端所在区块都需加载。源区块卸载时路由条目被清理。
 */
public class LivingEnderChestAccessor implements SlotAccessor {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final int channel;
    private final MinecraftServer server;
    private final ComponentState filterState;
    private final Set<Integer> transferredTargetSlots;

    private ContainerContext sourceContainerCtx;
    private int sourceSlot;

    private ResourceKey<Level> rollbackDim;
    private BlockPos rollbackPos;
    private int rollbackSlot;

    public LivingEnderChestAccessor(MinecraftServer server, int channel,
                                     ComponentState filterState, Set<Integer> transferredTargetSlots) {
        this.server = server;
        this.channel = channel;
        this.filterState = filterState;
        this.transferredTargetSlots = transferredTargetSlots;
    }

    /**
     * 注册路由条目 —— 记录源物品的类型和位置，不实际存储物品。
     *
     * <p>由 {@code ItemTransferComponent.executeTransfer()} 在目标为活末影箱时调用。</p>
     *
     * @param sourceStack 源物品栈
     * @param containerCtx 当前容器上下文
     * @param slot 源物品槽位
     */
    public void registerRoute(ItemStack sourceStack, ContainerContext containerCtx, int slot, int registrarSlot) {
        if (sourceStack.isEmpty()) {
            LOGGER.warn("LivingEnderChestAccessor: registerRoute called with empty stack, channel={}", channel);
            return;
        }

        Level level = containerCtx.getLevel();
        BlockPos pos = containerCtx.getBlockPos();
        if (level == null || pos == null || level.isClientSide) {
            LOGGER.warn("LivingEnderChestAccessor: registerRoute invalid context, level={}, pos={}, channel={}",
                level, pos, channel);
            return;
        }

        String itemType = BuiltInRegistries.ITEM.getKey(sourceStack.getItem()).toString();
        EnderChannelEntry entry = new EnderChannelEntry(
            itemType, level.dimension(), pos, slot, registrarSlot);

        EnderChannelRegistry registry = EnderChannelRegistry.getInstance();
        // 先清理同位置+槽位的旧路由（物品类型可能已变化），再插入新路由
        registry.removeByPositionAndSlot(channel, pos, slot);
        registry.insert(channel, entry);
        LOGGER.debug("LivingEnderChestAccessor: registered route channel={}, item={}, pos={}, slot={}, count={}",
            channel, itemType, pos, slot, sourceStack.getCount());
    }

    @Override
    public ItemStack extract(int amount, ItemStack filterType) {
        EnderChannelRegistry registry = EnderChannelRegistry.getInstance();
        LOGGER.debug("LivingEnderChestAccessor: extract begin channel={}, amount={}", channel, amount);

        while (true) {
            EnderChannelEntry entry = registry.peek(channel, filterState);
            if (entry == null) {
                LOGGER.debug("LivingEnderChestAccessor: extract channel={}, no entry found", channel);
                return ItemStack.EMPTY;
            }

            ServerLevel sourceLevel = server.getLevel(entry.sourceDim());
            if (sourceLevel == null || !sourceLevel.isLoaded(entry.sourcePos())) {
                LOGGER.debug("LivingEnderChestAccessor: extract source unloaded, pop channel={}, pos={}",
                    channel, entry.sourcePos());
                registry.pop(channel);
                continue;
            }

            BlockEntity be = sourceLevel.getBlockEntity(entry.sourcePos());
            if (!(be instanceof Container sourceContainer)) {
                LOGGER.debug("LivingEnderChestAccessor: extract source not container, pop channel={}, pos={}",
                    channel, entry.sourcePos());
                registry.pop(channel);
                continue;
            }

            ItemStack sourceStack = sourceContainer.getItem(entry.sourceSlot());
            if (sourceStack.isEmpty()) {
                LOGGER.debug("LivingEnderChestAccessor: extract source slot empty, pop channel={}, slot={}",
                    channel, entry.sourceSlot());
                registry.pop(channel);
                continue;
            }

            String itemId = BuiltInRegistries.ITEM.getKey(sourceStack.getItem()).toString();
            if (!itemId.equals(entry.itemType())) {
                LOGGER.debug("LivingEnderChestAccessor: extract type mismatch, pop channel={}, expected={}, actual={}",
                    channel, entry.itemType(), itemId);
                registry.pop(channel);
                continue;
            }

            if (filterState != null && !ItemFilterComponent.allows(filterState, sourceStack)) {
                LOGGER.debug("LivingEnderChestAccessor: extract filter blocked, pop channel={}, item={}",
                    channel, itemId);
                registry.pop(channel);
                continue;
            }

            int toExtract = Math.min(amount, sourceStack.getCount());
            ItemStack extracted = sourceStack.copy();
            extracted.setCount(toExtract);
            sourceStack.shrink(toExtract);
            sourceContainer.setItem(entry.sourceSlot(), sourceStack);

            rollbackDim = entry.sourceDim();
            rollbackPos = entry.sourcePos();
            rollbackSlot = entry.sourceSlot();

            if (sourceStack.isEmpty()) {
                registry.pop(channel);
            }

            LOGGER.info("LivingEnderChestAccessor: extracted channel={}, item={}, count={}, from={}, slot={}",
                channel, itemId, toExtract, entry.sourcePos(), entry.sourceSlot());
            return extracted;
        }
    }

    @Override
    public int insert(ItemStack stack) {
        return 0;
    }

    @Override
    public void rollback(ItemStack stack) {
        if (rollbackPos == null || rollbackDim == null) {
            LOGGER.warn("LivingEnderChestAccessor: rollback no saved position, channel={}", channel);
            return;
        }

        ServerLevel level = server.getLevel(rollbackDim);
        if (level == null || !level.isLoaded(rollbackPos)) {
            LOGGER.warn("LivingEnderChestAccessor: rollback target unloaded, channel={}, pos={}",
                channel, rollbackPos);
            return;
        }

        BlockEntity be = level.getBlockEntity(rollbackPos);
        if (!(be instanceof Container container)) {
            LOGGER.warn("LivingEnderChestAccessor: rollback target not container, channel={}, pos={}",
                channel, rollbackPos);
            return;
        }

        ItemStack current = container.getItem(rollbackSlot);
        if (current.isEmpty()) {
            container.setItem(rollbackSlot, stack);
        } else if (ItemStack.isSameItemSameComponents(current, stack)) {
            current.grow(stack.getCount());
            container.setItem(rollbackSlot, current);
        }
        LOGGER.debug("LivingEnderChestAccessor: rollback channel={}, item={}, count={}, pos={}, slot={}",
            channel, stack.getHoverName().getString(), stack.getCount(), rollbackPos, rollbackSlot);
    }

    @Override
    public boolean isEmpty() {
        return false;
    }

    @Override
    public boolean isFull() {
        return false;
    }

    @Override
    public void markTransferred() {
        if (transferredTargetSlots != null) {
            transferredTargetSlots.add(sourceSlot);
        }
    }

    @Override
    public void sync() {
    }
}