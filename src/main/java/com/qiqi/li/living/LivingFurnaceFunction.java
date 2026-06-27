package com.qiqi.li.living;

import java.util.function.Consumer;
import net.minecraft.network.chat.Component;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.extensions.IItemExtension;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

/**
 * 活熔炉功能。
 *
 * 工作原理：
 * 当一个被标记为"活物品"的熔炉放在容器（如箱子）中时，它可以：
 * - 读取左侧槽位的物品作为输入（原料）
 * - 读取下方槽位的物品作为燃料
 * - 将熔炼产物输出到右侧槽位
 *
 * 堆叠机制：
 * - 堆叠了 N 个活熔炉，可以同时熔炼 N 个物品（受输入数量和输出空间限制）
 * - 燃料消耗仍然是每次一个（需要多个燃料才能持续熔炼）
 * - 双倍产物的配方会让每个输入产生多个输出
 *
 * NBT 数据结构（存放在 LIVING_FUNCTION_DATA 组件的 "living_furnace" 子 tag 中）:
 * <pre>
 * {
 *   "burn_time": 200,        // 当前燃料剩余燃烧时间（tick）
 *   "cook_time": 50,         // 当前已烹饪时间（tick）
 *   "cook_time_total": 200   // 完成一次烹饪所需总时间（tick）
 * }
 * </pre>
 */
public class LivingFurnaceFunction implements LivingItemFunction {

    /** 功能 ID —— 作为 NBT 中的 key，稳定且唯一 */
    public static final String ID = "living_furnace";

    public static final Logger LOGGER = LogUtils.getLogger();

    // ==================== NBT 数据键名 ====================

    /** 剩余燃烧时间（tick），每 tick 减 1，为 0 时需要消耗新燃料 */
    private static final String DATA_BURN_TIME = "burn_time";

    /** 已烹饪时间（tick），每 tick 加 1，达到 cook_time_total 时完成一次烹饪 */
    private static final String DATA_COOK_TIME = "cook_time";

    /** 烹饪所需总时间（tick），根据当前配方决定，初始为 0 表示尚未开始 */
    private static final String DATA_COOK_TIME_TOTAL = "cook_time_total";

    // ==================== 容器布局 ====================

    /** 容器宽度（行长度），用于计算相对位置 */
    private static final int CONTAINER_WIDTH = 9;

    // ==================== 接口实现 ====================

    /**
     * 判断是否为活熔炉 —— 必须是熔炉物品且被标记为活物品。
     */
    @Override
    public boolean canApply(ItemStack stack) {
        return stack.is(Items.FURNACE) && LivingItemManager.isLivingItem(stack);
    }

