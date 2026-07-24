package com.qiqi.li.living.core.components;

import java.util.*;
import java.util.concurrent.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.core.ComponentConfig;
import com.qiqi.li.living.core.ComponentContext;
import com.qiqi.li.living.core.ComponentState;
import com.qiqi.li.living.function.LivingChestFunction;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

/**
 * 内部存储组件 (Internal Storage Component)
 * 
 * <h2>架构概述</h2>
 * <p>
 * 活箱子的存储系统采用<strong>两层分离架构</strong>：
 * </p>
 * <ul>
 *   <li><strong>NBT 索引层</strong>（ItemStack DataComponent）：存储 UUID 列表和缓存计数</li>
 *   <li><strong>磁盘数据层</strong>（WorldStorage）：每个 UUID 对应独立的 .dat 文件，存储实际物品数据</li>
 * </ul>
 * 
 * <h2>设计原则</h2>
 * <ul>
 *   <li><strong>索引与数据分离</strong>：NBT 层只存 UUID 引用，不存物品数据，避免 ItemStack 膨胀</li>
 *   <li><strong>分文件存储</strong>：每个虚拟箱子独立文件，支持成千上万个实例而不影响性能</li>
 *   <li><strong>懒加载 + LRU 缓存</strong>：只在访问时加载，最近使用的保留在内存中</li>
 *   <li><strong>精确脏标记</strong>：只保存修改过的数据，避免全量写入</li>
 * </ul>
 * 
 * <h2>数据流示例</h2>
 * <pre>{@code
 * // 存入物品流程
 * LivingChestFunction.insertItem(server, chestStack, diamondStack, 27)
 *   → getStorageState()          // 从 NBT 读取 UUID 列表
 *   → insertItem()               // 修改 WorldStorage 中的物品
 *   → markDirty(uuid)            // 标记为脏数据
 *   → saveStorageState()         // 将更新后的 UUID 列表写回 NBT
 *   
 * // 持久化触发（LevelEvent.Save 或 ServerStoppingEvent）
 * WorldStorage.saveAllDirty()
 *   → 遍历 dirtyKeys 集合
 *   → saveToDisk(uuid, items)    // 写入磁盘文件（原子操作）
 * }</pre>
 * 
 * <h2>关键注意事项</h2>
 * <ul>
 *   <li><strong>返回值陷阱</strong>：{@code ItemStack.save(Provider, Tag)} 返回编码后的 Tag，
 *       传入的参数不会被修改！必须使用返回值！</li>
 *   <li><strong>引用语义</strong>：{@code getOrCreate()} 返回的是内部列表的直接引用，
 *       修改它会直接影响缓存中的数据</li>
 *   <li><strong>状态同步</strong>：修改 WorldStorage 后必须调用 {@code markDirty()}，
 *       修改 ComponentState 后必须调用 {@code saveStorageState()}</li>
 * </ul>
 * 
 * @author Living Item Mod Team
 * @version 2024.12
 * @see LivingChestFunction 活箱子功能入口
 * @see WorldStorage 全局存储管理器
 */
public class InternalStorageComponent implements ILivingComponent {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 组件 ID，用于在 NBT 中标识此组件 */
    public static final String ID = "internal_storage";
    
    /** NBT 键名：虚拟箱子的 UUID 列表 */
    public static final String KEY_UUIDS = "uuids";
    
    /** NBT 键名：缓存的堆叠数量（用于 tick 快速路径） */
    public static final String KEY_CACHED_COUNT = "_cc";
    /** 已用槽位数（用于客户端 tooltip 显示，无需访问磁盘） */
    public static final String KEY_USED_SLOTS = "_us";

    /**
     * 返回组件的唯一标识符
     * 
     * @return 组件 ID 字符串 "internal_storage"
     */
    @Override
    public String getComponentId() { return ID; }

    /**
     * 每个服务端 tick 执行的维护逻辑
     * 
     * <h3>功能说明</h3>
     * <p>
     * 检查活箱子的堆叠数量是否发生变化，并相应地调整虚拟箱子的数量：
     * </p>
     * <ul>
     *   <li><strong>数量增加</strong>（拆分）：创建新的空虚拟箱子</li>
     *   <li><strong>数量减少</strong>（合并）：删除多余的虚拟箱子，将其中的物品掉落到世界</li>
     *   <li><strong>数量不变</strong>：快速跳过（性能优化）</li>
     * </ul>
     * 
     * <h3>快速路径优化</h3>
     * <p>
     * 使用 {@code _cc}（Cached Count）字段记录上次已知的堆叠数量。
     * 如果当前堆叠数与缓存值匹配，直接跳过所有逻辑，避免每 tick 都解析 UUID 列表。
     * </p>
     * 
     * <h3>执行时机</h3>
     * <p>
     * 由 {@link BaseLivingFunction#tick} 在每个服务端 tick 中自动调用。
     * 只在服务端执行，客户端跳过。
     * </p>
     *
     * @param ctx 组件上下文，提供世界、容器等信息
     * @param hostSlot 宿主物品在容器中的槽位索引
     * @param hostStack 宿主物品栈（活箱子本身）
     * @param state 当前组件状态（包含 UUID 列表）
     * @param config 组件配置（未使用）
     */
    @Override
    public void tick(ComponentContext ctx, int hostSlot, ItemStack hostStack,
                     ComponentState state, ComponentConfig config) {
        if (ctx.level().isClientSide()) return;
        MinecraftServer server = ctx.level().getServer();
        if (server == null) return;

        int expectedCount = hostStack.getCount();
        int capacityPerChest = LivingChestFunction.CHEST_SLOTS;

        if (!state.contains(KEY_UUIDS)) {
            // 首次 tick：活箱子刚放入容器，主动创建 UUID 列表
            int initCount = hostStack.getCount();
            if (initCount <= 0) {
                LOGGER.debug("tick: hostStack count is 0, skipping UUID init");
                return;
            }
            LOGGER.info("tick: initializing UUIDs for new chest, count={}, capacity={}", initCount, capacityPerChest);
            List<UUID> initUuids = new ArrayList<>(initCount);
            for (int i = 0; i < initCount; i++) {
                initUuids.add(WorldStorage.createAndRegister(server, capacityPerChest, "tick-init"));
            }
            saveUuids(state, initUuids);
            state.setInt(KEY_CACHED_COUNT, initCount);
            state.setInt(KEY_USED_SLOTS, 0);  // 新箱子，0 已用
            return;
        }

        int cachedCount = state.getInt(KEY_CACHED_COUNT, -1);
        
        if (cachedCount == expectedCount) {
            return;  // 快速路径：堆叠数未变，跳过调整
        }

        LOGGER.info("tick: adjusting chest count from {} to {}, uuids={}",
            cachedCount, expectedCount, getUuids(state).size());

        List<UUID> uuids = new ArrayList<>(getUuids(state));
        int actualCount = uuids.size();
        boolean changed = false;
        WorldStorage storage = WorldStorage.get(server);

        // 数量不足时：创建新的空虚拟箱子
        if (actualCount < expectedCount) {
            while (actualCount < expectedCount) {
                uuids.add(WorldStorage.createAndRegister(server, capacityPerChest, "tick-grow"));
                actualCount++;
                changed = true;
            }
        } 
        // 数量过多时：不删除 UUID（UUID 可以多不能少，避免数据丢失）
        // 后续如果堆叠数再次增加，可直接复用这些 UUID，无需重建
        else if (actualCount > expectedCount) {
            LOGGER.info("tick: stack count decreased from {} to {}, keeping all {} uuids (no deletion)",
                cachedCount, expectedCount, actualCount);
        }

        if (changed) {
            saveUuids(state, uuids);
        }
        state.setInt(KEY_CACHED_COUNT, expectedCount);
        countUsedSlots(server, state, capacityPerChest);
    }

    /**
     * 创建默认的组件状态（空的 UUID 列表）
     * 
     * @return 空的 ComponentState 实例
     */
    @Override
    public ComponentState createDefaultState() {
        return new ComponentState();
    }

