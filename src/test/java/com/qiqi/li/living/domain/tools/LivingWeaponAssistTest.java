package com.qiqi.li.living.domain.tools;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.qiqi.li.living.container.EntityContainers;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/**
 * 活武器辅助攻击的<b>实体宿主触发判据</b>（2026-11-12，D-container-25）：
 * 准入实体容器（船 / 矿车 / 生物背包）的近战命中触发补刀；玩家与 FakePlayer 走另一条路，
 * 结构上落不进本判据。
 *
 * <p>出手循环（无敌帧压制 / 直连目标 / 写回）依赖 ServerLevel + FakePlayer 链，
 * 单测环境不建 ⇒ 与玩家路径同口径进实机清单。</p>
 */
class LivingWeaponAssistTest {

    /** 带临时 Provider 的实体宿主替身（注册不撤销，identity 匹配天然隔离）。 */
    private static Entity registeredEntityHost() {
        Entity entity = mock(Entity.class);
        when(entity.getUUID()).thenReturn(UUID.randomUUID());
        EntityContainers.registerProvider(new EntityContainers.Provider() {
            @Override public boolean supports(Entity value) { return value == entity; }
            @Override public com.qiqi.li.living.container.EntityContainerContext createContext(
                    Entity value, ServerLevel world) {
                throw new IllegalStateException("触发判据测试不应构造上下文");
            }
            @Override public int menuPhysicalSlot(Entity value,
                    net.minecraft.world.inventory.AbstractContainerMenu menu,
                    net.minecraft.world.inventory.Slot slot) {
                return -1;
            }
        });
        return entity;
    }

    @Test
    @DisplayName("准入实体宿主 ⇒ 触发（生物背包 / 船 / 矿车同类）")
    void registeredEntityHost_triggers() {
        assertTrue(LivingWeaponAssist.isEntityHostSource(registeredEntityHost()));
    }

    @Test
    @DisplayName("无准入实体 ⇒ 不触发（普通生物 / 任意实体）")
    void plainEntity_doesNotTrigger() {
        assertFalse(LivingWeaponAssist.isEntityHostSource(mock(Entity.class)));
    }

    @Test
    @DisplayName("ServerPlayer / FakePlayer 结构上落不进实体判据（玩家走独立分支 + fake 排除）")
    void serverPlayer_doesNotTrigger() {
        assertFalse(LivingWeaponAssist.isEntityHostSource(mock(ServerPlayer.class)));
    }

    @Test
    @DisplayName("null 来源 ⇒ 不触发")
    void nullSource_doesNotTrigger() {
        assertFalse(LivingWeaponAssist.isEntityHostSource(null));
    }
}
