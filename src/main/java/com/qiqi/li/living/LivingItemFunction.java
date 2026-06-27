package com.qiqi.li.living;

import java.util.function.Consumer;
import net.minecraft.network.chat.Component;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

/**
 * 活物品功能接口。
 * 每个实现类代表一种活物品的功能（如活熔炉、活漏斗、活箱子等）。
 *
 * 一个 ItemStack 可以拥有多个活物品功能（例如，一个活熔炉同时也可以是活漏斗），
 * 但通常每个物品只应用一个主功能。
 */
public interface LivingItemFunction {

    /**
     * 判断该功能是否适用于指定的物品。
     * 用于在物品 tick 和 tooltip 渲染时筛选适用的功能。
     *
     * @param stack 要检查的物品
     * @return 如果适用则返回 true
     */
    boolean canApply(ItemStack stack);

    /**
     * 每 tick 调用一次，执行该功能的逻辑。
     * 仅在服务器端调用（已通过 {@code !level.isClientSide} 过滤）。
     *
     * @param stack 活物品本身
     * @param slotIndex 活物品在容器中的槽位索引
     * @param context 容器上下文，用于访问其他槽位、获取槽位数据等
     * @param level 当前世界
     */
    void tick(ItemStack stack, int slotIndex, ContainerContext context, Level level);

    /**
     * 向 Tooltip 添加该功能的状态信息。
     * 采用 Minecraft 标准的 {@link TooltipProvider} 风格签名。
     *
     * 与旧签名相比的变化：
     * - 不再传入 ItemStack：数据从 functionData 参数直接获取，避免重新从组件中读取
     * - 不再传入 Level：通过 TooltipContext.registries() 可以访问注册表
     * - 使用 Consumer<Component> 替代 List<Component>：与 TooltipProvider 接口保持一致
     * - 增加 TooltipFlag：支持根据详细程度显示不同内容
     *
     * @param functionData 该功能对应的运行时数据（如活熔炉的燃烧时间、烹饪进度）
     * @param context 物品 tooltip 上下文，包含 registryAccess 等信息
     * @param tooltipAdder 向 tooltip 添加一行内容的 Consumer，直接 accept 即可
     * @param flag tooltip 显示标志（是否详细模式、是否创造模式）
     */
    void addToTooltip(CompoundTag functionData, Item.TooltipContext context,
                      Consumer<Component> tooltipAdder, TooltipFlag flag);

    /**
     * 获取该功能的唯一标识符。
     * 用作 NBT 中的 key（如 "living_furnace"），因此必须保持稳定且唯一。
     *
     * @return 功能的唯一 ID 字符串
     */
    String getFunctionId();
}