    /**
     * 向工具提示添加内部存储信息
     * 
     * <p>显示当前活箱子拥有的虚拟箱子数量。</p>
     *
     * @param state 当前组件状态
     * @param tooltipAdder 工具提示添加器回调
     */
    @Override
    public void appendTooltip(ComponentState state, java.util.function.Consumer<net.minecraft.network.chat.Component> tooltipAdder) {
        List<UUID> uuids = getUuids(state);
        if (!uuids.isEmpty()) {
            int totalSlots = uuids.size() * LivingChestFunction.CHEST_SLOTS;
            int usedSlots = state.getInt(KEY_USED_SLOTS, -1);
            
            if (usedSlots >= 0) {
                int freeSlots = totalSlots - usedSlots;
                tooltipAdder.accept(net.minecraft.network.chat.Component.translatable(
                    "tooltip.livingitem.chest.storage", usedSlots, totalSlots, freeSlots)
                    .withStyle(net.minecraft.ChatFormatting.GREEN));
            } else {
                tooltipAdder.accept(net.minecraft.network.chat.Component.translatable(
                    "tooltip.livingitem.chest.storage_empty", totalSlots)
                    .withStyle(net.minecraft.ChatFormatting.GREEN));
            }
        }
    }

    /**
     * 从组件状态中提取 UUID 列表
     * 
     * <h3>数据格式</h3>
     * <p>UUID 列表以 String 类型的 ListTag 存储在 NBT 中。</p>
     * 
     * <h3>容错性</h3>
     * <p>如果某个元素不是有效的 UUID 格式，会静默跳过而不是抛出异常。
     * 这确保了损坏的数据不会导致整个系统崩溃。</p>
     *
     * @param state 组件状态
     * @return UUID 列表的副本（可能为空列表，但不会为 null）
     */
    public static List<UUID> getUuids(ComponentState state) {
        if (!state.contains(KEY_UUIDS)) {
            return Collections.emptyList();
        }
        List<UUID> result = new ArrayList<>();
        ListTag uuidList = state.getList(KEY_UUIDS, Tag.TAG_STRING);
        for (int i = 0; i < uuidList.size(); i++) {
            try {
                result.add(UUID.fromString(uuidList.getString(i)));
            } catch (IllegalArgumentException e) {
                continue;  // 忽略无效的 UUID
            }
        }
        return result;
    }

    /**
     * 将 UUID 列表保存到组件状态中
     * 
     * <p>此方法会覆盖原有的 UUID 列表。调用后需要通过
     * {@code saveStorageState()} 将状态写回 ItemStack 的 NBT。</p>
     *
     * @param state 要修改的组件状态
     * @param uuids 新的 UUID 列表
     */
    public static void saveUuids(ComponentState state, List<UUID> uuids) {
        ListTag uuidList = new ListTag();
        for (UUID uuid : uuids) {
            uuidList.add(StringTag.valueOf(uuid.toString()));
        }
        state.putList(KEY_UUIDS, uuidList);
    }

    /**
     * 获取合并后的所有虚拟箱子物品列表
     * 
     * <h3>用途</h3>
     * <p>主要用于 GUI 显示或批量操作。返回的是所有虚拟箱子槽位的扁平化列表，
     * 顺序为：第一个箱子的所有槽位，第二个箱子的所有槽位，以此类推。</p>
     * 
     * <h3>性能注意</h3>
     * <p>此方法会触发所有相关 UUID 的缓存加载（如果尚未加载）。
     * 对于大量虚拟箱子的情况，可能会产生显著的 I/O 开销。</p>
     *
     * @param服务器 Minecraft 服务器实例
     * @param state 组件状态
     * @param capacityPerChest 每个虚拟箱子的槽数量
     * @return 合并后的物品列表（只读副本）
     */
    public static List<ItemStack> getMergedStorage(MinecraftServer server, ComponentState state, int capacityPerChest) {
        List<UUID> uuids = getUuids(state);
        if (uuids.isEmpty()) {
            return Collections.emptyList();
        }
        WorldStorage storage = WorldStorage.get(server);
        List<ItemStack> merged = new ArrayList<>();
        for (UUID uuid : uuids) {
            merged.addAll(storage.getOrCreate(uuid, capacityPerChest));
        }
        return merged;
    }

    /**
     * 统计已用槽位数（非空槽位数量）
     * 
     * <p>遍历所有虚拟箱子，统计非空槽位数量。结果写入 {@link #KEY_USED_SLOTS}。
     * 此方法在 insertItem/extractItem/tick 中调用，确保客户端 tooltip 能正确显示。</p>
     *
     * @param server Minecraft 服务器实例
     * @param state 组件状态（会写入 KEY_USED_SLOTS）
     * @param capacityPerChest 每个虚拟箱子的槽数量
     */
    private static void countUsedSlots(MinecraftServer server, ComponentState state, int capacityPerChest) {
        List<UUID> uuids = getUuids(state);
        if (uuids.isEmpty()) {
            state.setInt(KEY_USED_SLOTS, 0);
            return;
        }
        WorldStorage storage = WorldStorage.get(server);
        int used = 0;
        for (UUID uuid : uuids) {
            List<ItemStack> items = storage.getOrCreate(uuid, capacityPerChest);
            for (ItemStack item : items) {
                if (!item.isEmpty()) {
                    used++;
                }
            }
        }
        state.setInt(KEY_USED_SLOTS, used);
    }

    /**
     * 检查存储是否已满（所有槽位都有物品）
     * 
     * <p>用于活漏斗传输前的前置判断，避免无效的 insertItem 调用。</p>
     *
     * @param state 组件状态
     * @param capacityPerChest 每个虚拟箱子的槽数量
     * @return true 如果所有槽位都已占用
     */
    public static boolean isStorageFull(ComponentState state, int capacityPerChest) {
        List<UUID> uuids = getUuids(state);
        if (uuids.isEmpty()) return false;
        int totalSlots = uuids.size() * capacityPerChest;
        int usedSlots = state.getInt(KEY_USED_SLOTS, 0);
        return usedSlots >= totalSlots;
    }

    /**
     * 检查存储是否为空（没有任何物品）
     * 
     * <p>用于活漏斗传输前的前置判断，避免无效的 extractItem 调用。</p>
     *
     * @param state 组件状态
     * @return true 如果没有任何物品
     */
    public static boolean isStorageEmpty(ComponentState state) {
        List<UUID> uuids = getUuids(state);
        if (uuids.isEmpty()) return true;
        return state.getInt(KEY_USED_SLOTS, 0) <= 0;
    }

    /**
     * 将合并后的物品列表同步回各个虚拟箱子
     * 
     * <h3>用途</h3>
     * <p>主要用于 GUI 操作后的数据回写。将用户在 GUI 中修改过的物品列表
     * 重新分配到对应的虚拟箱子中。</p>
     * 
     * <h3>算法</h3>
     * <ol>
     *   <li>按照顺序遍历每个 UUID</li>
     *   <li>从 merged 列表中取出 {@code capacityPerChest} 个物品</li>
     *   <li>比较每个槽位，只有实际变化才标记为脏</li>
     * </ol>
     * 
     * <h3>⚠️ 重要</h3>
     * <p>调用此方法后，<strong>必须</strong>在外层调用 {@code saveStorageState()}
     * 以确保 NBT 层的状态也被更新！</p>
     *
     * @param server Minecraft 服务器实例
     * @param state 组件状态（会被修改）
     * @param merged 合并后的物品列表（来自 GUI 或其他来源）
     * @param capacityPerChest 每个虚拟箱子的槽数量
     */
    public static void syncMergedToStorage(MinecraftServer server, ComponentState state,
                                           List<ItemStack> merged, int capacityPerChest) {
        List<UUID> uuids = getUuids(state);
        if (uuids.isEmpty()) return;
        WorldStorage storage = WorldStorage.get(server);
        int offset = 0;

        for (int i = 0; i < uuids.size() && offset < merged.size(); i++) {
            UUID uuid = uuids.get(i);
            List<ItemStack> chestStorage = storage.getOrCreate(uuid, capacityPerChest);
            boolean modified = false;

            for (int j = 0; j < capacityPerChest && offset < merged.size(); j++) {
                ItemStack newStack = merged.get(offset).copy();
                if (!ItemStack.matches(chestStorage.get(j), newStack)) {
                    chestStorage.set(j, newStack);
                    modified = true;
                }
                offset++;
            }

            if (modified) {
                storage.markDirty(uuid);
            }
        }
    }

