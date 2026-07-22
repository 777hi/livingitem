package com.qiqi.li.living.function;

import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeType;
import com.qiqi.li.living.core.ComponentConfig;
import com.qiqi.li.living.core.ComponentState;
import com.qiqi.li.living.core.LivingFunctionConfig;
import com.qiqi.li.living.BaseLivingFunction;
import com.qiqi.li.living.LivingItemManager;
import com.qiqi.li.living.LivingFunctionData;
import com.qiqi.li.living.core.model.Pos2D;
import com.qiqi.li.living.core.components.DirectionModeComponent;
import com.qiqi.li.living.core.components.FuelConsumeComponent;
import com.qiqi.li.living.core.components.ItemTransformComponent;
import com.qiqi.li.living.core.components.ProgressComponent;
import com.qiqi.li.living.core.orchestrator.Orchestrators;

/**
 * 活熔炉功能 —— 实现活熔炉的自动熔炼逻辑。
 *
 * 功能概述：
 * 活熔炉是一种可以自动在容器内熔炼物品的活物品。
 * 它从输入槽位取出原料，消耗燃料槽位的燃料，将产物放入输出槽位。
 *
 * 组件配置：
 * - DirectionModeComponent（SLOTS模式）：管理输入/燃料/输出槽位方向
 * - FuelConsumeComponent：管理燃料消耗和燃烧时间
 * - ProgressComponent：管理熔炼进度（默认 200 ticks = 10 秒）
 * - ItemTransformComponent：执行配方匹配和物品转化
 *
 * 熔炼规则：
 * - 只能熔炼非活物品（LivingItemManager.isLivingItem() 检查）
 * - 只能消耗非活燃料（同上）
 * - 支持堆叠加速（8 个活熔炉堆叠 = 8 倍速度）
 * - 使用 Minecraft 原版 SMELTING 配方类型
 *
 * 编排策略：FUEL_PROGRESS
 * - 检查燃料+输入有效性 → tick/pauseTick → 完成时转化
 *
 * 槽位布局示例（在 9 列箱子中）：
 * ┌───┬───┬───┬───┬───┬───┬───┬───┬───┐
 * │   │   │   │   │   │   │   │   │   │
 * ├───┼───┼───┼───┼───┼───┼───┼───┼───┤
 * │原料│   │   │熔炉│   │   │产物│   │   │
 * ├───┼───┼───┼───┼───┼───┼───┼───┼───┤
 * │   │   │燃料│   │   │   │   │   │   │
 * └───┴───┴───┴───┴───┴───┴───┴───┴───┘
 */
public class LivingFurnaceFunction extends BaseLivingFunction {

    public static final String ID = "living_furnace";

    private static LinkedHashMap<String, Pos2D> slotOrder() {
        LinkedHashMap<String, Pos2D> map = new LinkedHashMap<>();
        map.put("input", Pos2D.LEFT);
        map.put("output", Pos2D.RIGHT);
        map.put("fuel", Pos2D.DOWN);
        return map;
    }

    private static final LivingFunctionConfig CONFIG = new LivingFunctionConfig()
        .withFunctionId(ID)
        .withStackMultiplier(true)
        .withOrchestrator(Orchestrators.FUEL_PROGRESS)
        .addComponent(new DirectionModeComponent(slotOrder()))
        .addComponent(FuelConsumeComponent.class,
            ComponentConfig.of("recipe_type", RecipeType.SMELTING))
        .addComponent(ProgressComponent.class,
            ComponentConfig.of("total_ticks", 200))
        .addComponent(ItemTransformComponent.class,
            ComponentConfig.of("recipe_type", RecipeType.SMELTING));

    @Override
    protected LivingFunctionConfig getConfig() { return CONFIG; }

    @Override
    protected String getTooltipTitleKey() { return "tooltip.livingitem.furnace.status"; }

    @Override
    public boolean canApply(ItemStack stack) {
        return stack.is(Items.FURNACE) && LivingItemManager.isLivingItem(stack);
    }

    @Override
    public String getFunctionId() { return ID; }

    public static LivingFunctionConfig getStaticConfig() { return CONFIG; }

    /**
     * 检查活熔炉是否正在燃烧（供客户端图标系统使用）。
     *
     * <p>从 LIVING_FUNCTION_DATA 组件中读取 living_furnace.fuel.burn_time，
     * 如果大于 0 则表示正在燃烧。
     *
     * @param stack 物品栈
     * @return 如果正在燃烧返回 true
     */
    public static boolean isBurning(ItemStack stack) {
        LivingFunctionData funcData = stack.get(LivingItemManager.LIVING_FUNCTION_DATA.value());
        if (funcData == null || funcData.isEmpty()) return false;

        net.minecraft.nbt.CompoundTag furnaceTag = funcData.getFunctionData(ID);
        if (furnaceTag.isEmpty()) return false;

        net.minecraft.nbt.CompoundTag fuelTag = furnaceTag.getCompound("fuel");
        return fuelTag.getInt("burn_time") > 0;
    }

    /**
     * 获取活熔炉的方向配置组件实例（SLOTS 模式）。
     */
    public static DirectionModeComponent getDirectionComponent() {
        return new DirectionModeComponent(slotOrder());
    }

    /**
     * 从 ItemStack 读取活熔炉的方向状态。
     */
    public static ComponentState readDirectionState(ItemStack furnaceStack) {
        return DirectionModeComponent.readStateFromStack(furnaceStack, ID);
    }

    /**
     * 更新活熔炉的指定槽位方向（服务端使用）。
     */
    public static boolean updateSlotDirection(ItemStack furnaceStack, String slotName, Pos2D direction) {
        if (furnaceStack == null || furnaceStack.isEmpty() || slotName == null || direction == null) return false;

        CompoundTag functionTag = LivingItemManager.getFunctionData(furnaceStack, ID).copy();

        ComponentState dirState;
        if (functionTag.contains(DirectionModeComponent.ID)) {
            dirState = ComponentState.fromNBT(functionTag.getCompound(DirectionModeComponent.ID));
        } else {
            dirState = getDirectionComponent().createDefaultState();
        }

        DirectionModeComponent dirComp = getDirectionComponent();
        boolean success = dirComp.setDirection(dirState, slotName, direction);
        if (!success) return false;

        String[] slotNames = dirComp.getSlotNames();
        for (int i = 0; i < slotNames.length; i++) {
            if (slotNames[i].equals(slotName)) {
                dirState.setInt("active_slot_index", (i + 1) % slotNames.length);
                break;
            }
        }

        functionTag.put(DirectionModeComponent.ID, dirState.toNBT());
        LivingItemManager.setFunctionData(furnaceStack, ID, functionTag);
        return true;
    }
}