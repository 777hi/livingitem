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
import com.qiqi.li.living.transfer.MechanicalReceivers;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

/** 活活塞功能适配器：同容器推拉，并通过机械接收端口交付链尾本体。 */
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
                case PUSH -> executePush(context, tick, level, slot, direction, stack.getCount());
                case PULL -> PistonActions.pull(context, slot, direction).success();
            };
            PistonRuntime next = PistonController.complete(transition, success);
            if (!next.equals(previous)) {
                ContainerRuntimeCache.update(context, slot, segments.with(PistonSegment.INSTANCE, next));
            }
        }
    }

    private static boolean executePush(ContainerContext context, TickContext tick, Level level,
                                       int pistonSlot, Pos2D direction, int force) {
        PistonPlan plan = PistonChainResolver.resolve(context, pistonSlot, direction, force);
        if (!(plan instanceof PistonPlan.Receiver receiver)) {
            return PistonActions.push(context, plan).success();
        }
        if (receiver.cargoSlots().isEmpty()) return false;
        int sourceSlot = receiver.cargoSlots().getLast();
        int before = context.getItem(sourceSlot).getCount();
        int delivered = MechanicalReceivers.receive(context, level, tick, sourceSlot,
            receiver.receiverSlot(), direction);
        if (delivered <= 0) return false;
        if (before > 0 && context.getItem(sourceSlot).isEmpty()) {
            tick.movedSlots.add(sourceSlot);   // 本体送空 ⇒ 旧设备条目/runtime 本拍立即失效
        }
        return true;
    }

    @Override
    public net.minecraft.nbt.CompoundTag getObservableState(
            ItemStack stack, int slot, ContainerContext container) {
        Pos2D direction = LivingPistonData.of(stack).direction();
        PistonRuntime runtime = ContainerRuntimeCache.get(container, slot).get(PistonSegment.INSTANCE);
        boolean extended = runtime != null
            && runtime.direction().equals(direction)
            && runtime.extended();

        net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
        tag.putInt("direction_x", direction.x());
        tag.putInt("direction_y", direction.y());
        tag.putBoolean("extended", extended);
        return tag;
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
    }
}