    /**
     * 向活箱子存入物品
     * 
     * <h3>核心逻辑</h3>
     * <ol>
     *   <li><strong>初始化检查</strong>：如果 UUID 列表为空，根据 hostStackCount 创建新的虚拟箱子</li>
     *   <li><strong>数量调整</strong>：如果 UUID 数量与 hostStackCount 不匹配，进行增删</li>
     *   <li><strong>物品插入</strong>：按顺序遍历虚拟箱子，尝试在每个空槽位或可合并槽位放入物品</li>
     *   <li><strong>脏标记</strong>：对被修改的虚拟箱子调用 markDirty()</li>
     * </ol>
     * 
     * <h3>插入策略</h3>
     * <ul>
     *   <li>优先放入空槽位（完全消耗源物品）</li>
     *   <li>其次尝试与同类型物品合并（部分消耗）</li>
     *   <li>按 UUID 列表顺序依次填充（先填满第一个箱子）</li>
     * </ul>
     * 
     * <h3>副作用</h3>
     * <ul>
     *   <li>会修改 itemToInsert 参数（减少其数量）</li>
     *   <li>会修改 state 参数（更新 UUID 列表和 _cc 字段）</li>
     *   <li>会修改 WorldStorage 中的缓存数据</li>
     * </ul>
     * 
     * <h3>⚠️ 注意事项</h3>
     * <p>调用此方法后，<strong>必须</strong>在外层调用 {@code saveStorageState()}
     * 以确保 NBT 层的状态也被更新！</p>
     *
     * @param server Minecraft 服务器实例
     * @param state 组件状态（会被修改）
     * @param itemToInsert 要存入的物品（会被修改，数量会减少）
     * @param capacityPerChest 每个虚拟箱子的槽数量
     * @param hostStackCount 活箱子的堆叠数量（决定虚拟箱子数量）
     * @return true 表示物品完全存入，false 表示部分/完全未存入
     */
    public static boolean insertItem(MinecraftServer server, ComponentState state,
                                     ItemStack itemToInsert, int capacityPerChest,
                                     int hostStackCount) {
        if (LivingChestFunction.isLivingChest(itemToInsert)) {
            LOGGER.warn("insertItem: rejected living chest self-insertion");
            return false;
        }

        List<UUID> uuids = getUuids(state);
        WorldStorage storage = WorldStorage.get(server);
        LOGGER.info("insertItem: uuids={}, item={}, capacity={}, hostCount={}",
            uuids.size(), itemToInsert, capacityPerChest, hostStackCount);

        // UUID 未初始化：可能是 tick 还没跑，拒绝插入并等待下次 tick
        if (uuids.isEmpty()) {
            LOGGER.warn("insertItem: UUIDs not initialized yet, rejecting insert");
            return false;
        }

        // 数量不匹配：调整 UUID 列表
        else if (uuids.size() != hostStackCount) {
            List<UUID> adjustedUuids;
            if (uuids.size() < hostStackCount) {
                adjustedUuids = new ArrayList<>(uuids);
                for (int i = uuids.size(); i < hostStackCount; i++) {
                    adjustedUuids.add(WorldStorage.createAndRegister(server, capacityPerChest, "insertItem"));
                }
            } else {
                // UUID 可以多不能少，不删除多余的 UUID
                LOGGER.info("insertItem: UUID count > stack count ({} > {}), keeping all uuids",
                    uuids.size(), hostStackCount);
                adjustedUuids = uuids;
            }
            saveUuids(state, adjustedUuids);
            state.setInt(KEY_CACHED_COUNT, hostStackCount);
            uuids = adjustedUuids;
        }

        // 按顺序向虚拟箱子中插入物品
        int effectiveCount = Math.min(uuids.size(), hostStackCount);
        for (int i = 0; i < effectiveCount && !itemToInsert.isEmpty(); i++) {
            UUID uuid = uuids.get(i);
            List<ItemStack> chestSlots = storage.getOrCreate(uuid, capacityPerChest);
            boolean modified = false;

            for (int j = 0; j < chestSlots.size() && !itemToInsert.isEmpty(); j++) {
                ItemStack slotItem = chestSlots.get(j);
                
                // 策略1：放入空槽位（完全消耗）
                if (slotItem.isEmpty()) {
                    chestSlots.set(j, itemToInsert.copy());
                    itemToInsert.setCount(0);
                    modified = true;
                } 
                // 策略2：与同类物品合并（部分消耗）
                else if (ItemStack.isSameItemSameComponents(slotItem, itemToInsert)) {
                    int spaceAvailable = slotItem.getMaxStackSize() - slotItem.getCount();
                    int transferAmount = Math.min(itemToInsert.getCount(), spaceAvailable);
                    if (transferAmount > 0) {
                        slotItem.grow(transferAmount);
                        itemToInsert.shrink(transferAmount);
                        modified = true;
                    }
                }
            }

            if (modified) {
                storage.markDirty(uuid);
            }
        }
        countUsedSlots(server, state, capacityPerChest);
        return itemToInsert.isEmpty();
    }

    /**
     * 从活箱子提取指定数量的物品（不限类型）
     * 
     * <h3>提取策略</h3>
     * <ol>
     *   <li>按 UUID 列表顺序遍历虚拟箱子</li>
     *   <li>在每个箱子中按槽位顺序查找非空物品</li>
     *   <li>优先提取第一个遇到的物品类型</li>
     *   <li>后续只提取相同类型的物品（用于堆叠）</li>
     * </ol>
     * 
     * <h3>返回值示例</h3>
     * <pre>{@code
     * // 提取 64 个物品
     * extractItem(server, state, 64, 27, 1)
     * → 可能返回: ItemStack{diamond, count=64}
     * 
     * // 提取超过可用数量
     * extractItem(server, state, 1000, 27, 1)
     * → 返回所有可用物品，如: ItemStack{diamond, count=32}
     * }</pre>
     * 
     * <h3>⚠️ 重要</h3>
     * <p>调用此方法后，<strong>必须</strong>在外层调用 {@code saveStorageState()}
     * 以确保 NBT 层的状态也被更新！</p>
     *
     * @param server Minecraft 服务器实例
     * @param state 组件状态
     * @param amount 最大提取数量
     * @param capacityPerChest 每个虚拟箱子的槽数量
     * @param hostStackCount 活箱子的堆叠数量
     * @return 提取的物品栈（可能为 EMPTY 如果没有物品）
     */
    public static ItemStack extractItem(MinecraftServer server, ComponentState state,
                                        int amount, int capacityPerChest, int hostStackCount) {
        List<UUID> uuids = getUuids(state);
        if (uuids.isEmpty()) return ItemStack.EMPTY;
        WorldStorage storage = WorldStorage.get(server);
        LOGGER.info("extractItem: uuids={}, amount={}, capacity={}, hostCount={}",
            uuids.size(), amount, capacityPerChest, hostStackCount);
        ItemStack result = ItemStack.EMPTY;
        int remaining = amount;
        int effectiveCount = Math.min(uuids.size(), hostStackCount);

        for (int i = 0; i < effectiveCount && remaining > 0; i++) {
            UUID uuid = uuids.get(i);
            List<ItemStack> chestSlots = storage.getOrCreate(uuid, capacityPerChest);
            boolean modified = false;

            for (int j = 0; j < chestSlots.size() && remaining > 0; j++) {
                ItemStack slotItem = chestSlots.get(j);
                if (slotItem.isEmpty()) continue;

                // 第一个物品：开始新的结果栈
                if (result.isEmpty()) {
                    int toExtract = Math.min(remaining, slotItem.getCount());
                    result = slotItem.copyWithCount(toExtract);
                    slotItem.shrink(toExtract);
                    remaining -= toExtract;
                    modified = true;
                } 
                // 后续物品：只能与结果栈同类型才能继续提取
                else if (ItemStack.isSameItemSameComponents(result, slotItem)) {
                    int toExtract = Math.min(remaining,
                        Math.min(slotItem.getCount(), result.getMaxStackSize() - result.getCount()));
                    if (toExtract > 0) {
                        result.grow(toExtract);
                        slotItem.shrink(toExtract);
                        remaining -= toExtract;
                        modified = true;
                    }
                }

                // 清理空槽位
                if (slotItem.isEmpty()) {
                    chestSlots.set(j, ItemStack.EMPTY);
                    modified = true;
                }
            }

            if (modified) {
                storage.markDirty(uuid);
            }
        }
        countUsedSlots(server, state, capacityPerChest);
        return result;
    }

