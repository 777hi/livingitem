package com.qiqi.li.living.container;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.qiqi.li.living.domain.redstone.ContainerRedstoneData;
import com.qiqi.li.living.transfer.ContainerCompatibilityConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.items.IItemHandler;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

/**
 * {@link ContainerContext} 的通用实现，基于 IItemHandler。
 *
 * 核心机制：
 * - handler：用于数据读写（getStackInSlot/extractItem/insertItem），统一基于 IItemHandler
 * - inventory：玩家背包引用（nullable），用于同步和 key 生成
 * - associatedBlockPositions / associatedBlockEntities：用于生成稳定的容器标识 key
 * - syncSlotToClients：手动同步 DataComponent 变化到客户端
 */
public class SimpleContainerContext implements TickableContainerContext {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 测试专用构造器的 key 序号（见 {@link #SimpleContainerContext(IItemHandler)}）。 */
    private static final java.util.concurrent.atomic.AtomicInteger TEST_SEQ =
        new java.util.concurrent.atomic.AtomicInteger();

    private final IItemHandler handler;
    private final Inventory inventory;
    private final String containerKey;
    private final List<BlockPos> associatedBlockPositions;
    private final List<BlockEntity> associatedBlockEntities;
    private final Level overrideLevel;

    private TickContext currentTickContext;

    @Override
    public void setTickContext(TickContext tick) {
        this.currentTickContext = tick;
        if (tick != null) {
            // ⚠️ 用 peek（**不创建**）：红石账本只在「容器与红石有关」时才有必要存在。
            // 2026-10-08 前这里调的是 getOrCreateRedstoneData() ⇒ **每个被 tick 的容器都无条件
            // 创建账本**，使红石驱动守卫（LivingRedstoneFunction.tickContainerData）的
            // `peek == null` 恒假 ⇒「无关容器零开销」那条路径**永不生效**。
            // 安全性：新建账本的 processedThisTick 在构造器里默认 false ⇒ 首次无需 reset；
            // 此后每 tick 账本已存在 ⇒ 照常 reset（这正是历史上「中继器不熄灭」的根因修复点）。
            ContainerRedstoneData rd = peekContainerData(ContainerRedstoneData.KEY);
            if (rd != null) {
                rd.resetProcessedFlag();
            }
        }
    }

    public TickContext getTickContext() {
        return currentTickContext;
    }

    /**
     * <b>测试专用</b>：自动生成唯一 containerKey。
     *
     * <p>用于「无玩家背包、无方块实体」的**替身场景**（单元测试里的 FakeHandler）。
     * 生产代码请用另外两个构造器 —— 真实容器必须有稳定身份，否则无法跨 tick
     * 持久化容器级数据（见 {@link #buildContainerKey} 的说明）。</p>
     *
     * <p><b>为什么要单独开一个构造器</b>：旧实现在「两者都无」时回退到
     * {@code handler.hashCode()} 做键 —— 那个分支**生产不可达、只被测试走到**，
     * 却把「静默丢数据」的隐患留在了生产代码里。现在拆成显式的测试入口，
     * 生产路径遇同类输入直接抛异常。</p>
     */
    public SimpleContainerContext(IItemHandler handler) {
        this(handler, (Level) null);
    }

    /**
     * <b>测试专用</b>：自动生成唯一 containerKey，并指定覆盖世界。
     * 说明见 {@link #SimpleContainerContext(IItemHandler)}。
     */
    public SimpleContainerContext(IItemHandler handler, Level overrideLevel) {
        this.handler = handler;
        this.inventory = null;
        this.overrideLevel = overrideLevel;
        this.associatedBlockPositions = new ArrayList<>();
        this.associatedBlockEntities = new ArrayList<>();
        this.containerKey = "test#" + TEST_SEQ.getAndIncrement();
    }

    /**
     * 为玩家背包创建容器上下文。
     */
    public SimpleContainerContext(IItemHandler handler, Inventory inventory) {
        this(handler, inventory, new ArrayList<>(), new ArrayList<>(), null);
    }

    /**
     * 为方块容器创建容器上下文。
     */
    public SimpleContainerContext(IItemHandler handler, List<BlockPos> positions, List<BlockEntity> blockEntities) {
        this(handler, null, positions, blockEntities, null);
    }

    /**
     * 完整构造器。
     *
     * @param handler      IItemHandler，用于物品读写
     * @param inventory    玩家背包（nullable，仅玩家背包场景传入）
     * @param positions    关联方块位置
     * @param blockEntities 关联方块实体
     * @param overrideLevel 覆盖的世界（nullable）
     */
    public SimpleContainerContext(IItemHandler handler, Inventory inventory,
                                  List<BlockPos> positions, List<BlockEntity> blockEntities,
                                  Level overrideLevel) {
        this(handler, inventory, positions, blockEntities, overrideLevel, null);
    }

