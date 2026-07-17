package com.qiqi.li.living.mixin;

import com.qiqi.li.living.LivingChestFunction;
import net.minecraft.recipebook.ServerPlaceRecipe;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.StackedContents;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerPlaceRecipe.class)
public abstract class ServerPlaceRecipeMixin {

    @Shadow
    @Final
    protected StackedContents stackedContents;

    @Shadow
    protected Inventory inventory;

    @Inject(method = "recipeClicked", at = @At(
        value = "INVOKE",
        target = "Lnet/minecraft/world/entity/player/Inventory;fillStackedContents(Lnet/minecraft/world/entity/player/StackedContents;)V",
        shift = At.Shift.AFTER
    ))
    private void afterFillStackedContents(ServerPlayer player, RecipeHolder recipe, boolean placeAll, CallbackInfo ci) {
        addLivingChestItemsToStackedContents(player);
    }

    private void addLivingChestItemsToStackedContents(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) return;

        for (ItemStack invStack : this.inventory.items) {
            if (!LivingChestFunction.isLivingChest(invStack)) continue;
            if (!LivingChestFunction.hasStorage(invStack)) continue;

            var merged = LivingChestFunction.getMergedStorage(server, invStack, 27);
            for (ItemStack chestItem : merged) {
                if (!chestItem.isEmpty()) {
                    this.stackedContents.accountStack(chestItem);
                }
            }
        }
    }

    @Inject(method = "moveItemToGrid", at = @At("HEAD"), cancellable = true)
    private void onMoveItemToGrid(Slot slot, ItemStack stack, int maxAmount, CallbackInfoReturnable<Integer> cir) {
        int slotIndex = this.inventory.findSlotMatchingUnusedItem(stack);
        if (slotIndex != -1) {
            return;
        }

        ItemStack extracted = extractFromLivingChests(stack, maxAmount);
        if (extracted.isEmpty()) {
            cir.setReturnValue(-1);
            return;
        }

        if (slot.getItem().isEmpty()) {
            slot.set(extracted);
        } else {
            slot.getItem().grow(extracted.getCount());
        }

        cir.setReturnValue(maxAmount - extracted.getCount());
    }

    private ItemStack extractFromLivingChests(ItemStack requested, int maxAmount) {
        MinecraftServer server = this.inventory.player.getServer();
        if (server == null) return ItemStack.EMPTY;

        for (int i = 0; i < this.inventory.items.size(); i++) {
            ItemStack invStack = this.inventory.items.get(i);
            if (!LivingChestFunction.isLivingChest(invStack)) continue;
            if (!LivingChestFunction.hasStorage(invStack)) continue;

            ItemStack extracted = LivingChestFunction.extractItem(server, invStack, requested, maxAmount, 27);
            if (!extracted.isEmpty()) {
                return extracted;
            }
        }
        return ItemStack.EMPTY;
    }
}