    /**
     * 从活箱子提取指定类型的物品
     * 
     * <h3>与 {@link #extractItem} 的区别</h3>
     * <p>此方法增加了类型过滤，只会提取与 target 参数相同类型的物品。
     * 用于 GUI 中的"取出特定物品"操作。</p>
     * 
     * <h3>过滤逻辑</h3>
     * <ul>
     *   <li>使用 {@code ItemStack.isSameItemSameComponents()} 进行精确匹配</li>
     *   <li>包括物品 ID、NBT 数据、附魔等所有属性</li>
     *   <li>不匹配的槽位会被跳过</li>
     * </ul>
     *
     * @param server Minecraft 服务器实例
     * @param state 组件状态
     * @param target 目标物品类型（用于过滤）
     * @param amount 最大提取数量
     * @param capacityPerChest 每个虚拟箱子的槽数量
     * @param hostStackCount 活箱子的堆叠数量
     * @return 提取的物品栈（可能为 EMPTY 如果没有匹配的物品）
     */
    public static ItemStack extractItem(MinecraftServer server, ComponentState state,
                                        ItemStack target, int amount, int capacityPerChest, int hostStackCount) {
        List<UUID> uuids = getUuids(state);
        if (uuids.isEmpty()) return ItemStack.EMPTY;
        WorldStorage storage = WorldStorage.get(server);
        ItemStack result = ItemStack.EMPTY;
        int remaining = amount;
        int effectiveCount = Math.min(uuids.size(), hostStackCount);

        for (int i = 0; i < effectiveCount && remaining > 0; i++) {
            UUID uuid = uuids.get(i);
            List<ItemStack> chestSlots = storage.getOrCreate(uuid, capacityPerChest);
            boolean modified = false;

            for (int j = 0; j < chestSlots.size() && remaining > 0; j++) {
                ItemStack slotItem = chestSlots.get(j);
                if (slotItem.isEmpty()) continue;
                if (!ItemStack.isSameItemSameComponents(target, slotItem)) continue;  // 类型过滤

                int toExtract;
                if (result.isEmpty()) {
                    toExtract = Math.min(remaining, slotItem.getCount());
                    result = slotItem.copyWithCount(toExtract);
                } else {
                    toExtract = Math.min(remaining,
                        Math.min(slotItem.getCount(), result.getMaxStackSize() - result.getCount()));
                    if (toExtract <= 0) {
                        remaining = 0;  // 结果栈已满
                        break;
                    }
                    result.grow(toExtract);
                }

                slotItem.shrink(toExtract);
                remaining -= toExtract;
                modified = true;

                if (slotItem.isEmpty()) {
                    chestSlots.set(j, ItemStack.EMPTY);
                }
            }

            if (modified) {
                storage.markDirty(uuid);
            }
        }
        countUsedSlots(server, state, capacityPerChest);
        return result;
    }

    /**
     * 创建一个新的 UUID 并注册到 WorldStorage
     * 
     * <p>新创建的 UUID 会对应一个空的物品列表（全部为 EMPTY）。</p>
     *
     * @param server Minecraft 服务器实例
     * @param capacity 每个虚拟箱子的槽数量
     * @return 新创建的 UUID
     */
    public static UUID createAndRegisterNewUuid(MinecraftServer server, int capacity) {
        return WorldStorage.createAndRegister(server, capacity, "api");
    }

    /**
     * 弹出并删除最后一个 UUID
     * 
     * <h3>⚠️ 危险操作</h3>
     * <p>会从磁盘永久删除该 UUID 对应的虚拟箱子文件。
     * 调用前必须确保该 UUID 中的物品已取出或掉落。
     * 仅在用户明确要销毁活箱子时使用（如 dropAllItems）。</p>
     *
     * @deprecated 尽量使用 tick 的自动调整，避免手动删除 UUID
     */
    @Deprecated
    public static UUID popUuid(MinecraftServer server, ComponentState state) {
        List<UUID> uuids = new ArrayList<>(getUuids(state));
        if (uuids.isEmpty()) return null;
        UUID last = uuids.remove(uuids.size() - 1);
        saveUuids(state, uuids);
        WorldStorage.get(server).remove(last);
        return last;
    }

    /**
     * 全局存储管理器 (World Storage Manager)
     * 
     * <h2>设计模式</h2>
     * <ul>
     *   <li><strong>单例模式</strong>：每个 MinecraftServer 实例只有一个 WorldStorage</li>
     *   <li><strong>工厂模式</strong>：通过 {@code get(server)} 获取或创建实例</li>
     *   <li><strong>观察者模式</strong>：监听 LevelEvent.Save 和 ServerStoppingEvent 触发持久化</li>
     * </ul>
     * 
     * <h2>核心职责</h2>
     * <ol>
     *   <li><strong>缓存管理</strong>：LRU + 超时双策略缓存，限制内存占用</li>
     *   <li><strong>磁盘 I/O</strong>：原子写入、分片目录、旧数据迁移</li>
     *   <li><strong>脏数据追踪</strong>：精确标记，只保存修改过的数据</li>
     * </ol>
     * 
     * <h2>文件结构</h2>
     * <pre>
     * &lt;存档&gt;/data/living_chests/
     * ├── 00/                          ← 分片目录 (UUID最低8位)
     * │   ├── 550e8400-e29b-41d4-a716-446655440000.dat
     * │   └── ...
     * ├── 01/
     * └── ff/ (共256个分片)
     * </pre>
     * 
     * <h2>线程安全性</h2>
     * <p><strong>非线程安全</strong>！所有方法都应该在主线程（服务端 tick 线程）中调用。
     * 如果需要在其他线程访问，需要外部加锁。</p>
     * 
     * <h2>生命周期</h2>
     * <ul>
     *   <li><strong>创建</strong>：首次调用 {@code get(server)} 时</li>
     *   <li><strong>运行</strong>：持续提供服务，响应 get/markDirty/saveAllDirty 等调用</li>
     *   <li><strong>保存</strong>：LevelEvent.Save 或 ServerStoppingEvent 时</li>
     *   <li><strong>销毁</strong>：服务器停止后，instance 引用会被 GC 回收</li>
     * </ul>
     */
    public static class WorldStorage {
        /** 存储目录名称 */
        private static final String DIR_NAME = "living_chests";
        
        /** 缓存超时时间：5分钟未被访问则卸载 */
        private static final long UNLOAD_TIMEOUT_MS = 5 * 60 * 1000;
        
        /** 最大缓存条目数：超过此数量会淘汰最久未访问的条目 */
        private static final int MAX_CACHE_SIZE = 7777;
        
        /** 孤儿文件清理间隔：每 N 次 saveAllDirty 调用执行一次 */
        private static final int CLEANUP_ORPHAN_INTERVAL = 10;
        
        /** 孤儿文件清理计数器 */
        private int cleanupOrphanCounter = 0;
        
        /** 异步保存防抖间隔（毫秒）：避免每次 markDirty 都提交 IO 任务 */
        private static final long ASYNC_SAVE_DEBOUNCE_MS = 1000;
        
        /** 异步保存最小批量：积累到此数量后立即提交，无视防抖 */
        private static final int ASYNC_SAVE_MIN_BATCH = 16;
        
        /** 上次触发异步保存的时间戳 */
        private long lastAsyncSaveTime = 0;
        
        /** 分片位数：8位 = 256个子目录 */
        private static final int SHARD_BITS = 8;
        
        /** 分片数量：2^8 = 256 */
        private static final int SHARD_COUNT = 1 << SHARD_BITS;
        
        /** 分片掩码：用于取 UUID 的低8位 */
        private static final int SHARD_MASK = SHARD_COUNT - 1;

        /** 关联的 MinecraftServer 实例 */
        private final MinecraftServer server;
        
        /** 存储根目录路径 */
        private final Path storageDir;
        
        /** LRU 缓存：访问顺序 LinkedHashMap，最近访问的在尾部 */
        private final LinkedHashMap<UUID, CachedStorage> cache;
        
        /** 脏数据集合：独立追踪被修改的 UUID，优化 saveAllDirty 性能。
         *  使用 ConcurrentHashMap.newKeySet() 保证线程安全（异步IO线程也会修改此集合） */
        private final Set<UUID> dirtyKeys;
        
        /** 🔴 P0修复：异步IO执行器（用于后台写入磁盘，避免主线程卡顿） */
        private final ExecutorService ioExecutor;
        
        /** 🔴 P0修复：待异步保存的脏数据集合 */
        private final Set<UUID> pendingAsyncSave = ConcurrentHashMap.newKeySet();
        
        /** 🔴 P0修复：异步写入是否已关闭 */
        private volatile boolean asyncShutdown = false;
        
        /** 🔴 P0修复：标记是否有异步保存任务正在执行，防止任务堆积淹没执行器 */
        private final java.util.concurrent.atomic.AtomicBoolean asyncSaveInProgress = new java.util.concurrent.atomic.AtomicBoolean(false);
        
        /** 是否已完成旧数据迁移 */
        private boolean migrated;

        /** 全局单例实例 */
        private static WorldStorage instance;

