package com.qiqi.li.living;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

/**
 * {@link ContainerContext} 的通用实现。
 *
 * 核心机制：
 * - container：用于数据读写（getItem/setItem），可以是任何 Container
 * - associatedBlockPositions / associatedBlockEntities：用于生成稳定的容器标识 key
 * - syncSlotToClients：手动同步 DataComponent 变化到客户端
 *
 * 同步策略：
 *   原版 broadcastChanges() 使用 ItemStack.matches() 检测变化，
 *   但 PatchedDataComponentMap.equals() 无法检测自定义组件变化，
 *   导致 LIVING_FUNCTION_DATA 更新后客户端 tooltip 不刷新。
 *
 *   解决方案：在活物品 tick 后，手动遍历所有正在查看该容器的玩家，
 *   通过 ClientboundContainerSetSlotPacket 发送更新包。
 *
 *   关键细节：
 *   1. 使用 containerMenu.incrementStateId() 获取新 stateId，
 *      确保客户端接受更新（客户端会忽略 stateId 不递增的包）
 *   2. 同时更新 remoteSlots，避免后续 broadcastChanges() 重复发送
 *   3. 对于玩家背包，需要同时处理 inventoryMenu 和 containerMenu
 */
public class SimpleContainerContext implements ContainerContext {

    private final Container container;
    private final String containerKey;
    private final List<BlockPos> associatedBlockPositions;
    private final List<BlockEntity> associatedBlockEntities;

    /** 本 tick 的已占用槽位集合（跨 Function 共享） */
    private final Set<String> occupiedSlots = new HashSet<>();

    /** 本 tick 已被传输到达的槽位集合（防止同 tick 级联传输） */
    private final Set<Integer> transferredTargetSlots = new HashSet<>();

    public SimpleContainerContext(Container container) {
        this(container, new ArrayList<>(), new ArrayList<>());
    }

    public SimpleContainerContext(Container container, List<BlockPos> positions, List<BlockEntity> blockEntities) {
        this.container = container;
        this.associatedBlockPositions = new ArrayList<>();
        this.associatedBlockEntities = new ArrayList<>();

        if (positions != null && !positions.isEmpty()) {
            this.associatedBlockPositions.addAll(positions);
        } else {
            autoDetectPositions(container, this.associatedBlockPositions);
        }

        if (blockEntities != null && !blockEntities.isEmpty()) {
            this.associatedBlockEntities.addAll(blockEntities);
        } else {
            autoDetectBlockEntities(container, this.associatedBlockEntities);
        }

        this.containerKey = buildContainerKey(container, this.associatedBlockPositions, this.associatedBlockEntities);
    }

    private static void autoDetectPositions(Container container, List<BlockPos> positions) {
        if (container instanceof ChestBlockEntity chest) {
            positions.add(chest.getBlockPos());
        } else if (container instanceof BlockEntity be) {
            positions.add(be.getBlockPos());
        }
    }

    private static void autoDetectBlockEntities(Container container, List<BlockEntity> entities) {
        if (container instanceof ChestBlockEntity chest) {
            entities.add(chest);
        } else if (container instanceof BlockEntity be) {
            entities.add(be);
        }
    }

    private static String buildContainerKey(Container container, List<BlockPos> positions, List<BlockEntity> entities) {
        if (container instanceof Inventory inv) {
            return "player_" + inv.player.getStringUUID();
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
        return "container_" + Integer.toHexString(container.hashCode());
    }

    @Override
    public int getSize() {
        try {
            return container.getContainerSize();
        } catch (Exception e) {
            return 0;
        }
    }

    @Override
    public int getWidth() {
        if (container instanceof Inventory) {
            return 9;
        }

        var adapter = com.qiqi.li.living.core.adapters.AdapterRegistry.getInstance().findAdapter(container);
        if (adapter != null) {
            try {
                var layout = adapter.getLayout(container);
                if (layout != null && layout.columns() > 0) {
                    return layout.columns();
                }
            } catch (Exception e) {
                // 回退到下一策略
            }
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
                if (rule.isPresent()) return rule;

                rule = com.qiqi.li.living.core.config.ContainerCompatibilityConfig.findRuleByNamespaceAndKeyword(
                    id.getNamespace(), id.getPath());
                if (rule.isPresent()) return rule;
            }
        }

        return com.qiqi.li.living.core.config.ContainerCompatibilityConfig.findRuleBySize(getSize());
    }

    @Override
    public ItemStack getItem(int logicalSlot) {
        try {
            if (logicalSlot < 0 || logicalSlot >= container.getContainerSize()) {
                return ItemStack.EMPTY;
            }
            return container.getItem(logicalSlot);
        } catch (Exception e) {
            return ItemStack.EMPTY;
        }
    }

    @Override
    public void setItem(int logicalSlot, ItemStack stack) {
        try {
            if (logicalSlot < 0 || logicalSlot >= container.getContainerSize()) {
                return;
            }
            container.setItem(logicalSlot, stack);
        } catch (Exception e) {
        }
    }

    @Override
    public int getMaxStackSize() {
        return container.getMaxStackSize();
    }

