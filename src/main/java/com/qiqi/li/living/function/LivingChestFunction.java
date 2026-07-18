package com.qiqi.li.living.function;

import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import com.qiqi.li.living.core.ComponentState;
import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.core.LivingFunctionConfig;
import com.qiqi.li.living.core.components.InternalStorageComponent;
import com.qiqi.li.living.BaseLivingFunction;
import com.qiqi.li.living.LivingItemManager;
import net.minecraft.nbt.CompoundTag;

/**
 * 活箱子功能 (Living Chest Function)
 * 
 * <h2>功能概述</h2>
 * <p>
 * 活箱子是一种特殊的活物品，它将普通箱子的物品存储功能<strong>虚拟化</strong>到独立的外部文件中。
 * 每个活箱子实例可以包含多个"虚拟箱子槽位"，每个槽位对应一个独立的 UUID 和磁盘文件。
 * </p>
 * 
 * <h2>核心特性</h2>
 * <ul>
 *   <li><strong>无限存储</strong>：通过堆叠活箱子可以线性增加存储容量（每个堆叠 = 1个虚拟箱子）</li>
 *   <li><strong>独立持久化</strong>：数据存储在存档目录的独立文件中，不依赖方块实体</li>
 *   <li><strong>可移动性</strong>：作为 ItemStack 存在，可以在容器间自由移动</li>
 *   <li><strong>自动同步</strong>：tick 时自动调整虚拟箱子数量以匹配堆叠数</li>
 * </ul>
 * 
 * <h2>使用示例</h2>
 * <pre>{@code
 * // 判断是否为活箱子
 * if (LivingChestFunction.isLivingChest(stack)) {
 *     // 存入物品
 *     boolean success = LivingChestFunction.insertItem(server, chestStack, diamond, 27);
 *     
 *     // 取出物品
 *     ItemStack extracted = LivingChestFunction.extractItem(server, chestStack, 64, 27);
 *     
 *     // 获取 UUID 列表
 *     List<UUID> uuids = LivingChestFunction.getUuids(chestStack);
 * }
 * }</pre>
 * 
 * <h2>架构位置</h2>
 * <pre>
 * 用户操作 → ItemTransferComponent → LivingChestFunction → InternalStorageComponent → WorldStorage
 *              (传输逻辑)          (功能入口)           (核心组件)            (存储管理器)
 * </pre>
 * 
 * <h2>⚠️ 重要注意事项</h2>
 * <ul>
 *   <li><strong>状态保存</strong>：所有公开方法都会自动调用 {@code saveStorageState()}，
 *       确保修改后的状态写回 ItemStack 的 NBT</li>
 *   <li><strong>线程安全</strong>：所有方法必须在服务端主线程中调用</li>
 *   <li><strong>容量计算</strong>：每个虚拟箱子固定为 27 槽（原版箱子大小），
 *       不随所在容器变化</li>
 * </ul>
 * 
 * @author Living Item Mod Team
 * @version 2024.12
 * @see InternalStorageComponent 内部存储组件实现
 * @see BaseLivingFunction 基类，提供 tick 编排
 */
public class LivingChestFunction extends BaseLivingFunction {

    /** 功能 ID，用于在 NBT 中标识此功能 */
    public static final String ID = "living_chest";

    /** 每个虚拟箱子的槽位数（固定为原版箱子大小 27） */
    public static final int CHEST_SLOTS = 27;

    /** 功能配置：只包含 InternalStorageComponent 组件 */
    private static final LivingFunctionConfig CONFIG = new LivingFunctionConfig()
        .withFunctionId(ID)
        .withStackMultiplier(false)  // 不使用堆叠倍增器（每个堆叠 = 1个虚拟箱子）
        .withOrchestrator(null)      // 不需要自定义编排器
        .addComponent(new InternalStorageComponent());

    /**
     * 返回此功能的配置
     * 
     * @return 包含 InternalStorageComponent 的配置
     */
    @Override
    protected LivingFunctionConfig getConfig() { return CONFIG; }

    /**
     * 返回工具提示标题的翻译键
     * 
     * @return 翻译键字符串
     */
    @Override
    protected String getTooltipTitleKey() { return "tooltip.livingitem.chest.status"; }

