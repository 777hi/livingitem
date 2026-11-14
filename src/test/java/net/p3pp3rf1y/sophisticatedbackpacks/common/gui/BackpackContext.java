package net.p3pp3rf1y.sophisticatedbackpacks.common.gui;

import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.IBackpackWrapper;

/** 精妙菜单上下文的同形替身；默认位置/主人不能作为物品真实宿主的证据。 */
public class BackpackContext {

    private final IBackpackWrapper wrapper;

    public BackpackContext(IBackpackWrapper wrapper) {
        this.wrapper = wrapper;
    }

    public IBackpackWrapper getBackpackWrapper(Player player) {
        return wrapper;
    }

    public ContextType getType() {
        return null;
    }

    public int getBackpackSlotIndex() {
        return -1;
    }

    public BackpackContext getParentBackpackContext() {
        return this;
    }

    public BlockPos getBackpackPosition(Player player) {
        return player.blockPosition();
    }

    public Optional<Entity> getOwnerPlayer(Player player) {
        return Optional.of(player);
    }

    public enum ContextType {
        BLOCK_BACKPACK, BLOCK_SUB_BACKPACK, ITEM_BACKPACK, ITEM_SUB_BACKPACK,
        ANOTHER_PLAYER_BACKPACK, ANOTHER_PLAYER_SUB_BACKPACK
    }

    public static class Item extends BackpackContext {
        private final int slot;

        public Item(IBackpackWrapper wrapper, int slot) {
            super(wrapper);
            this.slot = slot;
        }

        @Override
        public ContextType getType() {
            return ContextType.ITEM_BACKPACK;
        }

        @Override
        public int getBackpackSlotIndex() {
            return slot;
        }
    }

    public static class AnotherPlayer extends Item {
        private final Player owner;

        public AnotherPlayer(IBackpackWrapper wrapper, int slot, Player owner) {
            super(wrapper, slot);
            this.owner = owner;
        }

        @Override
        public ContextType getType() {
            return ContextType.ANOTHER_PLAYER_BACKPACK;
        }

        @Override
        public Optional<Entity> getOwnerPlayer(Player player) {
            return Optional.of(owner);
        }
    }

    public static class ItemSubBackpack extends Item {
        private final BackpackContext parent;

        public ItemSubBackpack(IBackpackWrapper wrapper, int slot, BackpackContext parent) {
            super(wrapper, slot);
            this.parent = parent;
        }

        @Override
        public ContextType getType() {
            return ContextType.ITEM_SUB_BACKPACK;
        }

        @Override
        public BackpackContext getParentBackpackContext() {
            return parent;
        }
    }

    public static class Block extends BackpackContext {
        private final BlockPos position;

        public Block(IBackpackWrapper wrapper, BlockPos position) {
            super(wrapper);
            this.position = position;
        }

        @Override
        public ContextType getType() {
            return ContextType.BLOCK_BACKPACK;
        }

        @Override
        public BlockPos getBackpackPosition(Player player) {
            return position;
        }
    }

    public static class BlockSubBackpack extends Block {
        private final BackpackContext parent;

        public BlockSubBackpack(IBackpackWrapper wrapper, BlockPos position, BackpackContext parent) {
            super(wrapper, position);
            this.parent = parent;
        }

        @Override
        public ContextType getType() {
            return ContextType.BLOCK_SUB_BACKPACK;
        }

        @Override
        public BackpackContext getParentBackpackContext() {
            return parent;
        }
    }
}