        /**
         * 私有构造函数：初始化存储管理器
         * 
         * <h3>初始化步骤</h3>
         * <ol>
         *   <li>确定存储根目录路径</li>
         *   <li>创建根目录和256个分片子目录</li>
         *   <li>初始化 LRU 缓存和脏数据集合</li>
         * </ol>
         * 
         * @param server 关联的 MinecraftServer 实例
         * @throws RuntimeException 如果无法创建存储目录
         */
        private WorldStorage(MinecraftServer server) {
            this.server = server;
            this.storageDir = server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(DIR_NAME);
            this.cache = new LinkedHashMap<>(16, 0.75f, true);  // accessOrder=true 启用 LRU
            this.dirtyKeys = ConcurrentHashMap.newKeySet();
            
            // 🔴 P0修复：初始化异步IO执行器（单线程，保证写入顺序）
            this.ioExecutor = Executors.newSingleThreadExecutor(r -> {
                Thread thread = new Thread(r, "LivingChest-AsyncIO");
                thread.setDaemon(true);  // 守护线程，不阻止JVM退出
                return thread;
            });
            
            this.asyncShutdown = false;
            this.migrated = false;

            try {
                Files.createDirectories(storageDir);
                ensureShardDirs();
                LOGGER.info("Living chest storage initialized at {} (async IO enabled)", 
                           storageDir.toAbsolutePath());
            } catch (IOException e) {
                LOGGER.error("Failed to create living chest storage directory at {}", storageDir.toAbsolutePath(), e);
                throw new RuntimeException("Failed to create living chest storage directory", e);
            }
        }

        /**
         * 确保256个分片子目录都存在
         * 
         * @throws IOException 如果无法创建目录
         */
        private void ensureShardDirs() throws IOException {
            for (int i = 0; i < SHARD_COUNT; i++) {
                Files.createDirectories(storageDir.resolve(String.format("%02x", i)));
            }
        }

        /**
         * 获取或创建 WorldStorage 单例
         * 
         * <h3>单例管理策略</h3>
         * <ul>
         *   <li>如果 instance 为 null 或关联的服务器不同，创建新实例</li>
         *   <li>否则返回现有实例</li>
         * </ul>
         * 
         * <h3>服务器切换场景</h3>
         * <ul>
         *   <li>单玩家游戏：通常不会切换</li>
         *   <li>集成服务器：可能在不同阶段切换</li>
         *   <li>存档切换：一定会切换（因为 server 对象不同）</li>
         * </ul>
         *
         * @param server 当前的 MinecraftServer 实例
         * @return WorldStorage 单例
         */
        public static WorldStorage get(MinecraftServer server) {
            if (instance == null) {
                LOGGER.info("Creating new WorldStorage instance (first time)");
                instance = new WorldStorage(server);
                return instance;
            }
            
            if (instance.server != server) {
                // 🔴 P0修复：服务器切换时自动保存脏数据，防止数据丢失
                LOGGER.warn("Server switching detected! Saving dirty data before creating new instance...");
                
                try {
                    int dirtyCount = instance.dirtyKeys.size();
                    if (dirtyCount > 0) {
                        LOGGER.info("Saving {} dirty entries from old server instance", dirtyCount);
                        instance.saveAllDirty();  // 强制同步保存所有脏数据
                    }
                    
                    // 清理旧缓存释放内存
                    instance.cleanupIdle();
                    
                    LOGGER.info("Old data saved successfully, creating new WorldStorage");
                } catch (Exception e) {
                    LOGGER.error("Failed to save old server data during switch! Data loss may occur!", e);
                    // 继续创建新实例，但记录错误日志
                }
                
                instance = new WorldStorage(server);
            }
            
            return instance;
        }

        /**
         * 计算 UUID 对应的分片索引
         * 
         * <p>使用 UUID 的最低8位作为分片索引，确保均匀分布。</p>
         *
         * @param uuid 目标 UUID
         * @return 分片索引 (0-255)
         */
        private int shardIndex(UUID uuid) {
            return (int) (uuid.getLeastSignificantBits() & SHARD_MASK);
        }

        /**
         * 获取 UUID 对应的文件路径（分片目录结构）
         * 
         * <p>格式：{@code data/living_chests/<xx>/<uuid>.dat}</p>
         *
         * @param uuid 目标 UUID
         * @return 文件的完整路径
         */
        private Path getFilePath(UUID uuid) {
            return storageDir.resolve(String.format("%02x", shardIndex(uuid)))
                .resolve(uuid.toString() + ".dat");
        }

        /**
         * 获取 UUID 对应的旧版文件路径（无分片）
         * 
         * <p>用于兼容旧版本的数据迁移。</p>
         *
         * @param uuid 目标 UUID
         * @return 旧格式的文件路径
         */
        private Path getLegacyFilePath(UUID uuid) {
            return storageDir.resolve(uuid.toString() + ".dat");
        }

        /**
         * 迁移旧版本的文件到新的分片目录结构
         * 
         * <h3>迁移规则</h3>
         * <ul>
         *   <li>扫描根目录下所有的 .dat 文件</li>
         *   <li>解析文件名为 UUID</li>
         *   <li>移动到对应的分片子目录</li>
         *   <li>只执行一次（通过 migrated 标志位控制）</li>
         * </ul>
         * 
         * <h3>容错性</h3>
         * <ul>
         *   <li>无效的文件名（不是 UUID 格式）会被跳过</li>
         *   <li>IO 异常会被静默忽略</li>
         *   <li>目标文件已存在时会覆盖（REPLACE_EXISTING）</li>
         * </ul>
         */
        private void migrateLegacyFiles() {
            if (migrated) return;
            migrated = true;
            try (var stream = Files.list(storageDir)) {
                stream.filter(p -> p.toString().endsWith(".dat") && Files.isRegularFile(p))
                    .forEach(p -> {
                        String name = p.getFileName().toString();
                        String uuidStr = name.substring(0, name.length() - 4);
                        try {
                            UUID uuid = UUID.fromString(uuidStr);
                            Path target = getFilePath(uuid);
                            Files.createDirectories(target.getParent());
                            Files.move(p, target, StandardCopyOption.REPLACE_EXISTING);
                        } catch (IllegalArgumentException | IOException ignored) {}
                    });
            } catch (IOException ignored) {}
        }

        /**
         * 获取或创建指定 UUID 的物品列表
         * 
         * <h3>三级查找策略</h3>
         * <ol>
         *   <li><strong>缓存命中</strong>：直接返回缓存的引用（更新访问时间）</li>
         *   <li><strong>磁盘加载</strong>：从 .dat 文件反序列化</li>
         *   <li><strong>新建空列表</strong>：首次使用，创建指定容量的空槽位</li>
         * </ol>
         * 
         * <h3>⚠️ 引用语义</h3>
         * <p><strong>重要：</strong>返回的是内部列表的<strong>直接引用</strong>！
         * 修改返回的列表会直接影响缓存中的数据。
         * 这是有意为之的设计，可以避免不必要的拷贝开销。</p>
         * 
         * <h3>副作用</h3>
         * <ul>
         *   <li>会将条目放入缓存（LRU 尾部）</li>
         *   <li>可能触发缓存淘汰（如果超出 MAX_CACHE_SIZE）</li>
         *   <li>可能触发旧数据迁移（首次调用时）</li>
         * </ul>
         *
         * @param uuid 目标 UUID
         * @param capacity 每个虚拟箱子的槽数量（仅在新建设计时使用）
         * @return 物品列表的可修改引用
         */
        public List<ItemStack> getOrCreate(UUID uuid, int capacity) {
            migrateLegacyFiles();

            CachedStorage cached;
            synchronized (cache) {
                cached = cache.get(uuid);
            }
            if (cached != null) {
                cached.lastAccess = System.currentTimeMillis();
                return cached.items;
            }

            List<ItemStack> items = loadFromDisk(uuid);
            if (items == null) {
                LOGGER.debug("Creating new empty storage for UUID={}, capacity={}", uuid, capacity);
                items = createEmptySlots(capacity);
            } else {
                LOGGER.debug("Loaded storage from disk for UUID={}, slots={}", uuid, items.size());
            }

            putCache(uuid, items, false);
            return items;
        }

        /**
         * 检查指定 UUID 是否存在（缓存或磁盘）
         *
         * @param uuid 目标 UUID
         * @return true 如果存在
         */
        public boolean contains(UUID uuid) {
            synchronized (cache) {
                if (cache.containsKey(uuid)) return true;
            }
            return Files.exists(getFilePath(uuid));
        }

        /**
         * 移除指定 UUID 的所有数据
         * 
         * <p>会同时清除缓存和磁盘文件。</p>
         *
         * @param uuid 要移除的 UUID
         */
        public void remove(UUID uuid) {
            synchronized (cache) {
                cache.remove(uuid);
            }
            dirtyKeys.remove(uuid);
            try {
                Files.deleteIfExists(getFilePath(uuid));
            } catch (IOException ignored) {}
        }

