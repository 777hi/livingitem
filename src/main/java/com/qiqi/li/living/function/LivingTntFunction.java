package com.qiqi.li.living.function;

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
import com.qiqi.li.living.components.ExplosionComponent;
import com.qiqi.li.living.data.ExplosionData;
import com.qiqi.li.living.data.LivingTntData;

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

        for (SlotEntry entry : entries) {
            int slot = entry.slotIndex();
            if (slot < 0 || slot >= context.getSize()) continue;

            ItemStack stack = entry.stack();
            LivingTntData data = LivingItemManager.getTntData(stack);
            ExplosionData explosion = data.explosion();

            if (!explosion.ignited()) continue;

            explosion = explosion.tick();

            if (explosion.isExploded()) {
                ExplosionComponent.ignite(context, level);
                LivingItemManager.setTntData(stack, LivingTntData.DEFAULT);
            } else {
                LivingItemManager.setTntData(stack, data.withExplosion(explosion));
            }

            context.syncSlotToClients(slot, stack);
        }
    }

    @Override
    public void addToTooltip(Item.TooltipContext context,
                             Consumer<Component> tooltipAdder,
                             TooltipFlag flag,
                             ItemStack stack) {
        LivingTntData data = LivingItemManager.getTntData(stack);
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
    public Set<DataComponentType<?>> getIgnoredComponentTypes() {
        return Set.of();
    }

    public static boolean isFuseActive(ItemStack stack) {
        return LivingItemManager.getTntData(stack).explosion().ignited();
    }

    public static int getFuseTimer(ItemStack stack) {
        ExplosionData e = LivingItemManager.getTntData(stack).explosion();
        return e.ignited() ? e.fuseTimer() : -1;
    }

    public static boolean startFuse(ItemStack tntStack) {
        LivingTntData data = LivingItemManager.getTntData(tntStack);
        if (data.explosion().ignited()) return false;
        LivingItemManager.setTntData(tntStack, data.withExplosion(data.explosion().ignite(80)));
        return true;
    }
}