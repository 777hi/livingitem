package com.qiqi.li.living.core.components;

import java.util.function.Consumer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.Level;
import com.qiqi.li.living.LivingItemManager;
import com.qiqi.li.living.core.ComponentConfig;
import com.qiqi.li.living.core.ComponentContext;
import com.qiqi.li.living.core.ComponentState;

/**
 * 物品转化组件 —— 实现活熔炉的配方匹配与物品转化逻辑。
 *
 * 职责：
 * 1. 检查输入槽位物品是否匹配配方
 * 2. 检查输出槽位是否有空间放置产物
 * 3. 执行转化：消耗输入物品，生成产物
 *
 * 工作流程：
 *   canProcess() → 检查输入/输出有效性
 *   executeTransform() → 实际执行转化操作
 *
 * 转化规则：
 *   - 通过 Minecraft 的 RecipeManager 查询匹配的配方
 *   - 转化数量受限于：输入物品数量、活熔炉堆叠数、输出空间
 *   - 活物品不能作为输入（LivingItemManager.isLivingItem 检查）
 *   - 堆叠加速：多个活熔炉堆叠时，一次转化可处理多个物品
 *
 * 配置参数（通过 ComponentConfig）：
 *   - recipe_type: RecipeType<?>, 配方类型（默认 SMELTING）
 *
 * 与其他组件的协作：
 *   - ProgressComponent：进度完成后触发 executeTransform()
 *   - FuelConsumeComponent：需要燃烧中才能执行转化
 *   - DirectionModeComponent：提供输入/输出槽位方向
 *
 * 使用示例（活熔炉配置）：
 * <pre>
 * .addComponent(ItemTransformComponent.class,
 *     ComponentConfig.of("recipe_type", RecipeType.SMELTING))
 * </pre>
 */
public class ItemTransformComponent implements ILivingComponent {

    /** 组件 ID，用于在 ComponentState 和 NBT 中标识此组件 */
    public static final String ID = "transform";

    /** NBT 键名：输入物品的资源路径（如 "minecraft:iron_ore"） */
    private static final String KEY_INPUT_ITEM = "input_item";

    /** NBT 键名：输出物品的资源路径（如 "minecraft:iron_ingot"） */
    private static final String KEY_OUTPUT_ITEM = "output_item";

    /** NBT 键名：上次配方检查的输入物品 ID（用于缓存跳过重复查表） */
    private static final String KEY_CACHED_INPUT = "cached_input";

    /** NBT 键名：上次配方检查的结果（0=无配方, 1=有配方） */
    private static final String KEY_CACHED_RESULT = "cached_result";

    /** NBT 键名：上次配方检查的输出物品 ID（用于缓存输出空间检查） */
    private static final String KEY_CACHED_OUTPUT = "cached_output";

    /** NBT 键名：缓存配方的输出物品数量（如 1 个矿石产出 1 个锭） */
    private static final String KEY_CACHED_OUTPUT_COUNT = "cached_output_count";

    /** NBT 键名：配方的烹饪时间（ticks），从 AbstractCookingRecipe.getCookingTime() 获取 */
    private static final String KEY_COOKING_TIME = "cooking_time";

    @Override
    public String getComponentId() { return ID; }

    /**
     * 此组件的 tick 为空操作。
     *
     * 实际的转化逻辑由 FunctionExecutor.handleCompletion() 在进度完成时触发，
     * 调用 executeTransform() 方法。这是因为转化需要与进度组件同步：
     * 进度未完成时不执行转化，进度完成时一次性执行。
     */
    @Override
    public void tick(ComponentContext ctx, int hostSlot, ItemStack hostStack,
                     ComponentState state, ComponentConfig config) {
        if (!ctx.hasValidInput()) {
            state.setString(KEY_INPUT_ITEM, "");
            state.setString(KEY_OUTPUT_ITEM, "");
            state.setString(KEY_CACHED_INPUT, "");
            state.setInt(KEY_CACHED_RESULT, 0);
            state.setString(KEY_CACHED_OUTPUT, "");
        }
    }

    @Override
    public ComponentState createDefaultState() {
        return new ComponentState();
    }

    /**
     * 追加 Tooltip 信息：显示当前配方（原料 → 成品）。
     *
     * <p>配方信息在 executeTransform() 中保存到状态，tooltip 直接读取显示。
     * 格式："原料: [铁矿石] → [铁锭]"
     *
     * <p>如果尚未执行过转化，不显示任何信息。
     */
    @Override
    public void appendTooltip(ComponentState state, Consumer<Component> tooltipAdder) {
        String inputId = state.getString(KEY_INPUT_ITEM, "");
        String outputId = state.getString(KEY_OUTPUT_ITEM, "");

        if (inputId.isEmpty()) return;

        Component inputName = getItemDisplayName(inputId);
        
        if (!outputId.isEmpty()) {
            Component outputName = getItemDisplayName(outputId);
            tooltipAdder.accept(Component.translatable(
                    "tooltip.livingitem.transform.recipe", inputName, outputName));
        }
    }

