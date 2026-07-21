package com.qiqi.li.living.function;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import com.qiqi.li.living.core.ComponentState;
import com.qiqi.li.living.core.LivingFunctionConfig;
import com.qiqi.li.living.BaseLivingFunction;
import com.qiqi.li.living.LivingItemManager;
import com.qiqi.li.living.core.accessor.EnderChannelRegistry;
import com.qiqi.li.living.core.components.DirectionModeComponent;
import com.qiqi.li.living.core.components.ItemFilterComponent;
import com.qiqi.li.living.core.components.ItemTransferComponent;
import com.qiqi.li.living.core.model.SlotMapping;
import com.qiqi.li.living.core.orchestrator.Orchestrators;
import com.qiqi.li.living.container.ContainerContext;

/**
 * 活漏斗功能 —— 实现活漏斗的物品传输逻辑。
 *
 * 功能概述：
 * 活漏斗是一种可以自动在容器内移动物品的活物品。
 * 它从源槽位取出物品，放入目标槽位，实现自动化的物品传输。
 *
 * 组件配置：
 * - DirectionModeComponent（TRANSFER模式）：管理传输方向（源→目标）
 * - ItemTransferComponent：执行实际的物品传输操作
 *
 * 传输规则：
 * - 只能传输非活物品（LivingItemManager.isLivingItem() 检查）
 * - 支持堆叠加速（多个活漏斗堆叠时缩短冷却时间）
 * - 默认冷却时间 8 ticks（约 0.4 秒）
 *
 * 方向配置：
 * 支持 12 种基本传输方向（上下左右之间的组合），
 * 通过 WASD 键入动态修改（客户端 LivingItemInputHandler 处理）。
 */
public class LivingHopperFunction extends BaseLivingFunction {

    public static final String ID = "living_hopper";

    private static final LivingFunctionConfig CONFIG = new LivingFunctionConfig()
        .withFunctionId(ID)
        .withStackMultiplier(true)
        .withOrchestrator(Orchestrators.SIMPLE)
        .addComponent(new ItemFilterComponent())
        .addComponent(new DirectionModeComponent())
        .addComponent(new ItemTransferComponent());

    @Override
    protected LivingFunctionConfig getConfig() { return CONFIG; }

    @Override
    protected String getTooltipTitleKey() { return "tooltip.livingitem.hopper.status"; }

    @Override
    public boolean canApply(ItemStack stack) {
        return stack.is(Items.HOPPER) && LivingItemManager.isLivingItem(stack);
    }

    @Override
    public String getFunctionId() { return ID; }

    /**
     * 重写 tick()：在活漏斗处理完成后清理已移除漏斗的路由。
     *
     * <p>当活漏斗从容器中被移走时，该漏斗注册的所有活末影箱路由
     * 应当被清理。此方法在父类 tick() 完成后收集当前容器中
     * 所有活漏斗的槽位，并调用 EnderChannelRegistry 清理
     * 对应容器位置中 registrarSlot 不在活跃列表中的路由。</p>
     */
    @Override
    public void tick(List<SlotEntry> entries, ContainerContext context, Level level) {
        super.tick(entries, context, level);

        if (level.isClientSide) return;

        BlockPos pos = context.getBlockPos();
        if (pos == null) return;

        Set<Integer> activeSlots = new HashSet<>();
        for (SlotEntry entry : entries) {
            activeSlots.add(entry.slotIndex());
        }

        EnderChannelRegistry.getInstance().removeStaleRoutes(pos, activeSlots);
    }

    /**
     * 判断物品是否为活漏斗。
     *
     * 同时检查物品类型（漏斗）和活物品标记。
     *
     * @param stack 要检查的物品
     * @return true 如果是活漏斗
     */
    public static boolean isLivingHopper(ItemStack stack) {
        return stack.is(Items.HOPPER) && LivingItemManager.isLivingItem(stack);
    }

    public static LivingFunctionConfig getStaticConfig() { return CONFIG; }

    public static boolean updateTransferMapping(ItemStack hopperStack, SlotMapping newMapping) {
        if (hopperStack == null || hopperStack.isEmpty() || newMapping == null) return false;
        return DirectionModeComponent.updateStateInStack(hopperStack, ID,
            (dirComp, dirState) -> dirComp.updateMapping(dirState, newMapping));
    }

    public static ComponentState readDirectionState(ItemStack hopperStack) {
        return DirectionModeComponent.readStateFromStack(hopperStack, ID);
    }
}