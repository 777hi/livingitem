package com.qiqi.li.living.domain.redstone;
import com.qiqi.li.living.components.LivingComponents;
import com.qiqi.li.living.domain.redstone.LivingRedstoneData;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import com.qiqi.li.living.api.LivingItemFunction;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.api.HasContainerData;
import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.container.TickContext;

/**
 * 活红石粉的功能实现，也是红石层的<b>唯一驱动点</b>。
 *
 * <p>红石粉本身只是被驱动的元件，但本类通过 {@code HasContainerData} 自维持（{@link #shouldTickWithoutOwnItems}
 * 与 {@link #getPriority} prio 2），在容器级数据流程里单点驱动 {@code ContainerRedstoneData#calculate}——
 * 即使容器里只有活按钮 / 拉杆 / 中继器（没有活红石粉），也能让它们正常联动。{@link #tickContainerData}
 * 用 {@link ContainerRedstoneData#hasRedstoneElements} 守卫「与红石无关」的容器，避免无谓计算。</p>
 */
public class LivingRedstoneFunction implements LivingItemFunction, HasContainerData {

    public static final String ID = "living_redstone";

    @Override
    public boolean canApply(ItemStack stack) {
        return stack.is(Items.REDSTONE) && LivingItemManager.isLivingItem(stack);
    }

    @Override
    public String getFunctionId() {
        return ID;
    }

    @Override
    public void tick(List<SlotEntry> entries, ContainerContext context, TickContext tick, Level level) {
    }

    @Override
    public Set<DataComponentType<?>> getOwnedComponentTypes() {
        return Set.of(LivingComponents.LIVING_REDSTONE_DATA.value());
    }

    @Override
    public Set<DataComponentType<?>> getIgnoredComponentTypes() {
        return Set.of();
    }

    @Override
    public void addToTooltip(Item.TooltipContext context,
                             Consumer<Component> tooltipAdder,
                             TooltipFlag flag,
                             ItemStack stack) {
        LivingRedstoneData data = LivingRedstoneData.of(stack);

        tooltipAdder.accept(Component.nullToEmpty(""));
        tooltipAdder.accept(Component.translatable("tooltip.livingitem.redstone.title"));

        if (data.isPowered()) {
            tooltipAdder.accept(Component.literal("  ")
                .append(Component.translatable("tooltip.livingitem.redstone.signal"))
                .append(Component.literal(": " + data.signalStrength()))
                .withStyle(ChatFormatting.RED));
        } else {
            tooltipAdder.accept(Component.translatable("tooltip.livingitem.redstone.no_signal")
                .withStyle(ChatFormatting.DARK_GRAY));
        }

        tooltipAdder.accept(Component.translatable("tooltip.livingitem.redstone.max_signal")
            .append(Component.literal(": " + ContainerRedstoneData.getSignalCap(stack.getCount())))
            .withStyle(ChatFormatting.GRAY));
    }

    /**
     * <b>自维持</b>（2026-10-08）：红石层驱动权收归本类，对称流体侧 {@code LivingFluidFunction}。
     *
     * <p>为什么必须自维持：容器里只有活按钮 / 拉杆 / 中继器（没有活红石粉）时，本类若不在
     * {@code grouped} 里就<b>无人驱动</b> {@code calculate} ⇒ 那些元件失灵。</p>
     */
    @Override
    public boolean shouldTickWithoutOwnItems(ContainerContext ctx) {
        return true;
    }

    /** 容器级数据顺序：2 = 红石层（流体 0 → 水车 1 → 红石 2 → 电力 3，电力依赖本层已算完）。 */
    @Override
    public int getPriority() {
        return 2;
    }

    /**
     * 红石层的<b>唯一驱动点</b>（2026-10-08 收归）。
     *
     * <p>原先 9 个红石元件 + 活 TNT 各写一段完全相同的 {@code calculate} 调用，靠
     * {@code processedThisTick} 幂等短路兜底 —— 那是「<b>错误样板的产地</b>」
     * （活 TNT 那处就是照抄来的，且抄错的注释至今留在代码里）。
     * 现改为：本类自维持 ⇒ 容器里没有活红石粉也进入容器级数据流程，在 prio 2 单点驱动。</p>
     *
     * <p>⚠️ <b>守卫必须保留</b>：自维持 = <b>每个被 tick 的容器</b>都会走到这里；
     * 没有守卫的话，纯活熔炉容器也会每 tick 创建账本并跑一次 {@code calculate}
     * （内含 {@code notifyBoundaryChange} → {@code level.updateNeighborsAt}，代价不可忽略）。</p>
     *
     * <p>⭐ 守卫<b>不会漏掉消费者</b>：{@code TickContext.getSensor(ctx)} 就是
     * {@code getOrCreateRedstoneData(ctx)} ⇒ 消费者（活 TNT / 活漏斗 / 电力层）在
     * {@code tick()} 里一读就创建了账本 ⇒ 等本方法跑到时 {@code peek != null} 必然成立。
     * ⇒ 守卫的真实语义是「<b>这个容器从来与红石无关</b>」，而不是「有没有人需要红石」。</p>
     */
    @Override
    public void tickContainerData(List<SlotEntry> entries, ContainerContext ctx, TickContext tick) {
        if (ctx.peekContainerData(ContainerRedstoneData.KEY) == null
                && !ContainerRedstoneData.hasRedstoneElements(tick)) {
            return;
        }
        tick.getOrCreateRedstoneData(ctx).calculate(ctx, tick);
    }
}