    /**
     * 每 tick 的熔炼逻辑。
     *
     * 流程图：
     * 1. 计算相对槽位（输入在左、燃料在下、输出在右）
     * 2. 从 LIVING_FUNCTION_DATA 组件读取 burn_time / cook_time / cook_time_total
     * 3. 燃料消耗：如果当前有燃烧中的燃料（burn_time > 0），每 tick 减 1
     * 4. 寻找配方：检查输入物品是否有对应的熔炼配方
     * 5. 有配方且能熔炼时：若燃料不足则消耗一个燃料，增加 cook_time
     * 6. cook_time 达到 cook_time_total 时：减少输入，增加输出，重置 cook_time
     * 7. 将更新后的数据写回 LIVING_FUNCTION_DATA 组件
     * 8. 主动同步到客户端（每 tick 一次），确保 tooltip 实时显示最新状态
     */
    @Override
    public void tick(ItemStack stack, int slotIndex, ContainerContext context, Level level) {
        // 仅在服务器端执行
        if (level.isClientSide) return;

        // 活熔炉堆叠数量 —— 决定了一次可以同时熔炼多少物品
        int furnaceCount = Math.max(1, stack.getCount());

        // 计算三个相对槽位：左=输入、下=燃料、右=输出
        int inputIndex = getRelativeIndex(slotIndex, CONTAINER_WIDTH, -1, 0);
        int fuelIndex = getRelativeIndex(slotIndex, CONTAINER_WIDTH, 0, 1);
        int outputIndex = getRelativeIndex(slotIndex, CONTAINER_WIDTH, 1, 0);

        // 检查槽位是否有效（例如活熔炉在第一行时，燃料槽会越界）
        if (!context.isValidSlot(inputIndex) || !context.isValidSlot(fuelIndex) || !context.isValidSlot(outputIndex)) {
            return;
        }

        // 获取三个槽位的物品引用
        // 注意：context.getItem 返回容器内部的 ItemStack 引用，
        // 所以我们对其的修改会直接影响容器中的物品（燃料消耗、输入减少等）
        ItemStack inputStack = context.getItem(inputIndex);
        ItemStack fuelStack = context.getItem(fuelIndex);
        ItemStack outputStack = context.getItem(outputIndex);

        // 从活物品数据组件读取当前状态
        CompoundTag tag = LivingItemManager.getFunctionData(stack, ID);
        int burnTime = tag.getInt(DATA_BURN_TIME);
        int cookTime = tag.getInt(DATA_COOK_TIME);
        int cookTimeTotal = tag.getInt(DATA_COOK_TIME_TOTAL);

        // ============ 阶段 1：燃料燃烧 ============
        // 如果当前有燃烧中的燃料，每 tick 消耗 1 单位燃烧时间
        if (burnTime > 0) burnTime--;

        // ============ 阶段 2：烹饪逻辑（仅当有输入物品时执行） ============
        if (!inputStack.isEmpty()) {
            // 查找输入物品对应的熔炼配方
            SingleRecipeInput recipeInput = new SingleRecipeInput(inputStack);
            var recipeHolderOpt = level.getRecipeManager()
                    .getRecipeFor(RecipeType.SMELTING, recipeInput, level);

            if (recipeHolderOpt.isPresent()) {
                // 有匹配的配方
                SmeltingRecipe recipe = recipeHolderOpt.get().value();
                ItemStack result = recipe.getResultItem(level.registryAccess());
                int resultCount = result.getCount();  // 单次产出数量（某些配方可能有双倍产物）

                // 计算一次最多能熔炼多少个物品
                int outputSpace = getOutputSpace(outputStack, result, context.getMaxStackSize());
                int maxByOutput = resultCount > 0 ? outputSpace / resultCount : 0;
                int smeltCount = Math.min(furnaceCount, Math.min(inputStack.getCount(), maxByOutput));

                if (smeltCount > 0) {
                    // 可以进行熔炼

                    // 如果燃料燃尽且有燃料物品，消耗一个燃料
                    if (burnTime <= 0 && !fuelStack.isEmpty()) {
                        int fuelValue = getFuelValue(fuelStack);
                        if (fuelValue > 0) {
                            burnTime = fuelValue;          // 设置新的燃烧时间
                            fuelStack.shrink(1);           // 消耗一个燃料
                            context.setItem(fuelIndex, fuelStack.copy());  // 通知容器数据变更
                        }
                    }

                    if (burnTime > 0) {
                        // 有燃料在燃烧，推进烹饪进度
                        if (cookTimeTotal == 0) {
                            // 刚开始烹饪 —— 设置配方的烹饪时间
                            cookTimeTotal = recipe.getCookingTime();
                        }
                        cookTime++;

                        if (cookTime >= cookTimeTotal) {
                            // 烹饪完成！重置计时器，消耗输入，增加输出
                            cookTime = 0;
                            cookTimeTotal = 0;

                            // 消耗输入
                            inputStack.shrink(smeltCount);
                            context.setItem(inputIndex, inputStack.copy());

                            // 增加输出
                            if (outputStack.isEmpty()) {
                                // 输出槽为空，创建新的物品堆
                                ItemStack newOutput = result.copy();
                                newOutput.setCount(smeltCount * resultCount);
                                context.setItem(outputIndex, newOutput);
                            } else {
                                // 输出槽已有物品，直接增加数量
                                outputStack.grow(smeltCount * resultCount);
                                context.setItem(outputIndex, outputStack.copy());
                            }
                        }
                    }
                } else {
                    // 无法熔炼（输出空间不足等）—— 重置烹饪进度
                    cookTime = 0;
                    cookTimeTotal = 0;
                }
            } else {
                // 输入物品没有对应的熔炼配方 —— 重置烹饪进度
                cookTime = 0;
                cookTimeTotal = 0;
            }
        } else {
            // 没有输入物品 —— 重置烹饪进度（但允许燃料继续燃烧）
            cookTime = 0;
            cookTimeTotal = 0;
        }

        // ============ 阶段 3：写回数据并同步到客户端 ============

        // 将更新后的数据写回 —— LivingItemManager 会处理不可变组件的更新逻辑
        tag.putInt(DATA_BURN_TIME, burnTime);
        tag.putInt(DATA_COOK_TIME, cookTime);
        tag.putInt(DATA_COOK_TIME_TOTAL, cookTimeTotal);
        LivingItemManager.setFunctionData(stack, ID, tag);

        // 主动同步到客户端：
        // 原版 broadcastChanges() 使用 ItemStack.matches() 检测变化，
        // 但 PatchedDataComponentMap.equals() 无法检测 LIVING_FUNCTION_DATA 的变化，
        // 所以必须手动发送 ClientboundContainerSetSlotPacket。
        // syncSlotToClients 会正确处理 stateId 和 remoteSlots 更新。
        context.syncSlotToClients(slotIndex, stack);
    }

