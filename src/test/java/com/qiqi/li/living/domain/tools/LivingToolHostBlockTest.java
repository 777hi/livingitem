package com.qiqi.li.living.domain.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.qiqi.li.living.config.GameplayConfig;
import com.qiqi.li.living.container.ContainerAnchor;
import com.qiqi.li.living.container.ItemFormContainerContext;
import com.qiqi.li.living.container.SimpleContainerContext;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.items.ItemStackHandler;

/**
 * 活工具「别挖自己容器」黑名单与射线起点<b>同源</b>（2026-10-25，TODO 6.2）。
 *
 * <h3>修的是什么</h3>
 * <p>旧实现的 {@code hostBlocks} 只从容器自己的位置列表取（{@code getAssociatedBlockPositions()}
 * + {@code getBlockPos()}），而<b>物品形态这两个都空</b>。射线起点却是宿主方块中心
 * （箱子里的背包）⇒ {@code scanForTarget} 第一个穿到的格子就是那只箱子：</p>
 * <ul>
 *   <li>蹲着记过方块类型 ⇒ 类型不匹配 ⇒ {@code stopDigging} ⇒ <b>完全没反应</b></li>
 *   <li>没记类型 ⇒ <b>开始挖自己所在的那只箱子</b></li>
 * </ul>
 *
 * <h3>为什么测这两个包级方法而不是 tick 全链路</h3>
 * <p>tick 全链路要 ServerLevel + 方块状态，而这里要守的只是「黑名单里有没有那一格」——
 * 一个纯几何判断。起点与黑名单是否<b>同源</b>（都用 {@code effectiveAnchor}）是本次修复的
 * 全部要点，直接验它比绕一大圈更准。</p>
 *
 * <p>⚠️ 配置开关靠反射翻（{@link GameplayConfig} 的静态字段）—— 生产代码不留测试 seam，
 * 与 {@code LivingTntConsumeTest} 同款做法。</p>
 */
class LivingToolHostBlockTest {

    private static final BlockPos HOST_CHEST = new BlockPos(10, 64, 10);
    private static final BlockPos OTHER = new BlockPos(11, 64, 10);

    private final ItemStackHandler handler = new ItemStackHandler(1);

    @BeforeEach
    @AfterEach
    void resetSwitch() {
        setToolAndWeaponHostAnchor(false);
    }

    private static void setToolAndWeaponHostAnchor(boolean value) {
        try {
            Field field = GameplayConfig.class.getDeclaredField("toolAndWeapon");
            field.setAccessible(true);
            field.set(null, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("无法设置 GameplayConfig.toolAndWeapon", e);
        }
    }

    /** 物品形态容器（= 活背包），带指定的宿主锚点。 */
    private ItemFormContainerContext itemForm(ContainerAnchor hostAnchor) {
        return new ItemFormContainerContext(handler, UUID.randomUUID(), null, 9, hostAnchor);
    }

    /** 方块容器上下文（= 工具直接在一只箱子里）。 */
    private SimpleContainerContext blockContainer() {
        return new SimpleContainerContext(handler, List.of(HOST_CHEST, OTHER), List.of());
    }

    /**
     * 玩家背包上下文（= 工具直接在玩家物品栏里）。
     *
     * <p>⚠️ {@code Inventory.player} 是 <b>public final 字段</b>，Mockito 桩不了
     * （项目已知边界，见 {@code LivingTntConsumeTest} 对 {@code Level.random} 的处理）
     * ⇒ 反射设值。{@code buildContainerKey} 要读 {@code inventory.player.getUUID()}。</p>
     */
    private SimpleContainerContext playerContainer() {
        Player player = mock(Player.class);
        when(player.getUUID()).thenReturn(UUID.randomUUID());
        Inventory inventory = mock(Inventory.class);
        try {
            Field f = Inventory.class.getDeclaredField("player");
            f.setAccessible(true);
            f.set(inventory, player);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("无法设置 Inventory.player", e);
        }
        return new SimpleContainerContext(handler, inventory);
    }

    @Test
    @DisplayName("背包在箱子里 + 开关开 ⇒ 黑名单含宿主那一格（本次修复）")
    void itemFormInChest_blacklistsHostBlock() {
        setToolAndWeaponHostAnchor(true);
        ItemFormContainerContext ctx = itemForm(new ContainerAnchor.AtBlock(HOST_CHEST));

        assertEquals(Set.of(HOST_CHEST), LivingToolFunction.hostBlocksOf(ctx),
            "射线起点 = 箱子中心，不把箱子加进黑名单就会挖到自己的容器");
        assertEquals(Set.of(HOST_CHEST), Set.of(anchorBlock(LivingToolFunction.effectiveAnchor(ctx))),
            "取点与黑名单必须同源：锚点就是那一格");
    }

    @Test
    @DisplayName("开关关 ⇒ 不借宿主锚点 ⇒ 黑名单空（与修复前一致）")
    void itemFormInChest_switchOff_emptyBlacklist() {
        ItemFormContainerContext ctx = itemForm(new ContainerAnchor.AtBlock(HOST_CHEST));

        assertNull(LivingToolFunction.effectiveAnchor(ctx), "开关关时不借宿主锚点");
        assertTrue(LivingToolFunction.hostBlocksOf(ctx).isEmpty(),
            "没有锚点就没有可跳过的宿主方块 —— 此时 resolveOrigin 也会返回 null，根本不会走到扫描");
    }

    @Test
    @DisplayName("方块容器：黑名单含自己的全部位置（大箱两格）—— 回归")
    void blockContainer_keepsOwnPositions() {
        SimpleContainerContext ctx = blockContainer();

        assertEquals(Set.of(HOST_CHEST, OTHER), LivingToolFunction.hostBlocksOf(ctx),
            "方块容器走的是老路，不能被本次改动改坏");
    }

    @Test
    @DisplayName("玩家背包 / 背包在玩家身上 ⇒ 无方块可跳过")
    void playerForms_emptyBlacklist() {
        // 工具直接在玩家背包里
        assertTrue(LivingToolFunction.hostBlocksOf(playerContainer()).isEmpty(),
            "玩家锚点没有方块 —— 射线从眼睛出发，没有「自己的容器」要跳过");

        // 背包（物品形态）在玩家身上
        setToolAndWeaponHostAnchor(true);
        ItemFormContainerContext onPlayer = itemForm(new ContainerAnchor.AtPlayer(
            mock(Player.class)));
        assertTrue(LivingToolFunction.hostBlocksOf(onPlayer).isEmpty(),
            "借到的是玩家锚点，同样没有方块");
    }

    @Test
    @DisplayName("方块容器不受开关影响（它自己有锚点，不需要借）")
    void blockContainer_ignoresSwitch() {
        setToolAndWeaponHostAnchor(true);   // 开着也不该改变方块容器的行为
        SimpleContainerContext ctx = blockContainer();

        assertEquals(new ContainerAnchor.AtBlock(HOST_CHEST),
            LivingToolFunction.effectiveAnchor(ctx),
            "方块容器用自己的锚点，开关只对物品形态有意义");
        assertEquals(Set.of(HOST_CHEST, OTHER), LivingToolFunction.hostBlocksOf(ctx),
            "开关开与否，方块容器的黑名单都是自己的两格");
    }

    @Nullable
    private static BlockPos anchorBlock(ContainerAnchor anchor) {
        return anchor instanceof ContainerAnchor.AtBlock block ? block.pos() : null;
    }
}