    /**
     * 完整构造器 + <b>显式稳定键</b> —— 第三条合法身份形态（2026-10-04 崩溃修复）。
     *
     * <p>用于「有稳定身份但不落在背包/坐标两分支」的容器，当前唯一调用者是
     * 末影箱：{@code player_<uuid>_ender_chest}（玩家作用域 UUID 键，无 BE ⇒ 无键漂移）。
     * 1a-2 删 hashCode 第三档时，其「不可达」静态证明只枚举了 {@code processContainerAt}
     * 两条路径，<b>漏了这个调用者</b> ⇒ 末影箱 tick 每拍抛异常（crash-2026-10-04）。
     * 显式键不是 hashCode 回退的复辟 —— 调用方必须给出<b>跨会话稳定</b>的键，
     * 否则等同两条内建分支的失败语义。</p>
     *
     * @param explicitContainerKey 外部给定的稳定键（null = 走 {@code buildContainerKey} 推导）
     */
    public SimpleContainerContext(IItemHandler handler, Inventory inventory,
                                  List<BlockPos> positions, List<BlockEntity> blockEntities,
                                  Level overrideLevel, String explicitContainerKey) {
        this.handler = handler;
        this.inventory = inventory;
        this.overrideLevel = overrideLevel;

        this.associatedBlockPositions = new ArrayList<>();
        if (positions != null) {
            this.associatedBlockPositions.addAll(positions);
        }

        this.associatedBlockEntities = new ArrayList<>();
        if (blockEntities != null) {
            this.associatedBlockEntities.addAll(blockEntities);
        }

        if (explicitContainerKey != null) {
            if (explicitContainerKey.isBlank()) {
                throw new IllegalStateException("显式容器键不得为空白 —— 空白键等同无身份");
            }
            this.containerKey = explicitContainerKey;
        } else {
            this.containerKey = buildContainerKey(inventory, this.associatedBlockPositions, this.associatedBlockEntities);
        }
    }

    /**
     * 构造容器的稳定标识键 —— **跨 tick 持久化容器级数据的前提**。
     *
     * <p>只有两条合法来源：玩家背包（UUID）或方块坐标（含维度）。二者都没有的容器
     * <b>没有稳定身份</b> —— 下次拿到的已是另一个对象，任何跨 tick 数据都留不住。</p>
     *
     * <p>⚠️ <b>hashCode 第三档已被显式删除</b>（2026-10-03）：对象身份哈希在 BE 重建后
     * 会变 ⇒ <b>键漂移 ⇒ 数据静默丢失</b>，故改为显式失败而不是保留隐患。
     * ⚠️ 2026-10-04 崩溃教训：当时的「不可达」静态证明<b>漏了末影箱调用者</b>
     * （{@code EnderChestContainerContext} 传空 positions + null inventory）——
     * 证明必须枚举全部调用点。有稳定键但落在两分支之外的容器走
     * {@linkplain #SimpleContainerContext(IItemHandler, Inventory, List, List, Level, String)
     * 显式键构造器}（如末影箱的玩家作用域键）。</p>
     */
    private static String buildContainerKey(Inventory inventory, List<BlockPos> positions, List<BlockEntity> entities) {
        if (inventory != null) {
            return "player_" + inventory.player.getStringUUID();
        }
        if (!positions.isEmpty()) {
            StringBuilder sb = new StringBuilder("chest");
            for (BlockPos pos : positions) {
                sb.append("_").append(pos.getX()).append("_").append(pos.getY()).append("_").append(pos.getZ());
            }
            return sb.toString();
        }
        if (!entities.isEmpty()) {
            StringBuilder sb = new StringBuilder("container");
            for (BlockEntity be : entities) {
                BlockPos pos = be.getBlockPos();
                Level level = be.getLevel();
                String dimKey = level != null ? level.dimension().location().toString() : "unknown";
                sb.append("_").append(dimKey).append("_").append(pos.getX()).append("_").append(pos.getY()).append("_").append(pos.getZ());
            }
            return sb.toString();
        }
        throw new IllegalStateException(
            "容器缺少稳定身份：既不是玩家背包，也没有方块位置 / 方块实体。"
            + "这类容器无法跨 tick 持久化容器级数据 —— 旧实现会回退到 hashCode 键，"
            + "而对象身份哈希在 BE 重建后会变化，导致数据静默丢失。"
            + "若确需支持，请为它提供稳定键（见本方法的两条合法分支）。");
    }

