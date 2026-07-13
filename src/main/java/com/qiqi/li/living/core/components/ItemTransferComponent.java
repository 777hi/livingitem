package com.qiqi.li.living.core.components;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
import com.qiqi.li.living.ContainerContext;
import com.qiqi.li.living.LivingItemManager;
import com.qiqi.li.living.core.ComponentConfig;
import com.qiqi.li.living.core.ComponentContext;
import com.qiqi.li.living.core.ComponentState;

/**
 * 物品传输组件 —— 实现活漏斗的核心传输逻辑。
 *
 * 职责：
 * 1. 从 ComponentContext 获取已解析的源/目标槽位索引
 *    （由 DirectionModeComponent.resolveSlots() 预先解析）
 * 2. 从源槽位取出物品，放入目标槽位
 * 3. 管理传输冷却时间，避免每 tick 都执行传输
 *
 * 工作流程：
 *   tick() → 检查冷却 → executeTransfer() → 更新冷却时间
 *
 * 冷却机制：
 *   - 默认冷却 8 ticks（约 0.4 秒）
 *   - 堆叠物品时冷却缩短：cooldown = baseCooldown / stackSize
 *     例如：1 个漏斗 = 8 ticks，8 个漏斗 = 1 tick
 *   - 这使得堆叠活漏斗可以加速传输，但不会无限快
 *
 * 活物品隔离：
 *   传输前检查源槽位物品是否为活物品（LivingItemManager.isLivingItem），
 *   如果是活物品则跳过传输。这确保活物品不会被其他活漏斗当作普通物品移动。
 *
 * 配置参数（通过 ComponentConfig）：
 *   - base_cooldown: int, 基础冷却 ticks（默认 8）
 *   - max_transfer_per_tick: int, 每次传输最大数量（默认 64）
 *
 * 使用示例（活漏斗配置）：
 * <pre>
 * new LivingFunctionConfig()
 *     .addComponent(new DirectionModeComponent())  // 方向配置
 *     .addComponent(new ItemTransferComponent());  // 传输逻辑
 * </pre>
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

    @Override
    public String getComponentId() { return ID; }

    /**
     * 每 tick 执行一次：检查冷却并尝试传输。
     *
     * 执行流程：
     * 1. 从 ComponentState 读取当前冷却值
     * 2. 如果冷却 > 0，递减冷却值并返回
     * 3. 如果冷却 == 0，调用 executeTransfer() 尝试传输
     * 4. 传输成功后，根据活漏斗堆叠数计算新的冷却时间
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

        boolean success = executeTransfer(ctx, hostStack.getCount(), maxTransfer);

        if (success) {
            int actualCooldown = calculateCooldown(baseCooldown, hostStack.getCount());
            state.setInt(KEY_COOLDOWN, actualCooldown);
        }
    }

    @Override
    public ComponentState createDefaultState() {
        ComponentState state = new ComponentState();
        state.setInt(KEY_COOLDOWN, 0);
        return state;
    }

    /**
     * 追加 Tooltip 信息：显示当前冷却状态。
     *
     * 如果正在冷却中，显示"冷却中: X ticks"（灰色文字）。
     * 如果冷却完毕，不显示额外信息（由 DirectionModeComponent 显示方向信息）。
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
     * 执行实际的物品传输操作。
     *
     * 传输规则：
     * 1. 从 ComponentContext 获取已解析的 sourceSlot 和 targetSlot
     *    （由 DirectionModeComponent.resolveSlots() 预先解析）
     * 2. 检查源槽位是否有物品、是否为活物品（活物品跳过）
     * 3. 检查目标槽位是否已被本 tick 其他活漏斗传输到达（防止级联传输）
     * 4. 检查目标槽位状态：
     *    - 空槽位：直接放入物品
     *    - 有相同物品且未满：追加到现有堆叠
     *    - 有不同物品或已满：无法传输
     * 5. 传输数量受限于：源物品数量、活漏斗堆叠数、maxTransfer 配置
     * 6. 传输成功后标记目标槽位，防止同 tick 内后续活漏斗继续传输该物品
     *
     * 级联传输防护：
     *   当多个活漏斗组成链时，如果传输方向与扫描顺序一致（如上传下），
     *   前面的活漏斗放入的物品会被后面的活漏斗在同一 tick 内继续传递，
     *   导致物品瞬间传到链底。通过 transferredTargetSlots 标记机制，
     *   确保每个物品每 tick 最多只被传输一次，无论传输方向如何。
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

        boolean sourceOutOfBounds = sourceSlot < 0;
        boolean targetOutOfBounds = targetSlot < 0 || targetSlot >= containerSize;

        if (sourceOutOfBounds || targetOutOfBounds) {
            return CrossContainerTransfer.execute(ctx, stackSize, maxTransfer);
        }

        if (sourceSlot == targetSlot) {
            return false;
        }

        Set<Integer> transferredTargetSlots = containerCtx.getTransferredTargetSlots();
        if (transferredTargetSlots != null && transferredTargetSlots.contains(sourceSlot)) {
            return false;
        }

        ItemStack sourceStack = containerCtx.getItem(sourceSlot);
        if (sourceStack.isEmpty() || LivingItemManager.isLivingItem(sourceStack)) {
            return false;
        }

        ItemStack targetStack = containerCtx.getItem(targetSlot);
        int transferAmount = Math.min(sourceStack.getCount(), Math.min(stackSize, maxTransfer));

        boolean success = false;

        if (targetStack.isEmpty()) {
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

        if (success && transferredTargetSlots != null) {
            transferredTargetSlots.add(targetSlot);
        }

        return success;
    }

    /**
     * 根据活漏斗堆叠数计算实际冷却时间。
     *
     * 算法：cooldown = baseCooldown / stackSize（最小为 1）
     *
     * 设计意图：
     * - 单个活漏斗：正常速度（8 ticks）
     * - 多个活漏斗堆叠：速度加快（8/stackSize ticks）
     * - 防止无限加速：最小冷却 1 tick（20 次/秒）
     *
     * @param baseCooldown 基础冷却时间（来自配置）
     * @param stackSize 活漏斗堆叠数量
     * @return 实际冷却时间（ticks），最小为 1
     */
    private int calculateCooldown(int baseCooldown, int stackSize) {
        if (stackSize <= 1) {
            return baseCooldown;
        }
        return Math.max(1, baseCooldown / stackSize);
    }
}