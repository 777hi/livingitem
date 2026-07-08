package com.qiqi.li.living.core.components;

import java.util.function.Consumer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
import com.qiqi.li.living.ContainerContext;
import com.qiqi.li.living.LivingItemManager;
import com.qiqi.li.living.core.ComponentConfig;
import com.qiqi.li.living.core.ComponentContext;
import com.qiqi.li.living.core.ComponentState;
import com.qiqi.li.living.core.model.Pos2D;
import com.qiqi.li.living.core.model.SlotMapping;
import com.qiqi.li.living.core.SlotResolver;

public class ItemTransferComponent implements ILivingComponent {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final String ID = "item_transfer";

    private static final String KEY_COOLDOWN = "transfer_cooldown";

    private static final int DEFAULT_COOLDOWN = 8;
    private static final int DEFAULT_MAX_TRANSFER = 64;

    @Override
    public String getComponentId() { return ID; }

    @Override
    public void tick(ComponentContext ctx, int hostSlot, ItemStack hostStack,
                     ComponentState state, ComponentConfig config) {

        SlotMapping mapping = getCurrentMapping(ctx);
        int baseCooldown = config.get("base_cooldown", Integer.class, DEFAULT_COOLDOWN);
        int maxTransfer = config.get("max_transfer_per_tick", Integer.class, DEFAULT_MAX_TRANSFER);

        int cooldown = state.getInt(KEY_COOLDOWN, 0);

        if (cooldown > 0) {
            cooldown--;
            state.setInt(KEY_COOLDOWN, cooldown);
            return;
        }

        boolean success = executeTransfer(ctx, hostSlot, mapping, hostStack.getCount(), maxTransfer);

        if (success) {
            int actualCooldown = calculateCooldown(baseCooldown, hostStack.getCount());
            state.setInt(KEY_COOLDOWN, actualCooldown);
        }
    }

    @Override
    public ComponentState createDefaultState() {
        ComponentState state = new ComponentState();
        state.setInt(KEY_COOLDOWN, 0);
        return state;
    }

    @Override
    public void appendTooltip(ComponentState state, Consumer<Component> tooltipAdder) {
        int cooldown = state.getInt(KEY_COOLDOWN, 0);

        if (cooldown > 0) {
            tooltipAdder.accept(Component.literal("冷却中: " + cooldown + " ticks")
                .withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
        }
    }

    private boolean executeTransfer(ComponentContext ctx, int hostSlot,
                                   SlotMapping mapping, int stackSize, int maxTransfer) {

        ContainerContext containerCtx = ctx.containerCtx();
        int containerSize = containerCtx.getSize();

        Pos2D sourceOffset = mapping.sourceOffset();
        Pos2D targetOffset = mapping.targetOffset();

        int sourceSlot = SlotResolver.resolve(hostSlot, sourceOffset, containerSize);
        int targetSlot = SlotResolver.resolve(hostSlot, targetOffset, containerSize);

        if (sourceSlot < 0 || targetSlot < 0 || sourceSlot >= containerSize || targetSlot >= containerSize) {
            return false;
        }

        if (sourceSlot == targetSlot) {
            return false;
        }

        ItemStack sourceStack = containerCtx.getItem(sourceSlot);
        if (sourceStack.isEmpty() || LivingItemManager.isLivingItem(sourceStack)) {
            return false;
        }

        ItemStack targetStack = containerCtx.getItem(targetSlot);
        int transferAmount = Math.min(sourceStack.getCount(), Math.min(stackSize, maxTransfer));

        if (targetStack.isEmpty()) {
            ItemStack toTransfer = sourceStack.copy();
            toTransfer.setCount(transferAmount);
            containerCtx.setItem(targetSlot, toTransfer);

            sourceStack.shrink(transferAmount);
            if (sourceStack.isEmpty()) {
                containerCtx.setItem(sourceSlot, ItemStack.EMPTY);
            } else {
                containerCtx.setItem(sourceSlot, sourceStack);
            }

            return true;
        } else if (targetStack.is(sourceStack.getItem()) &&
                   targetStack.getCount() < targetStack.getMaxStackSize()) {

            int spaceAvailable = targetStack.getMaxStackSize() - targetStack.getCount();
            int actualTransfer = Math.min(transferAmount, spaceAvailable);

            targetStack.grow(actualTransfer);
            containerCtx.setItem(targetSlot, targetStack);

            sourceStack.shrink(actualTransfer);
            if (sourceStack.isEmpty()) {
                containerCtx.setItem(sourceSlot, ItemStack.EMPTY);
            } else {
                containerCtx.setItem(sourceSlot, sourceStack);
            }

            return true;
        }

        return false;
    }

    private int calculateCooldown(int baseCooldown, int stackSize) {
        if (stackSize <= 1) {
            return baseCooldown;
        }
        return Math.max(1, baseCooldown / stackSize);
    }

    private SlotMapping getCurrentMapping(ComponentContext ctx) {
        ComponentState dirState = ctx.getComponentState(DirectionModeComponent.ID);
        if (dirState == null) {
            LOGGER.debug("DirectionModeComponent状态为空，使用默认映射");
            return SlotMapping.UP_TO_DOWN;
        }

        DirectionModeComponent dirComp = new DirectionModeComponent();
        SlotMapping mapping = dirComp.getCurrentMapping(dirState);

        if (mapping == null) {
            LOGGER.debug("无法解析槽位映射，使用默认值");
            return SlotMapping.UP_TO_DOWN;
        }

//        LOGGER.debug("使用动态槽位映射: {}", mapping.displayName());
        return mapping;
    }

}