package com.qiqi.li.client.render;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;

import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.domain.piston.LivingPistonData;
import com.qiqi.li.living.domain.piston.PistonSegment;
import com.qiqi.li.living.model.Pos2D;
import com.qiqi.li.living.runtime.LivingItemClientCache;
import com.qiqi.li.living.runtime.RuntimeSegments;

import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.SimpleContainer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class LivingPistonOverlayTest {

    @AfterEach
    void clean() {
        LivingItemClientCache.clear();
    }

    private static ItemStack livingPiston(Item item, Pos2D direction) {
        ItemStack stack = new ItemStack(item);
        LivingItemManager.setLiving(stack, true);
        LivingPistonData.set(stack, new LivingPistonData(direction));
        return stack;
    }

    @Test
    void admissionAcceptsOnlyLivingPistons() {
        assertNull(LivingPistonOverlay.admission(ItemStack.EMPTY));
        assertNull(LivingPistonOverlay.admission(new ItemStack(Items.PISTON)));
        assertNull(LivingPistonOverlay.admission(livingPiston(Items.HOPPER, Pos2D.UP)));
        assertEquals(Pos2D.UP, LivingPistonOverlay.admission(livingPiston(Items.PISTON, Pos2D.UP)));
        assertEquals(Pos2D.RIGHT,
            LivingPistonOverlay.admission(livingPiston(Items.STICKY_PISTON, Pos2D.RIGHT)));
    }

    @Test
    void admissionReadsComponentDirectionNotIconOrientation() {
        assertEquals(Pos2D.DOWN, LivingPistonOverlay.admission(livingPiston(Items.PISTON, Pos2D.DOWN)));
        ItemStack plain = new ItemStack(Items.STICKY_PISTON);
        assertNull(LivingPistonOverlay.admission(plain), "未活化不画");
        plain.setCount(0);
        assertNull(LivingPistonOverlay.admission(plain));
    }

    @Test
    void backpackSlotIsDetectedByContainerIdentity() {
        var playerInv = new net.minecraft.world.entity.player.Inventory(null);
        var backpackSlot = new Slot(playerInv, 5, 0, 0);
        var chestSlot = new Slot(new SimpleContainer(9), 5, 0, 0);

        assertTrue(LivingPistonOverlay.isBackpackSlot(playerInv, backpackSlot));
        assertFalse(LivingPistonOverlay.isBackpackSlot(playerInv, chestSlot));
        assertFalse(LivingPistonOverlay.isBackpackSlot(null, backpackSlot));
    }

    @Test
    void segmentForRoutesBySlotKind() {
        var general = RuntimeSegments.EMPTY.with(PistonSegment.INSTANCE,
            new com.qiqi.li.living.domain.piston.PistonRuntime(true, Pos2D.UP));
        var player = RuntimeSegments.EMPTY.with(PistonSegment.INSTANCE,
            new com.qiqi.li.living.domain.piston.PistonRuntime(false, Pos2D.LEFT));
        LivingItemClientCache.update("chest_test", Map.of(5, general));
        LivingItemClientCache.updatePlayer(Map.of(5, player));

        assertFalse(LivingPistonOverlay.segmentFor(true, 5).get(PistonSegment.INSTANCE).extended(),
            "背包槽走 getPlayer，不受容器缓存串台");
        assertTrue(LivingPistonOverlay.segmentFor(false, 5).get(PistonSegment.INSTANCE).extended(),
            "容器槽走通用缓存");
        assertNull(LivingPistonOverlay.segmentFor(false, 99).get(PistonSegment.INSTANCE),
            "缺失槽 = 无片段 = 不画");
    }

    @Test
    void slotNumberCollisionAcrossKindsStaysSeparated() {
        // 同号 5：容器槽与背包槽键前缀不同 ⇒ 同帧互不覆盖（进度键约定）。
        assertEquals("P5", LivingPistonOverlay.keyFor(true, 5));
        assertEquals("C5", LivingPistonOverlay.keyFor(false, 5));
        assertNotEquals(LivingPistonOverlay.keyFor(true, 5), LivingPistonOverlay.keyFor(false, 5));
    }
}