        /**
         * 标记指定 UUID 的数据为脏数据
         * 
         * <h3>脏标记机制</h3>
         * <p>脏标记表示该 UUID 对应的数据已被修改但尚未写入磁盘。
         * 通过独立集合 {@code dirtyKeys} 追踪，优化 {@code saveAllDirty()} 的性能。</p>
         * 
         * <h3>何时调用</h3>
         * <ul>
         *   <li>insertItem() 后：物品被插入</li>
         *   <li>extractItem() 后：物品被取出</li>
         *   <li>syncMergedToStorage() 后：GUI 操作同步</li>
         * </ul>
         * 
         * <h3>⚠️ 注意事项</h3>
         * <p>如果 UUID 不在缓存中，会输出警告日志但不抛异常。
         * 这可能是正常的（例如刚被 cleanupIdle 卸载）。</p>
         *
         * @param uuid 被修改的 UUID
         */
        public void markDirty(UUID uuid) {
            CachedStorage cached;
            synchronized (cache) {
                cached = cache.get(uuid);
            }
            if (cached != null) {
                cached.dirty = true;
                dirtyKeys.add(uuid);
                
                pendingAsyncSave.add(uuid);
                
                LOGGER.debug("Marked dirty: UUID={}, cacheSize={}, dirtyKeys={}, asyncPending={}", 
                           uuid, cache.size(), dirtyKeys.size(), pendingAsyncSave.size());
                
                triggerAsyncSave();
            } else {
                LOGGER.warn("markDirty called but UUID={} not in cache!", uuid);
            }
        }

        /**
         * 🔴 P0修复：触发异步保存（非阻塞）
         * 
         * <h3>设计原理</h3>
         * <ul>
         *   <li><strong>非阻塞</strong>：立即返回，不等待IO完成</li>
         *   <li><strong>批量处理</strong>：将多个脏数据合并为一次写入</li>
         *   <li><strong>顺序保证</strong>：单线程执行器确保写入顺序</li>
         * </ul>
         * 
         * <h3>调用时机</h3>
         * <p>每次 {@code markDirty()} 后自动调用，也可以手动调用。</p>
         */
        private void triggerAsyncSave() {
            if (asyncShutdown || pendingAsyncSave.isEmpty()) return;
            
            // 🔴 关键修复：如果已有异步保存任务正在执行，跳过本次触发
            // 防止大量 markDirty 调用导致任务堆积淹没单线程执行器
            if (!asyncSaveInProgress.compareAndSet(false, true)) {
                return;
            }
            
            int pendingCount = pendingAsyncSave.size();
            long now = System.currentTimeMillis();
            
            if (pendingCount < ASYNC_SAVE_MIN_BATCH) {
                long elapsed = now - lastAsyncSaveTime;
                if (elapsed < ASYNC_SAVE_DEBOUNCE_MS) {
                    asyncSaveInProgress.set(false);  // 恢复标志
                    return;
                }
            }
            
            lastAsyncSaveTime = now;
            
            // 快照当前待保存的UUID集合（避免并发修改）
            final Set<UUID> toSave = new HashSet<>(pendingAsyncSave);
            pendingAsyncSave.clear();
            
            // 提交到异步线程（非阻塞！）
            ioExecutor.submit(() -> {
                try {
                    int savedCount = 0;
                    for (UUID uuid : toSave) {
                        CachedStorage cached;
                        synchronized(cache) {
                            cached = cache.get(uuid);
                        }
                        
                        if (cached != null && cached.dirty) {
                            saveToDisk(uuid, cached.items);
                            synchronized(cache) {
                                cached.dirty = false;
                            }
                            savedCount++;
                            
                            // dirtyKeys 现在是 ConcurrentHashMap.newKeySet()，线程安全
                            dirtyKeys.remove(uuid);
                        }
                    }
                    
                    if (savedCount > 0) {
                        LOGGER.info("[AsyncIO] Saved {} entries asynchronously", savedCount);
                    }
                } catch (Exception e) {
                    LOGGER.error("[AsyncIO] Failed to save data asynchronously!", e);
                    // 失败时重新加入待保存队列（下次重试）
                    pendingAsyncSave.addAll(toSave);
                } finally {
                    // 🔴 关键修复：任务完成后重置标志，允许下一次触发
                    asyncSaveInProgress.set(false);
                }
            });
        }

        /**
         * 将所有脏数据写入磁盘
         * 
         * <h3>触发时机</h3>
         * <ul>
         *   <li>{@code LevelEvent.Save}：主世界保存时（使用异步版本）</li>
         *   <li>{@code ServerStoppingEvent}：服务器停止时（强制同步）</li>
         * </ul>
         * 
         * <h3>🔴 P0修复：双模式设计</h3>
         * <table border="1">
         *   <tr><th>模式</th><th>方法</th><th>阻塞</th><th>用途</th></tr>
         *   <tr><td>异步</td><td>{@link #asyncSaveAllDirty()}</td><td>❌ 不阻塞</td><td>正常游戏运行时</td></tr>
         *   <tr><td>同步</td><td>{@link #saveAllDirtySync()}</td><td>✅ 阻塞</td><td>服务器停止时</td></tr>
         * </table>
         * 
         * <h3>性能优化</h3>
         * <p>只遍历 {@code dirtyKeys} 集合，而非全部缓存条目。
         * 时间复杂度：O(dirtyCount) 而非 O(cacheSize)。</p>
         * 
         * <h3>原子性保证</h3>
         * <p>即使中途失败，已保存的数据不会丢失（每个文件独立写入）。
         * 未保存的脏数据会在下次调用时继续尝试。</p>
         */
        public void saveAllDirty() {
            // 🔴 P0修复：默认使用异步保存（不阻塞主线程）
            asyncSaveAllDirty();
        }

        /**
         * 🔴 P0修复：异步保存所有脏数据（不阻塞主线程）
         * 
         * <p><strong>推荐在正常运行时使用此方法！</strong></p>
         * 
         * @see #saveAllDirtySync() 服务器停止时请用同步版本
         */
        public void asyncSaveAllDirty() {
            if (dirtyKeys.isEmpty()) return;
            
            // 将所有脏数据加入异步队列
            pendingAsyncSave.addAll(dirtyKeys);
            
            // 触发异步保存
            triggerAsyncSave();
            
            LOGGER.debug("Queued {} entries for async saving", dirtyKeys.size());
        }

        /**
         * 🔴 P0修复：同步保存所有脏数据（阻塞直到完成）
         * 
         * <h3>⚠️ 使用场景</h3>
         * <p><strong>仅用于服务器停止前！</strong></p>
         * <ul>
         *   <li>{@code ServerStoppingEvent} 事件处理中</li>
         *   <li>确保所有数据都已持久化后才允许服务器关闭</li>
         * </ul>
         * 
         * <h3>执行流程</h3>
         * <ol>
         *   <li>先等待所有异步任务完成（防止并发冲突）</li>
         *   <li>然后同步保存剩余的脏数据</li>
         *   <li>最后关闭异步执行器</li>
         * </ol>
         *
         * @see #asyncSaveAllDirty() 正常运行时请用异步版本
         */
        public void saveAllDirtySync() {
            // 1. 先停止接受新的异步任务
            asyncShutdown = true;
            
            try {
                // 2. 将 pendingAsyncSave 中的任务也加入 dirtyKeys（防止遗漏）
                if (!pendingAsyncSave.isEmpty()) {
                    dirtyKeys.addAll(pendingAsyncSave);
                    pendingAsyncSave.clear();
                    LOGGER.info("[Sync] Merged {} pending async saves into dirtyKeys", dirtyKeys.size());
                }
                
                // 3. 等待当前正在执行的异步任务完成（最多等待 5 秒）
                ioExecutor.shutdown();
                if (!ioExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    LOGGER.warn("Async IO executor did not terminate in 5s! Forcing shutdown...");
                    ioExecutor.shutdownNow();
                    // shutdownNow 后不再等待，直接执行紧急保存
                }
                
                // 4. 同步保存剩余的脏数据
                if (!dirtyKeys.isEmpty()) {
                    LOGGER.info("[Sync] Saving {} remaining dirty entries before shutdown...", dirtyKeys.size());
                    
                    synchronized (cache) {
                        for (UUID uuid : new ArrayList<>(dirtyKeys)) {
                            CachedStorage cached = cache.get(uuid);
                            if (cached != null && cached.dirty) {
                                saveToDisk(uuid, cached.items);
                                cached.dirty = false;
                            }
                        }
                    }
                    
                    dirtyKeys.clear();
                    LOGGER.info("[Sync] All data saved successfully");
                }
                
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOGGER.error("[Sync] Interrupted while waiting for async tasks! Data may be lost!", e);
                emergencySave();
            } finally {
                asyncShutdown = false;
            }
        }

