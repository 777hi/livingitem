package com.qiqi.li.living.domain.tools;

import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.container.ContainerContexts;
import com.qiqi.li.living.container.ContainerUuids;
import com.qiqi.li.living.interaction.InteractionEntry;
import com.qiqi.li.living.interaction.InteractionHandler;
import com.qiqi.li.living.interaction.InteractionRegistry;
import com.qiqi.li.living.interaction.MenuActionAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.BlockItemStateProperties;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.ItemAbilities;

public final class AxeStripHandler implements InteractionHandler {
    public static final String ACTION_ID = "axe_strip";

    public static boolean canStripWith(ItemStack carried, ItemStack target) {
        return carried.getCount() == 1 && LivingItemManager.isLivingItem(carried)
            && carried.canPerformAction(ItemAbilities.AXE_STRIP) && !strippedResult(target).isEmpty();
    }

    public static ItemStack strippedResult(ItemStack source) {
        if (source.isEmpty() || !LivingItemManager.isLivingItem(source)
                || !(source.getItem() instanceof BlockItem blockItem)
                || source.has(DataComponents.CONTAINER) || source.has(DataComponents.BUNDLE_CONTENTS)
                || source.has(DataComponents.BLOCK_ENTITY_DATA) || ContainerUuids.ofItem(source) != null
                || LivingItemManager.hasAnyFunctionFor(source)) return ItemStack.EMPTY;
        BlockState original = blockItem.getBlock().defaultBlockState();
        BlockItemStateProperties properties = source.get(DataComponents.BLOCK_STATE);
        if (properties != null) original = properties.apply(original);
        BlockState stripped = AxeItem.getAxeStrippingState(original);
        if (stripped == null || stripped.getBlock().asItem() == Items.AIR
                || stripped.getBlock().asItem() == source.getItem()) return ItemStack.EMPTY;
        ItemStack result = source.transmuteCopy(stripped.getBlock());
        if (properties != null) {
            BlockItemStateProperties retained = BlockItemStateProperties.EMPTY;
            for (String name : properties.properties().keySet()) {
                var property = stripped.getBlock().getStateDefinition().getProperty(name);
                if (property != null) retained = retained.with(property, stripped);
            }
            if (retained.isEmpty()) result.remove(DataComponents.BLOCK_STATE);
            else result.set(DataComponents.BLOCK_STATE, retained);
        }
        return LivingItemManager.hasAnyFunctionFor(result) ? ItemStack.EMPTY : result;
    }

    @Override
    public void handle(ServerPlayer player, Slot targetSlot) {
        if (!MenuActionAccess.validTarget(player, targetSlot)) return;
        AbstractContainerMenu menu = player.containerMenu;
        ItemStack carried = menu.getCarried().copy();
        ItemStack source = targetSlot.getItem().copy();
        InteractionEntry entry = InteractionRegistry.findInteraction(carried, source, 1, false);
        if (entry == null || !ACTION_ID.equals(entry.actionId()) || !canStripWith(carried, source)) return;
        ItemStack result = strippedResult(source);
        if (!targetSlot.mayPickup(player) || !targetSlot.mayPlace(result)) return;
        ContainerContext ctx = ContainerContexts.resolve(player, targetSlot);
        if (ctx == null) return;
        int logical = ctx.toLogicalSlot(targetSlot.getContainerSlot());
        if (!ctx.isValidSlot(logical) || !ctx.supportsExactWrite(logical)
                || ctx.simulateExtractItem(logical, source.getCount()) < source.getCount()
                || !ctx.isItemValid(logical, result)
                || !ItemStack.matches(ctx.getItem(logical), source)) return;
        boolean[] inputMatched = {false};
        if (!ctx.transform(logical, current -> {
            if (!ItemStack.matches(current, source)) return current;
            inputMatched[0] = true;
            return result.copy();
        }) || !inputMatched[0] || !ItemStack.matches(ctx.getItem(logical), result)) return;
        if (!player.hasInfiniteMaterials()) {
            carried.hurtAndBreak(1, player, EquipmentSlot.MAINHAND);
            menu.setCarried(carried);
        }
        player.playNotifySound(SoundEvents.AXE_STRIP, SoundSource.PLAYERS, 1.0F, 1.0F);
    }
}