    /**
     * 根据物品资源路径获取其显示名称。
     *
     * @param itemId 物品资源路径（如 "minecraft:iron_ore"）
     * @return 物品的显示名称组件；如果物品不存在则返回原始 ID
     */
    private static Component getItemDisplayName(String itemId) {
        try {
            ResourceLocation rl = ResourceLocation.parse(itemId);
            Item item = BuiltInRegistries.ITEM.get(rl);
            if (item != null) {
                return new ItemStack(item).getDisplayName();
            }
        } catch (Exception ignored) {
        }
        return Component.literal(itemId);
    }

    /**
     * 查询配方结果，优先使用缓存。
     *
     * <p>缓存命中时跳过 RecipeManager 查询，零开销。
     * 缓存未命中时查询配方并更新缓存。</p>
     *
     * @return 配方结果（null 表示无匹配配方）
     */
    private RecipeResult resolveRecipe(ComponentContext ctx, ComponentState state, ComponentConfig config) {
        ItemStack inputStack = ctx.containerCtx().getItem(ctx.inputSlot());
        if (inputStack.isEmpty() || LivingItemManager.isLivingItem(inputStack)) return null;

        RecipeType<?> recipeType = config.get("recipe_type", RecipeType.class, RecipeType.SMELTING);
        ResourceLocation inputRl = BuiltInRegistries.ITEM.getKey(inputStack.getItem());
        String inputKey = inputRl.toString();

        String cachedInput = state.getString(KEY_CACHED_INPUT, "");
        if (inputKey.equals(cachedInput)) {
            if (state.getInt(KEY_CACHED_RESULT, 0) != 1) return null;
            String outputItemId = state.getString(KEY_CACHED_OUTPUT, "");
            int resultCount = state.getInt(KEY_CACHED_OUTPUT_COUNT, 1);
            return new RecipeResult(inputKey, outputItemId, resultCount);
        }

        SingleRecipeInput recipeInput = new SingleRecipeInput(inputStack);
        var recipeHolderOpt = ctx.level().getRecipeManager()
                .getRecipeFor((RecipeType)recipeType, recipeInput, ctx.level());

        if (recipeHolderOpt.isEmpty()) {
            state.setString(KEY_CACHED_INPUT, inputKey);
            state.setInt(KEY_CACHED_RESULT, 0);
            state.setString(KEY_CACHED_OUTPUT, "");
            state.setInt(KEY_CACHED_OUTPUT_COUNT, 1);
            return null;
        }

        Recipe<?> recipe = unwrapRecipe(recipeHolderOpt.get());
        ItemStack result = recipe.getResultItem(ctx.level().registryAccess());
        int resultCount = result.getCount();
        int cookingTime = getCookingTimeFromRecipe(recipe);

        ResourceLocation outputRl = BuiltInRegistries.ITEM.getKey(result.getItem());
        String outputKey = outputRl.toString();

        state.setString(KEY_CACHED_INPUT, inputKey);
        state.setInt(KEY_CACHED_RESULT, 1);
        state.setString(KEY_CACHED_OUTPUT, outputKey);
        state.setInt(KEY_CACHED_OUTPUT_COUNT, resultCount);
        state.setInt(KEY_COOKING_TIME, cookingTime);
        state.setString(KEY_INPUT_ITEM, inputKey);
        state.setString(KEY_OUTPUT_ITEM, outputKey);

        return new RecipeResult(inputKey, outputKey, resultCount);
    }

    private record RecipeResult(String inputKey, String outputItemId, int resultCount) {}

