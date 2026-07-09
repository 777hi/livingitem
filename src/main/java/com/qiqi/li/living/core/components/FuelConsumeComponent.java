package com.qiqi.li.living.core.components;

import java.util.function.Consumer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeType;
import net.neoforged.neoforge.common.extensions.IItemExtension;
import com.qiqi.li.living.LivingItemManager;
import com.qiqi.li.living.core.ComponentConfig;
import com.qiqi.li.living.core.ComponentContext;
import com.qiqi.li.living.core.ComponentState;

/**
 * 燃料消耗组件 —— 管理活熔炉的燃料消耗逻辑。
 *
 * 职责：
 * 1. 维护当前燃烧时间（burn_time），每 tick 递减
 * 2. 燃烧时间耗尽时，尝试从燃料槽位消耗新燃料
 * 3. 检查燃料槽位是否有可用燃料（非活物品）
 *
 * 工作流程：
 *   tick() → 有燃烧时间？递减并返回 : 尝试消耗新燃料
 *
 * 燃料消耗规则：
 *   - 通过 IItemExtension.getBurnTime() 获取物品的燃料值
 *   - 每次消耗 1 个燃料物品
 *   - 活物品不能作为燃料（LivingItemManager.isLivingItem 检查）
 *
 * 配置参数（通过 ComponentConfig）：
 *   - recipe_type: RecipeType<?>, 配方类型（默认 SMELTING）
 *     不同配方类型可能影响燃料值的计算
 *
 * 使用示例（活熔炉配置）：
 * <pre>
 * .addComponent(FuelConsumeComponent.class,
 *     ComponentConfig.of("recipe_type", RecipeType.SMELTING))
 * </pre>
 */
public class FuelConsumeComponent implements ILivingComponent {

    /** 组件 ID，用于在 ComponentState 和 NBT 中标识此组件 */
    public static final String ID = "fuel";

    /** NBT 键名：剩余燃烧时间（ticks） */
    private static final String KEY_BURN_TIME = "burn_time";

    @Override
    public String getComponentId() { return ID; }

    /**
     * 每 tick 执行一次：管理燃烧时间。
     *
     * 执行流程：
     * 1. 如果当前有燃烧时间（burn_time > 0），递减 1 并返回
     * 2. 如果燃烧时间耗尽，检查燃料槽位是否有可用燃料
     * 3. 有可用燃料时，消耗 1 个燃料物品，设置新的燃烧时间
     */
    @Override
    public void tick(ComponentContext ctx, int hostSlot, ItemStack hostStack,
                     ComponentState state, ComponentConfig config) {

        RecipeType<?> recipeType = config.get("recipe_type", RecipeType.class, RecipeType.SMELTING);
        int burnTime = state.getInt(KEY_BURN_TIME, 0);

        if (burnTime > 0) {
            burnTime--;
            state.setInt(KEY_BURN_TIME, burnTime);
            return;
        }

        if (ctx.hasValidFuel()) {
            ItemStack fuelStack = ctx.containerCtx().getItem(ctx.fuelSlot());
            int fuelValue = getFuelValue(fuelStack, recipeType);

            if (fuelValue > 0 && !LivingItemManager.isLivingItem(fuelStack)) {
                fuelStack.shrink(1);
                ctx.containerCtx().setItem(ctx.fuelSlot(), fuelStack.copy());
                burnTime = fuelValue;
                state.setInt(KEY_BURN_TIME, burnTime);
            }
        }
    }

    @Override
    public ComponentState createDefaultState() {
        return new ComponentState();
    }

    /**
     * 追加 Tooltip 信息：显示当前燃烧剩余时间。
     *
     * 显示格式："燃烧: X.Xs"（将 ticks 转换为秒）
     */
    @Override
    public void appendTooltip(ComponentState state, Consumer<Component> tooltipAdder) {
        int burnTime = state.getInt(KEY_BURN_TIME, 0);
        tooltipAdder.accept(Component.translatable(
            "tooltip.livingitem.fuel_burn",
            String.format("%.1f", burnTime / 20.0)
        ));
    }

    /**
     * 检查当前是否正在燃烧。
     *
     * @param state 组件状态
     * @return 如果燃烧时间 > 0 则返回 true
     */
    public boolean isBurning(ComponentState state) {
        return state.getInt(KEY_BURN_TIME, 0) > 0;
    }

    /**
     * 暂停 tick 时递减燃烧时间（防止无输入时燃料白白消耗）。
     *
     * 当活熔炉无法继续处理（无输入或输出满）时，
     * FunctionExecutor 调用此方法而非正常 tick，
     * 使燃烧时间缓慢递减，模拟余热消耗。
     *
     * @param state 组件状态
     */
    public void pauseTick(ComponentState state) {
        int burnTime = state.getInt(KEY_BURN_TIME, 0);
        if (burnTime > 0) {
            state.setInt(KEY_BURN_TIME, Math.max(0, burnTime - 1));
        }
    }

    /**
     * 检查燃料槽位是否有可用的燃料。
     *
     * 检查条件：
     * 1. 燃料槽位索引有效（>= 0）
     * 2. 燃料槽位有物品
     * 3. 物品有燃料值（getBurnTime > 0）
     * 4. 物品不是活物品
     *
     * @param ctx 组件上下文
     * @param config 组件配置（包含 recipe_type）
     * @return 是否有可用燃料
     */
    public boolean hasUsableFuel(ComponentContext ctx, ComponentConfig config) {
        if (!ctx.hasValidFuel()) {
            return false;
        }

        RecipeType<?> recipeType = config.get("recipe_type", RecipeType.class, RecipeType.SMELTING);
        ItemStack fuelStack = ctx.containerCtx().getItem(ctx.fuelSlot());
        int fuelValue = getFuelValue(fuelStack, recipeType);

        return fuelValue > 0 && !LivingItemManager.isLivingItem(fuelStack);
    }

    /**
     * 获取物品的燃料值。
     *
     * 通过 NeoForge 的 IItemExtension.getBurnTime() 接口查询，
     * 支持原版和模组添加的燃料物品。
     *
     * @param stack 要查询的物品
     * @param recipeType 配方类型（影响燃料值计算）
     * @return 燃料值（ticks），不可燃物品返回 0
     */
    private int getFuelValue(ItemStack stack, RecipeType<?> recipeType) {
        if (stack.isEmpty()) return 0;
        Item item = stack.getItem();
        if (!(item instanceof IItemExtension extension)) return 0;
        return extension.getBurnTime(stack, recipeType);
    }
}