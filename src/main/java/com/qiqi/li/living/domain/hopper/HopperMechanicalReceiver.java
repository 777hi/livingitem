package com.qiqi.li.living.domain.hopper;

import com.qiqi.li.living.components.ItemFilterComponent;
import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.container.TickContext;
import com.qiqi.li.living.model.GridDirections;
import com.qiqi.li.living.model.Pos2D;
import com.qiqi.li.living.transfer.MechanicalReceivers;
import com.qiqi.li.living.transfer.SlotAccessor;
import com.qiqi.li.living.transfer.SlotAccessorFactory;
import com.qiqi.li.living.transfer.SlotResolver;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;

/**
 * 活漏斗的机械压送接收端。
 *
 * <p>职责只有四件事：权威重验输入面、按动作开始时快照过滤本体、用漏斗数量限制剂量、
 * 把本体交付到既有输出端点并返回真实 {@code q}。不调用主动 tick，不读写 cooldown / disabled，
 * 不走施肥交互或末影路由策略；目标是另一活漏斗时 Accessor 工厂返回 null ⇒ 不形成物品管道。</p>
 */
final class HopperMechanicalReceiver implements MechanicalReceivers.Receiver {

    @Override
    public boolean matches(ItemStack receiverStack) {
        return LivingHopperFunction.isLivingHopper(receiverStack);
    }

    @Override
    public int receive(MechanicalReceivers.Request request) {
        ContainerContext ctx = request.context();
        TickContext tick = request.tick();
        int sourceSlot = request.sourceSlot();
        int receiverSlot = request.receiverSlot();
        ItemStack receiverStack = ctx.getItem(receiverSlot);
        if (!LivingHopperFunction.isLivingHopper(receiverStack)) return 0;

        DirectionTransferData direction = LivingHopperData.of(receiverStack).direction();
        Pos2D expectedInput = request.pushDirection().negate();
        if (!direction.sourceOffset().equals(expectedInput)) return 0;
        int resolvedSource = SlotResolver.resolve(receiverSlot, direction.sourceOffset(),
            ctx.getSize(), ctx.getWidth());
        if (resolvedSource != sourceSlot) return 0;

        int amount = Math.min(ctx.getItem(sourceSlot).getCount(), receiverStack.getCount());
        if (amount <= 0) return 0;
        ItemStack cargo = request.bodySource().simulateExtract(amount);
        if (cargo.isEmpty() || !ItemFilterComponent.allows(tick.getSnapshot().getFilterOf(receiverSlot), cargo)) {
            return 0;
        }

        int targetSlot = SlotResolver.resolve(receiverSlot, direction.targetOffset(),
            ctx.getSize(), ctx.getWidth());
        if (targetSlot < 0) {
            return CrossContainerTransfer.pushMechanicalToNeighbor(ctx, request.level(), receiverSlot,
                direction.targetOffset(), request.bodySource(), amount, tick);
        }
        if (targetSlot == sourceSlot || targetSlot == receiverSlot) return 0;

        MinecraftServer server = request.level().getServer();
        if (server == null) return 0;
        SlotAccessor target = SlotAccessorFactory.create(server, ctx, targetSlot, null,
            tick.transferredTargetSlots, tick.getSnapshot());
        return target != null ? SlotAccessor.transferAmount(request.bodySource(), target, amount) : 0;
    }
}