    public boolean executeTransform(ComponentContext ctx, ItemStack hostStack,
                                     ComponentConfig config, ProgressComponent progress,
                                     ComponentState transformState) {

        if (!ctx.hasValidInput() || !ctx.hasValidOutput()) return false;

        RecipeResult recipe = resolveRecipe(ctx, transformState, config);
        if (recipe == null) return false;

        ItemStack resultItem;
        try {
            ResourceLocation outputRl = ResourceLocation.parse(recipe.outputItemId());
            Item outputItem = BuiltInRegistries.ITEM.get(outputRl);
            resultItem = new ItemStack(outputItem, recipe.resultCount());
        } catch (Exception e) {
            return false;
        }

        ItemStack inputStack = ctx.containerCtx().getItem(ctx.inputSlot());
        int stackMultiplier = Math.max(1, hostStack.getCount());
        int outputSpace = calculateOutputSpace(ctx, resultItem);
        int maxByOutput = recipe.resultCount() > 0 ? outputSpace / recipe.resultCount() : 0;
        int transformCount = Math.min(stackMultiplier, Math.min(inputStack.getCount(), maxByOutput));

        if (transformCount <= 0) return false;

        inputStack.shrink(transformCount);
        ctx.containerCtx().setItem(ctx.inputSlot(), inputStack.copy());

        ItemStack outputStack = ctx.containerCtx().getItem(ctx.outputSlot());
        if (outputStack.isEmpty()) {
            ItemStack newOutput = resultItem.copy();
            newOutput.setCount(transformCount * recipe.resultCount());
            ctx.containerCtx().setItem(ctx.outputSlot(), newOutput);
        } else {
            outputStack.grow(transformCount * recipe.resultCount());
            ctx.containerCtx().setItem(ctx.outputSlot(), outputStack.copy());
        }

        return true;
    }

    private static Recipe<?> unwrapRecipe(Object recipeHolder) {
        if (recipeHolder instanceof net.minecraft.world.item.crafting.RecipeHolder<?> holder) {
            return holder.value();
        }
        return (Recipe<?>)recipeHolder;
    }

    private static int getCookingTimeFromRecipe(Recipe<?> recipe) {
        try {
            var method = recipe.getClass().getMethod("getCookingTime");
            return (int) method.invoke(recipe);
        } catch (Exception ignored) {}
        return 200;
    }

    /**
     * 计算输出槽位的剩余空间。
     *
     * 三种情况：
     * 1. 输出槽位为空：空间 = min(容器最大堆叠, 产物最大堆叠)
     * 2. 输出槽位有相同物品：空间 = 最大堆叠 - 当前数量
     * 3. 输出槽位有不同物品：空间 = 0（无法输出）
     *
     * @param ctx 组件上下文
     * @param result 配方产物
     * @return 可放置的物品数量
     */
    private int calculateOutputSpace(ComponentContext ctx, ItemStack result) {
        ItemStack outputStack = ctx.containerCtx().getItem(ctx.outputSlot());
        int slotLimit = ctx.containerCtx().getSlotLimit(ctx.outputSlot());

        if (outputStack.isEmpty()) {
            return Math.min(slotLimit, result.getMaxStackSize());
        }

        if (ItemStack.isSameItemSameComponents(outputStack, result)) {
            int maxCount = Math.min(slotLimit, outputStack.getMaxStackSize());
            return maxCount - outputStack.getCount();
        }

        return 0;
    }

    /**
     * 检查当前是否可以执行转化，并在找到配方时保存输入/输出物品信息到状态。
     *
     * <p>检查条件：
     * 1. 输入和输出槽位索引有效
     * 2. 输入槽位有物品
     * 3. 输入物品不是活物品
     * 4. 输入物品有匹配的配方
     *
     * <p>注意：此方法不检查输出空间，仅检查输入有效性。
     * 输出空间检查在 executeTransform() 中进行。
     *
     * <p>副作用：当找到匹配配方时，将输入/输出物品 ID 保存到状态中，
     * 使 tooltip 能在进度刚开始时就显示配方信息，无需等到第一次转化完成。
     *
     * @param ctx   组件上下文
     * @param state 本组件的状态（用于保存配方信息）
     * @return 是否可以执行转化
     */
    public boolean canProcess(ComponentContext ctx, ComponentState state, ComponentConfig config) {
        if (!ctx.hasValidInput() || !ctx.hasValidOutput()) {
            return false;
        }

        RecipeResult recipe = resolveRecipe(ctx, state, config);
        if (recipe == null) return false;

        return hasOutputSpace(ctx, recipe.outputItemId());
    }

    private boolean hasOutputSpace(ComponentContext ctx, String outputItemId) {
        ItemStack outputStack = ctx.containerCtx().getItem(ctx.outputSlot());
        if (outputStack.isEmpty()) return true;

        ResourceLocation outputRl = BuiltInRegistries.ITEM.getKey(outputStack.getItem());
        if (!outputRl.toString().equals(outputItemId)) return false;

        int slotLimit = ctx.containerCtx().getSlotLimit(ctx.outputSlot());
        int maxCount = Math.min(slotLimit, outputStack.getMaxStackSize());
        return outputStack.getCount() < maxCount;
    }

    public int getCookingTime(ComponentState state) {
        return state.getInt(KEY_COOKING_TIME, 200);
    }
}