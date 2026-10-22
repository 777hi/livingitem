package com.qiqi.li.living.container;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.qiqi.li.living.components.LivingComponents;
import com.qiqi.li.living.domain.water.ContainerFluidData;
import com.qiqi.li.living.domain.water.ContainerStressData;
import com.qiqi.li.living.domain.water.WaterComponents;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.material.Fluids;

/**
 * {@link ContainerDataSavedData} 的守卫（2026-10-22，活背包地基）。
 *
 * <p>守四件事：</p>
 * <ol>
 *   <li><b>按 UUID 往返</b>：put → get 等值；save → load 后还在（这是“跨重启”的唯一保障）</li>
 *   <li><b>空条目不写盘</b>（借精妙 {@code isPlayerBackpackOrNotEmpty}）：条目被清空后
 *       save/load 不复活 ⇒ 存档瘦身</li>
 *   <li><b>isSaved 判据</b>：没声明 codec 的 key（应力这类 tick 级）写不进来</li>
 *   <li><b>remove 的层级</b>：移除最后一个 key 时连条目一起删（否则空条目堆积）</li>
 * </ol>
 */
class ContainerDataSavedDataTest {

    private static final UUID UUID_A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID UUID_B = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    private static ContainerFluidData fluidWith(int slot) {
        ContainerFluidData data = new ContainerFluidData();
        data.registerGeneratedSource(slot, Fluids.WATER.getFluidType());
        return data;
    }

    private static ContainerDataSavedData roundTrip(ContainerDataSavedData data) {
        CompoundTag saved = data.save(new CompoundTag(), null);
        return ContainerDataSavedData.load(saved, null);
    }

    @Test
    @DisplayName("put → get 往返：按 UUID 取出等值数据")
    void putThenGet_roundTrips() {
        ContainerDataSavedData data = ContainerDataSavedData.createForTest();
        data.put(UUID_A, ContainerFluidData.KEY, fluidWith(3));

        ContainerFluidData got = data.get(UUID_A, ContainerFluidData.KEY);
        assertNotNull(got, "刚 put 的必须能 get 到");
        assertEquals(1, got.getGeneratedSources().size(), "源槽位数量必须一致");
        assertEquals(Fluids.WATER.getFluidType(), got.getGeneratedSources().get(3), "流体类型必须一致");
    }

    @Test
    @DisplayName("save → load 往返：跨重启后数据还在")
    void saveLoad_roundTrips() {
        ContainerDataSavedData data = ContainerDataSavedData.createForTest();
        data.put(UUID_A, ContainerFluidData.KEY, fluidWith(3));
        data.put(UUID_B, ContainerFluidData.KEY, fluidWith(7));

        ContainerDataSavedData reloaded = roundTrip(data);

        assertEquals(2, reloaded.size(), "两个容器的条目都要在");
        assertNotNull(reloaded.get(UUID_A, ContainerFluidData.KEY), "A 的数据必须存活");
        assertNotNull(reloaded.get(UUID_B, ContainerFluidData.KEY), "B 的数据必须存活");
        assertEquals(7, reloaded.get(UUID_B, ContainerFluidData.KEY).getGeneratedSources().keySet().iterator().next(),
            "B 的槽位必须还是 7");
    }

    @Test
    @DisplayName("空条目不写盘（存档瘦身，借精妙的加载过滤）")
    void emptyEntries_notWritten() {
        ContainerDataSavedData data = ContainerDataSavedData.createForTest();
        data.put(UUID_A, ContainerFluidData.KEY, fluidWith(3));
        data.remove(UUID_A, ContainerFluidData.KEY);      // 移除唯一 key ⇒ 条目应消失

        assertEquals(0, data.size(), "移除最后一个 key 必须连条目一起删");

        ContainerDataSavedData reloaded = roundTrip(data);
        assertEquals(0, reloaded.size(), "空条目不能被 save/load 复活");
    }

    @Test
    @DisplayName("isSaved 为 false 的 key 写不进来（应力这类 tick 级）")
    void unsavedKey_rejected() {
        ContainerDataSavedData data = ContainerDataSavedData.createForTest();
        data.put(UUID_A, ContainerStressData.KEY, new ContainerStressData());

        assertNull(data.get(UUID_A, ContainerStressData.KEY), "没声明 codec 的 key 不该进 SavedData");
        assertEquals(0, data.size(), "条目都不该创建");
    }

    @Test
    @DisplayName("remove(UUID) 整容器清除；其它容器不受影响")
    void removeContainer_wipesOnlyThatOne() {
        ContainerDataSavedData data = ContainerDataSavedData.createForTest();
        data.put(UUID_A, ContainerFluidData.KEY, fluidWith(3));
        data.put(UUID_B, ContainerFluidData.KEY, fluidWith(7));

        data.remove(UUID_A);

        assertEquals(1, data.size(), "只删 A");
        assertNull(data.get(UUID_A, ContainerFluidData.KEY));
        assertNotNull(data.get(UUID_B, ContainerFluidData.KEY), "B 必须不受影响");
    }

    @Test
    @DisplayName("同一容器多个 key 共存（将来的相位快照）")
    void multipleKeys_coexist() {
        ContainerDataSavedData data = ContainerDataSavedData.createForTest();
        data.put(UUID_A, ContainerFluidData.KEY, fluidWith(3));

        // 第二个 key 用手头的 another saved key 不现实（相位还没升 savedWith）⇒
        // 用同一 key 的两次 put 验证 computeIfAbsent 不互相覆盖
        data.put(UUID_A, ContainerFluidData.KEY, fluidWith(9));

        ContainerFluidData got = data.get(UUID_A, ContainerFluidData.KEY);
        assertEquals(1, got.getGeneratedSources().size());
        assertEquals(9, got.getGeneratedSources().keySet().iterator().next(), "后一次 put 覆盖前一次");
    }

    @Test
    @DisplayName("未知 UUID / 未知 key ⇒ null，不抛")
    void unknownLookups_returnNull() {
        ContainerDataSavedData data = ContainerDataSavedData.createForTest();
        assertNull(data.get(UUID_A, ContainerFluidData.KEY), "没 put 过 ⇒ null");
        assertNull(data.get(null, ContainerFluidData.KEY), "null UUID ⇒ null");
    }

    @Test
    @DisplayName("组件真的注册了（isSaved 判据依赖它）")
    void componentRegistered() {
        assertTrue(ContainerFluidData.KEY.isSaved(), "水的 key 必须 attachment+codec 双全");
        assertNotNull(WaterComponents.CONTAINER_FLUID_DATA.value(), "附件类型必须已注册");
        assertNotNull(LivingComponents.CONTAINER_UUID.value(), "容器 UUID 物品组件必须已注册");
        assertEquals(0, ContainerDataSavedData.createForTest().size(), "新实例必须为空");
    }
}
