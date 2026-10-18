package com.qiqi.li.living.compat.ironchest;

import static org.junit.jupiter.api.Assertions.assertFalse;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * IronChestCompat 防御性测试 —— 单测环境（无 ModList / 无 ironchest）必须降级为「不支持」且不炸。
 */
class IronChestCompatTest {

    @Test
    @DisplayName("null ⇒ false")
    void nullSafe() {
        assertFalse(IronChestCompat.isIronChest(null));
    }

    @Test
    @DisplayName("原版箱子在单测环境 ⇒ false（ironchest 不在 ⇒ 反射类为 null）")
    void vanillaChestFalseInUnitEnv() {
        assertFalse(IronChestCompat.isIronChest(
            new ChestBlockEntity(net.minecraft.core.BlockPos.ZERO,
                Blocks.CHEST.defaultBlockState())));
    }
}