    /**
     * 判断指定物品栈是否可以作为活箱子
     * 
     * <h3>条件</h3>
     * <ul>
     *   <li>必须是原版箱子（Items.CHEST）</li>
     *   <li>必须已被激活为活物品（LivingItemManager.isLivingItem）</li>
     * </ul>
     *
     * @param stack 要检查的物品栈
     * @return true 如果可以作为活箱子
     */
    @Override
    public boolean canApply(ItemStack stack) {
        return stack.is(Items.CHEST) && LivingItemManager.isLivingItem(stack);
    }

    /**
     * 返回此功能的功能 ID
     * 
     * @return "living_chest"
     */
    @Override
    public String getFunctionId() { return ID; }

    /**
     * 快速判断指定物品栈是否是活箱子
     * 
     * <p>便捷方法，等同于 {@code canApply(stack)}。</p>
     *
     * @param stack 要检查的物品栈
     * @return true 如果是活箱子
     */
    public static boolean isLivingChest(ItemStack stack) {
        return stack.is(Items.CHEST) && LivingItemManager.isLivingItem(stack);
    }

    /**
     * 获取每个虚拟箱子的槽位数（固定为 27，即原版箱子大小）
     * 
     * <p>活箱子的容量不随所在容器变化，始终为原版箱子标准大小。</p>
     *
     * @param ctx 容器上下文（未使用，保留参数兼容性）
     * @return 固定值 27
     */
    public static int getCapacity(ContainerContext ctx) {
        return CHEST_SLOTS;
    }

    /**
     * 获取活箱子的 UUID 列表
     * 
     * <h3>UUID 含义</h3>
     * <p>每个 UUID 对应一个虚拟箱子，也就是一组独立的物品槽位。
     * UUID 的数量应该等于活箱子的堆叠数量。</p>
     * 
     * <h3>用途</h3>
     * <ul>
     *   <li>调试和诊断</li>
     *   <li>GUI 显示</li>
     *   <li>高级操作（直接访问特定虚拟箱子）</li>
     * </ul>
     *
     * @param stack 活箱子物品栈
     * @return UUID 列表的副本（可能为空列表）
     */
    public static List<UUID> getUuids(ItemStack stack) {
        ComponentState state = getStorageState(stack);
        return InternalStorageComponent.getUuids(state);
    }

    /**
     * 获取合并后的所有虚拟箱子物品列表
     * 
     * <h3>返回格式</h3>
     * <p>扁平化的物品列表，顺序为：
     * [第一个箱子的0号槽, 第一个箱子的1号槽, ..., 第二个箱子的0号槽, ...]</p>
     * 
     * <h3>性能注意</h3>
     * <p>此方法会触发所有相关 UUID 的缓存加载。
     * 对于大量虚拟箱子的情况可能会产生 I/O 开销。</p>
     *
     * @param server Minecraft 服务器实例
     * @param stack 活箱子物品栈
     * @param capacityPerChest 每个虚拟箱子的槽数量
     * @return 合并后的物品列表（只读副本）
     */
    public static List<ItemStack> getMergedStorage(MinecraftServer server, ItemStack stack, int capacityPerChest) {
        ComponentState state = getStorageState(stack);
        return InternalStorageComponent.getMergedStorage(server, state, capacityPerChest);
    }

    /**
     * 将合并后的物品列表同步回各个虚拟箱子
     * 
     * <h3>典型场景</h3>
     * <ul>
     *   <li>GUI 操作后保存用户修改</li>
     *   <li>批量导入/导出物品</li>
     *   <li>程序化修改物品数据</li>
     * </ul>
     * 
     * <h3>⚠️ 自动保存</h3>
     * <p>此方法会自动调用 {@code saveStorageState()}，
     * 无需在外层再次调用。</p>
     *
     * @param server Minecraft 服务器实例
     * @param stack 活箱子物品栈（会被更新）
     * @param merged 合并后的物品列表（来自 GUI 或其他来源）
     * @param capacityPerChest 每个虚拟箱子的槽数量
     */
    public static void syncMergedToStorage(MinecraftServer server, ItemStack stack,
                                           List<ItemStack> merged, int capacityPerChest) {
        ComponentState state = getStorageState(stack);
        InternalStorageComponent.syncMergedToStorage(server, state, merged, capacityPerChest);
        saveStorageState(stack, state);  // ← 自动保存状态！
    }