        /**
         * 🔴 P0修复：紧急保存（最后的防线）
         * 
         * <p>在异常情况下尝试保存尽可能多的数据。
         * 即使失败也不会抛异常（避免阻止服务器关闭）。</p>
         */
        private void emergencySave() {
            LOGGER.warn("[Emergency] Attempting emergency save of {} dirty entries...", dirtyKeys.size());
            
            int saved = 0;
            int failed = 0;
            
            synchronized (cache) {
                for (UUID uuid : new ArrayList<>(dirtyKeys)) {
                    try {
                        CachedStorage cached = cache.get(uuid);
                        if (cached != null && cached.dirty) {
                            saveToDisk(uuid, cached.items);
                            saved++;
                        }
                    } catch (Exception e) {
                        failed++;
                        LOGGER.error("[Emergency] Failed to save UUID={}", uuid, e);
                    }
                }
            }
            
            LOGGER.warn("[Emergency] Save complete: {} saved, {} failed out of {}", saved, failed, dirtyKeys.size());
        }

        /**
         * 清理超时的缓存条目
         * 
         * <h3>淘汰条件</h3>
         * <ul>
         *   <li>超过 UNLOAD_TIMEOUT_MS（5分钟）未被访问</li>
         *   <li>缓存大小超过 MAX_CACHE_SIZE（200）</li>
         * </ul>
         * 
         * <h3>淘汰前的安全措施</h3>
         * <p>对于脏数据，会先调用 {@code saveToDisk()} 确保数据持久化，
         * 然后才从缓存中移除。</p>
         * 
         * <h3>调用频率</h3>
         * <ul>
         *   <li>{@code putCache()} 中每20次调用触发一次</li>
         *   <li>{@code onLevelSave} 中主动调用</li>
         *   <li>也可以手动调用</li>
         * </ul>
         */
        public void cleanupIdle() {
            // 收集需要淘汰的 UUID 和需要保存的脏数据（在锁外执行磁盘IO）
            List<UUID> toUnload = new ArrayList<>();
            List<Map.Entry<UUID, CachedStorage>> dirtyToSave = new ArrayList<>();

            synchronized (cache) {
                long now = System.currentTimeMillis();

                for (Map.Entry<UUID, CachedStorage> entry : cache.entrySet()) {
                    if (now - entry.getValue().lastAccess > UNLOAD_TIMEOUT_MS) {
                        toUnload.add(entry.getKey());
                    }
                }

                for (UUID uuid : toUnload) {
                    CachedStorage cached = cache.get(uuid);
                    if (cached != null && cached.dirty) {
                        dirtyToSave.add(new java.util.AbstractMap.SimpleEntry<>(uuid, cached));
                    }
                    cache.remove(uuid);
                    dirtyKeys.remove(uuid);
                }

                while (cache.size() > MAX_CACHE_SIZE) {
                    Map.Entry<UUID, CachedStorage> eldest = cache.entrySet().iterator().next();
                    UUID eldestKey = eldest.getKey();
                    CachedStorage eldestValue = eldest.getValue();
                    if (eldestValue != null && eldestValue.dirty) {
                        dirtyToSave.add(new java.util.AbstractMap.SimpleEntry<>(eldestKey, eldestValue));
                    }
                    cache.remove(eldestKey);
                    dirtyKeys.remove(eldestKey);
                }
            }

            // 在锁外执行磁盘IO，避免阻塞异步线程
            for (Map.Entry<UUID, CachedStorage> entry : dirtyToSave) {
                saveToDisk(entry.getKey(), entry.getValue().items);
            }
        }

        public Set<UUID> getAllUuidsOnDisk() {
            Set<UUID> result = new HashSet<>();
            if (!Files.exists(storageDir)) return result;

            try (var shardDirs = Files.list(storageDir)) {
                var dirs = shardDirs.filter(Files::isDirectory).toList();

                for (Path shardDir : dirs) {
                    try (var files = Files.list(shardDir)) {
                        var datFiles = files.filter(p -> p.toString().endsWith(".dat") && Files.isRegularFile(p)).toList();

                        for (Path file : datFiles) {
                            String name = file.getFileName().toString();
                            String uuidStr = name.substring(0, name.length() - 4);
                            try {
                                result.add(UUID.fromString(uuidStr));
                            } catch (IllegalArgumentException ignored) {}
                        }
                    }
                }
            } catch (IOException e) {
                LOGGER.warn("Failed to scan UUID files on disk: {}", e.getMessage());
            }

            return result;
        }

        public boolean hasFileOnDisk(UUID uuid) {
            return Files.exists(getFilePath(uuid));
        }

        /**
         * 被动清理孤儿文件：删除磁盘上不再被任何活物品引用的空数据文件。
         * 
         * <h3>清理策略</h3>
         * <ol>
         *   <li>扫描所有分片子目录中的 .dat 文件</li>
         *   <li>跳过缓存中仍活跃的 UUID（正在使用中）</li>
         *   <li>读取文件内容，检查是否所有槽位都为空</li>
         *   <li>全空的文件直接删除（活物品再次引用时会自动重建）</li>
         * </ol>
         * 
         * <h3>安全性</h3>
         * <p>只删除全部槽位为空的文件。即使误删了仍被引用的文件，
         * 活物品在下一次 tick 时会通过 {@code createAndRegister()} 重建。</p>
         * 
         * <h3>调用频率</h3>
         * <p>通过 {@code CLEANUP_ORPHAN_INTERVAL} 控制，默认每 10 次 save 执行一次，
         * 避免频繁扫描磁盘。</p>
         */
        /**
         * 异步清理孤儿文件（不阻塞主线程）
         * 
         * <p>在 {@code LevelEvent.Save} 中调用此方法，
         * 避免磁盘扫描阻塞主线程。</p>
         */
        public void cleanupOrphanedFilesAsync() {
            if (!shouldRunCleanup()) return;
            if (!Files.exists(storageDir)) return;
            if (asyncShutdown) return;
            
            ioExecutor.submit(() -> {
                try {
                    cleanupOrphanedFilesInternal();
                } catch (Exception e) {
                    LOGGER.error("[AsyncIO] Failed to cleanup orphaned files!", e);
                }
            });
        }

        /**
         * 同步清理孤儿文件（阻塞直到完成）
         * 
         * <h3>⚠️ 使用场景</h3>
         * <p><strong>仅用于服务器停止前！</strong>正常运行时请用 {@link #cleanupOrphanedFilesAsync()}</p>
         */
        public void cleanupOrphanedFiles() {
            if (!shouldRunCleanup()) return;
            if (!Files.exists(storageDir)) return;
            
            cleanupOrphanedFilesInternal();
        }

        private boolean shouldRunCleanup() {
            cleanupOrphanCounter++;
            if (cleanupOrphanCounter < CLEANUP_ORPHAN_INTERVAL) {
                return false;
            }
            cleanupOrphanCounter = 0;
            return true;
        }

        private void cleanupOrphanedFilesInternal() {
            int deleted = 0;
            int checked = 0;
            
            try (var shardDirs = Files.list(storageDir)) {
                var dirs = shardDirs.filter(Files::isDirectory).toList();
                
                for (Path shardDir : dirs) {
                    try (var files = Files.list(shardDir)) {
                        var datFiles = files.filter(p -> p.toString().endsWith(".dat") && Files.isRegularFile(p)).toList();
                        
                        for (Path file : datFiles) {
                            checked++;
                            String name = file.getFileName().toString();
                            String uuidStr = name.substring(0, name.length() - 4);
                            
                            try {
                                UUID uuid = UUID.fromString(uuidStr);
                                
                                // 跳过缓存中活跃的 UUID
                                boolean inCache;
                                synchronized (cache) {
                                    inCache = cache.containsKey(uuid);
                                }
                                if (inCache) continue;
                                
                                // 快速检查文件大小：空文件非常小（< 300 字节）
                                long fileSize = Files.size(file);
                                if (fileSize > 300) continue;  // 有物品数据，跳过
                                
                                // 读取文件确认是否真的全空
                                if (isFileEmpty(file)) {
                                    Files.deleteIfExists(file);
                                    deleted++;
                                }
                            } catch (IllegalArgumentException e) {
                                // 无效 UUID 文件名，跳过
                            }
                        }
                    }
                }
            } catch (IOException e) {
                LOGGER.warn("Failed to scan orphaned files: {}", e.getMessage());
            }
            
            if (deleted > 0) {
                LOGGER.info("Cleaned up {} orphaned empty files (checked {} files)", deleted, checked);
            }
        }
        
