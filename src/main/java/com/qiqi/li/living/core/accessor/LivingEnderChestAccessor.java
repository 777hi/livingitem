package com.qiqi.li.living.core.accessor;

import java.util.Set;
import java.util.UUID;

import com.qiqi.li.living.core.ComponentState;
import com.qiqi.li.living.core.components.ItemFilterComponent;
import com.qiqi.li.living.container.ContainerContext;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
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
    private String rollbackContainerKey;

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
        String containerKey = containerCtx.getContainerKey();

        if (level == null || level.isClientSide) {
            LOGGER.warn("LivingEnderChestAccessor: registerRoute invalid level, channel={}", channel);
            return;
        }

        if (pos == null && containerKey == null) {
            LOGGER.warn("LivingEnderChestAccessor: registerRoute no pos and no containerKey, channel={}", channel);
            return;
        }

        String itemType = BuiltInRegistries.ITEM.getKey(sourceStack.getItem()).toString();
        EnderChannelEntry entry = new EnderChannelEntry(
            itemType, level.dimension(), pos, slot, registrarSlot, containerKey);

        EnderChannelRegistry registry = EnderChannelRegistry.getInstance();
        // 先清理同位置+槽位的旧路由（物品类型可能已变化），再插入新路由
        if (pos != null) {
            registry.removeByPositionAndSlot(channel, pos, slot);
        } else {
            registry.removeByPositionAndSlot(channel, containerKey, slot);
        }
        registry.insert(channel, entry);
        LOGGER.debug("LivingEnderChestAccessor: registered route channel={}, item={}, pos={}, key={}, slot={}, count={}",
            channel, itemType, pos, containerKey, slot, sourceStack.getCount());
    }

    @Override
    public ItemStack extract(int amount, ItemStack filterType) {
        EnderChannelRegistry registry = EnderChannelRegistry.getInstance();
        LOGGER.debug("LivingEnderChestAccessor: extract begin channel={}, amount={}", channel, amount);

        while (true) {
            EnderChannelEntry entry = registry.peek(channel, filterState);
            if (entry == null) {
                LOGGER.trace("LivingEnderChestAccessor: extract channel={}, no entry found", channel);
                return ItemStack.EMPTY;
            }

            ServerLevel sourceLevel = server.getLevel(entry.sourceDim());
            if (sourceLevel == null) {
                LOGGER.debug("LivingEnderChestAccessor: extract source dim invalid, remove channel={}, dim={}",
                    channel, entry.sourceDim());
                registry.remove(channel, entry);
                continue;
            }

            IItemHandler sourceHandler;
            BlockPos sourcePos = entry.sourcePos();

            if (sourcePos != null) {
                if (!sourceLevel.isLoaded(sourcePos)) {
                    LOGGER.debug("LivingEnderChestAccessor: extract source unloaded, remove channel={}, pos={}",
                        channel, sourcePos);
                    registry.remove(channel, entry);
                    continue;
                }

                BlockEntity be = sourceLevel.getBlockEntity(sourcePos);
                sourceHandler = getHandler(sourceLevel, sourcePos, be);
                if (sourceHandler == null) {
                    LOGGER.debug("LivingEnderChestAccessor: extract source not container, remove channel={}, pos={}",
                        channel, sourcePos);
                    registry.remove(channel, entry);
                    continue;
                }
            } else {
                String containerKey = entry.containerKey();
                if (containerKey == null || !containerKey.startsWith("player_")) {
                    LOGGER.debug("LivingEnderChestAccessor: extract unknown containerKey, remove channel={}, key={}",
                        channel, containerKey);
                    registry.remove(channel, entry);
                    continue;
                }

                try {
                    UUID playerId = UUID.fromString(containerKey.substring(7));
                    ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                    if (player == null) {
                        LOGGER.debug("LivingEnderChestAccessor: extract player offline, remove channel={}, uuid={}",
                            channel, playerId);
                        registry.remove(channel, entry);
                        continue;
                    }
                    sourceHandler = player.getCapability(Capabilities.ItemHandler.ENTITY);
                    if (sourceHandler == null) {
                        registry.remove(channel, entry);
                        continue;
                    }
                } catch (IllegalArgumentException e) {
                    LOGGER.debug("LivingEnderChestAccessor: extract invalid containerKey, remove channel={}, key={}",
                        channel, containerKey);
                    registry.remove(channel, entry);
                    continue;
                }
            }

            ItemStack sourceStack = sourceHandler.getStackInSlot(entry.sourceSlot());
            if (sourceStack.isEmpty()) {
                LOGGER.debug("LivingEnderChestAccessor: extract source slot empty, remove channel={}, slot={}",
                    channel, entry.sourceSlot());
                registry.remove(channel, entry);
                continue;
            }

            String itemId = BuiltInRegistries.ITEM.getKey(sourceStack.getItem()).toString();
            if (!itemId.equals(entry.itemType())) {
                LOGGER.debug("LivingEnderChestAccessor: extract type mismatch, remove channel={}, expected={}, actual={}",
                    channel, entry.itemType(), itemId);
                registry.remove(channel, entry);
                continue;
            }

            if (filterState != null && !ItemFilterComponent.allows(filterState, sourceStack)) {
                LOGGER.debug("LivingEnderChestAccessor: extract filter blocked, remove channel={}, item={}",
                    channel, itemId);
                registry.remove(channel, entry);
                continue;
            }

            int toExtract = Math.min(amount, sourceStack.getCount());
            ItemStack extracted = sourceHandler.extractItem(entry.sourceSlot(), toExtract, false);

            rollbackDim = entry.sourceDim();
            rollbackPos = sourcePos;
            rollbackSlot = entry.sourceSlot();
            rollbackContainerKey = entry.containerKey();

            if (sourceHandler.getStackInSlot(entry.sourceSlot()).isEmpty()) {
                registry.remove(channel, entry);
            }

            LOGGER.info("LivingEnderChestAccessor: extracted channel={}, item={}, count={}, from={}, slot={}",
                channel, itemId, toExtract, sourcePos != null ? sourcePos : entry.containerKey(), entry.sourceSlot());
            return extracted;
        }
    }

    @Override
    public int insert(ItemStack stack) {
        return 0;
    }

    @Override
    public void rollback(ItemStack stack) {
        if (rollbackDim == null) {
            LOGGER.warn("LivingEnderChestAccessor: rollback no saved dim, channel={}", channel);
            return;
        }

        ServerLevel level = server.getLevel(rollbackDim);
        if (level == null) {
            LOGGER.warn("LivingEnderChestAccessor: rollback invalid dim, channel={}", channel);
            return;
        }

        if (rollbackPos != null) {
            if (!level.isLoaded(rollbackPos)) {
                LOGGER.warn("LivingEnderChestAccessor: rollback target unloaded, channel={}, pos={}",
                    channel, rollbackPos);
                return;
            }

            BlockEntity be = level.getBlockEntity(rollbackPos);
            IItemHandler handler = getHandler(level, rollbackPos, be);
            if (handler == null) {
                LOGGER.warn("LivingEnderChestAccessor: rollback target not container, channel={}, pos={}",
                    channel, rollbackPos);
                return;
            }
            handler.insertItem(rollbackSlot, stack, false);
        } else if (rollbackContainerKey != null && rollbackContainerKey.startsWith("player_")) {
            try {
                UUID playerId = UUID.fromString(rollbackContainerKey.substring(7));
                ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                if (player == null) {
                    LOGGER.warn("LivingEnderChestAccessor: rollback player offline, channel={}, uuid={}",
                        channel, playerId);
                    return;
                }
                IItemHandler handler = player.getCapability(Capabilities.ItemHandler.ENTITY);
                if (handler != null) {
                    handler.insertItem(rollbackSlot, stack, false);
                }
            } catch (IllegalArgumentException e) {
                LOGGER.warn("LivingEnderChestAccessor: rollback invalid containerKey, channel={}, key={}",
                    channel, rollbackContainerKey);
                return;
            }
        } else {
            LOGGER.warn("LivingEnderChestAccessor: rollback no saved position or containerKey, channel={}", channel);
            return;
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

    /**
     * 从方块位置获取 IItemHandler，统一通过 NeoForge 能力获取。
     */
    private static IItemHandler getHandler(Level level, BlockPos pos, BlockEntity be) {
        return level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null);
    }
}