    /**
     * 向活箱子存入物品
     * 
     * <h3>完整流程</h3>
     * <ol>
     *   <li>从 ItemStack 的 NBT 加载当前组件状态</li>
     *   <li>调用 InternalStorageComponent 执行实际的插入逻辑</li>
     *   <li>将更新后的状态写回 ItemStack 的 NBT</li>
     *   <li>返回是否完全存入</li>
     * </ol>
     * 
     * <h3>副作用</h3>
     * <ul>
     *   <li>会修改 {@code itemToInsert} 参数（减少其数量或清零）</li>
     *   <li>会修改 {@code chestStack} 的 NBT 数据（更新 UUID 列表）</li>
     *   <li>会修改 WorldStorage 中的缓存数据</li>
     *   <li>会标记相关的 UUID 为脏数据</li>
     * </ul>
     * 
     * <h3>返回值含义</h3>
     * <ul>
     *   <li>true：物品被完全存入（itemToInsert.count == 0）</li>
     *   <li>false：物品部分存入或未存入（活箱子已满）</li>
     * </ul>
     *
     * @param server Minecraft 服务器实例
     * @param chestStack 目标活箱子物品栈（会被更新）
     * @param itemToInsert 要存入的物品（会被修改）
     * @param capacityPerChest 每个虚拟箱子的槽数量（通常是 27 或 54）
     * @return true 表示物品完全存入
     */
    public static boolean insertItem(MinecraftServer server, ItemStack chestStack,
                                     ItemStack itemToInsert, int capacityPerChest) {
        ComponentState state = getStorageState(chestStack);
        boolean result = InternalStorageComponent.insertItem(server, state, itemToInsert, capacityPerChest, chestStack.getCount());
        saveStorageState(chestStack, state);  // ← 必须保存状态！
        return result;
    }

    /**
     * 从活箱子取出物品（不限类型）
     * 
     * <h3>提取策略</h3>
     * <ol>
     *   <li>按 UUID 列表顺序遍历虚拟箱子</li>
     *   <li>在每个箱子中按槽位顺序查找非空物品</li>
     *   <li>优先提取第一个遇到的物品类型</li>
     *   <li>后续只提取相同类型的物品（用于堆叠）</li>
     * </ol>
     * 
     * <h3>⚠️ 自动保存</h3>
     * <p>此方法会自动调用 {@code saveStorageState()}，
     * 确保取出的物品不会在下次 tick 时被恢复。</p>
     * 
     * <h3>返回值示例</h3>
     * <pre>{@code
     * // 正常情况
     * extractItem(server, chestStack, 64, 27)
     * → ItemStack{diamond, count=64}
     * 
     * // 数量不足
     * extractItem(server, chestStack, 1000, 27)
     * → ItemStack{diamond, count=32}  // 只有32个钻石
     * 
     * // 空箱子
     * extractItem(server, emptyChestStack, 64, 27)
     * → ItemStack.EMPTY
     * }</pre>
     *
     * @param server Minecraft 服务器实例
     * @param chestStack 源活箱子物品栈（会被更新）
     * @param amount 最大提取数量
     * @param capacityPerChest 每个虚拟箱子的槽数量
     * @return 提取的物品栈（可能为 EMPTY）
     */
    public static ItemStack extractItem(MinecraftServer server, ItemStack chestStack,
                                        int amount, int capacityPerChest) {
        ComponentState state = getStorageState(chestStack);
        ItemStack result = InternalStorageComponent.extractItem(server, state, amount, capacityPerChest, chestStack.getCount());
        saveStorageState(chestStack, state);  // ← 必须保存状态！防止下次 tick 覆盖
        return result;
    }