    @Override
    public String getStableKey(int logicalSlot, String functionId) {
        return containerKey + "_slot_" + logicalSlot + "_func_" + functionId;
    }

    @Override
    public Set<String> getOccupiedSlots() {
        return occupiedSlots;
    }

    @Override
    public Set<Integer> getTransferredTargetSlots() {
        return transferredTargetSlots;
    }

    @Override
    public BlockPos getBlockPos() {
        if (!associatedBlockPositions.isEmpty()) {
            return associatedBlockPositions.get(0);
        }
        if (container instanceof Inventory inv) {
            return inv.player.blockPosition();
        }
        return null;
    }

    @Override
    public Level getLevel() {
        if (container instanceof BlockEntity be && be.getLevel() != null) {
            return be.getLevel();
        }
        for (BlockEntity be : associatedBlockEntities) {
            if (be.getLevel() != null) {
                return be.getLevel();
            }
        }
        return null;
    }

    @Override
    public net.minecraft.world.Container getContainer() {
        return container;
    }

    /**
     * 将指定槽位的物品数据同步到所有正在查看该容器的客户端。
     *
     * 实现逻辑：
     * 1. 玩家背包：直接向背包所属玩家同步 inventoryMenu 和 containerMenu
     * 2. 世界容器（箱子等）：遍历服务器上所有玩家，找到正在查看该容器的玩家
     *
     * 对于玩家背包的特殊处理：
     *   - 创造模式下 containerMenu 是 ItemPickerMenu，不是 InventoryMenu
     *   - ServerPlayer.tick() 只同步 containerMenu，不同步 inventoryMenu
     *   - 所以需要同时向两个菜单发送同步包
     *   - 客户端 handleContainerSetSlot() 根据 containerId 路由到对应菜单
     *
     * stateId 机制：
     *   - 客户端只接受 stateId 递增的同步包，忽略旧包
     *   - 使用 containerMenu.incrementStateId() 获取新的 stateId
     *   - 同时更新 remoteSlots 防止 broadcastChanges() 重复发送
     */
    @Override
    public void syncSlotToClients(int logicalSlot, ItemStack stack) {
        if (container instanceof Inventory inv) {
            syncPlayerInventory(inv, logicalSlot, stack);
        } else {
            syncWorldContainer(logicalSlot, stack);
        }
    }

    /**
     * 同步玩家背包中的活物品数据。
     *
     * 需要同时处理两个菜单：
     * - inventoryMenu：玩家背包菜单（containerId=0），始终存在
     * - containerMenu：当前打开的菜单（可能是 ChestMenu、ItemPickerMenu 等）
     *
     * 两个菜单可能共享相同的 Inventory 对象，但有不同的 Slot 列表和 remoteSlots。
     * 需要分别找到活物品在两个菜单中的槽位索引并发送同步包。
     */
    private void syncPlayerInventory(Inventory inv, int logicalSlot, ItemStack stack) {
        if (!(inv.player instanceof ServerPlayer serverPlayer)) return;

        syncInventoryMenuSlot(serverPlayer, serverPlayer.inventoryMenu, logicalSlot, stack);

        if (serverPlayer.containerMenu != serverPlayer.inventoryMenu) {
            syncInventoryMenuSlot(serverPlayer, serverPlayer.containerMenu, logicalSlot, stack);
        }
    }

    private void syncInventoryMenuSlot(ServerPlayer serverPlayer, AbstractContainerMenu menu,
                                        int logicalSlot, ItemStack stack) {
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i);
            if (slot.container instanceof Inventory && slot.getContainerSlot() == logicalSlot) {
                int stateId = menu.incrementStateId();
                menu.remoteSlots.set(i, stack.copy());
                serverPlayer.connection.send(
                    new ClientboundContainerSetSlotPacket(menu.containerId, stateId, i, stack.copy()));
            }
        }
    }

    /**
     * 同步世界容器（箱子等）中的活物品数据。
     *
     * 遍历服务器上所有玩家，找到正在查看该容器的玩家。
     *
     * 匹配策略 —— ItemStack 引用匹配：
     *   容器中同一槽位的 ItemStack 在内存中是同一个对象引用。
     *   无论通过 CompoundContainer（大箱子）还是 ChestBlockEntity（单箱子）访问，
     *   最终都路由到同一个底层 ItemStack 对象。
     *
     *   因此用 ==（引用相等）比较 slot.getItem() 和 stack 即可精准匹配，
     *   无需关心容器的包装层级，适用于所有容器类型。
     *
     *   示例：
     *   container.getItem(5)       → ChestBlockEntity.getItem(5) → ItemStack@A
     *   slot.getItem()             → CompoundContainer.getItem(5) → ChestBlockEntity.getItem(5) → ItemStack@A
     *                                                                                          ↑ 同一个对象
     */
    private void syncWorldContainer(int logicalSlot, ItemStack stack) {
        Level level = null;
        if (container instanceof BlockEntity be && be.getLevel() != null) {
            level = be.getLevel();
        } else {
            for (BlockEntity be : associatedBlockEntities) {
                if (be.getLevel() != null) {
                    level = be.getLevel();
                    break;
                }
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