package com.qiqi.li.living.domain.ender;

import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.container.ContainerSnapshot;
import com.qiqi.li.living.transfer.FilteredSlotAccessor;
import com.qiqi.li.living.transfer.SlotAccessor;
import com.qiqi.li.living.transfer.FilterData;
import com.qiqi.li.living.domain.ender.LivingEnderChestFunction;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.PlayerEnderChestContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;
import net.neoforged.neoforge.items.wrapper.InvWrapper;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.Set;
import java.util.UUID;

/**
 * 活末影箱槽位访问器 —— 支持直连模式与两种路由模式。
 *
 * <h3>v12 三态模型</h3>
 * <table>
 *   <tr><th>模式</th><th>触发条件</th><th>数据流向</th><th>路由表</th></tr>
 *   <tr><td><b>直连模式</b></td><td>绑定玩家 且 堆叠数 = 1</td>
 *       <td>直接读写绑定玩家的末影箱背包</td><td>❌ 绕过</td></tr>
 *   <tr><td><b>专属频道</b></td><td>绑定玩家 且 堆叠数 ≥ 2</td>
 *       <td>经全局路由表无线传输，频道键带玩家归属</td><td>✅ 使用</td></tr>
 *   <tr><td><b>公共频道</b></td><td>未绑定玩家</td>
 *       <td>经全局路由表无线传输，频道键仅由堆叠数决定</td><td>✅ 使用</td></tr>
 * </table>
 *
 * <p>工作模式完全由 {@link EnderChannelKey}（绑定 UUID + 堆叠数）决定，
 * 玩家通过拆分/合并堆叠即可切换 —— 这是本 mod「堆叠数即配置旋钮」一贯设计的延伸。</p>
 *
 * <h3>路由模式（公共频道 / 专属频道）</h3>
 * 活末影箱不存储任何物品，只是一个路由器：
 * <ul>
 *   <li><strong>insert（Push）</strong>：不实际存储物品，只注册路由条目到全局路由表</li>
 *   <li><strong>extract（Pull）</strong>：查路由表 → 跳转到源容器 → 提取物品 → 返回</li>
 * </ul>
 *
 * <h3>直连模式</h3>
 * 直接读写绑定玩家的末影箱背包：
 * <ul>
 *   <li><strong>insert（Push）</strong>：直接插入到玩家末影箱背包</li>
 *   <li><strong>extract（Pull）</strong>：直接从玩家末影箱背包提取</li>
 *   <li>玩家离线时跳过</li>
 * </ul>
 *
 * <h3>黑白名单过滤</h3>
 * <p>直连模式的过滤由 {@link FilteredSlotAccessor} 统一处理，因此 {@link #filterData} 置空。
 * 路由模式（含专属频道）的 {@code filterData} 用于路由表预过滤（{@code registry.peek()}），
 * 避免提取不匹配的物品类型 —— <b>必须保留，不可置空</b>。</p>
 */
public class LivingEnderChestAccessor implements SlotAccessor {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final EnderChannelKey channelKey;
    private final MinecraftServer server;
    /** 路由模式下用于路由表预过滤；直连模式下为 null，由 FilteredSlotAccessor 处理 */
    @Nullable
    private final FilterData filterData;
    private final Set<Integer> transferredTargetSlots;
    @Nullable
    private final UUID boundPlayerUuid;
    private final boolean directMode;

    /** 预加载的玩家末影箱引用（仅直连模式缓存，避免重复查找玩家） */
    @Nullable
    private final PlayerEnderChestContainer cachedEnderChest;

    /** 缓存的 IItemHandler 包装（仅直连模式，避免每 tick 重复创建 InvWrapper） */
    @Nullable
    private final IItemHandler cachedInvWrapper;

    /** 直连模式提取轮询指针，记录下次提取的起始槽位 */
    private int nextExtractSlot = 0;

    /** 路由模式偏好物品类型（注册名），用于贪心提取策略 */
    @Nullable
    private String preferredItemType;

    private ContainerContext sourceContainerCtx;
    private int sourceSlot;

    private ResourceKey<Level> rollbackDim;
    private BlockPos rollbackPos;
    private int rollbackSlot;
    private String rollbackContainerKey;