    /**
     * 向 Tooltip 添加活熔炉状态信息。
     * 采用 Minecraft 标准的 TooltipProvider 风格。
     *
     * 显示内容示例：
     * <pre>
     *   [Living Furnace]
     *   Fuel: 10.0s
     *   Smelting: 25% (5.0/20.0s)
     * </pre>
     *
     * @param functionData 活熔炉对应的运行时数据
     * @param context 物品 tooltip 上下文
     * @param tooltipAdder 向 tooltip 添加内容的 Consumer
     * @param flag tooltip 显示标志
     */
    @Override
    public void addToTooltip(CompoundTag functionData, Item.TooltipContext context,
                             Consumer<Component> tooltipAdder, TooltipFlag flag) {
        // 如果没有任何数据（如新物品或未工作过），不显示状态信息
        if (functionData == null || functionData.isEmpty()) return;

        // 从数据中读取三个关键字段
        int burnTime = functionData.getInt(DATA_BURN_TIME);
        int cookTime = functionData.getInt(DATA_COOK_TIME);
        int cookTimeTotal = functionData.getInt(DATA_COOK_TIME_TOTAL);

        // 空行 —— 与其他 tooltip 内容之间留出视觉分隔
        tooltipAdder.accept(Component.nullToEmpty(""));
        // 标题 —— 显示"活熔炉"功能名称
        tooltipAdder.accept(Component.translatable("tooltip.livingitem.furnace.status"));
        // 燃料剩余时间 —— tick 转换为秒（除以 20），保留 1 位小数
        tooltipAdder.accept(Component.translatable("tooltip.livingitem.furnace.burn",
                String.format("%.1f", burnTime / 20.0)));

        if (cookTimeTotal > 0) {
            // 正在烹饪 —— 显示进度百分比和已烹饪/总时间
            int percent = (int) ((cookTime * 100.0f) / cookTimeTotal);
            tooltipAdder.accept(Component.translatable("tooltip.livingitem.furnace.cook",
                    percent,
                    String.format("%.1f", cookTime / 20.0),
                    String.format("%.1f", cookTimeTotal / 20.0)));
        } else if (cookTime > 0) {
            // 烹饪刚开始但还没完成一次（理论上应该不会进入这个分支）
            tooltipAdder.accept(Component.translatable("tooltip.livingitem.furnace.cook_wait",
                    String.format("%.1f", cookTime / 20.0)));
        }
    }

    // ==================== 辅助方法 ====================

    /**
     * 根据相对偏移计算槽位索引。
     *
     * @param fromIndex 起始槽位（活熔炉所在位置）
     * @param width 容器宽度（每行多少格）
     * @param dx 水平偏移（-1 左，+1 右）
     * @param dy 垂直偏移（-1 上，+1 下）
     * @return 计算得到的槽位索引
     */
    private int getRelativeIndex(int fromIndex, int width, int dx, int dy) {
        int row = fromIndex / width;
        int col = fromIndex % width;
        return (row + dy) * width + (col + dx);
    }

    /**
     * 计算输出槽剩余空间。
     *
     * @param outputStack 当前输出槽的物品（可能为空）
     * @param result 配方产出物品（用于判断类型是否兼容）
     * @param maxStackSize 容器允许的最大堆叠数
     * @return 还能容纳的物品数量
     */
    private int getOutputSpace(ItemStack outputStack, ItemStack result, int maxStackSize) {
        if (outputStack.isEmpty()) {
            // 输出槽为空 —— 可容纳 min(容器限制, 物品本身最大堆叠数)
            return Math.min(maxStackSize, result.getMaxStackSize());
        }
        if (ItemStack.isSameItemSameComponents(outputStack, result)) {
            // 输出槽已有相同物品 —— 剩余空间 = 最小限制 - 当前数量
            return Math.min(maxStackSize, outputStack.getMaxStackSize()) - outputStack.getCount();
        }
        // 物品不同（或组件不同）—— 无法堆叠
        return 0;
    }

    /**
     * 获取物品的燃料价值（燃烧时间，单位 tick）。
     * 使用 NeoForge 的扩展接口，支持 mod 添加的燃料物品。
     *
     * @param stack 要检查的燃料物品
     * @return 可提供的燃烧时间（tick），如果不是燃料则返回 0
     */
    private int getFuelValue(ItemStack stack) {
        return ((IItemExtension) stack.getItem()).getBurnTime(stack, RecipeType.SMELTING);
    }

    @Override
    public String getFunctionId() {
        return ID;
    }
}