    @Override
    public int getSize() {
        return handler.getSlots();
    }

    @Override
    public int getWidth() {
        if (inventory != null) {
            return 9;
        }

        // Tier 1: 已注册规则（按 BE 类型匹配）
        java.util.Optional<ContainerCompatibilityConfig.ContainerRule> rule =
            findContainerRule();
        if (rule.isPresent()) {
            return rule.get().columns();
        }

        // Tier 2: 已知容器类型直接推断
        int size = getSize();
        for (BlockEntity be : associatedBlockEntities) {
            if (be.getLevel() == null) continue;
            Container container = ContainerContext.getContainer(be.getLevel(), be.getBlockPos());
            if (container == null) continue;
            int cols = ContainerCompatibilityConfig.resolveColumns(size, container);
            if (cols > 0) return cols;
        }

        // Tier 3: 加权启发式（回退）
        return ContainerCompatibilityConfig.resolveColumns(size, null);
    }

    private java.util.Optional<ContainerCompatibilityConfig.ContainerRule> findContainerRule() {
        if (associatedBlockEntities.isEmpty()) return java.util.Optional.empty();

        for (BlockEntity be : associatedBlockEntities) {
            net.minecraft.resources.ResourceLocation id =
                net.minecraft.core.registries.BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType());
            if (id != null) {
                var rule = ContainerCompatibilityConfig.findRule(id);
                if (rule.isPresent() && rule.get().containerSize() == getSize()) return rule;

                rule = ContainerCompatibilityConfig.findRuleByNamespaceAndKeyword(
                    id.getNamespace(), id.getPath());
                if (rule.isPresent() && rule.get().containerSize() == getSize()) return rule;
            }
        }

        return java.util.Optional.empty();
    }

    @Override
    public ItemStack getItem(int logicalSlot) {
        if (logicalSlot < 0 || logicalSlot >= handler.getSlots()) {
            return ItemStack.EMPTY;
        }
        return handler.getStackInSlot(logicalSlot);
    }

    private static boolean isArmorSlot(Inventory inventory, int slot) {
        return inventory != null && slot >= 36 && slot < 40;
    }

    @Override
    public void setItem(int logicalSlot, ItemStack stack) {
        if (logicalSlot < 0 || logicalSlot >= handler.getSlots()) {
            return;
        }
        ItemStack toInsert = stack.copy();

        handler.extractItem(logicalSlot, Integer.MAX_VALUE, false);
        ItemStack remaining = handler.insertItem(logicalSlot, toInsert, false);
        if (!remaining.isEmpty() && isArmorSlot(inventory, logicalSlot)) {
            inventory.armor.set(logicalSlot - 36, toInsert);
            syncSlotToClients(logicalSlot, toInsert);
        } else if (!remaining.isEmpty()) {
            LOGGER.warn("SimpleContainerContext.setItem: {} items of {} 未能插入槽位 {}",
                remaining.getCount(), toInsert.getItem(), logicalSlot);
        }
        notifyBlockEntitiesChanged();
        ContainerLivingItemHandler.bumpContainerRevision(this);
    }

    @Override
    public int getMaxStackSize() {
        return handler.getSlots() > 0 ? handler.getSlotLimit(0) : 64;
    }

    @Override
    public int getSlotLimit(int slot) {
        if (isArmorSlot(inventory, slot)) {
            return getMaxStackSize();
        }
        return handler.getSlotLimit(slot);
    }

    @Override
    public boolean isItemValid(int slot, ItemStack stack) {
        if (isArmorSlot(inventory, slot)) {
            return true;
        }
        return handler.isItemValid(slot, stack);
    }

