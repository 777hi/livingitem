package com.qiqi.li.living.compat.sophisticated;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.items.IItemHandler;
import net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.BackpackWrapper;
import net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.IBackpackWrapper;
import net.p3pp3rf1y.sophisticatedbackpacks.common.gui.BackpackContainer;
import net.p3pp3rf1y.sophisticatedbackpacks.common.gui.BackpackContext;

import com.qiqi.li.living.container.ContainerUuids;
import com.qiqi.li.living.container.ItemFormContainers;

/**
 * 精妙背包来源的守卫（2026-10-23）—— 重点是 {@link SophisticatedBackpackSource#handleOfMenu}
 * 的<b>菜单锚定</b>覆写。
 *
 * <h3>为什么必须有这个测试</h3>
 * <p>默认实现「扫玩家物品栏 + 唯一一件才认」在玩家带<b>两个</b>活背包时返回 null
 * ⇒ {@code ContainerContexts.resolve} 解析不出 ⇒ tooltip / 倒汲 / GUI 槽位设置全废
 * （真机：两个活背包时活流体倒不了）。精妙的菜单自带锚点
 * （{@code getBackpackContext() → getBackpackWrapper(player).getBackpack()}），
 * 覆写后不需要猜。</p>
 *
 * <p>⚠️ 反射链（类名 / 方法名 / 签名）靠<b>同形替身</b>钉住：替身必须真的落在精妙的包下
 * （见 {@code net.p3pp3rf1y.sophisticatedbackpacks.*} 那几个类），否则只能测到「不认」。
 * 上游改 API 时这些替身不会自动发现 —— 那由运行期的 WARN 日志负责（I-C6）。</p>
 *
 * <p>⚠️ 「背包物品栈」用 {@link Items#SHULKER_BOX} 充当：{@code new Item()} 在测试里会撞
 * 「Registry is already frozen」，而这条反射链里<b>不含物品类型判断</b>
 * （{@code isContainerItem} 不参与菜单反查）⇒ 不需要真的 {@code BackpackItem}。</p>
 */
class SophisticatedBackpackSourceTest {

    private final SophisticatedBackpackSource source = new SophisticatedBackpackSource();

    @BeforeEach
    void setUp() {
        ItemFormContainers.clearForTest();
        ItemFormContainers.register(source);
    }

    @AfterEach
    void tearDown() {
        ItemFormContainers.clearForTest();
    }

    private static Player player() {
        return Mockito.mock(Player.class);
    }

    /** 造一个「锚定 {@code stack}」的背包菜单（GUI 正在看的就是它）。 */
    private static BackpackContainer menuOf(ItemStack stack) {
        IBackpackWrapper wrapper = new IBackpackWrapper() {
            @Override
            public ItemStack getBackpack() {
                return stack;
            }
        };
        return new BackpackContainer(new BackpackContext(wrapper));
    }

    private static ItemStack backpack() {
        return new ItemStack(Items.SHULKER_BOX);
    }

    /** 活化过的背包（带容器 UUID ⇒ 有容器级数据）。 */
    private static ItemStack livingBackpack() {
        ItemStack stack = backpack();
        ContainerUuids.getOrCreateItem(stack);
        return stack;
    }

    @Test
    @DisplayName("两个活背包 ⇒ 认菜单锚定的那一个（本次修复的核心）")
    void handleOf_twoLivingBackpacks_resolvesTheAnchoredOne() {
        ItemStack first = livingBackpack();
        ItemStack second = livingBackpack();
        BackpackContainer menu = menuOf(second);

        ItemFormContainers.Handle handle = ItemFormContainers.handleOf(menu, player());

        assertNotNull(handle, "带两个活背包也必须解析得出 —— 否则倒汲 / tooltip / 槽位设置全废");
        assertEquals(ContainerUuids.ofItem(second), handle.containerUuid(),
            "必须是菜单锚定的那个容器，不是物品栏里碰巧找到的那个");
        assertNotEquals(ContainerUuids.ofItem(first), handle.containerUuid());
    }

    @Test
    @DisplayName("菜单锚定的背包未活化 ⇒ null（没有身份就没有容器级数据）")
    void handleOf_anchoredBackpackNotActivated_returnsNull() {
        assertNull(ItemFormContainers.handleOf(menuOf(backpack()), player()),
            "未活化的背包不该被解析 —— 它没有 cuuid 键，解析出来也无处落数据");
    }

    @Test
    @DisplayName("不是背包菜单 ⇒ null（交给默认扫描 / 其它来源）")
    void handleOf_foreignMenu_returnsNull() {
        assertNull(ItemFormContainers.handleOf(Mockito.mock(AbstractContainerMenu.class), player()));
    }

    @Test
    @DisplayName("反查拿到的内容视图与 tick 路径是同一个（两条路径一套映射）")
    void handleOf_resolvedHandlerMatchesTickPath() {
        ItemStack stack = livingBackpack();

        ItemFormContainers.Handle handle = ItemFormContainers.handleOf(menuOf(stack), player());
        assertNotNull(handle);

        IItemHandler tickPath = source.contentHandler(stack);
        assertSame(tickPath, handle.handler(),
            "菜单反查与 tick 必须拿到同一个内容视图 —— 否则设置与运转读写的不是同一份内容");
    }
}
