package com.qiqi.li.living.core.accessor;

import java.util.Set;
import java.util.UUID;

import com.qiqi.li.living.container.ContainerContext;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.PlayerEnderChestContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;
import net.neoforged.neoforge.items.wrapper.InvWrapper;
import org.slf4j.Logger;

/**
 * 活末影箱槽位访问器 —— 支持路由模式和直连模式。
 *
 * <h3>路由模式</h3>（无绑定玩家）
 * 活末影箱不存储任何物品，只是一个路由器：
 * <ul>
 *   <li><strong>insert（Push）</strong>：不实际存储物品，只注册路由条目到全局路由表</li>
 *   <li><strong>extract（Pull）</strong>：查路由表 → 跳转到源容器 → 提取物品 → 返回</li>
 * </ul>
 *
 * <h3>直连模式</h3>（有绑定玩家）
 * 直接读写绑定玩家的末影箱背包：
 * <ul>
 *   <li><strong>insert（Push）</strong>：直接插入到玩家末影箱背包</li>
 *   <li><strong>extract（Pull）</strong>：直接从玩家末影箱背包提取</li>
 *   <li>玩家离线时跳过</li>
 * </ul>
 *
 * <h3>黑白名单过滤</h3>
 * <p>直连模式的过滤由 {@link FilteredSlotAccessor} 统一处理。
 * 路由模式的 {@code filterState} 仅用于路由表预过滤（{@code registry.peek()}），
 * 避免提取不匹配的物品类型。</p>
 *
 * <h3>频道隔离</h3>
 * 路由模式下：堆叠数 = 频道号。直连模式下：频道号无意义。
 */
public class LivingEnderChestAccessor implements SlotAccessor {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final int channel;
    private final MinecraftServer server;
    /** 路由模式下用于路由表预过滤（直连模式下为 null，由 FilteredSlotAccessor 处理） */
    private final com.qiqi.li.living.core.ComponentState filterState;
    private final Set<Integer> transferredTargetSlots;
    private final UUID boundPlayerUuid;
    private final boolean directMode;

    /** 预加载的玩家末影箱引用（直连模式下缓存，避免重复查找玩家） */
    private final PlayerEnderChestContainer cachedEnderChest;

    private ContainerContext sourceContainerCtx;
    private int sourceSlot;

    private ResourceKey<Level> rollbackDim;
    private BlockPos rollbackPos;
    private int rollbackSlot;
    private String rollbackContainerKey;

    private int directRollbackSlot = -1;

    public LivingEnderChestAccessor(MinecraftServer server, int channel,
                                     Set<Integer> transferredTargetSlots) {
        this(server, channel, null, transferredTargetSlots);
    }

    public LivingEnderChestAccessor(MinecraftServer server, int channel,
                                     com.qiqi.li.living.core.ComponentState filterState,
                                     Set<Integer> transferredTargetSlots) {
        this.server = server;
        this.channel = channel;
        this.filterState = filterState;
        this.transferredTargetSlots = transferredTargetSlots;
        this.boundPlayerUuid = null;
        this.directMode = false;
        this.cachedEnderChest = null;
    }

    public LivingEnderChestAccessor(MinecraftServer server, int channel,
                                     Set<Integer> transferredTargetSlots,
                                     UUID boundPlayerUuid) {
        this.server = server;
        this.channel = channel;
        this.filterState = null; // 直连模式不需要 filterState，由 FilteredSlotAccessor 处理
        this.transferredTargetSlots = transferredTargetSlots;
        this.boundPlayerUuid = boundPlayerUuid;
        this.directMode = boundPlayerUuid != null;

        // 预加载玩家末影箱引用，避免每次操作都查找玩家
        ServerPlayer player = server.getPlayerList().getPlayer(boundPlayerUuid);
        this.cachedEnderChest = player != null ? player.getEnderChestInventory() : null;
    }

    public boolean isDirectMode() {
        return directMode;
    }

    /** 获取频道号（路由模式下 = 堆叠数，直连模式下无意义） */
    public int getChannel() {
        return channel;
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
            itemType, level.dimension(), pos, slot, registrarSlot, containerKey, -1);