    /**
     * 从活箱子取出指定类型的物品
     * 
     * <h3>与 {@link #extractItem(MinecraftServer, ItemStack, int, int)} 的区别</h3>
     * <p>增加了类型过滤参数，只会提取与 {@code target} 相同类型的物品。
     * 用于 GUI 中的"取出特定物品"按钮等场景。</p>
     * 
     * <h3>匹配规则</h3>
     * <p>使用 {@code ItemStack.isSameItemSameComponents()} 进行精确匹配，
     * 包括：</p>
     * <ul>
     *   <li>物品 ID（如 minecraft:diamond）</li>
     *   <li>NBT 数据（如附魔、耐久度等）</li>
     *   <li>Damage 值</li>
     * </ul>
     *
     * @param server Minecraft 服务器实例
     * @param chestStack 源活箱子物品栈（会被更新）
     * @param target 目标物品类型（用于过滤）
     * @param amount 最大提取数量
     * @param capacityPerChest 每个虚拟箱子的槽数量
     * @return 提取的物品栈（可能为 EMPTY 如果没有匹配的物品）
     */
    public static ItemStack extractItem(MinecraftServer server, ItemStack chestStack,
                                        ItemStack target, int amount, int capacityPerChest) {
        ComponentState state = getStorageState(chestStack);
        ItemStack result = InternalStorageComponent.extractItem(server, state, target, amount, capacityPerChest, chestStack.getCount());
        saveStorageState(chestStack, state);  // ← 必须保存状态！
        return result;
    }

    /**
     * 检查活箱子是否有存储数据
     * 
     * <p>用于判断活箱子是否已经被初始化过（即是否已经有 UUID 列表）。</p>
     *
     * @param stack 活箱子物品栈
     * @return true 如果有存储数据（UUID 列表非空）
     */
    public static boolean hasStorage(ItemStack stack) {
        return !getUuids(stack).isEmpty();
    }

    /**
     * 清空活箱子的存储数据（UUID 列表和已用槽位计数）。
     * 
     * <h3>用途</h3>
     * <p>当活箱子被放置为方块后，物品已转移到实体箱子中，需要清空活箱子的虚拟存储。
     * 此方法仅清空 NBT 层的 UUID 引用，不删除磁盘文件（遵循 UUID 只增不减原则）。</p>
     * 
     * <h3>与 {@link #dropAllItems} 的区别</h3>
     * <ul>
     *   <li>{@code dropAllItems}：掉落物品到世界 + 删除磁盘文件 + 清空 UUID（用于取消活化）</li>
     *   <li>{@code clearStorage}：只清空 NBT 层的 UUID 引用（用于方块放置后的清理）</li>
     * </ul>
     * 
     * <h3>⚠️ 注意事项</h3>
     * <p>调用此方法前，应确保虚拟箱子中的物品已转移到目标容器中。
     * 此方法不会删除磁盘文件，但会清空 ItemStack 上的 UUID 引用，
     * 使得活箱子变为"空箱子"状态。</p>
     *
     * @param chestStack 活箱子物品栈（会被修改）
     */
    public static void clearStorage(ItemStack chestStack) {
        if (!isLivingChest(chestStack)) return;
        ComponentState state = getStorageState(chestStack);
        InternalStorageComponent.saveUuids(state, List.of());
        state.setInt(InternalStorageComponent.KEY_USED_SLOTS, 0);
        saveStorageState(chestStack, state);
    }

