package com.qiqi.li.living.container;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.items.IItemHandler;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

/**
 * {@link ContainerContext} 的通用实现，基于 IItemHandler。
 *
 * 核心机制：
 * - handler：用于数据读写（getStackInSlot/extractItem/insertItem），统一基于 IItemHandler
 * - inventory：玩家背包引用（nullable），用于同步和 key 生成
 * - associatedBlockPositions / associatedBlockEntities：用于生成稳定的容器标识 key
 * - syncSlotToClients：手动同步 DataComponent 变化到客户端
 */
public class SimpleContainerContext implements ContainerContext {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final IItemHandler handler;
    private final Inventory inventory;
    private final String containerKey;
    private final List<BlockPos> associatedBlockPositions;
    private final List<BlockEntity> associatedBlockEntities;
    private final Level overrideLevel;

    private ContainerFluidData fluidData;

    /**
     * 为玩家背包创建容器上下文。
     */
    public SimpleContainerContext(IItemHandler handler, Inventory inventory) {
        this(handler, inventory, new ArrayList<>(), new ArrayList<>(), null);
    }

    /**
     * 为方块容器创建容器上下文。
     */
    public SimpleContainerContext(IItemHandler handler, List<BlockPos> positions, List<BlockEntity> blockEntities) {
        this(handler, null, positions, blockEntities, null);
    }

    /**
     * 完整构造器。
     *
     * @param handler      IItemHandler，用于物品读写
     * @param inventory    玩家背包（nullable，仅玩家背包场景传入）
     * @param positions    关联方块位置
     * @param blockEntities 关联方块实体
     * @param overrideLevel 覆盖的世界（nullable）
     */
    public SimpleContainerContext(IItemHandler handler, Inventory inventory,
                                  List<BlockPos> positions, List<BlockEntity> blockEntities,
                                  Level overrideLevel) {
        this.handler = handler;
        this.inventory = inventory;
        this.overrideLevel = overrideLevel;

        this.associatedBlockPositions = new ArrayList<>();
        if (positions != null) {
            this.associatedBlockPositions.addAll(positions);
        }

        this.associatedBlockEntities = new ArrayList<>();
        if (blockEntities != null) {
            this.associatedBlockEntities.addAll(blockEntities);
        }

        this.containerKey = buildContainerKey(inventory, this.associatedBlockPositions, this.associatedBlockEntities, handler);
    }

    private static String buildContainerKey(Inventory inventory, List<BlockPos> positions, List<BlockEntity> entities, IItemHandler handler) {
        if (inventory != null) {
            return "player_" + inventory.player.getStringUUID();
        }
        if (!positions.isEmpty()) {
            StringBuilder sb = new StringBuilder("chest");
            for (BlockPos pos : positions) {
                sb.append("_").append(pos.getX()).append("_").append(pos.getY()).append("_").append(pos.getZ());
            }
            return sb.toString();
        }
        if (!entities.isEmpty()) {
            StringBuilder sb = new StringBuilder("container");
            for (BlockEntity be : entities) {
                BlockPos pos = be.getBlockPos();
                Level level = be.getLevel();
                String dimKey = level != null ? level.dimension().location().toString() : "unknown";
                sb.append("_").append(dimKey).append("_").append(pos.getX()).append("_").append(pos.getY()).append("_").append(pos.getZ());
            }
            return sb.toString();
        }
        return "container_" + Integer.toHexString(handler.hashCode());
    }

    @Override
    public int getSize() {
        return handler.getSlots();
    }

    @Override
    public int getWidth() {
        if (inventory != null) {
            return 9;
        }

        java.util.Optional<com.qiqi.li.living.core.config.ContainerCompatibilityConfig.ContainerRule> rule =
            findContainerRule();
        if (rule.isPresent() && rule.get().columns() > 0) {
            return rule.get().columns();
        }

        int size = getSize();
        if (size > 0 && size % 9 != 0) {
            return guessWidth(size);
        }

        return ContainerContext.super.getWidth();
    }

