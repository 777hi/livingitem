package com.qiqi.li.living.domain.water;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.qiqi.li.living.config.GameplayConfig;
import com.qiqi.li.living.container.ContainerAnchor;
import com.qiqi.li.living.container.EntityContainerContext;
import com.qiqi.li.living.container.ItemFormContainerContext;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.items.ItemStackHandler;

/**
 * 活水车「无 BE、无玩家背包」时的注入锚点双路径（2026-11-12 实体宿主行为兼容）：
 * 实体容器用自己的位置 —— 不设门（与方块容器同判）；物品形态借宿主锚点 ——
 * {@code host_anchor.water_wheel} 门（D-container-16 原范围）。
 *
 * <p>只测锚点解析，不测 {@code StressOutputManager.apply} 链（Create 未加载时它在
 * {@code CreateCompat.isLoaded()} 即返回，注入面由 Create 侧 mixin 守卫）。</p>
 */
class ContainerStressWritebackTest {

    private static final BlockPos HOST_CHEST = new BlockPos(10, 64, 10);
    private static final BlockPos ENTITY_POS = new BlockPos(20, 70, 20);

    private final ItemStackHandler handler = new ItemStackHandler(1);

    @BeforeEach
    @AfterEach
    void resetSwitch() {
        setWaterWheelHostAnchor(false);
    }

    /** ⚠️ 生产代码不留测试 seam，配置开关靠反射翻 —— 与 {@code LivingToolHostBlockTest} 同款。 */
    private static void setWaterWheelHostAnchor(boolean value) {
        try {
            Field field = GameplayConfig.class.getDeclaredField("waterWheel");
            field.setAccessible(true);
            field.set(null, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("无法设置 GameplayConfig.waterWheel", e);
        }
    }

    /** 实体容器上下文（= 活水车在船 / 矿车 / 生物背包 / 掉落物里）。 */
    private EntityContainerContext entityCtx() {
        Entity entity = mock(Entity.class);
        when(entity.getUUID()).thenReturn(UUID.randomUUID());
        when(entity.blockPosition()).thenReturn(ENTITY_POS);
        return new EntityContainerContext(entity, mock(ServerLevel.class), handler);
    }

    /** 物品形态容器（= 活背包），带指定的宿主锚点。 */
    private ItemFormContainerContext itemForm(ContainerAnchor hostAnchor) {
        return new ItemFormContainerContext(handler, UUID.randomUUID(), null, 9, hostAnchor);
    }

    @Test
    @DisplayName("实体容器：用自己的位置（不设门，开关关也非空）")
    void entityContainer_usesOwnAnchor_ignoringSwitch() {
        ContainerAnchor anchor = ContainerStressWriteback.selfOrBorrowedHostAnchor(entityCtx());

        assertTrue(anchor instanceof ContainerAnchor.AtEntity, "实体容器自己的锚点 = AtEntity");
        assertEquals(ENTITY_POS, ((ContainerAnchor.AtEntity) anchor).entity().blockPosition(),
            "注入点解析用实体所在格（StressOutputManager 内部取 below() = 脚下方块）");
    }

    @Test
    @DisplayName("实体容器：开关开也不变 —— 借来的位置才受开关门")
    void entityContainer_switchOn_unaffected() {
        setWaterWheelHostAnchor(true);

        assertTrue(ContainerStressWriteback.selfOrBorrowedHostAnchor(entityCtx())
            instanceof ContainerAnchor.AtEntity, "实体锚点不受开关影响");
    }

    @Test
    @DisplayName("物品形态：开关关 ⇒ null（不借宿主锚点，应力只进 tooltip）")
    void itemForm_switchOff_null() {
        assertNull(ContainerStressWriteback.selfOrBorrowedHostAnchor(
            itemForm(new ContainerAnchor.AtBlock(HOST_CHEST))));
    }

    @Test
    @DisplayName("物品形态：开关开 ⇒ 借宿主锚点（D-container-16 原样）")
    void itemForm_switchOn_borrowsHostAnchor() {
        setWaterWheelHostAnchor(true);
        ItemFormContainerContext ctx = itemForm(new ContainerAnchor.AtBlock(HOST_CHEST));

        ContainerAnchor anchor = ContainerStressWriteback.selfOrBorrowedHostAnchor(ctx);
        assertTrue(anchor instanceof ContainerAnchor.AtBlock);
        assertEquals(HOST_CHEST, ((ContainerAnchor.AtBlock) anchor).pos(),
            "借到的宿主锚点原样用于注入点解析");
    }
}
