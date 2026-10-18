package com.qiqi.li.living.domain.beacon;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.EnderChestBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 宿主判据（协议级 {@code LidBlockEntity}）测试 —— 2026-10-18 从「类型白名单」重构为
 * 「协议判据」后的覆盖矩阵守卫。
 */
class BeaconFunctionTest {

    @Test
    @DisplayName("原版箱子 / 末影箱 ⇒ 协议判据 true（末影箱虽 true 但实际到不了本判据——见下）")
    void liddedContainers() {
        assertTrue(BeaconFunction.isLiddedChest(
            new ChestBlockEntity(BlockPos.ZERO, Blocks.CHEST.defaultBlockState())));
        // ⚠️ 末影箱虽是 LidBlockEntity，但其容器上下文 getBlockPos() 恒 null（方块无
        // ItemHandler.BLOCK 能力面 ⇒ 不被世界扫描 ⇒ 走 processEnderChest 玩家锚定路径）
        // ⇒ 永远到不了本判据；此断言仅守「协议包含关系」，不产生方块宿主，无需特判排除。
        assertTrue(BeaconFunction.isLiddedChest(
            new EnderChestBlockEntity(BlockPos.ZERO, Blocks.ENDER_CHEST.defaultBlockState())));
    }

    @Test
    @DisplayName("木桶（未实现 LidBlockEntity）/ 潜影盒（自有动画）⇒ 非宿主")
    void nonLiddedContainers() {
        assertFalse(BeaconFunction.isLiddedChest(
            new BarrelBlockEntity(BlockPos.ZERO, Blocks.BARREL.defaultBlockState())));
        assertFalse(BeaconFunction.isLiddedChest(
            new ShulkerBoxBlockEntity(BlockPos.ZERO, Blocks.SHULKER_BOX.defaultBlockState())));
    }

    @Test
    @DisplayName("null / 无 BE ⇒ 非宿主")
    void nullSafe() {
        assertFalse(BeaconFunction.isLiddedChest(null));
    }
}
