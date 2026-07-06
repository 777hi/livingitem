package com.qiqi.li.living;

import java.util.List;
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
 *
 * tick 调用模型：
 *   每个容器每 tick 对每种功能只调用一次 {@link #tick}，
 *   传入该容器中所有拥有此功能的活物品列表。
 *   由功能实现自行决定如何处理（如活熔炉每 tick 只处理一个），
 *   避免多个同类活物品并行导致速度翻倍。
 */
public interface LivingItemFunction {

    /**
     * 活物品在容器中的槽位条目，包含槽位索引和物品引用。
     */
    record SlotEntry(int slotIndex, ItemStack stack) {}

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
     *
     * 调用模型：每个容器每 tick 对每种功能只调用一次，
     * 传入该容器中所有拥有此功能的活物品列表。
     * 由功能实现自行决定如何分配处理（如活熔炉每 tick 只处理一个），
     * 避免多个同类活物品并行导致速度翻倍。
     *
     * 仅在服务器端调用（已通过 {@code !level.isClientSide} 过滤）。
     *
     * @param entries 该容器中拥有此功能的所有活物品条目
     * @param context 容器上下文，用于访问其他槽位、获取槽位数据等
     * @param level 当前世界
     */
    void tick(List<SlotEntry> entries, ContainerContext context, Level level);

    /**
     * 向 Tooltip 添加该功能的状态信息。
     * 采用 Minecraft 标准的 {@link TooltipProvider} 风格签名。
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