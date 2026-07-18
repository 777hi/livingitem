package com.qiqi.li.living.core.components;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.function.LivingChestFunction;
import com.qiqi.li.living.LivingItemManager;
import com.qiqi.li.living.core.ComponentConfig;
import com.qiqi.li.living.core.ComponentContext;
import com.qiqi.li.living.core.ComponentState;

/**
 * 物品传输组件 (Item Transfer Component)
 * 
 * <h2>功能概述</h2>
 * <p>
 * 实现活漏斗的核心传输逻辑，负责在容器内的不同槽位之间移动物品。
 * 支持普通物品传输和活箱子特殊传输两种模式。
 * </p>
 * 
 * <h2>核心职责</h2>
 * <ol>
 *   <li><strong>冷却管理</strong>：控制传输频率，避免每 tick 都执行传输</li>
 *   <li><strong>源/目标解析</strong>：从 ComponentContext 获取已解析的槽位索引</li>
 *   <li><strong>类型检测</strong>：判断源/目标是否为活箱子，选择不同的传输策略</li>
 *   <li><strong>级联防护</strong>：防止同一 tick 内物品被多次传递</li>
 * </ol>
 * 
 * <h2>支持的传输模式</h2>
 * <table border="1">
 *   <tr><th>源类型</th><th>目标类型</th><th>传输策略</th></tr>
 *   <tr><td>普通物品</td><td>普通物品</td><td>直接槽位间移动</td></tr>
 *   <tr><td>普通物品</td><td>活箱子</td><td>调用 LivingChestFunction.insertItem()</td></tr>
 *   <tr><td>活箱子</td><td>普通物品</td><td>调用 LivingChestFunction.extractItem()</td></tr>
 *   <tr><td>活箱子</td><td>活箱子</td><td>extract → insert 原子操作</td></tr>
 * </table>
 * 
 * <h2>冷却机制详解</h2>
 * <p>
 * 传输操作不是每 tick 都执行，而是受冷却时间控制：
 * </p>
 * <ul>
 *   <li><strong>基础冷却</strong>：默认 8 ticks（约 0.4 秒）</li>
 *   <li><strong>堆叠加速</strong>：{@code cooldown = baseCooldown / stackSize}</li>
 *   <li><strong>最小限制</strong>：最小 1 tick（20 次/秒），防止无限加速</li>
 * </ul>
 * 
 * <pre>{@code
 * 示例：
 * - 1 个活漏斗 → 8 ticks/次 (2.5 次/秒)
 * - 4 个活漏斗 → 2 ticks/次 (10 次/秒)
 * - 8 个活漏斗 → 1 tick/次 (20 次/秒)
 * - 16 个活漏斗 → 1 tick/次 (上限)
 * }</pre>
 * 
 * <h2>级联传输防护</h2>
 * <p>
 * 当多个活漏斗组成链时（如上传下），前面的活漏斗放入的物品
 * 可能会被后面的活漏斗在同一 tick 内继续传递，导致物品瞬间传到链底。
 * </p>
 * <p>
 * 通过 {@code transferredTargetSlots} 标记机制解决：
 * 每次成功传输后，将目标槽位加入已传输集合，
 * 后续活漏斗检查到目标槽位已在集合中则跳过。
 * </p>
 * 
 * <h2>活物品隔离</h2>
 * <p>
 * 传输前会检查源槽位物品是否为活物品（LivingItemManager.isLivingItem）。
 * 如果是活物品但<strong>不是</strong>活箱子，则跳过传输。
 * 这确保活物品不会被其他活漏斗当作普通物品移动。
 * </p>
 * 
 * <h2>配置参数</h2>
 * <table border="1">
 *   <tr><th>参数名</th><th>类型</th><th>默认值</th><th>说明</th></tr>
 *   <tr><td>base_cooldown</td><td>int</td><td>8</td><td>基础冷却时间（ticks）</td></tr>
 *   <tr><td>max_transfer_per_tick</td><td>int</td><td>64</td><td>每次最大传输数量</td></tr>
 * </table>
 * 
 * <h2>架构位置</h2>
 * <pre>
 * BaseLivingFunction.tick()
 *   → ItemTransferComponent.tick()           // 冷却检查 + 传输执行
 *     → executeTransfer()                     // 类型检测 + 策略选择
 *       → transferBetweenSlots()              // 普通物品传输
 *       → transferToLivingChest()             // 普通物品 → 活箱子
 *       → transferFromLivingChest()           // 活箱子 → 普通物品
 *       → transferBetweenLivingChests()       // 活箱子 ↔ 活箱子
 *         → LivingChestFunction.insertItem()  // 存入活箱子
 *         → LivingChestFunction.extractItem() // 取出活箱子
 * </pre>
 * 
 * @author Living Item Mod Team
 * @version 2024.12
 * @see LivingChestFunction 活箱子功能接口
 * @see DirectionModeComponent 方向配置组件（提供 sourceSlot/targetSlot）
 */