    private int directRollbackSlot = -1;

    /**
     * 尝试创建 LivingEnderChestAccessor（用于注册式工厂）。
     *
     * <p>频道键与工作模式均从物品栈解析：绑定 UUID 取自 DataComponent，堆叠数取自
     * {@code stack.getCount()}。{@code filterData} 在所有模式下统一透传，
     * 由构造器根据最终模式决定是否保留。</p>
     *
     * @return 如果是活末影箱则返回 Accessor，否则返回 null
     */
    @Nullable
    public static SlotAccessor tryCreate(MinecraftServer server, ContainerContext containerCtx, int slot,
                                          @Nullable FilterData filterData, Set<Integer> transferredTargetSlots,
                                          ContainerSnapshot snapshot) {
        ItemStack stack = containerCtx.getItem(slot);
        if (!LivingEnderChestFunction.isLivingEnderChest(stack)) {
            return null;
        }
        EnderChannelKey key = EnderChannelKey.of(stack);
        return new LivingEnderChestAccessor(server, key, filterData, transferredTargetSlots, key.owner());
    }

    /**
     * 规范构造器。
     *
     * @param server                服务端实例
     * @param channelKey            频道键（归属玩家 + 堆叠数）
     * @param filterData            黑白名单过滤数据，直连模式下被丢弃
     * @param transferredTargetSlots 本 tick 已传输的目标槽位集合
     * @param boundPlayerUuid       绑定玩家 UUID，null 表示未绑定
     */
    public LivingEnderChestAccessor(MinecraftServer server, EnderChannelKey channelKey,
                                     @Nullable FilterData filterData,
                                     Set<Integer> transferredTargetSlots,
                                     @Nullable UUID boundPlayerUuid) {
        this.server = server;
        this.channelKey = channelKey;
        this.transferredTargetSlots = transferredTargetSlots;
        this.boundPlayerUuid = boundPlayerUuid;
        this.directMode = boundPlayerUuid != null && channelKey.count() == 1;

        if (directMode) {
            // 直连模式：过滤交由 FilteredSlotAccessor，路由表不参与
            this.filterData = null;
            // 预加载玩家末影箱引用，避免每次操作都查找玩家
            ServerPlayer player = server.getPlayerList().getPlayer(boundPlayerUuid);
            this.cachedEnderChest = player != null ? player.getEnderChestInventory() : null;
            this.cachedInvWrapper = cachedEnderChest != null ? new InvWrapper(cachedEnderChest) : null;
        } else {
            // 路由模式（公共频道 / 玩家专属频道）：filterData 必须保留，用于路由表预过滤
            this.filterData = filterData;
            this.cachedEnderChest = null;
            this.cachedInvWrapper = null;
        }
    }

    /** 路由模式便捷构造器（无过滤）。 */
    public LivingEnderChestAccessor(MinecraftServer server, EnderChannelKey channelKey,
                                     Set<Integer> transferredTargetSlots) {
        this(server, channelKey, null, transferredTargetSlots, null);
    }

    /** 路由模式便捷构造器（带过滤）。 */
    public LivingEnderChestAccessor(MinecraftServer server, EnderChannelKey channelKey,
                                     @Nullable FilterData filterData,
                                     Set<Integer> transferredTargetSlots) {
        this(server, channelKey, filterData, transferredTargetSlots, null);
    }

    /** 是否为直连模式（绑定玩家且堆叠数 = 1）。 */
    public boolean isDirectMode() {
        return directMode;
    }

    /** 获取频道键（直连模式下该键不参与路由表操作）。 */
    public EnderChannelKey getChannelKey() {
        return channelKey;
    }