    @Override
    public int simulateInsertItem(int slot, ItemStack stack) {
        if (isArmorSlot(inventory, slot)) {
            int slotLimit = getSlotLimit(slot);
            int maxStack = Math.min(slotLimit, stack.getMaxStackSize());
            return Math.min(stack.getCount(), maxStack);
        }

        // ⚠ 多方块容器陷阱：ContainerContext.getContainer 只返回「pos 那一个方块实体」的
        // 容器，而 handler 是多方块合并后的完整容器 —— 两套槽位编号体系可能整体错位
        // （大箱子实测：Container 27 槽 vs handler 54 槽，Container 的槽 22 = GUI 的槽 49）。
        // 此处要用 Container 读「目标槽现有物品」，错位就会读到另一个方块部分的槽位：
        // 目标槽明明是空的却读到东西 → 判定不可插入 → 活漏斗静默不传输（不设冷却，像卡死）。
        // 判据不假设容器结构（几方块、怎么排），只探测「两套体系是否同编号」，
        // 故三方块 / 四块 / 任意多方块容器同样成立（living-hopper-tech.md §10.25）。
        Container container = ContainerContext.getContainer(getLevel(), getBlockPos());
        if (container != null && isSameSlotSpaceAsHandler(container, slot)) {
            if (!container.canPlaceItem(slot, stack)) {
                return 0;
            }
            ItemStack existing = container.getItem(slot);
            int slotLimit = container.getMaxStackSize();
            if (existing.isEmpty()) {
                int maxStack = Math.min(slotLimit, stack.getMaxStackSize());
                return Math.min(stack.getCount(), maxStack);
            }
            if (ItemStack.isSameItemSameComponents(existing, stack)) {
                int maxStack = Math.min(slotLimit, existing.getMaxStackSize());
                int space = maxStack - existing.getCount();
                return Math.min(stack.getCount(), Math.max(0, space));
            }
            return 0;
        }

        ItemStack remaining = handler.insertItem(slot, stack.copy(), true);
        return stack.getCount() - remaining.getCount();
    }

    /**
     * 槽位体系一致性探针（Q6 批次 B，2026-10-04）—— 两级判据已迁至
     * {@link ContainerContexts#isSameSlotSpace}（边界带共享内核），此处保留薄委托。
     *
     * <p>任一级不过 ⇒ 判定该 Container 的槽位编号不可信 ⇒ 调用方回退到 handler
     * （handler 才是多方块合并后的真实后端，与 GUI 同体系）。回退并不丢语义：
     * {@code handler.insertItem(slot, …, true)} 内部已转调容器的
     * {@code canPlaceItem/isItemValid}，模拟与真实写入还因此变成同源。</p>
     */
    private boolean isSameSlotSpaceAsHandler(Container container, int slot) {
        return ContainerContexts.isSameSlotSpace(container, handler, slot);
    }

    @Override
    public String getStableKey(int logicalSlot, String functionId) {
        return containerKey + "_slot_" + logicalSlot + "_func_" + functionId;
    }

    @Override
    public String getContainerKey() {
        return containerKey;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    @Override
    public List<BlockEntity> getAssociatedBlockEntities() {
        return associatedBlockEntities;
    }

    @Override
    public BlockPos getBlockPos() {
        if (!associatedBlockPositions.isEmpty()) {
            return associatedBlockPositions.get(0);
        }
        return null;
    }

    @Override
    public List<BlockPos> getAssociatedBlockPositions() {
        return Collections.unmodifiableList(associatedBlockPositions);
    }

    @Override
    public Level getLevel() {
        if (overrideLevel != null) {
            return overrideLevel;
        }
        for (BlockEntity be : associatedBlockEntities) {
            if (be.getLevel() != null) {
                return be.getLevel();
            }
        }
        if (inventory != null) {
            return inventory.player.level();
        }
        return null;
    }

    private void notifyBlockEntitiesChanged() {
        for (BlockEntity be : associatedBlockEntities) {
            be.setChanged();
        }
    }

    @Override
    public void syncSlotToClients(int logicalSlot, ItemStack stack) {
        // 就地修改 DataComponent（方向配置、状态等）会使快照失效，先 bump 修订计数
        ContainerLivingItemHandler.bumpContainerRevision(this);
        if (currentTickContext != null) {
            currentTickContext.dirtySlots.add(logicalSlot);
        } else {
            flushSlotSync(logicalSlot, stack);
        }
    }

    @Override
    public void flushDirtySlots() {
        if (currentTickContext == null) return;
        for (int slot : currentTickContext.dirtySlots) {
            flushSlotSync(slot, getItem(slot));
        }
        currentTickContext.dirtySlots.clear();
    }

    private void flushSlotSync(int logicalSlot, ItemStack stack) {
        if (inventory != null) {
            syncPlayerInventory(inventory, logicalSlot, stack);
        } else {
            syncWorldContainer(logicalSlot, stack);
        }
    }

    public ContainerRedstoneData getOrCreateRedstoneData() {
        return getOrCreateContainerData(ContainerRedstoneData.KEY);
    }

    /**
     * 容器级持久数据的统一访问入口（1a-4）：委托给 Handler 的统一 store。
     * 旧实现是「每个类型一个字段 + 一个方法」，新增一种数据要改三处；
     * 现在新增一种只需定义一个 {@link ContainerDataKey} 常量。
     */
    @Override
    public <T> T peekContainerData(ContainerDataKey<T> key) {
        return ContainerLivingItemHandler.peekContainerData(this, key);
    }

    @Override
    public <T> T getOrCreateContainerData(ContainerDataKey<T> key) {
        return ContainerLivingItemHandler.getOrCreateContainerData(this, key);
    }

    private void syncPlayerInventory(Inventory inv, int logicalSlot, ItemStack stack) {
        if (!(inv.player instanceof ServerPlayer serverPlayer)) return;

        serverPlayer.connection.send(
            new ClientboundContainerSetSlotPacket(-2, 0, logicalSlot, stack.copy()));

        syncInventoryMenuSlot(serverPlayer, serverPlayer.inventoryMenu, logicalSlot, stack);

        if (serverPlayer.containerMenu != serverPlayer.inventoryMenu) {
            syncInventoryMenuSlot(serverPlayer, serverPlayer.containerMenu, logicalSlot, stack);
        }
    }

    private void syncInventoryMenuSlot(ServerPlayer serverPlayer, AbstractContainerMenu menu,
                                        int logicalSlot, ItemStack stack) {
        Inventory inv = (Inventory) serverPlayer.getInventory();
        boolean found = false;

        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i);
            if (slot.container instanceof Inventory && slot.getContainerSlot() == logicalSlot) {
                sendSlotSync(serverPlayer, menu, i, stack);
                found = true;
                break;
            }
        }

