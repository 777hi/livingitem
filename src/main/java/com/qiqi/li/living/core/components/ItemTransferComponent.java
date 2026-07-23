package com.qiqi.li.living.core.components;

import java.util.Set;
import java.util.function.Consumer;

import com.qiqi.li.living.container.CrossContainerTransfer;
import com.qiqi.li.living.core.accessor.LivingEnderChestAccessor;
import com.qiqi.li.living.core.accessor.SlotAccessor;
import com.qiqi.li.living.core.accessor.SlotAccessorFactory;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.BaseLivingFunction;
import com.qiqi.li.living.LivingItemManager;
import com.qiqi.li.living.core.ComponentConfig;
import com.qiqi.li.living.core.ComponentContext;
import com.qiqi.li.living.core.ComponentState;
import com.qiqi.li.living.function.LivingEnderChestFunction;

/**
 * 物品传输组件 (Item Transfer Component)
 * 
 * <h2>功能概述</h2>
 * <p>
 * 实现活漏斗的核心传输逻辑，负责在容器内的不同槽位之间移动物品。
 * 通过 {@link com.qiqi.li.living.core.accessor.SlotAccessor} 接口统一抽象不同存储后端，
 * 传输引擎不再直接依赖具体存储类型（活箱子、活末影箱、活潜影箱等）。
 * </p>
 * 
 * <h2>核心职责</h2>
 * <ol>
 *   <li><strong>冷却管理</strong>：控制传输频率，避免每 tick 都执行传输</li>
 *   <li><strong>源/目标解析</strong>：从 ComponentContext 获取已解析的槽位索引</li>
 *   <li><strong>SlotAccessor 创建</strong>：通过 {@link com.qiqi.li.living.core.accessor.SlotAccessorFactory} 为每个槽位创建对应访问器</li>
 *   <li><strong>级联防护</strong>：防止同一 tick 内物品被多次传递</li>
 * </ol>
 * 
 * <h2>传输模式（SlotAccessor 架构）</h2>
 * <p>不再按源/目标类型组合分发，而是统一调用 {@code source.extract() → target.insert()}：</p>
 * <pre>{@code
 * executeTransfer():
 *   ├─ 跨容器？→ CrossContainerTransfer.execute()
 *   ├─ 自环/级联/空槽位 → 跳过
 *   ├─ 创建 source = SlotAccessorFactory.create(sourceSlot)
 *   ├─ 创建 target = SlotAccessorFactory.create(targetSlot)
 *   ├─ extracted = source.extract(amount, filterType)
 *   ├─ inserted = target.insert(extracted)
 *   └─ 回滚/标记/同步
 * }</pre>
 * 
 * <h2>冷却机制详解</h2>
 * <p>
 * 传输操作不是每 tick 都执行，而是受冷却时间控制：
 * </p>
 * <ul>
 *   <li><strong>基础冷却</strong>：默认 8 ticks（约 0.4 秒）</li>
 *   <li><strong>堆叠加速</strong>：{@code cooldown = max(1, baseCooldown - stackSize / 8)}</li>
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
 *     → executeTransfer()                     // 前置检查 + SlotAccessor 创建
 *       → SlotAccessorFactory.create()        // 根据槽位类型创建对应访问器
 *         → PlainSlotAccessor                 // 普通槽位（直接读写）
 *         → LivingChestAccessor               // 活箱子（insertItem/extractItem）
 *       → source.extract() → target.insert()  // 统一传输逻辑
 *       → CrossContainerTransfer.execute()    // 跨容器（特殊分支）
 * </pre>
 * 
 * @author Living Item Mod Team
 * @version 2025.07
 * @see com.qiqi.li.living.core.accessor.SlotAccessor 槽位访问器接口
 * @see com.qiqi.li.living.core.accessor.SlotAccessorFactory 访问器工厂
 * @see DirectionModeComponent 方向配置组件（提供 sourceSlot/targetSlot）
 */
public class ItemTransferComponent implements ILivingComponent {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 组件 ID，用于在 ComponentState 和 NBT 中标识此组件 */
    public static final String ID = "item_transfer";
    
    /** NBT 键名：剩余冷却 ticks */
    private static final String KEY_COOLDOWN = "transfer_cooldown";
    
