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
    @DisplayName("原版箱子 / 末影箱 ⇒ 宿主（LidBlockEntity；陷阱箱共用 BE 类型但 block 不同，构造校验挡着，略）")
    void liddedContainers() {
        assertTrue(BeaconFunction.isLiddedChest(
            new ChestBlockEntity(BlockPos.ZERO, Blocks.CHEST.defaultBlockState())));
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
