package com.qiqi.li.living.container;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.SlotItemHandler;

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
    /** 缓存键 memo（{@link #getCacheKey()} 每次调用都拼维度前缀，热路径按槽位频率调用）*/
    private String cacheKey;
    private final List<BlockPos> associatedBlockPositions;
    private final List<BlockEntity> associatedBlockEntities;
    private final Level overrideLevel;

    private TickContext currentTickContext;
    private ContainerLayout lastLayout;

    @Override
    public void setTickContext(TickContext tick) {
        this.currentTickContext = tick;
        if (tick != null) {
            // 本 tick 的领域初始化 —— 原「红石账本 resetProcessedFlag」硬编码在此，
            // 2026-10-08 计划 ⑤ 移入红石领域注册的 ContainerTickHook（时机不变，实现换位置）。
            ContainerTickHooks.fireTickStart(this, tick);
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
    public final int getWidth() {
        return getLayout().columns();
    }

    @Override
    public final ContainerLayout getLayout() {
        lastLayout = ContainerLayoutResolver.resolve(this, lastLayout);
        return lastLayout;
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
        ItemStack target = clampToSlotLimit(logicalSlot, stack.copy());

        // I-C3（D-container-02）：写回 = **覆盖槽位**，不是插入。
        // 第三方容器的 insertItem(slot, …) 可以忽略 slot（整库重定向）⇒ 绝不能拿它写回自己的槽位，
        // 否则「改槽 17 的进度」会变成「把物品搬到编号最低的空槽」。详见 container-boundary.md。
        if (!writeBackDirectly(logicalSlot, target)) {
            writeBackViaExtractInsert(logicalSlot, target);
        }

        notifyBlockEntitiesChanged();
        ContainerLivingItemHandler.bumpContainerRevision(this);
    }

    /**
     * 按<b>槽位上限</b>截断超量写入，并告警（I-C6：降级必须可观测）。
     *
     * <p>⚠️ <b>不能用 {@code stack.getMaxStackSize()} 当上限</b>：超大堆叠容器（堆叠升级、抽屉）
     * 一个槽合法地放几百个，而物品自身的 {@code getMaxStackSize()} 仍是 64
     * ⇒ 按物品上限截断会把超大堆叠砍回 64。槽位上限才是权威。</p>
     *
     * <p>截断而不是拒写：写入超量会让容器进入非法状态（GUI / 漏斗 / 比较器都假定
     * {@code count <= getSlotLimit}），比丢几个物品更难排查。</p>
     */
    private ItemStack clampToSlotLimit(int slot, ItemStack stack) {
        if (stack.isEmpty()) return stack;
        int limit = handler.getSlotLimit(slot);
        if (limit > 0 && stack.getCount() > limit) {
            LOGGER.warn("SimpleContainerContext.setItem: 槽位 {} 上限 {}，写入 {} 个 {} 已截断",
                slot, limit, stack.getCount(), stack.getItem());
            return stack.copyWithCount(limit);
        }
        return stack;
    }

    /**
     * 直接覆盖槽位 —— I-C3 的正解。返回 {@code false} 表示该 handler 不支持，调用方走回退路径。
     *
     * <p>为什么用 {@code setStackInSlot} 而不是「先抽再插」：前者是<b>单槽操作</b>，语义确定；
     * 后者的 {@code insertItem(slot, …)} 在部分模组里是<b>整库操作</b>（slot 只作参考），
     * 两者不是一回事。</p>
     *
     * <p>{@code UnsupportedOperationException} 是 {@code IItemHandlerModifiable} 的合法实现
     * （NeoForge 的 {@code FilteredItemHandler} 就只在 {@code Modifiable} 子类里实现它）
     * ⇒ 捕获后退回旧路径，而不是让整个 tick 抛异常。</p>
     */
    private boolean writeBackDirectly(int slot, ItemStack target) {
        if (!(handler instanceof net.neoforged.neoforge.items.IItemHandlerModifiable modifiable)) {
            return false;
        }
        try {
            modifiable.setStackInSlot(slot, target);
            return true;
        } catch (UnsupportedOperationException e) {
            return false;
        }
    }

    /**
     * 回退路径：handler 不支持 {@code setStackInSlot} 时，用「抽取 + 插入」模拟。
     *
     * <p>⚠️ <b>三条铁律</b>（对应 I-C7 写后校验）：</p>
     * <ol>
     *   <li><b>抽取必须真的发生</b> —— 带输出过滤器的容器会返回 {@code EMPTY}。
     *       此时若继续插入，就是「旧的还在 + 新的也进」= <b>物品复制</b>。</li>
     *   <li><b>部分抽取也算失败</b> —— 只抽走一部分就插入，同样会复制。</li>
     *   <li><b>写后校验</b> —— 插入可能被整库重定向（落到别的槽），必须核对目标槽内容。</li>
     * </ol>
     *
     * <p>失败时<b>只告警不静默</b>（I-C6）：回退路径本身就罕见，静默会让「物品去哪了」无法排查。</p>
     */
    private void writeBackViaExtractInsert(int slot, ItemStack target) {
        ItemStack before = handler.getStackInSlot(slot);

        // ① + ②：抽取必须是完整的一整份
        ItemStack extracted = handler.extractItem(slot, before.getCount(), false);
        if (!before.isEmpty() && extracted.getCount() != before.getCount()) {
            if (!extracted.isEmpty()) {
                handler.insertItem(slot, extracted, false);   // 尽量还原
            }
            LOGGER.warn("SimpleContainerContext.setItem: 槽位 {} 抽取不完整（{} / {}），放弃写入以免复制",
                slot, extracted.getCount(), before.getCount());
            return;
        }

        // ③ 插回
        ItemStack remaining = handler.insertItem(slot, target, false);
        if (!remaining.isEmpty() && isArmorSlot(inventory, slot)) {
            inventory.armor.set(slot - 36, target);
            syncSlotToClients(slot, target);
        } else if (!remaining.isEmpty()) {
            LOGGER.warn("SimpleContainerContext.setItem: {} 个 {} 未能写回槽位 {}（容器拒绝或重定向）",
                remaining.getCount(), target.getItem(), slot);
        }

        // ④ 写后校验：目标槽必须就是期望内容（防整库重定向）
        ItemStack after = handler.getStackInSlot(slot);
        if (!ItemStack.matches(after, target)) {
            LOGGER.warn("SimpleContainerContext.setItem: 写后校验失败 —— 槽位 {} 期望 {}×{}，实际 {}×{}",
                slot, target.getItem(), target.getCount(), after.getItem(), after.getCount());
        }
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

    /**
     * 缓存键 memo 化覆写 —— 规则与代价见 {@link ContainerIdentity#getCacheKey()}。
     *
     * <p>字段都是 final（{@code overrideLevel} / positions / BEs）⇒ 一个上下文实例内
     * 只算一次；上下文本身每 tick 新建，不存在跨 tick 过期问题。</p>
     */
    @Override
    public String getCacheKey() {
        String k = cacheKey;
        if (k == null) {
            k = ContainerIdentity.cacheKeyOf(this);
            cacheKey = k;
        }
        return k;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    /**
     * 容器级数据的持久宿主 = 全部关联 BE（大箱子两半都写，见 {@code ContainerFluidWriteback}）。
     *
     * <p>实体容器覆写为实体（{@code EntityContainerContext}）⇒ 领域侧不再关心
     * 「宿主是 BE 还是实体」，只遍历本列表。</p>
     */
    @Override
    public List<net.neoforged.neoforge.attachment.IAttachmentHolder> getDataHosts() {
        return List.copyOf(associatedBlockEntities);
    }

    /**
     * 容器 UUID = 第一个带身份的数据宿主（2026-10-22，活背包 / `D-container-13`）。
     *
     * <p>方块容器：BE 附件上存着（{@code ContainerUuids.getOrCreateHost} 懒创建）。
     * 大箱子两半理论上各有一份，取第一份即可 —— 身份是**容器级**的，不该两半不同。</p>
     *
     * <p>⚠️ <b>物品形态容器覆写本方法</b>：它的 UUID 在物品组件上（{@code ContainerUuids.ofItem}），
     * 而 {@code getDataHosts()} 为空（物品栈不是 {@code IAttachmentHolder}）。</p>
     */
    @Override
    public java.util.UUID getContainerUuid() {
        for (var host : getDataHosts()) {
            java.util.UUID uuid = ContainerUuids.ofHost(host);
            if (uuid != null) return uuid;
        }
        return null;
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

    /** 所属玩家 —— 玩家背包有 inventory；方块容器没有（末影箱另行覆写）。 */
    @Override
    public Player getOwnerPlayer() {
        return inventory != null ? inventory.player : null;
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
            // 空栈没有身份：ItemStack.EMPTY 是单例，拿它做同一性比较会命中**任意**空槽
            // ⇒ 回退分支只在源槽真有物品时才有意义（2026-10-28 审查补：这条曾让空栈
            //    绕过 sendSlotSync 里的「锁住的槽不发」判据，从侧门再打进去）。
            if (inv.getItem(logicalSlot).isEmpty()) return;
            for (int i = 0; i < menu.slots.size(); i++) {
                Slot slot = menu.slots.get(i);
                if (slot.container instanceof Inventory && slot.getItem() == inv.getItem(logicalSlot)) {
                    sendSlotSync(serverPlayer, menu, i, stack);
                    break;
                }
            }
        }
    }

    /**
     * 手发一条槽位包 —— <b>玩家物品栏槽同步的唯一发包点</b>。
     *
     * <p>判据放在这里而不是调用点：`syncInventoryMenuSlot` 有两条找槽的路（按
     * {@code containerSlot} 精确匹配、按对象同一性回退），判据只盖住前一条的话，
     * 后一条就是侧门（2026-10-28 审查实测到）。</p>
     */
    private void sendSlotSync(ServerPlayer serverPlayer, AbstractContainerMenu menu,
                               int slotIndex, ItemStack stack) {
        if (isSlotLockedByMenu(menu, menu.slots.get(slotIndex), serverPlayer)) return;
        int stateId = menu.incrementStateId();
        menu.remoteSlots.set(slotIndex, stack.copy());
        serverPlayer.connection.send(
            new ClientboundContainerSetSlotPacket(menu.containerId, stateId, slotIndex, stack.copy()));
    }

    /**
     * 这个<b>玩家物品栏</b>槽是不是被菜单方锁住了（玩家拿不走它）。
     *
     * <p>原版玩家物品栏槽恒可拿；只有第三方菜单会给自己的「锚点物品」套一个
     * {@code mayPickup → false} 的槽。精妙背包 GUI 的「背包本体」槽就是这种
     * （{@code StorageContainerMenuBase#addStorageItemSafeSlot}）—— 那个槽在菜单里的
     * 编号就是它的 {@code storageItemSlotNumber}。</p>
     *
     * <p><b>为什么锁住的槽不能由我们同步</b>：它的内容不归我们管（我们既改不动它，
     * 也不该替菜单方宣布「它空了」）。2026-10-28 真机：背包本体离开物品栏时我们把
     * 那个槽同步成空栈，精妙客户端 {@code BackpackWrapper.cacheSlotNumbers} 把
     * {@code AirItem} 硬转成 {@code BackpackItem} ⇒ {@code ClassCastException} 断线
     * （上游 bug，但空栈是我们发的）。菜单方自己的处理是<b>关界面</b>
     * （{@code hasSomethingMessedWithStorage}），交给它，别抢。</p>
     *
     * <p>⚠️ 只跳「发槽位包」这一件事：{@link #syncPlayerInventory} 前面那条
     * {@code containerId = -2} 的包已经把玩家物品栏本身改对了，而背包 GUI 下方的
     * 物品栏槽是直接读 {@code Inventory} 的 ⇒ 界面照样刷新。</p>
     */
    private static boolean isSlotLockedByMenu(AbstractContainerMenu menu, Slot slot, Player player) {
        return menu != player.inventoryMenu && !slot.mayPickup(player);
    }

    /**
     * 把「就地修改过的 DataComponent」推给正在看这个容器的玩家。
     *
     * <p>⚠️ <b>2026-10-18（专题 S3）改口径：按<b>位置</b>匹配 viewer，不再要求
     * {@code be instanceof Container}</b>。旧实现要求 BE 是原版 {@code Container} 才收集
     * 「我的容器实例集合」，否则<b>直接 return</b> ⇒ 那类容器方块里的活熔炉图标
     * （{@code furnace_active} 布尔组件）永远不刷新，必须关掉 GUI 再打开才更新。</p>
     *
     * <p>位置解析见 {@link MenuSlotOwners}：原版 BE / 大箱子反射 / 模组菜单的
     * {@code getBlockPosition()}。契约 I-C5。</p>
     */
    private void syncWorldContainer(int logicalSlot, ItemStack stack) {
        // ⚠️ 2026-10-21 实体容器接入：原来的「从 associatedBlockEntities 取 level」对
        // 实体容器恒为空列表 ⇒ 直接 return ⇒ 船/驴里的图标永不刷新。改用 getLevel()
        // （overrideLevel 优先，实体容器构造时传入）—— 对 BE 容器等价（那时 overrideLevel 为 null，
        // getLevel 自己会回落到 BE 世界）。
        Level level = getLevel();
        if (level == null || level.isClientSide()) return;

        List<BlockPos> myPositions = getAssociatedBlockPositions();
        if (myPositions.isEmpty()) {
            // 物品形态容器（活背包）：没有世界位置可按位置匹配 ⇒ 按容器 UUID 找正在开它
            // GUI 的观众直发槽位包。不能指望原版 broadcastChanges 兜底：菜单槽快照是
            // stack.copy()，而 PatchedDataComponentMap 是 copy-on-write 共享 patch map，
            // 就地改组件会同时改到快照 ⇒ ItemStack.matches 恒真 ⇒ 纯组件翻转永不触发
            // 槽位同步（2026-10-31：活水车在活背包 GUI 里转向不刷新的根因）。
            syncItemFormSlot(level, logicalSlot, stack);
            return;
        }

        for (ServerPlayer serverPlayer : level.getServer().getPlayerList().getPlayers()) {
            AbstractContainerMenu menu = serverPlayer.containerMenu;
            if (menu == serverPlayer.inventoryMenu) continue;

            // 快路径：整个菜单就是这一个方块 ⇒ 不必逐槽解析
            BlockPos menuPos = MenuSlotOwners.menuPosition(menu);
            boolean wholeMenuIsMine = menuPos != null && myPositions.contains(menuPos);

            for (int i = 0; i < menu.slots.size(); i++) {
                Slot slot = menu.slots.get(i);
                // 必须同时匹配：槽位索引 + 容器归属，防止跨容器虚影。
                // 大箱子：菜单槽位容器是 CompoundContainer 包装对象而非 BE 本体，
                // 实例比较永远不命中 ⇒ 走位置解析（与 tick 构建的容器键同源）。
                // ⚠️ 必须比【物理槽】：菜单/包的槽号是 handler 索引，而 logicalSlot 是容器存储格编号
                // （马/驴/骡的索引 0 是马鞍 ⇒ 两者差 1；不过这一层就是「渲染和逻辑差一格」）
                if (slot.getContainerSlot() != toPhysicalSlot(logicalSlot)) continue;
                if (slot.container == serverPlayer.getInventory()) continue;
                if (!wholeMenuIsMine && !slotBelongsToPositions(menu, slot, myPositions)) continue;

                int stateId = menu.incrementStateId();
                menu.remoteSlots.set(i, stack.copy());
                serverPlayer.connection.send(
                    new ClientboundContainerSetSlotPacket(menu.containerId, stateId, i, stack.copy()));
            }
        }
    }

    /** 该菜单槽是否属于 {@code positions} 中的任一位置（I-C5 的位置口径）。 */
    private static boolean slotBelongsToPositions(AbstractContainerMenu menu, Slot slot,
                                                  List<BlockPos> positions) {
        for (BlockPos pos : MenuSlotOwners.resolve(menu, slot)) {
            if (positions.contains(pos)) return true;
        }
        return false;
    }

    /**
     * 物品形态容器（活背包）的槽位直发：把「就地改过的 DataComponent」推给正在开它 GUI 的观众。
     *
     * <p>观众判定走 {@link ContainerViewers} 的 ITEM_FORM 分支（按容器 UUID 反查菜单，
     * 与流体快照轨同口径；隐私红线同样成立 —— 只发正在看这个容器的玩家）。
     * 槽位按 <b>handler 实例</b>匹配而不是槽号：菜单里存储槽与升级槽各挂各的 handler，
     * 槽号都从 0 起算，按号会撞；而 GUI 菜单的 handler 与本 ctx 的 handler 是同一实例
     * （联动来源与菜单解析都走 {@code BackpackWrapper.fromStack(stack)} 的按实例缓存）。</p>
     *
     * <p>⚠️ 不做 {@code isSlotLockedByMenu} 跳过：那个守卫保护的是「菜单自有、内容不归我们管」
     * 的锚点槽（背包本体槽，挂的是玩家物品栏，handler 实例匹配天然排除）；本 handler 的槽
     * 内容归我们管，被槽位封锁升级挡住的存储槽也该照常刷新显示。</p>
     */
    private void syncItemFormSlot(Level level, int logicalSlot, ItemStack stack) {
        UUID containerUuid = getContainerUuid();
        if (containerUuid == null || handler == null) return;
        var server = level.getServer();
        if (server == null) return;   // mock / 测试替身没有服务器 ⇒ 无观众可发，宁可不发
        int physicalSlot = toPhysicalSlot(logicalSlot);

        List<ServerPlayer> viewers = ContainerViewers.of(
            server.getPlayerList().getPlayers(), getCacheKey(), List.of());
        for (ServerPlayer player : viewers) {
            AbstractContainerMenu menu = player.containerMenu;
            if (menu == player.inventoryMenu) continue;

            for (int i = 0; i < menu.slots.size(); i++) {
                Slot slot = menu.slots.get(i);
                if (!(slot instanceof SlotItemHandler handlerSlot)) continue;
                if (handlerSlot.getItemHandler() != handler) continue;
                if (slot.getContainerSlot() != physicalSlot) continue;

                int stateId = menu.incrementStateId();
                menu.remoteSlots.set(i, stack.copy());
                player.connection.send(
                    new ClientboundContainerSetSlotPacket(menu.containerId, stateId, i, stack.copy()));
            }
        }
    }
}