    /** 默认基础冷却：4 ticks（约 0.2 秒） */
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
            return;
        }

        // 尝试传输，只有实际传输了物品才设置冷却
        boolean transferred = executeTransfer(ctx, hostSlot, hostStack.getCount(), maxTransfer);
        if (transferred) {
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
     * <p>通过 {@link SlotAccessor} 接口统一处理不同存储后端，
     * 不再按源/目标类型组合分发。新增存储类型只需在
     * {@link SlotAccessorFactory} 中注册，无需修改此方法。</p>
     *
     * <h3>执行流程</h3>
     * <ol>
     *   <li>跨容器检测 → 委托给 CrossContainerTransfer</li>
     *   <li>自环/级联/空槽位 → 跳过</li>
     *   <li>活物品隔离 → 跳过</li>
     *   <li>普通物品过滤 → 跳过</li>
     *   <li>创建 SlotAccessor → 执行 extract → insert</li>
     * </ol>
     *
     * @param ctx 组件上下文
     * @param stackSize 活漏斗堆叠数
     * @param maxTransfer 最大传输量配置
     * @return 是否成功传输了物品
     */
    private boolean executeTransfer(ComponentContext ctx, int hostSlot, int stackSize, int maxTransfer) {

        ContainerContext containerCtx = ctx.containerCtx();
        int containerSize = containerCtx.getSize();

        int sourceSlot = ctx.sourceSlot();
        int targetSlot = ctx.targetSlot();

        boolean sourceOutOfBounds = sourceSlot < 0 || sourceSlot >= containerSize;
        boolean targetOutOfBounds = targetSlot < 0 || targetSlot >= containerSize;

        if (sourceOutOfBounds || targetOutOfBounds) {
            return CrossContainerTransfer.execute(ctx, stackSize, maxTransfer, hostSlot);
        }

        if (sourceSlot == targetSlot) {
            return false;
        }

        Set<Integer> transferredTargetSlots = containerCtx.getTransferredTargetSlots();
        if (transferredTargetSlots != null && transferredTargetSlots.contains(sourceSlot)) {
            return false;
        }

        ItemStack sourceStack = containerCtx.getItem(sourceSlot);
        if (sourceStack.isEmpty()) {
            return false;
        }

        boolean isStorageContainer = com.qiqi.li.living.function.LivingChestFunction.isLivingChest(sourceStack)
            || com.qiqi.li.living.function.LivingEnderChestFunction.isLivingEnderChest(sourceStack);

        if (LivingItemManager.isLivingItem(sourceStack) && !isStorageContainer) {
            return false;
        }

        ComponentState filterState = ctx.getComponentState(ItemFilterComponent.ID);
        if (filterState != null && !isStorageContainer
            && !ItemFilterComponent.allows(filterState, sourceStack)) {
            return false;
        }

        MinecraftServer server = ctx.level().getServer();
        if (server == null) return false;

        SlotAccessor source = SlotAccessorFactory.create(server, containerCtx, sourceSlot,
            filterState, transferredTargetSlots);
        SlotAccessor target = SlotAccessorFactory.create(server, containerCtx, targetSlot,
            null, transferredTargetSlots);

        if (source == null || target == null) {
            return false;
        }

        if (target.unwrap() instanceof LivingEnderChestAccessor enderChest) {
            if (enderChest.isDirectMode()) {
                return doTransfer(source, target, Math.min(stackSize, maxTransfer));
            }
            ItemStack sourceStackForRoute = containerCtx.getItem(sourceSlot);
            if (!sourceStackForRoute.isEmpty()
                && !LivingItemManager.isLivingItem(sourceStackForRoute)) {
                LOGGER.trace("ItemTransferComponent: push to ender chest, sourceSlot={}, targetSlot={}",
                    sourceSlot, targetSlot);
                enderChest.registerRoute(sourceStackForRoute, containerCtx, sourceSlot, hostSlot);
            }
            return true;
        }

        if (source.unwrap() instanceof LivingEnderChestAccessor) {
            return doTransfer(source, target, Math.min(stackSize, maxTransfer));
        }

        return doTransfer(source, target, Math.min(stackSize, maxTransfer));
    }

    private boolean doTransfer(SlotAccessor source, SlotAccessor target, int amount) {
        return SlotAccessor.transfer(source, target, amount);
    }

    /**
     * 根据活漏斗堆叠数计算实际冷却时间
     * 
     * <h3>算法公式</h3>
     * <pre>
     * cooldown = max(1, baseCooldown - stackSize / 8)
     * </pre>
     * 
     * <h3>设计意图</h3>
     * <ul>
     *   <li><strong>阶梯加速</strong>：每多堆叠8个活漏斗，冷却减少1 tick</li>
     *   <li><strong>上限控制</strong>：最快 1 tick（20次/秒），防止无限加速</li>
     *   <li><strong>游戏平衡</strong>：鼓励玩家制作多个活漏斗堆叠使用</li>
     * </ul>
     * 
     * <h3>示例（baseCooldown = 8）</h3>
     * <table border="1">
     *   <tr><th>堆叠数</th><th>冷却时间</th><th>传输频率</th></tr>
     *   <tr><td>1-7</td><td>8 ticks</td><td>2.5 次/秒</td></tr>
     *   <tr><td>8-15</td><td>7 ticks</td><td>~2.9 次/秒</td></tr>
     *   <tr><td>16-23</td><td>6 ticks</td><td>~3.3 次/秒</td></tr>
     *   <tr><td>24-31</td><td>5 ticks</td><td>4 次/秒</td></tr>
     *   <tr><td>32-39</td><td>4 ticks</td><td>5 次/秒</td></tr>
     *   <tr><td>40-47</td><td>3 ticks</td><td>~6.7 次/秒</td></tr>
     *   <tr><td>48-55</td><td>2 ticks</td><td>10 次/秒</td></tr>
     *   <tr><td>56-64</td><td>1 tick</td><td>20 次/秒（上限）</td></tr>
     * </table>
     *
     * @param baseCooldown 基础冷却时间（来自配置，默认 8）
     * @param stackSize 活漏斗的堆叠数量
     * @return 实际冷却时间（ticks），最小为 1
     */
    private int calculateCooldown(int baseCooldown, int stackSize) {
        return Math.max(1, baseCooldown - stackSize / 8);
    }
}