public class ItemTransferComponent implements ILivingComponent {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 组件 ID，用于在 ComponentState 和 NBT 中标识此组件 */
    public static final String ID = "item_transfer";
    
    /** NBT 键名：剩余冷却 ticks */
    private static final String KEY_COOLDOWN = "transfer_cooldown";
    
    /** 默认基础冷却：8 ticks（约 0.4 秒） */
    private static final int DEFAULT_COOLDOWN = 8;
    
    /** 默认每次最大传输数量：64（一整组） */
    private static final int DEFAULT_MAX_TRANSFER = 64;

    /**
     * 返回组件的唯一标识符
     * 
     * @return "item_transfer"
     */
    @Override
    public String getComponentId() { return ID; }

    /**
     * 每 tick 执行一次：检查冷却并尝试传输
     * 
     * <h3>执行流程</h3>
     * <ol>
     *   <li>从 ComponentState 读取当前冷却值</li>
     *   <li>如果冷却 > 0：递减冷却值并返回（等待中）</li>
     *   <li>如果冷却 == 0：调用 executeTransfer() 尝试传输</li>
     *   <li>传输成功后：根据堆叠数计算新的冷却时间并保存</li>
     * </ol>
     * 
     * <h3>调用时机</h3>
     * <p>由 {@link BaseLivingFunction#tick} 在每个服务端 tick 中自动调用。
     * 只在服务端执行，客户端跳过。</p>
     *
     * @param ctx 组件上下文，包含容器引用和已解析的槽位索引
     * @param hostSlot 宿主物品（活漏斗）在容器中的槽位索引
     * @param hostStack 宿主物品栈（活漏斗本身）
     * @param state 当前组件状态（包含冷却计时器）
     * @param config 组件配置（base_cooldown, max_transfer_per_tick）
     */
    @Override
    public void tick(ComponentContext ctx, int hostSlot, ItemStack hostStack,
                     ComponentState state, ComponentConfig config) {

        int baseCooldown = config.get("base_cooldown", Integer.class, DEFAULT_COOLDOWN);
        int maxTransfer = config.get("max_transfer_per_tick", Integer.class, DEFAULT_MAX_TRANSFER);

        int cooldown = state.getInt(KEY_COOLDOWN, 0);

        if (cooldown > 0) {
            cooldown--;
            state.setInt(KEY_COOLDOWN, cooldown);
            return;  // 冷却中，跳过本次传输
        }

        boolean success = executeTransfer(ctx, hostStack.getCount(), maxTransfer);

        if (success) {
            int actualCooldown = calculateCooldown(baseCooldown, hostStack.getCount());
            state.setInt(KEY_COOLDOWN, actualCooldown);
        }
    }

    /**
     * 创建默认的组件状态（冷却时间为 0）
     * 
     * @return 初始状态：transfer_cooldown = 0
     */
    @Override
    public ComponentState createDefaultState() {
        ComponentState state = new ComponentState();
        state.setInt(KEY_COOLDOWN, 0);
        return state;
    }