    private static int guessWidth(int size) {
        for (int w = 9; w >= 1; w--) {
            if (size % w == 0) return w;
        }
        return 9;
    }

    private java.util.Optional<com.qiqi.li.living.core.config.ContainerCompatibilityConfig.ContainerRule> findContainerRule() {
        if (associatedBlockEntities.isEmpty()) return java.util.Optional.empty();

        for (BlockEntity be : associatedBlockEntities) {
            net.minecraft.resources.ResourceLocation id =
                net.minecraft.core.registries.BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType());
            if (id != null) {
                var rule = com.qiqi.li.living.core.config.ContainerCompatibilityConfig.findRule(id);
                if (rule.isPresent() && rule.get().containerSize() == getSize()) return rule;

                rule = com.qiqi.li.living.core.config.ContainerCompatibilityConfig.findRuleByNamespaceAndKeyword(
                    id.getNamespace(), id.getPath());
                if (rule.isPresent() && rule.get().containerSize() == getSize()) return rule;
            }
        }

        return java.util.Optional.of(
            com.qiqi.li.living.core.config.ContainerCompatibilityConfig.findOrGenerateRule(getSize()));
    }

    @Override
    public ItemStack getItem(int logicalSlot) {
        if (logicalSlot < 0 || logicalSlot >= handler.getSlots()) {
            return ItemStack.EMPTY;
        }
        return handler.getStackInSlot(logicalSlot);
    }

    private static boolean isArmorSlot(Inventory inventory, int slot) {
        return inventory != null && slot >= 36 && slot < 40;
    }

    @Override
    public void setItem(int logicalSlot, ItemStack stack) {
        if (logicalSlot < 0 || logicalSlot >= handler.getSlots()) {
            return;
        }
        ItemStack toInsert = stack.copy();
        handler.extractItem(logicalSlot, Integer.MAX_VALUE, false);
        ItemStack remaining = handler.insertItem(logicalSlot, toInsert, false);
        if (!remaining.isEmpty() && isArmorSlot(inventory, logicalSlot)) {
            // 活漏斗绕过盔甲槽限制，直接设置物品（方块放头上等趣味玩法）
            inventory.armor.set(logicalSlot - 36, toInsert);
            syncSlotToClients(logicalSlot, toInsert);
        } else if (!remaining.isEmpty()) {
            LOGGER.warn("SimpleContainerContext.setItem: {} items of {} 未能插入槽位 {}",
                remaining.getCount(), toInsert.getItem(), logicalSlot);
        }
    }

    @Override
    public int getMaxStackSize() {
        return handler.getSlots() > 0 ? handler.getSlotLimit(0) : 64;
    }

    @Override
    public int getSlotLimit(int slot) {
        if (isArmorSlot(inventory, slot)) {
            return getMaxStackSize();
        }
        return handler.getSlotLimit(slot);
    }

    @Override
    public boolean isItemValid(int slot, ItemStack stack) {
        if (isArmorSlot(inventory, slot)) {
            return true;
        }
        return handler.isItemValid(slot, stack);
    }

    @Override
    public int simulateInsertItem(int slot, ItemStack stack) {
        if (isArmorSlot(inventory, slot)) {
            int slotLimit = getSlotLimit(slot);
            int maxStack = Math.min(slotLimit, stack.getMaxStackSize());
            return Math.min(stack.getCount(), maxStack);
        }
        ItemStack remaining = handler.insertItem(slot, stack.copy(), true);
        return stack.getCount() - remaining.getCount();
    }

    @Override
    public String getStableKey(int logicalSlot, String functionId) {
        return containerKey + "_slot_" + logicalSlot + "_func_" + functionId;
    }

    @Override
    public String getContainerKey() {
        return containerKey;
    }

    public Inventory getInventory() {
        return inventory;
    }

    @Override
    public BlockPos getBlockPos() {
        if (!associatedBlockPositions.isEmpty()) {
            return associatedBlockPositions.get(0);
        }
        return null;
    }

    @Override
    public Level getLevel() {
        if (overrideLevel != null) {
            return overrideLevel;
        }
        for (BlockEntity be : associatedBlockEntities) {
            if (be.getLevel() != null) {
                return be.getLevel();
            }
        }
        if (inventory != null) {
            return inventory.player.level();
        }
        return null;
    }

    @Override
    public void syncSlotToClients(int logicalSlot, ItemStack stack) {
        if (inventory != null) {
            syncPlayerInventory(inventory, logicalSlot, stack);
        } else {
            syncWorldContainer(logicalSlot, stack);
        }
    }

    /**
     * 获取或创建容器流体数据。
     */
    ContainerFluidData getOrCreateFluidData() {
        if (fluidData == null) {
            fluidData = ContainerLivingItemHandler.getFluidData(containerKey);
        }
        return fluidData;
    }

    private void syncPlayerInventory(Inventory inv, int logicalSlot, ItemStack stack) {
        if (!(inv.player instanceof ServerPlayer serverPlayer)) return;

        serverPlayer.connection.send(
            new ClientboundContainerSetSlotPacket(-2, 0, logicalSlot, stack.copy()));

        syncInventoryMenuSlot(serverPlayer, serverPlayer.inventoryMenu, logicalSlot, stack);

        if (serverPlayer.containerMenu != serverPlayer.inventoryMenu) {
            syncInventoryMenuSlot(serverPlayer, serverPlayer.containerMenu, logicalSlot, stack);
        }
    }

    private void syncInventoryMenuSlot(ServerPlayer serverPlayer, AbstractContainerMenu menu,
                                        int logicalSlot, ItemStack stack) {
        Inventory inv = (Inventory) serverPlayer.getInventory();
        boolean found = false;

        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i);
            if (slot.container instanceof Inventory && slot.getContainerSlot() == logicalSlot) {
                sendSlotSync(serverPlayer, menu, i, stack);
                found = true;
                break;
            }
        }

        if (!found) {
            for (int i = 0; i < menu.slots.size(); i++) {
                Slot slot = menu.slots.get(i);
                if (slot.container instanceof Inventory && slot.getItem() == inv.getItem(logicalSlot)) {
                    sendSlotSync(serverPlayer, menu, i, stack);
                    break;
                }
            }
        }
    }

    private void sendSlotSync(ServerPlayer serverPlayer, AbstractContainerMenu menu,
                               int slotIndex, ItemStack stack) {
        int stateId = menu.incrementStateId();
        menu.remoteSlots.set(slotIndex, stack.copy());
        serverPlayer.connection.send(
            new ClientboundContainerSetSlotPacket(menu.containerId, stateId, slotIndex, stack.copy()));
    }

    private void syncWorldContainer(int logicalSlot, ItemStack stack) {
        Level level = null;
        for (BlockEntity be : associatedBlockEntities) {
            if (be.getLevel() != null) {
                level = be.getLevel();
                break;
            }
        }
        if (level == null || level.isClientSide) return;

        for (ServerPlayer serverPlayer : level.getServer().getPlayerList().getPlayers()) {
            AbstractContainerMenu menu = serverPlayer.containerMenu;
            if (menu == serverPlayer.inventoryMenu) continue;

            for (int i = 0; i < menu.slots.size(); i++) {
                Slot slot = menu.slots.get(i);
                if (slot.getItem() == stack && slot.getContainerSlot() == logicalSlot) {
                    int stateId = menu.incrementStateId();
                    menu.remoteSlots.set(i, stack.copy());
                    serverPlayer.connection.send(
                        new ClientboundContainerSetSlotPacket(menu.containerId, stateId, i, stack.copy()));
                }
            }
        }
    }
}