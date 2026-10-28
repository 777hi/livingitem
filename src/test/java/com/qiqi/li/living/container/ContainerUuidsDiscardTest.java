package com.qiqi.li.living.container;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.qiqi.li.living.domain.water.ContainerFluidData;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.items.ItemStackHandler;

/**
 * 取消活化的**彻底清理**（2026-10-28 修）。
 *
 * <p>物品形态容器没有位置 ⇒ {@code ContainerLivingItemHandler.removeDataByPos} 的位置反查
 * 永远命中不了它；而内存层（容器级数据 / 槽位追踪 / 运行时缓存）按缓存键另存一份。
 * 旧实现只删 SavedData ⇒ 熔炉进度等留到停服（{@code ContainerRuntimeCache} 没有 TTL）。</p>
 */
class ContainerUuidsDiscardTest {

    @Test
    @DisplayName("discard 必须清掉内存层容器数据（不只是 SavedData）")
    void discard_clearsInMemoryContainerData() {
        ItemStack stack = new ItemStack(Items.SHULKER_BOX);
        UUID uuid = ContainerUuids.getOrCreateItem(stack);
        assertNotNull(uuid, "前置：物品已拿到容器 UUID");

        ItemStackHandler handler = new ItemStackHandler(27);
        ItemFormContainerContext ctx = new ItemFormContainerContext(handler, uuid, null, 9, null, -1);

        ContainerLivingItemHandler.putContainerData(ctx, ContainerFluidData.KEY, ContainerFluidData.EMPTY);
        assertNotNull(ContainerLivingItemHandler.peekContainerData(ctx, ContainerFluidData.KEY),
            "前置：内存层已写入");

        try {
            ContainerUuids.discard(stack);

            assertNull(ContainerLivingItemHandler.peekContainerData(ctx, ContainerFluidData.KEY),
                "取消活化后内存层必须被清（旧实现只删 SavedData ⇒ 运行时缓存留到停服）");
            assertNull(ContainerUuids.ofItem(stack), "UUID 必须被剥掉（重新活化要拿新身份）");
        } finally {
            ContainerLivingItemHandler.removeDataByKey(ContainerKeys.cuuidKey(uuid));
        }
    }
}