    /**
     * 向工具提示添加冷却状态信息
     * 
     * <p>显示内容：</p>
     * <ul>
     *   <li>冷却中："冷却中: X ticks"（灰色文字）</li>
     *   <li>就绪：不显示额外信息（由 DirectionModeComponent 显示方向信息）</li>
     * </ul>
     *
     * @param state 当前组件状态
     * @param tooltipAdder 工具提示添加器回调
     */
    @Override
    public void appendTooltip(ComponentState state, Consumer<Component> tooltipAdder) {
        int cooldown = state.getInt(KEY_COOLDOWN, 0);

        if (cooldown > 0) {
            tooltipAdder.accept(Component.literal("冷却中: " + cooldown + " ticks")
                .withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
        }
    }

    /**
     * 执行实际的物品传输操作（核心方法）
     * 
     * <h3>传输规则</h3>
     * <ol>
     *   <li><strong>边界检查</strong>：源/目标槽位必须在容器范围内</li>
     *   <li><strong>自环防护</strong>：源槽位不能等于目标槽位</li>
     *   <li><strong>级联防护</strong>：源槽位不能是本 tick 其他活漏斗的目标槽位</li>
     *   <li><strong>空槽位检查</strong>：源槽位必须有物品</li>
     *   <li><strong>活物品隔离</strong>：源物品如果是活物品（非活箱子）则跳过</li>
     *   <li><strong>类型检测</strong>：判断源/目标是否为活箱子，选择对应策略</li>
     * </ol>
     * 
     * <h3>策略分发逻辑</h3>
     * <pre>
     * if (sourceIsChest && targetIsChest):
     *     → transferBetweenLivingChests()      // 活箱子 → 活箱子
     * else if (sourceIsChest):
     *     → transferFromLivingChest()          // 活箱子 → 普通物品
     * else if (targetIsChest):
     *     → transferToLivingChest()            // 普通物品 → 活箱子
     * else:
     *     → transferBetweenSlots()             // 普通物品 → 普通物品
     * </pre>
     * 
     * <h3>跨容器支持</h3>
     * <p>如果源或目标槽位超出当前容器范围，
     * 会委托给 {@link CrossContainerTransfer} 处理跨容器传输。</p>
     *
     * @param ctx 组件上下文（包含容器引用和已解析的槽位索引）
     * @param stackSize 活漏斗的堆叠数量（影响单次传输量）
     * @param maxTransfer 配置的最大传输数量
     * @return 是否成功传输了物品
     */
    private boolean executeTransfer(ComponentContext ctx, int stackSize, int maxTransfer) {

        ContainerContext containerCtx = ctx.containerCtx();
        int containerSize = containerCtx.getSize();

        int sourceSlot = ctx.sourceSlot();
        int targetSlot = ctx.targetSlot();

        boolean sourceOutOfBounds = sourceSlot < 0 || sourceSlot >= containerSize;
        boolean targetOutOfBounds = targetSlot < 0 || targetSlot >= containerSize;

        // 跨容器传输：委托给专门的处理器
        if (sourceOutOfBounds || targetOutOfBounds) {
            return CrossContainerTransfer.execute(ctx, stackSize, maxTransfer);
        }

        // 自环防护：源和目标不能是同一个槽位
        if (sourceSlot == targetSlot) {
            return false;
        }

        // 级联防护：检查源槽位是否已被其他活漏斗在本 tick 传输到达
        Set<Integer> transferredTargetSlots = containerCtx.getTransferredTargetSlots();
        if (transferredTargetSlots != null && transferredTargetSlots.contains(sourceSlot)) {
            return false;
        }

        ItemStack sourceStack = containerCtx.getItem(sourceSlot);
        ItemStack targetStack = containerCtx.getItem(targetSlot);

        // 空槽位检查
        if (sourceStack.isEmpty()) {
            return false;
        }

        // 类型检测：判断源/目标是否为活箱子
        boolean sourceIsChest = LivingChestFunction.isLivingChest(sourceStack);
        boolean targetIsChest = LivingChestFunction.isLivingChest(targetStack);

        // 活物品隔离：活物品（非活箱子）不能被当作普通物品移动
        if (LivingItemManager.isLivingItem(sourceStack) && !sourceIsChest) {
            return false;
        }

        // 根据类型组合选择传输策略
        if (sourceIsChest) {
            if (targetIsChest) {
                return transferBetweenLivingChests(ctx, containerCtx, sourceSlot, targetSlot,
                    stackSize, maxTransfer);
            } else {
                return transferFromLivingChest(ctx, containerCtx, sourceSlot, targetSlot,
                    targetStack, stackSize, maxTransfer);
            }
        } else {
            if (targetIsChest) {
                return transferToLivingChest(ctx, containerCtx, sourceSlot, targetSlot,
                    sourceStack, stackSize, maxTransfer);
            } else {
                return transferBetweenSlots(ctx, containerCtx, sourceSlot, targetSlot,
                    sourceStack, targetStack, stackSize, maxTransfer, transferredTargetSlots);
            }
        }
    }

    /**
     * 活箱子之间的物品传输（原子操作）
     * 
     * <h3>原子性保证</h3>
     * <p>此方法确保"要么完全成功，要么完全失败"：</p>
     * <ul>
     *   <li>提取失败：不影响任何一方</li>
     *   <li>插入失败：自动回滚（退回源箱子）</li>
     *   <li>部分插入：剩余物品退回源箱子</li>
     * </ul>
     * 
     * <h3>执行流程</h3>
     * <ol>
     *   <li>从源活箱子提取物品（extractItem）</li>
     *   <li>尝试插入目标活箱子（insertItem）</li>
     *   <li>计算实际插入数量（originalCount - remainingCount）</li>
     *   <li>如果完全未插入（actuallyInserted <= 0）：退回全部物品</li>
     *   <li>如果部分插入（remainingCount > 0）：退回剩余物品</li>
     *   <li>标记目标槽位（防止级联传输）</li>
     * </ol>
     * 
     * <h3>性能注意</h3>
     * <p>涉及两次 WorldStorage 操作（extract + insert），
     * 以及可能的两次额外 insert 操作（回滚）。</p>
     *
     * @param ctx 组件上下文
     * @param containerCtx 容器上下文
     * @param sourceSlot 源活箱子的槽位索引
     * @param targetSlot 目标活箱子的槽位索引
     * @param stackSize 活漏斗堆叠数（影响传输量）
     * @param maxTransfer 最大传输量配置
     * @return 是否成功传输了物品
     */
    private boolean transferBetweenLivingChests(ComponentContext ctx, ContainerContext containerCtx,
                                                int sourceSlot, int targetSlot,
                                                int stackSize, int maxTransfer) {
        if (ctx.level().isClientSide()) return false;

        MinecraftServer server = ctx.level().getServer();
        if (server == null) return false;

        ItemStack sourceChestStack = containerCtx.getItem(sourceSlot);
        ItemStack targetChestStack = containerCtx.getItem(targetSlot);
        int capacityPerChest = LivingChestFunction.getCapacity(containerCtx);
        int transferAmount = Math.min(stackSize, maxTransfer);

        // 前置判断：源箱子为空 或 目标箱子已满 则跳过
        ComponentState sourceState = LivingChestFunction.getStorageState(sourceChestStack);
        ComponentState targetState = LivingChestFunction.getStorageState(targetChestStack);
        if (InternalStorageComponent.isStorageEmpty(sourceState)
            || InternalStorageComponent.isStorageFull(targetState, capacityPerChest)) {
            return false;
        }

        // 步骤1：从源活箱子提取物品
        ItemStack extracted = LivingChestFunction.extractItem(server, sourceChestStack, transferAmount, capacityPerChest);
        if (extracted.isEmpty()) return false;  // 源箱子为空

        // 步骤2：尝试插入目标活箱子
        int originalCount = extracted.getCount();
        LivingChestFunction.insertItem(server, targetChestStack, extracted, capacityPerChest);
        int actuallyInserted = originalCount - extracted.getCount();

        // 步骤3：处理插入结果
        if (actuallyInserted <= 0) {
            // 完全未插入：退回全部物品
            LivingChestFunction.insertItem(server, sourceChestStack, extracted, capacityPerChest);
            return false;
        }

        if (!extracted.isEmpty()) {
            // 部分插入：退回剩余物品
            LivingChestFunction.insertItem(server, sourceChestStack, extracted, capacityPerChest);
        }

        // 步骤4：标记目标槽位（防止级联传输）
        Set<Integer> transferredTargetSlots = containerCtx.getTransferredTargetSlots();
        if (transferredTargetSlots != null) {
            transferredTargetSlots.add(targetSlot);
        }

        return true;
    }

    /**
     * 普通槽位间的物品传输（最基础的传输方式）
     * 
     * <h3>适用场景</h3>
     * <p>源和目标都是普通物品（非活箱子），且在同一容器内。</p>
     * 
     * <h3>传输逻辑</h3>
     * <ol>
     *   <li><strong>空目标槽位</strong>：直接复制物品过去</li>
     *   <li><strong>同类型未满槽位</strong>：追加到现有堆叠（受 maxStackSize 限制）</li>
     *   <li><strong>不同类型或已满</strong>：无法传输</li>
     * </ol>
     * 
     * <h3>传输数量计算</h3>
     * <pre>
     * transferAmount = min(源物品数量, 堆叠数, maxTransfer)
     * </pre>
     *
     * @param ctx 组件上下文
     * @param containerCtx 容器上下文
     * @param sourceSlot 源槽位索引
     * @param targetSlot 目标槽位索引
     * @param sourceStack 源物品（会被修改）
     * @param targetStack 目标物品（会被修改）
     * @param stackSize 活漏斗堆叠数
     * @param maxTransfer 最大传输量配置
     * @param transferredTargetSlots 已传输集合（用于级联防护）
     * @return 是否成功传输
     */
    private boolean transferBetweenSlots(ComponentContext ctx, ContainerContext containerCtx,
                                         int sourceSlot, int targetSlot,
                                         ItemStack sourceStack, ItemStack targetStack,
                                         int stackSize, int maxTransfer, Set<Integer> transferredTargetSlots) {
        int transferAmount = Math.min(sourceStack.getCount(), Math.min(stackSize, maxTransfer));
        boolean success = false;

        if (targetStack.isEmpty()) {
            // 场景1：目标槽位为空 → 直接放入
            ItemStack toTransfer = sourceStack.copy();
            toTransfer.setCount(transferAmount);
            containerCtx.setItem(targetSlot, toTransfer);

            sourceStack.shrink(transferAmount);
            if (sourceStack.isEmpty()) {
                containerCtx.setItem(sourceSlot, ItemStack.EMPTY);
            } else {
                containerCtx.setItem(sourceSlot, sourceStack);
            }

            success = true;
        } else if (targetStack.is(sourceStack.getItem()) &&
                   targetStack.getCount() < targetStack.getMaxStackSize()) {
            // 场景2：目标槽位有同类型物品且未满 → 追加
            int spaceAvailable = targetStack.getMaxStackSize() - targetStack.getCount();
            int actualTransfer = Math.min(transferAmount, spaceAvailable);

            targetStack.grow(actualTransfer);
            containerCtx.setItem(targetSlot, targetStack);

            sourceStack.shrink(actualTransfer);
            if (sourceStack.isEmpty()) {
                containerCtx.setItem(sourceSlot, ItemStack.EMPTY);
            } else {
                containerCtx.setItem(sourceSlot, sourceStack);
            }

            success = true;
        }
        // 场景3：目标槽位有不同物品或已满 → 无法传输（success 保持 false）

        if (success && transferredTargetSlots != null) {
            transferredTargetSlots.add(targetSlot);  // 标记目标槽位
        }

        return success;
    }

    /**
     * 从普通物品向活箱子存入物品
     * 
     * <h3>典型场景</h3>
     * <p>活漏斗从相邻容器（如箱子、熔炉）取出物品，存入旁边的活箱子。</p>
     * 
     * <h3>执行流程</h3>
     * <ol>
     *   <li>创建要插入的物品副本（避免修改原始物品）</li>
     *   <li>调用 LivingChestFunction.insertItem() 执行实际的存储逻辑</li>
     *   <li>计算实际传输数量（originalCount - remainingCount）</li>
     *   <li>更新源槽位的物品数量</li>
     *   <li>标记目标槽位（防止级联传输）</li>
     * </ol>
     * 
     * <h3>⚠️ 副作用</h3>
     * <ul>
     *   <li>会修改源槽位的物品（减少数量或清空）</li>
     *   <li>会修改目标活箱子的 NBT 数据（更新 UUID 列表）</li>
     *   <li>会修改 WorldStorage 的缓存数据</li>
     * </ul>
     *
     * @param ctx 组件上下文
     * @param containerCtx 容器上下文
     * @param sourceSlot 源槽位（普通物品）
     * @param targetSlot 目标槽位（活箱子）
     * @param sourceStack 源物品（会被修改）
     * @param stackSize 活漏斗堆叠数
     * @param maxTransfer 最大传输量配置
     * @return 是否成功传输了物品
     */
    private boolean transferToLivingChest(ComponentContext ctx, ContainerContext containerCtx,
                                          int sourceSlot, int targetSlot,
                                          ItemStack sourceStack, int stackSize, int maxTransfer) {
        if (ctx.level().isClientSide()) return false;

        MinecraftServer server = ctx.level().getServer();
        if (server == null) return false;

        ItemStack targetChestStack = containerCtx.getItem(targetSlot);
        int capacityPerChest = LivingChestFunction.getCapacity(containerCtx);

        // 前置判断：活箱子已满则跳过，避免无效的 insertItem 调用
        ComponentState chestState = LivingChestFunction.getStorageState(targetChestStack);
        if (InternalStorageComponent.isStorageFull(chestState, capacityPerChest)) {
            return false;
        }

        // 计算传输数量：受限于源物品数量、堆叠数、maxTransfer 配置
        int transferAmount = Math.min(sourceStack.getCount(), Math.min(stackSize, maxTransfer));
        
        // 创建副本以避免影响原始物品（insertItem 会修改参数）
        ItemStack toInsert = sourceStack.copy();
        toInsert.setCount(transferAmount);

        // 调用活箱子的存入方法
        int originalCount = toInsert.getCount();
        LivingChestFunction.insertItem(server, targetChestStack, toInsert, capacityPerChest);
        int actuallyTransferred = originalCount - toInsert.getCount();

        if (actuallyTransferred <= 0) {
            return false;  // 活箱子已满或出错
        }

        // 更新源槽位：减少已传输的数量
        sourceStack.shrink(actuallyTransferred);
        if (sourceStack.isEmpty()) {
            containerCtx.setItem(sourceSlot, ItemStack.EMPTY);
        } else {
            containerCtx.setItem(sourceSlot, sourceStack);
        }

        // 标记目标槽位（防止级联传输）
        Set<Integer> transferredTargetSlots = containerCtx.getTransferredTargetSlots();
        if (transferredTargetSlots != null) {
            transferredTargetSlots.add(targetSlot);
        }

        return true;
    }

    /**
     * 从活箱子向普通槽位取出物品
     * 
     * <h3>典型场景</h3>
     * <p>活漏斗从活箱子中取出物品，放入相邻容器（如箱子、熔炉）。</p>
     * 
     * <h3>执行流程</h3>
     * <ol>
     *   <li>调用 LivingChestFunction.extractItem() 从活箱子提取物品</li>
     *   <li>检查目标槽位状态：</li>
     *   <ul>
     *     <li>空槽位：直接放入</li>
     *     <li>同类型未满：追加（可能部分插入）</li>
     *     <li>不同类型或已满：退回活箱子</li>
     *   </ul>
     *   <li>标记目标槽位（防止级联传输）</li>
     * </ol>
     * 
     * <h3>⚠️ 回滚机制</h3>
     * <p>如果目标槽位只能接收部分物品（空间不足），
     * 剩余物品会通过 insertItem() 退回源活箱子。</p>
     *
     * @param ctx 组件上下文
     * @param containerCtx 容器上下文
     * @param sourceSlot 源槽位（活箱子）
     * @param targetSlot 目标槽位（普通物品）
     * @param targetStack 目标物品（会被修改）
     * @param stackSize 活漏斗堆叠数
     * @param maxTransfer 最大传输量配置
     * @return 是否成功传输了物品
     */
    private boolean transferFromLivingChest(ComponentContext ctx, ContainerContext containerCtx,
                                            int sourceSlot, int targetSlot,
                                            ItemStack targetStack, int stackSize, int maxTransfer) {
        if (ctx.level().isClientSide()) return false;

        MinecraftServer server = ctx.level().getServer();
        if (server == null) return false;

        ItemStack sourceChestStack = containerCtx.getItem(sourceSlot);
        int capacityPerChest = LivingChestFunction.getCapacity(containerCtx);
        int extractAmount = Math.min(stackSize, maxTransfer);

        // 前置判断：活箱子为空则跳过，避免无效的 extractItem 调用
        ComponentState chestState = LivingChestFunction.getStorageState(sourceChestStack);
        if (InternalStorageComponent.isStorageEmpty(chestState)) {
            return false;
        }

        // 从活箱子提取物品
        ItemStack extracted = LivingChestFunction.extractItem(server, sourceChestStack, extractAmount, capacityPerChest);
        if (extracted.isEmpty()) return false;  // 活箱子为空

        boolean success = false;

        if (targetStack.isEmpty()) {
            // 场景1：目标槽位为空 → 直接放入
            containerCtx.setItem(targetSlot, extracted);
            success = true;
        } else if (targetStack.is(extracted.getItem()) &&
                   targetStack.getCount() < targetStack.getMaxStackSize()) {
            // 场景2：目标槽位有同类型物品且未满 → 追加
            int spaceAvailable = targetStack.getMaxStackSize() - targetStack.getCount();
            int actualTransfer = Math.min(extracted.getCount(), spaceAvailable);

            targetStack.grow(actualTransfer);
            containerCtx.setItem(targetSlot, targetStack);

            if (actualTransfer < extracted.getCount()) {
                // 部分插入：剩余物品退回活箱子
                extracted.shrink(actualTransfer);
                LivingChestFunction.insertItem(server, sourceChestStack, extracted, capacityPerChest);
            }
            success = true;
        } else {
            // 场景3：目标槽位不兼容 → 全部退回活箱子
            LivingChestFunction.insertItem(server, sourceChestStack, extracted, capacityPerChest);
            return false;
        }

        if (success) {
            // 标记目标槽位（防止级联传输）
            Set<Integer> transferredTargetSlots = containerCtx.getTransferredTargetSlots();
            if (transferredTargetSlots != null) {
                transferredTargetSlots.add(targetSlot);
            }
        }

        return success;
    }

    /**
     * 根据活漏斗堆叠数计算实际冷却时间
     * 
     * <h3>算法公式</h3>
     * <pre>
     * if (stackSize <= 1):
     *     return baseCooldown          // 单个漏斗：正常速度
     * else:
     *     return max(1, baseCooldown / stackSize)  // 多个漏斗：加速（最低1tick）
     * </pre>
     * 
     * <h3>设计意图</h3>
     * <ul>
     *   <li><strong>线性加速</strong>：堆叠越多，传输越快</li>
     *   <li><strong>上限控制</strong>：最快 1 tick（20次/秒），防止无限加速</li>
     *   <li><strong>游戏平衡</strong>：鼓励玩家制作多个活漏斗堆叠使用</li>
     * </ul>
     * 
     * <h3>示例</h3>
     * <table border="1">
     *   <tr><th>堆叠数</th><th>冷却时间</th><th>传输频率</th></tr>
     *   <tr><td>1</td><td>8 ticks</td><td>2.5 次/秒</td></tr>
     *   <tr><td>2</td><td>4 ticks</td><td>5 次/秒</td></tr>
     *   <tr><td>4</td><td>2 ticks</td><td>10 次/秒</td></tr>
     *   <tr><td>8+</td><td>1 tick</td><td>20 次/秒（上限）</td></tr>
     * </table>
     *
     * @param baseCooldown 基础冷却时间（来自配置，默认 8）
     * @param stackSize 活漏斗的堆叠数量
     * @return 实际冷却时间（ticks），最小为 1
     */
    private int calculateCooldown(int baseCooldown, int stackSize) {
        if (stackSize <= 1) {
            return baseCooldown;
        }
        return Math.max(1, baseCooldown / stackSize);
    }
}