        EnderChannelRegistry registry = EnderChannelRegistry.getInstance();
        // 快速路径：如果当前频道已存在相同条目，跳过（绝大多数tick走这里）
        if (registry.contains(channel, entry)) {
            return;
        }
        // 频道可能已改变，清理所有频道中同位置+槽位的旧路由
        if (pos != null) {
            registry.removeByPositionAndSlotFromAllChannels(pos, slot);
        } else {
            registry.removeByPositionAndSlotFromAllChannels(containerKey, slot);
        }
        registry.insert(channel, entry);
        LOGGER.debug("LivingEnderChestAccessor: registered route channel={}, item={}, pos={}, key={}, slot={}, count={}",
            channel, itemType, pos, containerKey, slot, sourceStack.getCount());
    }

    @Override
    public ItemStack extract(int amount, ItemStack filterType) {
        if (directMode) {
            return directExtract(amount);
        }
        return routeExtract(amount);
    }

    @Override
    public ItemStack simulateExtract(int amount) {
        if (directMode) {
            return directSimulateExtract(amount);
        }
        return routeSimulateExtract(amount);
    }

    private ItemStack directSimulateExtract(int amount) {
        if (cachedEnderChest == null) return ItemStack.EMPTY;

        InvWrapper wrapper = new InvWrapper(cachedEnderChest);
        for (int i = 0; i < wrapper.getSlots(); i++) {
            ItemStack slotStack = wrapper.getStackInSlot(i);
            if (slotStack.isEmpty()) continue;

            int toExtract = Math.min(amount, slotStack.getCount());
            return wrapper.extractItem(i, toExtract, true);
        }
        return ItemStack.EMPTY;
    }

    private ItemStack routeSimulateExtract(int amount) {
        EnderChannelRegistry registry = EnderChannelRegistry.getInstance();
        EnderChannelEntry entry = registry.peek(channel, filterState);
        if (entry == null) return ItemStack.EMPTY;

        ServerLevel sourceLevel;
        BlockPos sourcePos = entry.sourcePos();

        if (sourcePos != null) {
            sourceLevel = server.getLevel(entry.sourceDim());
            if (sourceLevel == null) return ItemStack.EMPTY;
            if (!sourceLevel.isLoaded(sourcePos)) return ItemStack.EMPTY;
        } else {
            String containerKey = entry.containerKey();
            UUID playerId = parsePlayerUuid(containerKey);
            if (playerId == null) return ItemStack.EMPTY;

            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player == null) return ItemStack.EMPTY;
            sourceLevel = (ServerLevel) player.level();
        }

        IItemHandler sourceHandler = resolveSourceHandler(entry, sourceLevel);
        if (sourceHandler == null) return ItemStack.EMPTY;

        ItemStack sourceStack = sourceHandler.getStackInSlot(entry.sourceSlot());
        if (sourceStack.isEmpty()) return ItemStack.EMPTY;

        String itemId = BuiltInRegistries.ITEM.getKey(sourceStack.getItem()).toString();
        if (!itemId.equals(entry.itemType())) return ItemStack.EMPTY;

        int toExtract = Math.min(amount, sourceStack.getCount());
        return sourceHandler.extractItem(entry.sourceSlot(), toExtract, true);
    }

    private ItemStack directExtract(int amount) {
        if (cachedEnderChest == null) {
            LOGGER.debug("LivingEnderChestAccessor: direct extract player offline, uuid={}", boundPlayerUuid);
            return ItemStack.EMPTY;
        }

        InvWrapper wrapper = new InvWrapper(cachedEnderChest);
        for (int i = 0; i < wrapper.getSlots(); i++) {
            ItemStack slotStack = wrapper.getStackInSlot(i);
            if (slotStack.isEmpty()) continue;

            int toExtract = Math.min(amount, slotStack.getCount());
            ItemStack extracted = wrapper.extractItem(i, toExtract, false);

            directRollbackSlot = i;

            LOGGER.debug("LivingEnderChestAccessor: direct extract slot={}, item={}, count={}",
                i, BuiltInRegistries.ITEM.getKey(extracted.getItem()).toString(), extracted.getCount());
            return extracted;
        }
        return ItemStack.EMPTY;
    }

    private ItemStack routeExtract(int amount) {
        EnderChannelRegistry registry = EnderChannelRegistry.getInstance();
        LOGGER.debug("LivingEnderChestAccessor: extract begin channel={}, amount={}", channel, amount);

        while (true) {
            EnderChannelEntry entry = registry.peek(channel, filterState);
            if (entry == null) {
                LOGGER.trace("LivingEnderChestAccessor: extract channel={}, no entry found", channel);
                return ItemStack.EMPTY;
            }

            ServerLevel sourceLevel;
            BlockPos sourcePos = entry.sourcePos();

            if (sourcePos != null) {
                // 方块容器：通过维度获取世界
                sourceLevel = server.getLevel(entry.sourceDim());
                if (sourceLevel == null) {
                    LOGGER.debug("LivingEnderChestAccessor: extract source dim invalid, remove channel={}, dim={}",
                        channel, entry.sourceDim());
                    registry.remove(channel, entry);
                    continue;
                }
            } else {
                // 玩家背包/末影箱：从 containerKey 解析玩家 UUID，获取玩家所在世界
                String containerKey = entry.containerKey();
                if (containerKey == null || !containerKey.startsWith("player_")) {
                    LOGGER.debug("LivingEnderChestAccessor: extract invalid containerKey, remove channel={}, key={}",
                        channel, containerKey);
                    registry.remove(channel, entry);
                    continue;
                }

                UUID playerId = parsePlayerUuid(containerKey);
                if (playerId == null) {
                    LOGGER.debug("LivingEnderChestAccessor: extract invalid containerKey, remove channel={}, key={}",
                        channel, containerKey);
                    registry.remove(channel, entry);
                    continue;
                }

                ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                if (player == null) {
                    LOGGER.debug("LivingEnderChestAccessor: extract player offline, remove channel={}, uuid={}",
                        channel, playerId);
                    registry.remove(channel, entry);
                    continue;
                }
                sourceLevel = (ServerLevel) player.level();
            }

            // 获取 sourceHandler
            IItemHandler sourceHandler = resolveSourceHandler(entry, sourceLevel);
            if (sourceHandler == null) {
                registry.remove(channel, entry);
                continue;
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

            int toExtract = Math.min(amount, sourceStack.getCount());
            ItemStack extracted = sourceHandler.extractItem(entry.sourceSlot(), toExtract, false);

            rollbackDim = entry.sourceDim();
            rollbackPos = sourcePos;
            rollbackSlot = entry.sourceSlot();
            rollbackContainerKey = entry.containerKey();

            if (sourceHandler.getStackInSlot(entry.sourceSlot()).isEmpty()) {
                registry.remove(channel, entry);
            }

            LOGGER.debug("LivingEnderChestAccessor: extracted channel={}, item={}, count={}, from={}, slot={}",
                channel, itemId, toExtract, sourcePos != null ? sourcePos : entry.containerKey(), entry.sourceSlot());
            return extracted;
        }
    }

    @Override
    public int insert(ItemStack stack) {
        if (!directMode) return 0;

        if (cachedEnderChest == null) {
            LOGGER.debug("LivingEnderChestAccessor: direct insert player offline, uuid={}", boundPlayerUuid);
            return 0;
        }

        int originalCount = stack.getCount();
        InvWrapper wrapper = new InvWrapper(cachedEnderChest);
        ItemStack remaining = ItemHandlerHelper.insertItem(wrapper, stack.copy(), false);
        int inserted = originalCount - remaining.getCount();
        stack.shrink(inserted);

        LOGGER.debug("LivingEnderChestAccessor: direct insert item={}, count={}, inserted={}",
            BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), originalCount, inserted);
        return inserted;
    }

    @Override
    public int simulateInsert(ItemStack stack) {
        if (!directMode) return 0;

        if (cachedEnderChest == null) return 0;

        InvWrapper wrapper = new InvWrapper(cachedEnderChest);
        ItemStack remaining = ItemHandlerHelper.insertItem(wrapper, stack.copy(), true);
        return stack.getCount() - remaining.getCount();
    }

    @Override
    public void rollback(ItemStack stack) {
        if (directMode && directRollbackSlot >= 0) {
            if (cachedEnderChest == null) {
                LOGGER.warn("LivingEnderChestAccessor: direct rollback player offline, uuid={}", boundPlayerUuid);
                return;
            }
            ItemStack existing = cachedEnderChest.getItem(directRollbackSlot);
            if (existing.isEmpty()) {
                cachedEnderChest.setItem(directRollbackSlot, stack.copy());
            } else if (ItemStack.isSameItemSameComponents(existing, stack)) {
                existing.grow(stack.getCount());
            } else {
                LOGGER.warn("LivingEnderChestAccessor: direct rollback slot mismatch, slot={}", directRollbackSlot);
                return;
            }
            int slot = directRollbackSlot;
            directRollbackSlot = -1;
            LOGGER.debug("LivingEnderChestAccessor: direct rollback item={}, count={}, slot={}",
                BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount(), slot);
            return;
        }

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
            UUID playerId = parsePlayerUuid(rollbackContainerKey);
            if (playerId == null) {
                LOGGER.warn("LivingEnderChestAccessor: rollback invalid containerKey, channel={}, key={}",
                    channel, rollbackContainerKey);
                return;
            }

            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player == null) {
                LOGGER.warn("LivingEnderChestAccessor: rollback player offline, channel={}, uuid={}",
                    channel, playerId);
                return;
            }

            IItemHandler handler;
            if (rollbackContainerKey.endsWith("_ender_chest")) {
                handler = new InvWrapper(player.getEnderChestInventory());
            } else {
                handler = player.getCapability(Capabilities.ItemHandler.ENTITY);
            }
            if (handler != null) {
                handler.insertItem(rollbackSlot, stack, false);
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
        if (directMode) {
            if (cachedEnderChest == null) return true;
            for (int i = 0; i < cachedEnderChest.getContainerSize(); i++) {
                if (!cachedEnderChest.getItem(i).isEmpty()) return false;
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean isFull() {
        if (directMode) {
            if (cachedEnderChest == null) return true;
            for (int i = 0; i < cachedEnderChest.getContainerSize(); i++) {
                ItemStack slotStack = cachedEnderChest.getItem(i);
                if (slotStack.isEmpty() || slotStack.getCount() < slotStack.getMaxStackSize()) {
                    return false;
                }
            }
            return true;
        }
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
     * 解析源 IItemHandler。
     *
     * @return IItemHandler 实例，如果无法解析则返回 null（调用方负责清理路由）
     */
    private IItemHandler resolveSourceHandler(EnderChannelEntry entry, ServerLevel sourceLevel) {
        BlockPos sourcePos = entry.sourcePos();

        if (sourcePos != null) {
            if (!sourceLevel.isLoaded(sourcePos)) {
                LOGGER.debug("LivingEnderChestAccessor: extract source unloaded, remove channel={}, pos={}",
                    channel, sourcePos);
                return null;
            }

            BlockEntity be = sourceLevel.getBlockEntity(sourcePos);
            IItemHandler handler = getHandler(sourceLevel, sourcePos, be);
            if (handler == null) {
                LOGGER.debug("LivingEnderChestAccessor: extract source not container, remove channel={}, pos={}",
                    channel, sourcePos);
            }
            return handler;
        } else {
            String containerKey = entry.containerKey();
            if (containerKey == null) return null;

            UUID playerId = parsePlayerUuid(containerKey);
            if (playerId == null) {
                LOGGER.debug("LivingEnderChestAccessor: extract invalid containerKey, channel={}, key={}",
                    channel, containerKey);
                return null;
            }

            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player == null) return null;

            if (containerKey.endsWith("_ender_chest")) {
                return new InvWrapper(player.getEnderChestInventory());
            } else {
                IItemHandler handler = player.getCapability(Capabilities.ItemHandler.ENTITY);
                if (handler == null) {
                    LOGGER.debug("LivingEnderChestAccessor: extract player has no ItemHandler, channel={}", channel);
                }
                return handler;
            }
        }
    }

    /**
     * 从方块位置获取 IItemHandler，统一通过 NeoForge 能力获取。
     */
    private static IItemHandler getHandler(Level level, BlockPos pos, BlockEntity be) {
        return level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null);
    }

    /**
     * 从 containerKey 中解析玩家 UUID。
     *
     * <p>支持两种格式：</p>
     * <ul>
     *   <li>"player_&lt;uuid&gt;_ender_chest" → 末影箱容器</li>
     *   <li>"player_&lt;uuid&gt;" → 玩家背包</li>
     * </ul>
     *
     * @param containerKey 容器唯一标识 key
     * @return 玩家 UUID，解析失败返回 null
     */
    private static UUID parsePlayerUuid(String containerKey) {
        if (containerKey == null || !containerKey.startsWith("player_")) return null;
        try {
            if (containerKey.endsWith("_ender_chest")) {
                return UUID.fromString(containerKey.substring(7, containerKey.length() - 12));
            } else {
                return UUID.fromString(containerKey.substring(7));
            }
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}