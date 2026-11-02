package com.qiqi.li.living.domain.piston;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import com.qiqi.li.living.api.HasDirection;
import com.qiqi.li.living.api.LivingItemFunction;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.api.RedstoneSensor;
import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.container.TickContext;
import com.qiqi.li.living.domain.piston.PistonComponents;
import com.qiqi.li.living.model.Pos2D;
import com.qiqi.li.living.runtime.ContainerRuntimeCache;
import com.qiqi.li.living.runtime.LivingItemClientCache;
import com.qiqi.li.living.runtime.RuntimeSegments;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

/** 普通推拉的功能适配器；框架接缝验收前不加入全局功能注册。 */
public final class LivingPistonFunction implements LivingItemFunction, HasDirection {

    public static final String ID = "living_piston";
    private static final String DIRECTION_SLOT = "direction";

    @Override
    public boolean canApply(ItemStack stack) {
        return (stack.is(Items.PISTON) || stack.is(Items.STICKY_PISTON))
            && LivingItemManager.isLivingItem(stack);
    }

    @Override
    public String getFunctionId() {
        return ID;
    }

    @Override
    public void tick(List<SlotEntry> entries, ContainerContext context, TickContext tick, Level level) {
        if (level.isClientSide()) return;
        RedstoneSensor sensor = tick.getSensor(context);
        if (sensor == null) return;

        for (SlotEntry entry : entries) {
            int slot = entry.slotIndex();
            if (!context.isValidSlot(slot)) continue;
            ItemStack stack = context.getItem(slot);
            if (!canApply(stack)) continue;

            Pos2D direction = LivingPistonData.of(stack).direction();
            RuntimeSegments segments = ContainerRuntimeCache.get(context, slot);
            PistonRuntime previous = segments.get(PistonSegment.INSTANCE);
            PistonRuntime state = previous != null ? previous : PistonRuntime.retracted(direction);
            PistonController.Transition transition = PistonController.next(
                state, direction, sensor.maxSensedSignal(slot), stack.is(Items.STICKY_PISTON));

            boolean success = switch (transition.action()) {
                case NONE -> false;
                case PUSH -> PistonActions.push(context,
                    PistonChainResolver.resolve(context, slot, direction, stack.getCount())).success();
                case PULL -> PistonActions.pull(context, slot, direction).success();
            };
            PistonRuntime next = PistonController.complete(transition, success);
            if (!next.equals(previous)) {
                ContainerRuntimeCache.update(context, slot, segments.with(PistonSegment.INSTANCE, next));
            }
        }
    }

    @Override
    public Set<DataComponentType<?>> getOwnedComponentTypes() {
        return Set.of(PistonComponents.LIVING_PISTON_DATA.value());
    }

    @Override
    public int getDirectionKeyCount() {
        return 1;
    }

    @Override
    public String[] getDirectionSlotNames() {
        return new String[]{DIRECTION_SLOT};
    }

    @Override
    public boolean updateSlotDirection(ItemStack stack, String slotName, Pos2D direction) {
        if (stack == null || !canApply(stack) || !DIRECTION_SLOT.equals(slotName)
            || PistonChainResolver.edgeDirection(direction) < 0) return false;
        LivingPistonData data = LivingPistonData.of(stack);
        if (data.direction().equals(direction)) return false;
        LivingPistonData.set(stack, data.withDirection(direction));
        return true;
    }

    @Override
    public Pos2D getSlotDirection(ItemStack stack, String slotName) {
        return DIRECTION_SLOT.equals(slotName) ? LivingPistonData.of(stack).direction() : null;
    }

    @Override
    public void addToTooltip(Item.TooltipContext context, Consumer<Component> tooltipAdder,
                             TooltipFlag flag, ItemStack stack) {
        Pos2D direction = LivingPistonData.of(stack).direction();
        tooltipAdder.accept(Component.empty());
        tooltipAdder.accept(Component.translatable(stack.is(Items.STICKY_PISTON)
            ? "tooltip.livingitem.piston.sticky_title" : "tooltip.livingitem.piston.title"));
        tooltipAdder.accept(Component.translatable("tooltip.livingitem.piston.direction", direction.getSymbol()));
        tooltipAdder.accept(Component.translatable("tooltip.livingitem.piston.force", stack.getCount()));

        PistonRuntime state = LivingItemClientCache.getCurrentTooltipData().get(PistonSegment.INSTANCE);
        if (state != null && state.direction().equals(direction)) {
            tooltipAdder.accept(Component.translatable(state.extended()
                ? "tooltip.livingitem.piston.extended" : "tooltip.livingitem.piston.retracted"));
        }
        tooltipAdder.accept(Component.translatable("tooltip.livingitem.piston.integration_pending"));
    }
}