        /**
         * 检查磁盘文件是否所有槽位都为空
         *
         * @param path 文件路径
         * @return true 如果所有槽位都是空的
         */
        private boolean isFileEmpty(Path path) {
            try {
                CompoundTag tag = NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap());
                ListTag itemsList = tag.getList("items", Tag.TAG_COMPOUND);
                
                for (int i = 0; i < itemsList.size(); i++) {
                    CompoundTag itemTag = itemsList.getCompound(i);
                    // 空的 CompoundTag 表示空槽位，有内容的标签会有字段
                    if (!itemTag.isEmpty()) {
                        return false;
                    }
                }
                return true;
            } catch (IOException e) {
                // 文件损坏，跳过
                return false;
            }
        }

        /**
         * 从磁盘加载指定 UUID 的物品数据
         * 
         * <h3>文件格式</h3>
         * <pre>
         * {
         *   "items": [
         *     {CompoundTag},  // 非 ItemStack.EMPTY
         *     {CompoundTag},  // ...
         *     {}              // 空 CompoundTag 表示 ItemStack.EMPTY
         *   ]
         * }
         * </pre>
         * 
         * <h3>错误处理</h3>
         * <ul>
         *   <li>文件不存在：返回 null（调用者会创建新数据）</li>
             *   <li>文件损坏：记录错误日志并返回 null</li>
         *   <li>IO 异常：记录错误日志并返回 null</li>
         * </ul>
         *
         * @param uuid 目标 UUID
         * @return 物品列表，null 表示文件不存在或读取失败
         */
        private List<ItemStack> loadFromDisk(UUID uuid) {
            Path path = getFilePath(uuid);
            if (!Files.exists(path)) return null;

            try {
                CompoundTag tag = NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap());
                ListTag itemsList = tag.getList("items", Tag.TAG_COMPOUND);
                var provider = server.registryAccess();
                List<ItemStack> items = new ArrayList<>();

                for (int i = 0; i < itemsList.size(); i++) {
                    CompoundTag itemTag = itemsList.getCompound(i);
                    if (itemTag.isEmpty()) {
                        items.add(ItemStack.EMPTY);
                    } else {
                        items.add(ItemStack.parseOptional(provider, itemTag));
                    }
                }
                LOGGER.debug("Loaded {} items from disk for UUID={}", items.size(), uuid);
                return items;
            } catch (IOException e) {
                LOGGER.error("Failed to load living chest data for UUID={}, path={}", uuid, path, e);
                return null;
            }
        }

        /**
         * 创建指定容量的空槽位列表
         *
         * @param capacity 槽位数量
         * @return 全部为 ItemStack.EMPTY 的列表
         */
        private static List<ItemStack> createEmptySlots(int capacity) {
            List<ItemStack> slots = new ArrayList<>(capacity);
            for (int i = 0; i < capacity; i++) {
                slots.add(ItemStack.EMPTY);
            }
            return slots;
        }

        /**
         * 将物品列表写入磁盘（原子操作）
         * 
         * <h3>原子写入保证</h3>
         * <ol>
         *   <li>先写入临时文件 {@code xxx.tmp}</li>
         *   <li>再原子替换为目标文件 {@code xxx.dat}</li>
         *   <li>如果 ATOMIC_MOVE 失败，降级为普通移动</li>
         * </ol>
         * 
         * <h3>🔴 关键 Bug 修复 (2024)</h3>
         * <p><strong>必须使用 {@code ItemStack.save()} 的返回值！</strong></p>
         * <p>
         * 在 NeoForge 1.21.1 中，{@code ItemStack.save(Provider, Tag)} 内部使用
         * {@code Codec.encode()} → {@code NbtOps.mergeToMap()}，而 mergeToMap()
         * 会创建浅拷贝（shallowCopy），<strong>不会修改传入的 tag 参数</strong>！
         * </p>
         * 
         * <pre>{@code
         * // ❌ 错误代码（导致数据丢失）
         * CompoundTag itemTag = new CompoundTag();
         * stack.save(provider, itemTag);  // 返回值被忽略！
         * itemsList.add(itemTag);         // itemTag 是空的！
         * 
         * // ✅ 正确代码（修复后）
         * Tag saved = stack.save(provider, new CompoundTag());  // 使用返回值
         * itemsList.add(saved);                                // saved 包含实际数据
         * }</pre>
         * 
         * <h3>文件内容示例</h3>
         * <pre>
         * {
         *   "items": [
         *     {"id":"minecraft:diamond","count":64,"components":{}},
         *     {"id":"minecraft:iron_ingot","count":32,"components":{}},
         *     {},  // 空槽位
         *     ...  // 共 capacity 个元素
         *   ]
         * }
         * </pre>
         *
         * @param uuid 目标 UUID
         * @param items 要保存的物品列表
         */
        private void saveToDisk(UUID uuid, List<ItemStack> items) {
            CompoundTag tag = new CompoundTag();
            ListTag itemsList = new ListTag();
            var provider = server.registryAccess();

            for (ItemStack stack : items) {
                if (!stack.isEmpty()) {
                    Tag saved = stack.save(provider, new CompoundTag());
                    itemsList.add(saved);
                } else {
                    itemsList.add(new CompoundTag());
                }
            }
            tag.put("items", itemsList);

            try {
                Path path = getFilePath(uuid);
                Path tmpPath = path.resolveSibling(uuid + ".tmp");
                Files.createDirectories(path.getParent());
                NbtIo.writeCompressed(tag, tmpPath);
                try {
                    Files.move(tmpPath, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException fallback) {
                    Files.move(tmpPath, path, StandardCopyOption.REPLACE_EXISTING);
                }
                LOGGER.debug("Saved living chest data for UUID={}, items={}", uuid, items.size());
            } catch (IOException e) {
                LOGGER.error("Failed to save living chest data for UUID={}", uuid, e);
            }
        }

        /**
         * 创建新的 UUID 并注册空存储
         * 
         * <h3>用途</h3>
         * <ul>
         *   <li>首次使用活箱子时创建初始虚拟箱子</li>
         *   <li>拆分活箱子时增加虚拟箱子数量</li>
         * </ul>
         *
         * @param server Minecraft 服务器实例
         * @param capacity 每个虚拟箱子的槽数量
         * @return 新创建的 UUID
         */
        public static UUID createAndRegister(MinecraftServer server, int capacity, String source) {
            UUID uuid = UUID.randomUUID();
            WorldStorage storage = get(server);
            List<ItemStack> emptySlots = createEmptySlots(capacity);
            storage.putCache(uuid, emptySlots, true);
            LOGGER.info("[{}] Created new UUID={} with capacity={}", source, uuid, capacity);
            return uuid;
        }

        /**
         * 将数据放入缓存
         * 
         * <h3>LRU 行为</h3>
         * <p>由于使用 accessOrder=true 的 LinkedHashMap，
         * put 操作会将条目移到链表尾部（最近访问位置）。</p>
         * 
         * <h3>容量控制</h3>
         * <p>每20次 put 操作会触发一次 {@code cleanupIdle()}，
         * 避免频繁检查影响性能。</p>
         *
         * @param uuid 目标 UUID
         * @param items 物品列表
         * @param dirty 是否标记为脏数据
         */
        private void putCache(UUID uuid, List<ItemStack> items, boolean dirty) {
            CachedStorage cached = new CachedStorage();
            cached.items = items;
            cached.dirty = dirty;
            cached.lastAccess = System.currentTimeMillis();
            synchronized (cache) {
                cache.put(uuid, cached);
            }
            if (dirty) {
                dirtyKeys.add(uuid);
            }

            if (cache.size() % 20 == 0) {
                cleanupIdle();
            }
        }
    }

    /**
     * 缓存数据包装类 (Cached Storage Entry)
     * 
     * <p>封装单个 UUID 的缓存数据，包括物品列表、脏标记和访问时间戳。</p>
     */
    private static class CachedStorage {
        /** 物品列表（可修改的引用） */
        List<ItemStack> items;
        
        /** 是否被修改但尚未保存到磁盘 */
        boolean dirty;
        
        /** 最后一次访问的时间戳（毫秒），用于超时淘汰 */
        long lastAccess;
    }
}