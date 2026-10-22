package com.qiqi.li.living.container;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;


import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * {@link ContainerUuids} 的守卫（2026-10-22，活背包地基）。
 *
 * <p>守三件事：</p>
 * <ol>
 *   <li><b>懒创建幂等</b>：同一件物品反复 {@code getOrCreate} 拿到同一个 UUID
 *       （否则每次打开 GUI 都是一条新缓存键，熔炉进度从零开始）</li>
 *   <li><b>复制 remove 范式</b>：{@code stripForCopy} 后复制品没有 UUID，
 *       下次懒创建拿新身份 ⇒ 数据独立</li>
 *   <li><b>键形互逆</b>：{@code keyOf} 与 {@code ContainerKeys.containerUuidOf} 往返一致，
 *       且 {@code kindOf} 认得它（不与 {@code container_<dim>_x_y_z} 混）</li>
 * </ol>
 */
class ContainerUuidsTest {

    private static ItemStack shulker() {
        return new ItemStack(Items.SHULKER_BOX);
    }

    @Test
    @DisplayName("懒创建：第一次生成并写入，第二次返回同一个")
    void getOrCreateItem_isIdempotent() {
        ItemStack stack = shulker();

        UUID first = ContainerUuids.getOrCreateItem(stack);
        assertNotNull(first, "首次调用必须生成 UUID");
        assertEquals(first, ContainerUuids.ofItem(stack), "必须已写进物品组件");

        UUID second = ContainerUuids.getOrCreateItem(stack);
        assertEquals(first, second, "再次调用必须复用 —— 否则跨 GUI 开合的进度会重置");
    }

    @Test
    @DisplayName("ofItem：没创建过 ⇒ null（不凭空生成）")
    void ofItem_withoutCreation_returnsNull() {
        assertNull(ContainerUuids.ofItem(shulker()), "读操作不得有写副作用");
        assertNull(ContainerUuids.ofItem(ItemStack.EMPTY), "空栈没有身份");
        assertNull(ContainerUuids.ofItem(null), "null 安全");
    }

    @Test
    @DisplayName("复制 remove 范式：strip 后复制品拿新 UUID、数据独立")
    void stripForCopy_givesCopyItsOwnIdentity() {
        ItemStack original = shulker();
        UUID originalUuid = ContainerUuids.getOrCreateItem(original);

        // ✅ 正确复制：先 strip 再 copy（精妙 cloneBackpack 的做法）
        ContainerUuids.stripForCopy(original);
        ItemStack safeCopy = original.copy();
        UUID copyUuid = ContainerUuids.getOrCreateItem(safeCopy);

        assertNotNull(copyUuid);
        assertNotEquals(originalUuid, copyUuid, "复制品必须拿到新身份，否则两个背包共享一份水");

        // 反例说明（不写测试断言，避免把错误做法固化）：
        // 直接 original.copy() 会把 UUID 带过去 ⇒ 改一个另一个也变
    }

    @Test
    @DisplayName("stripForCopy：没 UUID / 空栈 / null 都不抛")
    void stripForCopy_isSafe() {
        ContainerUuids.stripForCopy(shulker());       // 没有 UUID
        ContainerUuids.stripForCopy(ItemStack.EMPTY);
        ContainerUuids.stripForCopy(null);
    }

    @Test
    @DisplayName("宿主侧：null 宿主返回 null（掉落物等无宿主容器不参与持久化）")
    void getOrCreateHost_nullSafe() {
        assertNull(ContainerUuids.getOrCreateHost(null));
        assertNull(ContainerUuids.ofHost(null));
    }

    @Test
    @DisplayName("discard（取消活化）：剥 UUID + 删数据，两半都做")
    void discard_removesUuidAndData() {
        ItemStack stack = shulker();
        UUID uuid = ContainerUuids.getOrCreateItem(stack);

        // 先塞一份数据进去，验证 discard 会把它删掉
        com.qiqi.li.living.container.ContainerDataSavedData savedData =
            com.qiqi.li.living.container.ContainerDataSavedData.get();
        com.qiqi.li.living.domain.water.ContainerFluidData fluid =
            new com.qiqi.li.living.domain.water.ContainerFluidData();
        fluid.registerGeneratedSource(3, net.minecraft.world.level.material.Fluids.WATER.getFluidType());
        savedData.put(uuid, com.qiqi.li.living.domain.water.ContainerFluidData.KEY, fluid);
        assertNotNull(savedData.get(uuid, com.qiqi.li.living.domain.water.ContainerFluidData.KEY),
            "前置：数据必须已写入");

        ContainerUuids.discard(stack);

        assertNull(ContainerUuids.ofItem(stack), "UUID 必须被剥掉");
        assertNull(savedData.get(uuid, com.qiqi.li.living.domain.water.ContainerFluidData.KEY),
            "数据必须一起删 —— 只剥 UUID 会留成孤儿条目");
    }

    @Test
    @DisplayName("discard：没有 UUID / 空栈 / null 都不抛")
    void discard_isSafe() {
        ContainerUuids.discard(shulker());
        ContainerUuids.discard(ItemStack.EMPTY);
        ContainerUuids.discard(null);
    }

    @Test
    @DisplayName("键形：keyOf 与 containerUuidOf 互逆，且 kindOf 认作 ITEM_FORM")
    void keyRoundTrips() {
        UUID uuid = UUID.fromString("11111111-2222-3333-4444-555555555555");
        String key = ContainerUuids.keyOf(uuid);

        assertTrue(key.startsWith("cuuid_"), "前缀必须是 cuuid_ —— 不能撞 container_<dim>_x_y_z");
        assertEquals(uuid, ContainerKeys.containerUuidOf(key), "解析必须往返");
        assertEquals(ContainerKind.ITEM_FORM, ContainerKeys.kindOf(key));
    }

    @Test
    @DisplayName("键形：cuuid_ 不被误认成世界方块容器（前缀不同形）")
    void cuuidKey_isNotWorldBlock() {
        String key = ContainerUuids.keyOf(UUID.randomUUID());
        assertNotEquals(ContainerKind.WORLD_BLOCK, ContainerKeys.kindOf(key),
            "cuuid_ 与 container_ 必须分得开 —— 这正是换前缀的理由");
        assertTrue(ContainerKeys.positionsOf(key).isEmpty(), "物品形态没有世界位置");
    }
}
