package com.qiqi.li.living.domain.tnt;
import com.qiqi.li.living.components.LivingComponents;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import com.qiqi.li.living.api.LivingItemFunction;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.container.TickContext;
import com.qiqi.li.living.domain.tnt.ExplosionData;
import com.qiqi.li.living.domain.tnt.LivingTntData;

/**
 * 活 TNT 的功能实现。
 *
 * <p>每 tick 检测所在槽位的红石信号：收到信号即点燃并启动引信倒计时，归零时经
 * {@link ExplosionComponent#ignite} 触发爆炸；同时维持红石账本以放行红石层的驱动守卫（容器里只有活 TNT 时也是如此）。</p>
 */
public class LivingTntFunction implements LivingItemFunction {

    public static final String ID = "living_tnt";

    @Override
    public boolean canApply(ItemStack stack) {
        return stack.is(Items.TNT) && LivingItemManager.isLivingItem(stack);
    }

    @Override
    public String getFunctionId() { return ID; }

    @Override
    public void tick(List<SlotEntry> entries, ContainerContext context, TickContext tick, Level level) {
        if (level.isClientSide) return;

        // 确保红石账本存在 —— ⚠️ **这行不是冗余代码，别删**：
        // 红石层的驱动守卫（LivingRedstoneFunction.tickContainerData）以「账本已存在」为放行判据之一，
        // 容器里只有活 TNT 时正是靠这行让守卫放行。见
        // docs/buffer/redstone-driver-consolidation-plan.md §5 改动 2。
        // （2026-10-08 计划 ⑤：getSensor 内部即「取或创建账本」，故改走端口 ——
        //  这样 TNT 不必 import redstone 领域，中继由 TickContext 承担。）
        tick.getSensor(context);
        int size = context.getSize();

        for (SlotEntry entry : entries) {
            int slot = entry.slotIndex();
            if (slot < 0 || slot >= size) continue;

            ItemStack stack = entry.stack();
            LivingTntData data = LivingTntData.of(stack);
            ExplosionData explosion = data.explosion();

            if (!explosion.ignited()) {
                int signal = tick.getSensor(context).maxSensedSignal(slot);
                if (signal > 0) {
                    explosion = explosion.ignite();
                    LivingTntData.set(stack, data.withExplosion(explosion));
                    context.syncSlotToClients(slot, stack);
                    continue;
                }
                continue;
            }

            explosion = explosion.tick();

            if (explosion.isExploded()) {
                ExplosionComponent.ignite(context, level);
                LivingTntData.set(stack, LivingTntData.DEFAULT);
            } else {
                LivingTntData.set(stack, data.withExplosion(explosion));
            }

            context.syncSlotToClients(slot, stack);
        }
    }

    @Override
    public void addToTooltip(Item.TooltipContext context,
                             Consumer<Component> tooltipAdder,
                             TooltipFlag flag,
                             ItemStack stack) {
        LivingTntData data = LivingTntData.of(stack);
        ExplosionData explosion = data.explosion();

        tooltipAdder.accept(Component.nullToEmpty(""));
        tooltipAdder.accept(Component.translatable("tooltip.livingitem.tnt.status"));

        if (explosion.ignited()) {
            tooltipAdder.accept(Component.literal("TNT [Fuse: " + explosion.fuseTimer() + " ticks]")
                .withStyle(net.minecraft.ChatFormatting.RED, net.minecraft.ChatFormatting.BOLD));
        } else {
            tooltipAdder.accept(Component.literal("TNT")
                .withStyle(net.minecraft.ChatFormatting.RED));
        }
    }

    @Override
    public Set<DataComponentType<?>> getOwnedComponentTypes() {
        return Set.of(LivingComponents.LIVING_TNT_DATA.value());
    }

    @Override
    public Set<DataComponentType<?>> getIgnoredComponentTypes() {
        return Set.of();
    }

    public static boolean isFuseActive(ItemStack stack) {
        return LivingTntData.of(stack).explosion().ignited();
    }

    public static int getFuseTimer(ItemStack stack) {
        ExplosionData e = LivingTntData.of(stack).explosion();
        return e.ignited() ? e.fuseTimer() : -1;
    }

    public static boolean startFuse(ItemStack tntStack) {
        LivingTntData data = LivingTntData.of(tntStack);
        if (data.explosion().ignited()) return false;
        LivingTntData.set(tntStack, data.withExplosion(data.explosion().ignite(80)));
        return true;
    }

}