    /**
     * 设置偏好物品类型（注册名），用于贪心提取策略。
     *
     * <p>当输出槽已有物品时，优先提取相同类型的物品（可堆叠），
     * 避免轮询到不同类型导致传输停止。</p>
     *
     * @param itemType 物品注册名（如 "minecraft:iron_ingot"），null 表示无偏好
     */
    public void setPreferredItemType(@Nullable String itemType) {
        this.preferredItemType = itemType;
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
        if (cachedInvWrapper == null) return ItemStack.EMPTY;

        int slots = cachedInvWrapper.getSlots();
        for (int offset = 0; offset < slots; offset++) {
            int i = (nextExtractSlot + offset) % slots;
            ItemStack slotStack = cachedInvWrapper.getStackInSlot(i);
            if (slotStack.isEmpty()) continue;

            int toExtract = Math.min(amount, slotStack.getCount());
            return cachedInvWrapper.extractItem(i, toExtract, true);
        }
        return ItemStack.EMPTY;
    }

    private ItemStack routeSimulateExtract(int amount) {
        EnderChannelRegistry registry = EnderChannelRegistry.getInstance();
        EnderChannelEntry entry = registry.peek(channelKey, filterData, preferredItemType);
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
        if (cachedInvWrapper == null) {
            LOGGER.debug("LivingEnderChestAccessor: direct extract player offline, uuid={}", boundPlayerUuid);
            return ItemStack.EMPTY;
        }

        int slots = cachedInvWrapper.getSlots();
        for (int offset = 0; offset < slots; offset++) {
            int i = (nextExtractSlot + offset) % slots;
            ItemStack slotStack = cachedInvWrapper.getStackInSlot(i);
            if (slotStack.isEmpty()) continue;

            int toExtract = Math.min(amount, slotStack.getCount());
            ItemStack extracted = cachedInvWrapper.extractItem(i, toExtract, false);

            directRollbackSlot = i;
            nextExtractSlot = (i + 1) % slots;

            LOGGER.debug("LivingEnderChestAccessor: direct extract slot={}, item={}, count={}",
                i, BuiltInRegistries.ITEM.getKey(extracted.getItem()).toString(), extracted.getCount());
            return extracted;
        }
        return ItemStack.EMPTY;
    }

    private ItemStack routeExtract(int amount) {
        EnderChannelRegistry registry = EnderChannelRegistry.getInstance();
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("LivingEnderChestAccessor: extract begin key={}, amount={}", channelKey, amount);
        }

