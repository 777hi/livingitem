package com.qiqi.li.living.domain.redstone;
import com.qiqi.li.living.components.LivingComponents;
import com.qiqi.li.living.domain.redstone.LivingRedstoneTorchData;

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
import com.qiqi.li.living.api.HasDirection;
import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.container.TickContext;
import com.qiqi.li.living.model.Pos2D;

/**
 * 活红石火把的功能实现。
 *
 * <p>红石火把是反相信号源：亮时（未接地）向外输出满信号，被侧边输入强信号「压灭」时熄灭且不输出。
 * 本类管理火把的朝向（{@link HasDirection}，由 {@link #updateTorchDirection} 设置）与亮灭状态。</p>
 */
public class LivingRedstoneTorchFunction implements LivingItemFunction, HasDirection {

    public static final String ID = "living_redstone_torch";
    private static final String[] SLOT_NAMES = {"direction"};

    @Override
    public boolean canApply(ItemStack stack) {
        return stack.is(Items.REDSTONE_TORCH) && LivingItemManager.isLivingItem(stack);
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
        return Set.of(RedstoneComponents.LIVING_REDSTONE_TORCH_DATA.value());
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
        LivingRedstoneTorchData data = LivingRedstoneTorchData.of(stack);

        tooltipAdder.accept(Component.nullToEmpty(""));
        tooltipAdder.accept(Component.translatable("tooltip.livingitem.redstone_torch.title"));

        tooltipAdder.accept(Component.literal("  ")
            .append(Component.translatable("tooltip.livingitem.redstone_torch.facing"))
            .append(Component.literal(": "))
            .append(Component.translatable("tooltip.livingitem.redstone_torch.direction." + directionKey(data.direction())))
            .withStyle(ChatFormatting.GRAY));

        if (data.isLit()) {
            tooltipAdder.accept(Component.translatable("tooltip.livingitem.redstone_torch.lit")
                .withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
        } else {
            tooltipAdder.accept(Component.translatable("tooltip.livingitem.redstone_torch.unlit")
                .withStyle(ChatFormatting.DARK_GRAY));
        }

        tooltipAdder.accept(Component.translatable("tooltip.livingitem.redstone_torch.max_signal")
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
        return updateTorchDirection(stack, direction);
    }

    public static boolean updateTorchDirection(ItemStack torchStack, Pos2D direction) {
        if (torchStack == null || torchStack.isEmpty() || direction == null) return false;
        LivingRedstoneTorchData data = LivingRedstoneTorchData.of(torchStack);
        LivingRedstoneTorchData.set(torchStack, data.withDirection(direction));
        return true;
    }
}