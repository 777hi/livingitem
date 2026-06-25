package com.qiqi.li.living;

import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

import java.util.IdentityHashMap;
import java.util.Set;

public class ContainerLivingItemHandler {
    public static final Logger LOGGER = LogUtils.getLogger();

    public static void processContainer(Container container, Level level) {
        if (level.isClientSide) return;
        ContainerContext context = buildContext(container, level);
        processContext(context, level);
    }

    public static ContainerContext buildContext(Container container, Level level) {
        if (container instanceof ChestBlockEntity chest) {
            return buildChestContext(chest, level);
        }
        return new SimpleContainerContext(container);
    }

    private static ContainerContext buildChestContext(ChestBlockEntity chest, Level level) {
        BlockPos pos = chest.getBlockPos();
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof ChestBlock chestBlock) {
            // 使用官方 API 获取合并好的 Container（单箱=27格，大箱=54格）
            Container container = ChestBlock.getContainer(chestBlock, state, level, pos, false);
            if (container != null) {
                return new SimpleContainerContext(container);
            }
        }
        return new SimpleContainerContext(chest);
    }

    public static void processContext(ContainerContext context, Level level) {
        for (int i = 0; i < context.getSize(); i++) {
            ItemStack stack = context.getItem(i);
            if (LivingItemManager.isLivingItem(stack)) {
                var functions = LivingItemManager.getApplicableFunctions(stack);
                for (var function : functions) {
                    function.tick(stack, i, context, level);
                }
            }
        }
    }

    public static void processBlockEntities(Iterable<BlockEntity> blockEntities, Level level,
                                            IdentityHashMap<Container, Boolean> processedContainers) {
        for (var be : blockEntities) {
            Container container = null;
            if (be instanceof ChestBlockEntity chest) {
                BlockPos pos = chest.getBlockPos();
                BlockState state = level.getBlockState(pos);
                if (state.getBlock() instanceof ChestBlock chestBlock) {
                    container = ChestBlock.getContainer(chestBlock, state, level, pos, false);
                }
                if (container == null) {
                    container = chest instanceof Container ? (Container) chest : null;
                }
            } else if (be instanceof Container c) {
                container = c;
            }
            if (container != null && processedContainers.put(container, Boolean.TRUE) == null) {
                processContext(new SimpleContainerContext(container), level);
            }
        }
    }
}