        while (true) {
            EnderChannelEntry entry;
            if (filterData != null) {
                // 有过滤条件：先查找匹配条目，再精确移除
                entry = registry.peek(channelKey, filterData, preferredItemType);
                if (entry == null) {
                    LOGGER.trace("LivingEnderChestAccessor: extract key={}, filter no match", channelKey);
                    return ItemStack.EMPTY;
                }
                registry.remove(channelKey, entry);
            } else {
                // 无过滤条件：从队列取出，优先匹配偏好类型
                entry = registry.poll(channelKey, preferredItemType);
                if (entry == null) {
                    LOGGER.trace("LivingEnderChestAccessor: extract key={}, no entry found", channelKey);
                    return ItemStack.EMPTY;
                }
            }

            ServerLevel sourceLevel;
            BlockPos sourcePos = entry.sourcePos();

            if (sourcePos != null) {
                // 方块容器：通过维度获取世界
                sourceLevel = server.getLevel(entry.sourceDim());
                if (sourceLevel == null) {
                    LOGGER.debug("LivingEnderChestAccessor: extract source dim invalid, key={}, dim={}",
                        channelKey, entry.sourceDim());
                    continue;
                }
            } else {
                // 玩家背包/末影箱：从 containerKey 解析玩家 UUID，获取玩家所在世界
                String containerKey = entry.containerKey();
                if (containerKey == null || !containerKey.startsWith("player_")) {
                    LOGGER.debug("LivingEnderChestAccessor: extract invalid containerKey, key={}, key={}",
                        channelKey, containerKey);
                    continue;
                }

                UUID playerId = parsePlayerUuid(containerKey);
                if (playerId == null) {
                    LOGGER.debug("LivingEnderChestAccessor: extract invalid containerKey, key={}, entryKey={}",
                        channelKey, containerKey);
                    continue;
                }

                ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                if (player == null) {
                    LOGGER.debug("LivingEnderChestAccessor: extract player offline, key={}, uuid={}",
                        channelKey, playerId);
                    continue;
                }
                sourceLevel = (ServerLevel) player.level();
            }

            // 获取 sourceHandler
            IItemHandler sourceHandler = resolveSourceHandler(entry, sourceLevel);
            if (sourceHandler == null) {
                continue;
            }

            ItemStack sourceStack = sourceHandler.getStackInSlot(entry.sourceSlot());
            if (sourceStack.isEmpty()) {
                LOGGER.debug("LivingEnderChestAccessor: extract source slot empty, key={}, slot={}",
                    channelKey, entry.sourceSlot());
                continue;
            }

            String itemId = BuiltInRegistries.ITEM.getKey(sourceStack.getItem()).toString();
            if (!itemId.equals(entry.itemType())) {
                LOGGER.debug("LivingEnderChestAccessor: extract type mismatch, key={}, expected={}, actual={}",
                    channelKey, entry.itemType(), itemId);
                continue;
            }

            int toExtract = Math.min(amount, sourceStack.getCount());
            ItemStack extracted = sourceHandler.extractItem(entry.sourceSlot(), toExtract, false);

            rollbackDim = entry.sourceDim();
            rollbackPos = sourcePos;
            rollbackSlot = entry.sourceSlot();
            rollbackContainerKey = entry.containerKey();

            if (sourcePos != null) {
                notifySourceChanged(sourceLevel, sourcePos);
            }

            if (sourceHandler.getStackInSlot(entry.sourceSlot()).isEmpty()) {
                // 源槽已空，entry 已从队列移除，不 reoffer
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("LivingEnderChestAccessor: extracted key={}, item={}, count={}, from={}, slot={}, drained",
                        channelKey, itemId, toExtract, sourcePos != null ? sourcePos : entry.containerKey(), entry.sourceSlot());
                }
            } else {
                // 源槽还有物品，放回队列尾部继续参与轮询
                registry.reoffer(channelKey, entry);
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("LivingEnderChestAccessor: extracted key={}, item={}, count={}, from={}, slot={}, reoffer",
                        channelKey, itemId, toExtract, sourcePos != null ? sourcePos : entry.containerKey(), entry.sourceSlot());
                }
            }
            return extracted;
        }
    }

    @Override
    public int insert(ItemStack stack) {
        if (!directMode) return 0;

        if (cachedInvWrapper == null) {
            LOGGER.debug("LivingEnderChestAccessor: direct insert player offline, uuid={}", boundPlayerUuid);
            return 0;
        }

        int originalCount = stack.getCount();
        ItemStack remaining = ItemHandlerHelper.insertItem(cachedInvWrapper, stack.copy(), false);
        int inserted = originalCount - remaining.getCount();
        stack.shrink(inserted);

        LOGGER.debug("LivingEnderChestAccessor: direct insert item={}, count={}, inserted={}",
            BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), originalCount, inserted);
        return inserted;
    }

    @Override
    public int simulateInsert(ItemStack stack) {
        if (!directMode) return 0;

        if (cachedInvWrapper == null) return 0;

        ItemStack remaining = ItemHandlerHelper.insertItem(cachedInvWrapper, stack.copy(), true);
        return stack.getCount() - remaining.getCount();
    }

    @Override
    public void rollback(ItemStack stack) {
        if (directMode && directRollbackSlot >= 0) {
            if (cachedInvWrapper == null) {
                LOGGER.warn("LivingEnderChestAccessor: direct rollback player offline, uuid={}", boundPlayerUuid);
                return;
            }
            ItemStack existing = cachedInvWrapper.getStackInSlot(directRollbackSlot);
            if (existing.isEmpty()) {
                cachedInvWrapper.insertItem(directRollbackSlot, stack.copy(), false);
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
            LOGGER.warn("LivingEnderChestAccessor: rollback no saved dim, key={}", channelKey);
            return;
        }

        ServerLevel level = server.getLevel(rollbackDim);
        if (level == null) {
            LOGGER.warn("LivingEnderChestAccessor: rollback invalid dim, key={}", channelKey);
            return;
        }

        if (rollbackPos != null) {
            if (!level.isLoaded(rollbackPos)) {
                LOGGER.warn("LivingEnderChestAccessor: rollback target unloaded, key={}, pos={}",
                    channelKey, rollbackPos);
                return;
            }

            BlockEntity be = level.getBlockEntity(rollbackPos);
            IItemHandler handler = getHandler(level, rollbackPos, be);
            if (handler == null) {
                LOGGER.warn("LivingEnderChestAccessor: rollback target not container, key={}, pos={}",
                    channelKey, rollbackPos);
                return;
            }
            handler.insertItem(rollbackSlot, stack, false);
            notifySourceChanged(level, rollbackPos);
        } else if (rollbackContainerKey != null && rollbackContainerKey.startsWith("player_")) {
            UUID playerId = parsePlayerUuid(rollbackContainerKey);
            if (playerId == null) {
                LOGGER.warn("LivingEnderChestAccessor: rollback invalid containerKey, key={}, entryKey={}",
                    channelKey, rollbackContainerKey);
                return;
            }

            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player == null) {
                LOGGER.warn("LivingEnderChestAccessor: rollback player offline, key={}, uuid={}",
                    channelKey, playerId);
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
            LOGGER.warn("LivingEnderChestAccessor: rollback no saved position or containerKey, key={}", channelKey);
            return;
        }
        LOGGER.debug("LivingEnderChestAccessor: rollback key={}, item={}, count={}, pos={}, slot={}",
            channelKey, stack.getHoverName().getString(), stack.getCount(), rollbackPos, rollbackSlot);
    }

    @Override
    public boolean isEmpty() {
        if (directMode) {
            if (cachedInvWrapper == null) return true;
            for (int i = 0; i < cachedInvWrapper.getSlots(); i++) {
                if (!cachedInvWrapper.getStackInSlot(i).isEmpty()) return false;
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean isFull() {
        if (directMode) {
            if (cachedInvWrapper == null) return true;
            for (int i = 0; i < cachedInvWrapper.getSlots(); i++) {
                ItemStack slotStack = cachedInvWrapper.getStackInSlot(i);
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
    @Nullable
    private IItemHandler resolveSourceHandler(EnderChannelEntry entry, ServerLevel sourceLevel) {
        BlockPos sourcePos = entry.sourcePos();

        if (sourcePos != null) {
            if (!sourceLevel.isLoaded(sourcePos)) {
                LOGGER.debug("LivingEnderChestAccessor: extract source unloaded, remove key={}, pos={}",
                    channelKey, sourcePos);
                return null;
            }

            BlockEntity be = sourceLevel.getBlockEntity(sourcePos);
            IItemHandler handler = getHandler(sourceLevel, sourcePos, be);
            if (handler == null) {
                LOGGER.debug("LivingEnderChestAccessor: extract source not container, remove key={}, pos={}",
                    channelKey, sourcePos);
            }
            return handler;
        } else {
            String containerKey = entry.containerKey();
            if (containerKey == null) return null;

            UUID playerId = parsePlayerUuid(containerKey);
            if (playerId == null) {
                LOGGER.debug("LivingEnderChestAccessor: extract invalid containerKey, key={}", channelKey);
                return null;
            }

            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player == null) return null;

            if (containerKey.endsWith("_ender_chest")) {
                return new InvWrapper(player.getEnderChestInventory());
            } else {
                IItemHandler handler = player.getCapability(Capabilities.ItemHandler.ENTITY);
                if (handler == null) {
                    LOGGER.debug("LivingEnderChestAccessor: extract player has no ItemHandler, key={}", channelKey);
                }
                return handler;
            }
        }
    }

    /**
     * 从方块位置获取 IItemHandler，统一通过 NeoForge 能力获取。
     */
    @Nullable
    private static IItemHandler getHandler(Level level, BlockPos pos, BlockEntity be) {
        return level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null);
    }

    /**
     * 通知源方块实体 inventory 已变更（遵循 NeoForge 约定）。
     */
    private static void notifySourceChanged(ServerLevel level, BlockPos pos) {
        if (pos != null && level.isLoaded(pos)) {
            if (level.getBlockEntity(pos) instanceof BlockEntity be) {
                be.setChanged();
            }
        }
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
    @Nullable
    private static UUID parsePlayerUuid(@Nullable String containerKey) {
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