        if (!found) {
            for (int i = 0; i < menu.slots.size(); i++) {
                Slot slot = menu.slots.get(i);
                if (slot.container instanceof Inventory && slot.getItem() == inv.getItem(logicalSlot)) {
                    sendSlotSync(serverPlayer, menu, i, stack);
                    break;
                }
            }
        }
    }

    private void sendSlotSync(ServerPlayer serverPlayer, AbstractContainerMenu menu,
                               int slotIndex, ItemStack stack) {
        int stateId = menu.incrementStateId();
        menu.remoteSlots.set(slotIndex, stack.copy());
        serverPlayer.connection.send(
            new ClientboundContainerSetSlotPacket(menu.containerId, stateId, slotIndex, stack.copy()));
    }

    private void syncWorldContainer(int logicalSlot, ItemStack stack) {
        Level level = null;
        for (BlockEntity be : associatedBlockEntities) {
            if (be.getLevel() != null) {
                level = be.getLevel();
                break;
            }
        }
        if (level == null || level.isClientSide) return;

        // 收集本容器关联的 Container 实例，用于验证 slot.container 归属
        java.util.HashSet<Container> myContainers = new java.util.HashSet<>();
        for (BlockEntity be : associatedBlockEntities) {
            if (be instanceof Container c) {
                myContainers.add(c);
            }
        }
        if (myContainers.isEmpty()) return;

        for (ServerPlayer serverPlayer : level.getServer().getPlayerList().getPlayers()) {
            AbstractContainerMenu menu = serverPlayer.containerMenu;
            if (menu == serverPlayer.inventoryMenu) continue;

            for (int i = 0; i < menu.slots.size(); i++) {
                Slot slot = menu.slots.get(i);
                // 必须同时匹配：槽位索引 + 容器归属，防止跨容器虚影。
                // 大箱子：菜单槽位容器是 CompoundContainer(左BE, 右BE) 包装对象而非
                // BE 本体，实例 contains 永远不命中 → 组件同步包从不发给大箱查看者
                // → 活水车动画/tooltip 停留在开箱快照（v19.1 修 ContainerRuntimeCache
                // 时漏掉的平行断点，修法与其对齐：用 compound.contains(be) 匹配）
                if (slot.getContainerSlot() == logicalSlot
                    && slot.container != serverPlayer.getInventory()
                    && slotBelongsTo(slot.container, myContainers)) {
                    int stateId = menu.incrementStateId();
                    menu.remoteSlots.set(i, stack.copy());
                    serverPlayer.connection.send(
                        new ClientboundContainerSetSlotPacket(menu.containerId, stateId, i, stack.copy()));
                }
            }
        }
    }

    /**
     * 判断菜单槽位的容器是否属于本容器关联的 Container 实例集合。
     *
     * <p>Q6 批次 B（2026-10-04）：实现已迁至 {@link ContainerContexts#ownsContainer}
     * （边界带共享内核），此处保留薄委托。</p>
     */
    private static boolean slotBelongsTo(Container menuContainer, java.util.Set<Container> myContainers) {
        return ContainerContexts.ownsContainer(menuContainer, myContainers);
    }
}