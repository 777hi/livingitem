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
 * 活侦测器的功能入口。
 *
 * <p>侦测器只观察输出方向反侧的相邻槽：普通物品比较类型、数量与活化身份，活物品可经
 * {@link LivingItemFunction#getObservableState} 追加公开语义状态。红石传播完成后统一采样，
 * 检出变化则延迟 2 game tick、沿输出方向发出 2 game tick 脉冲。</p>
 *
 * <p>本类只承载注册、持久方向配置（{@link HasDirection}）与 tooltip；观测基线、延迟与脉冲
 * 属于 {@link ContainerRedstoneData} 的每槽运行态，不跟随物品迁移。</p>
 */
public class LivingObserverFunction implements LivingItemFunction, HasDirection {

    public static final String ID = "living_observer";
    private static final String[] SLOT_NAMES = {"direction"};

    public static boolean matches(ItemStack stack) {
        return stack != null && stack.is(Items.OBSERVER)
            && LivingItemManager.isLivingItem(stack);
    }

    @Override
    public boolean canApply(ItemStack stack) {
        return matches(stack);
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

    @Override
    public net.minecraft.nbt.CompoundTag getObservableState(
            ItemStack stack, int slot, ContainerContext container) {
        var tag = RedstoneObservableState.create(container, slot);
        RedstoneObservableState.putDirection(
            tag, "direction", LivingObserverData.of(stack).direction());
        return tag;
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
        if (!matches(stack) || !SLOT_NAMES[0].equals(slotName)
                || !isCardinal(direction)) return false;
        LivingObserverData data = LivingObserverData.of(stack);
        if (data.direction().equals(direction)) return false;
        LivingObserverData.set(stack, data.withDirection(direction));
        return true;
    }

    private static boolean isCardinal(Pos2D direction) {
        return direction != null && (Pos2D.UP.equals(direction) || Pos2D.DOWN.equals(direction)
            || Pos2D.LEFT.equals(direction) || Pos2D.RIGHT.equals(direction));
    }
}