    /**
     * 掉落活箱子中的所有物品并清理存储文件。
     * 
     * <p>在活箱子取消活化时调用，确保：
     * <ol>
     *   <li>所有虚拟箱子中的物品掉落到玩家脚下</li>
     *   <li>所有 UUID 对应的磁盘文件被删除</li>
     *   <li>组件状态被重置（清空 UUID 列表和已用槽位计数）</li>
     * </ol>
     * 
     * <p>如果箱子为空（无 UUID 或所有物品为空），则直接返回，不产生任何效果。</p>
     *
     * @param server Minecraft 服务器实例
     * @param chestStack 活箱子物品栈（会被修改）
     * @param player 掉落位置的玩家（物品掉落在玩家脚下）
     */
    public static void dropAllItems(MinecraftServer server, ItemStack chestStack, Player player) {
        if (!isLivingChest(chestStack)) return;

        ComponentState state = getStorageState(chestStack);
        List<UUID> uuids = InternalStorageComponent.getUuids(state);
        if (uuids.isEmpty()) return;

        InternalStorageComponent.WorldStorage storage = InternalStorageComponent.WorldStorage.get(server);
        Level level = player.level();
        BlockPos dropPos = player.blockPosition();
        int droppedCount = 0;

        // 遍历所有虚拟箱子，掉落物品
        for (UUID uuid : uuids) {
            List<ItemStack> chestItems = storage.getOrCreate(uuid, CHEST_SLOTS);
            for (ItemStack item : chestItems) {
                if (!item.isEmpty()) {
                    level.addFreshEntity(new ItemEntity(
                        level,
                        dropPos.getX() + 0.5,
                        dropPos.getY() + 0.5,
                        dropPos.getZ() + 0.5,
                        item.copy()));
                    droppedCount += item.getCount();
                }
            }
            // 删除磁盘文件
            storage.remove(uuid);
        }

        // 重置状态：清空 UUID 列表和已用槽位计数
        InternalStorageComponent.saveUuids(state, List.of());
        state.setInt(InternalStorageComponent.KEY_USED_SLOTS, 0);
        saveStorageState(chestStack, state);

        if (droppedCount > 0) {
            LivingItemManager.LOGGER.info("活箱子取消活化：掉落 {} 个物品，清理 {} 个 UUID 文件",
                droppedCount, uuids.size());
        }
    }

    /**
     * 从活箱子的 NBT 数据中加载内部存储状态
     * 
     * <h3>数据来源</h3>
     * <p>从 ItemStack 的 DataComponent → LivingFunctionData → living_chest → internal_storage
     * 路径加载 ComponentState。</p>
     * 
     * <h3>深拷贝保证</h3>
     * <p>返回的 ComponentState 是原始数据的深拷贝，修改它不会影响 ItemStack 中的数据。
     * 需要通过 {@code saveStorageState()} 显式写回。</p>
     * 
     * <h3>错误处理</h3>
     * <ul>
     *   <li>如果功能数据不存在：返回空的 ComponentState</li>
     *   <li>如果内部存储组件不存在：返回空的 ComponentState</li>
     *   <li>任何异常都不会抛出，而是返回默认值</li>
     * </ul>
     *
     * @param stack 活箱子物品栈
     * @return 内部存储组件的状态（深拷贝）
     */
    public static ComponentState getStorageState(ItemStack stack) {
        CompoundTag funcData = LivingItemManager.getFunctionData(stack, ID).copy();
        if (funcData == null || funcData.isEmpty()) {
            return new ComponentState();
        }
        CompoundTag storageTag = funcData.getCompound(InternalStorageComponent.ID).copy();
        return ComponentState.fromNBT(storageTag);
    }

    /**
     * 将内部存储状态保存回活箱子的 NBT 数据
     * 
     * <h3>保存路径</h3>
     * <p>ComponentState → internal_storage CompoundTag → living_chest CompoundTag →
     * LivingItemManager.setFunctionData() → ItemStack DataComponent</p>
     * 
     * <h3>何时调用</h3>
     * <ul>
     *   <li>{@code insertItem()} 之后：UUID 列表可能已变更</li>
     *   <li>{@code extractItem()} 之后：确保状态一致性</li>
     *   <li>{@code syncMergedToStorage()} 之后：GUI 操作已同步</li>
     * </ul>
     * 
     * <h3>⚠️ 为什么必须手动保存？</h3>
     * <p>因为 ComponentState 是从 NBT 的深拷贝创建的，修改它不会自动影响原始数据。
     * 必须显式调用此方法将修改写回 ItemStack。</p>
     * 
     * <h3>🔴 P0 修复：访问权限调整</h3>
     * <p>原为 {@code private}，现改为 package-private 以支持
     * {@link ChestTransaction} 事务管理器的回滚操作。</p>
     *
     * @param stack 目标活箱子物品栈（会被更新）
     * @param state 要保存的组件状态
     */
    public static void saveStorageState(ItemStack stack, ComponentState state) {
        CompoundTag funcData = LivingItemManager.getFunctionData(stack, ID).copy();
        funcData.put(InternalStorageComponent.ID, state.toNBT());
        LivingItemManager.setFunctionData(stack, ID, funcData);
    }
}