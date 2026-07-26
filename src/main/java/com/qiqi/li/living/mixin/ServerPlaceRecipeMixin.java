package com.qiqi.li.living.mixin;

import com.qiqi.li.living.core.components.InternalStorageComponent;
import com.qiqi.li.living.function.LivingChestFunction;
import net.minecraft.recipebook.ServerPlaceRecipe;
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

    private static final boolean ENABLED = true;

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
        if (!ENABLED) return;
        addLivingChestItemsToStackedContents();
    }

    private void addLivingChestItemsToStackedContents() {
        for (ItemStack invStack : this.inventory.items) {
            if (!LivingChestFunction.isLivingChest(invStack)) continue;
            if (!LivingChestFunction.hasStorage(invStack)) continue;

            java.util.List<ItemStack> items = InternalStorageComponent.getItems(invStack);
            for (ItemStack chestItem : items) {
                if (!chestItem.isEmpty()) {
                    this.stackedContents.accountStack(chestItem);
                }
            }
        }
    }

    @Inject(method = "moveItemToGrid", at = @At("HEAD"), cancellable = true)
    private void onMoveItemToGrid(Slot slot, ItemStack stack, int maxAmount, CallbackInfoReturnable<Integer> cir) {
        if (!ENABLED) return;
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
        for (int i = 0; i < this.inventory.items.size(); i++) {
            ItemStack invStack = this.inventory.items.get(i);
            if (!LivingChestFunction.isLivingChest(invStack)) continue;
            if (!LivingChestFunction.hasStorage(invStack)) continue;

            ItemStack extracted = LivingChestFunction.extractItem(invStack, requested, maxAmount);
            if (!extracted.isEmpty()) {
                return extracted;
            }
        }
        return ItemStack.EMPTY;
    }
}