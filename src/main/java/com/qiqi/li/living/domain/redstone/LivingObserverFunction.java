package com.qiqi.li.living.domain.redstone;

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
import com.qiqi.li.living.api.HasDirection;
import com.qiqi.li.living.api.LivingItemFunction;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.container.TickContext;
import com.qiqi.li.living.model.Pos2D;

/**
 * 活观测者的功能实现 —— 容器内容的「变化传感器」。
 *
 * <p>观测本容器的内容签名（物品 id + 数量 + 槽位排列），变化时沿朝向发出一次红石脉冲
 * （2 game tick 延迟 + 2 game tick 宽度，对齐原版观测者）。全部传播逻辑住在
 * {@link RedstonePropagation}（Phase 0 倒计时 / Phase 1 检测受理与输出），
 * 本类只承载注册、朝向配置（{@link HasDirection}）与 tooltip。</p>
 *
 * <p>⚠️ 只读元件：不写任何槽位、不消耗物品；驱动权在 {@link LivingRedstoneFunction}
 * 单点（不实现 {@code HasContainerData}，同火把 / 拉杆）。</p>
 */
public class LivingObserverFunction implements LivingItemFunction, HasDirection {

    public static final String ID = "living_observer";
    private static final String[] SLOT_NAMES = {"direction"};

    @Override
    public boolean canApply(ItemStack stack) {
        return stack.is(Items.OBSERVER) && LivingItemManager.isLivingItem(stack);
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
        return Set.of(RedstoneComponents.LIVING_OBSERVER_DATA.value());
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
        LivingObserverData data = LivingObserverData.of(stack);

        tooltipAdder.accept(Component.nullToEmpty(""));
        tooltipAdder.accept(Component.translatable("tooltip.livingitem.observer.title"));

        tooltipAdder.accept(Component.literal("  ")
            .append(Component.translatable("tooltip.livingitem.observer.facing"))
            .append(Component.literal(": "))
            .append(Component.translatable("tooltip.livingitem.observer.direction." + directionKey(data.direction())))
            .withStyle(ChatFormatting.GRAY));

        tooltipAdder.accept(Component.translatable("tooltip.livingitem.observer.max_signal")
            .append(Component.literal(": " + ContainerRedstoneData.getSignalCap(stack.getCount())))
            .withStyle(ChatFormatting.GRAY));
    }

    private static String directionKey(Pos2D dir) {
        if (dir.equals(Pos2D.UP)) return "up";
        if (dir.equals(Pos2D.DOWN)) return "down";
        if (dir.equals(Pos2D.LEFT)) return "left";
        if (dir.equals(Pos2D.RIGHT)) return "right";
        return "up";
    }

    @Override
    public int getDirectionKeyCount() {
        return 1;
    }

    @Override
    public String[] getDirectionSlotNames() {
        return SLOT_NAMES;
    }

    @Override
    public boolean updateSlotDirection(ItemStack stack, String slotName, Pos2D direction) {
        if (stack == null || stack.isEmpty() || direction == null) return false;
        LivingObserverData data = LivingObserverData.of(stack);
        LivingObserverData.set(stack, data.withDirection(direction));
        return true;
    }
}
