package com.qiqi.li.living.domain.beacon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import com.qiqi.li.living.container.ItemFormContainerContext;
import com.qiqi.li.living.container.SimpleContainerContext;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.items.ItemStackHandler;

/**
 * 信标宿主分派与取点<b>同源</b>（2026-10-25，TODO 6.2）。
 *
 * <h3>修的是什么</h3>
 * <p>真机现象：四个宿主锚点开关全开，活背包里的活信标 <b>buff 生效但没有光束</b>。
 * 根因是 {@code resolveHostPosition} 已经改成问锚点，但拿到 hostPos 之后的<b>下游分派</b>
 * 又绕回去问容器自己：</p>
 *
 * <pre>
 * BlockPos containerPos = context.getBlockPos();     // 物品形态恒 null
 * if (containerPos == null) {
 *     Player owner = context.getOwnerPlayer();       // 物品形态恒 null
 *     ...                                          // ⇒ 两条分支都进不去
 * }</pre>
 *
 * <p>⇒ 光束 / 开盖 / 玻璃色 / 联机广播全部不执行，只有 {@code applyEffects} 吃到了 hostPos。
 * 修法：分派也问 {@link #effectiveAnchor} ——「宿主是谁」只问一次。</p>
 *
 * <h3>为什么只测 effectiveAnchor</h3>
 * <p>{code tick} 全链路在单测里走不到宿主分支：金字塔等级要靠
 * {@code BEACON_BASE_BLOCKS} 物品标签判定底座材质，而**标签在单测环境不加载**
 * （项目已知限制，见 {@code BeaconBaseBlocks} 注释）⇒ 等级恒 0 ⇒ 宿主分支被
 * {@code pyramidLevel >= 1} 挡在门外。故这里只守「分派依据」这一个决策点，
 * 下游的 {@code isLiddedChest} / {@code BeaconChestHost.report} 各有既有测试守着。</p>
 *
 * <p>⚠️ 配置开关靠反射翻（{@link GameplayConfig} 的静态字段），与
 * {@code LivingTntConsumeTest} / {@code LivingToolHostBlockTest} 同款做法。</p>
 */
class BeaconHostDispatchTest {

    private static final BlockPos CHEST = new BlockPos(10, 64, 10);

    private final ItemStackHandler handler = new ItemStackHandler(1);

    @BeforeEach
    @AfterEach
    void resetSwitch() {
        setBeaconHostAnchor(false);
    }

    private static void setBeaconHostAnchor(boolean value) {
        try {
            Field field = GameplayConfig.class.getDeclaredField("beacon");
            field.setAccessible(true);
            field.set(null, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("无法设置 GameplayConfig.beacon", e);
        }
    }

    private ItemFormContainerContext itemForm(ContainerAnchor hostAnchor) {
        return new ItemFormContainerContext(handler, UUID.randomUUID(), null, 9, hostAnchor);
    }

    @Test
    @DisplayName("背包在箱子里 + 开关开 ⇒ 分派拿到【宿主箱子】的锚点（本次修复）")
    void itemFormInChest_dispatchesToChestHost() {
        setBeaconHostAnchor(true);

        assertEquals(new ContainerAnchor.AtBlock(CHEST),
            BeaconFunction.effectiveAnchor(itemForm(new ContainerAnchor.AtBlock(CHEST))),
            "旧写法在这里拿到 null（getBlockPos/getOwnerPlayer 双 null）⇒ 有 buff 没光");
    }

    @Test
    @DisplayName("背包在玩家身上 + 开关开 ⇒ 分派拿到玩家锚点")
    void itemFormOnPlayer_dispatchesToPlayerHost() {
        setBeaconHostAnchor(true);
        Player player = mock(Player.class);
        when(player.getUUID()).thenReturn(UUID.randomUUID());

        assertEquals(new ContainerAnchor.AtPlayer(player),
            BeaconFunction.effectiveAnchor(itemForm(new ContainerAnchor.AtPlayer(player))));
    }

    @Test
    @DisplayName("开关关 ⇒ 不借宿主锚点 ⇒ 无宿主（与修复前一致：不开光）")
    void switchOff_noHost() {
        assertNull(BeaconFunction.effectiveAnchor(itemForm(new ContainerAnchor.AtBlock(CHEST))),
            "开关关时物品形态没有锚点 ⇒ 不开光是既定语义");
    }

    @Test
    @DisplayName("方块容器用自己的锚点，开关无关 —— 回归")
    void blockContainer_usesOwnAnchor() {
        setBeaconHostAnchor(true);
        SimpleContainerContext ctx = new SimpleContainerContext(handler, java.util.List.of(CHEST), java.util.List.of());

        assertEquals(new ContainerAnchor.AtBlock(CHEST), BeaconFunction.effectiveAnchor(ctx),
            "箱子里的信标走箱子宿主，这条路本来是对的，不能被改坏");
    }

    @Test
    @DisplayName("玩家背包用自己的玩家锚点 —— 回归")
    void playerContainer_usesOwnAnchor() {
        Player player = mock(Player.class);
        when(player.getUUID()).thenReturn(UUID.randomUUID());
        Inventory inventory = mock(Inventory.class);
        // Inventory.player 是 public final 字段，Mockito 桩不了 ⇒ 反射设值（同 Level.random）
        try {
            Field f = Inventory.class.getDeclaredField("player");
            f.setAccessible(true);
            f.set(inventory, player);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("无法设置 Inventory.player", e);
        }

        assertEquals(new ContainerAnchor.AtPlayer(player),
            BeaconFunction.effectiveAnchor(new SimpleContainerContext(handler, inventory)));
    }
}
