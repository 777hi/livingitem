package com.qiqi.li.living.domain.backpack;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.qiqi.li.living.api.LivingItemActivation;
import com.qiqi.li.living.container.ContainerDataSavedData;
import com.qiqi.li.living.container.ContainerUuids;
import com.qiqi.li.living.container.ItemFormContainers;
import com.qiqi.li.living.domain.water.ContainerFluidData;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;

/**
 * {@link LivingBackpackFunction} 的守卫（2026-10-22）。
 *
 * <p>它<b>没有</b>自己的扫描器/判定/生命周期 —— 那些全由现有 tick 管线提供。
 * 所以这里只守三件事：认领判据（靠来源注册口）、取消活化清理、以及「不做多余的事」。</p>
 */
class LivingBackpackFunctionTest {

    private static final class FakeSource implements ItemFormContainers.Source {
        @Override
        public boolean isContainerItem(ItemStack stack) {
            return stack.is(Items.SHULKER_BOX);
        }

        @Override
        public IItemHandler contentHandler(ItemStack stack) {
            return new ItemStackHandler(9);
        }
    }

    private final LivingBackpackFunction function = new LivingBackpackFunction();

    @AfterEach
    void tearDown() {
        ItemFormContainers.clearForTest();
    }

    @Test
    @DisplayName("认领：没有来源注册 ⇒ 不认领任何物品（未装模组时零影响）")
    void canApply_withoutSource_isFalse() {
        assertFalse(function.canApply(new ItemStack(Items.SHULKER_BOX)));
    }

    @Test
    @DisplayName("认领：来源注册后只认它说「是容器」的物品")
    void canApply_withSource_claimsOnlyContainerItems() {
        ItemFormContainers.register(new FakeSource());

        assertTrue(function.canApply(new ItemStack(Items.SHULKER_BOX)));
        assertFalse(function.canApply(new ItemStack(Items.STONE)), "普通物品不该被活背包认领");
    }

    @Test
    @DisplayName("功能 ID 稳定（tick 顺序 / 缓存键依赖它）")
    void functionId_isStable() {
        org.junit.jupiter.api.Assertions.assertEquals("living_backpack", function.getFunctionId());
    }

    @Test
    @DisplayName("取消活化：连 SavedData 数据一起清（不是只剥 UUID）")
    void onDeactivated_clearsUuidAndData() {
        ItemStack stack = new ItemStack(Items.SHULKER_BOX);
        UUID uuid = ContainerUuids.getOrCreateItem(stack);
        assertNotNull(uuid);

        ContainerFluidData fluid = new ContainerFluidData();
        fluid.registerGeneratedSource(3, Fluids.WATER.getFluidType());
        ContainerDataSavedData.get().put(uuid, ContainerFluidData.KEY, fluid);
        assertNotNull(ContainerDataSavedData.get().get(uuid, ContainerFluidData.KEY), "前置：数据已写入");

        assertTrue(function.onDeactivated(stack, null, null, LivingItemActivation.Via.PLAYER),
            "取消活化不该被否决");

        assertNull(ContainerUuids.ofItem(stack), "UUID 必须被剥掉");
        assertNull(ContainerDataSavedData.get().get(uuid, ContainerFluidData.KEY),
            "数据必须一起删 —— 只剥 UUID 会留成孤儿条目");
    }

    @Test
    @DisplayName("取消活化：没有 UUID 的物品也不抛")
    void onDeactivated_withoutUuid_isSafe() {
        assertTrue(function.onDeactivated(new ItemStack(Items.SHULKER_BOX), null, null,
            LivingItemActivation.Via.PLAYER));
    }
}
