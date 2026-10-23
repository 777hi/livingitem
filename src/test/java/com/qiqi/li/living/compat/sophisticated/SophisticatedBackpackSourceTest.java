package com.qiqi.li.living.compat.sophisticated;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.items.IItemHandler;
import net.p3pp3rf1y.sophisticatedbackpacks.backpack.BackpackItem;
import net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.BackpackWrapper;
import net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.IBackpackWrapper;
import net.p3pp3rf1y.sophisticatedbackpacks.common.gui.BackpackContainer;
import net.p3pp3rf1y.sophisticatedbackpacks.common.gui.BackpackContext;

import com.qiqi.li.living.container.ContainerUuids;
import com.qiqi.li.living.container.ItemFormContainers;

/**
 * 精妙背包来源的守卫（2026-10-23）—— 重点是 {@link SophisticatedBackpackSource#handleOfMenu}
 * 的<b>菜单锚定</b>覆写，以及它的<b>降级阶梯</b>。
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
 * <h3>一个测试基建坑（踩过）</h3>
 * <p><b>不能 {@code new BackpackItem()}</b>：{@code Item} 构造器要写注册表，测试里已冻结
 * （{@code Registry is already frozen}）⇒ 用 {@link mock(Class)} 无构造实例化（Objenesis），
 * 再补两个桩：{@code asItem()}（否则 {@code ItemStack} 里的 item 字段是 null）、
 * {@code components()}（否则原型图为 null，一碰组件就 NPE）。</p>
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
        return playerWith();
    }

    /** 造一个带物品栏的玩家（默认扫描要扫它）。 */
    private static Player playerWith(ItemStack... items) {
        Player player = mock(Player.class);
        Inventory inv = mock(Inventory.class);
        when(player.getInventory()).thenReturn(inv);
        when(inv.getContainerSize()).thenReturn(items.length);
        for (int i = 0; i < items.length; i++) {
            when(inv.getItem(i)).thenReturn(items[i]);
        }
        return player;
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

    /**
     * 一个背包物品栈（{@code isContainerItem} 认它）。
     *
     * <p>⚠️ 三个桩都是必需的，少一个就 NPE / 认不出：</p>
     * <ol>
     *   <li>{@code mock(BackpackItem.class)} —— <b>不能 {@code new}</b>：{@code Item} 构造器
     *       要写注册表，测试里已冻结（{@code Registry is already frozen}）。
     *       Mockito 走 Objenesis 无构造实例化。</li>
     *   <li>{@code asItem()} —— 不桩则 {@code ItemStack} 里的 item 字段是 null。</li>
     *   <li>{@code components()} —— 不桩则 {@code ItemStack} 的原型图为 null，
     *       一碰组件（{@code ContainerUuids} 读 UUID）就 NPE。
     *       借个已注册物品的组件图即可，内容无关。</li>
     * </ol>
     */
    private static ItemStack backpack() {
        BackpackItem item = mock(BackpackItem.class);
        doReturn(item).when(item).asItem();
        doReturn(Items.SHULKER_BOX.components()).when(item).components();
        return new ItemStack(item, 1);
    }

    /** 活化过的背包（带容器 UUID ⇒ 有容器级数据）。 */
    private static ItemStack livingBackpack() {
        ItemStack stack = backpack();
        ContainerUuids.getOrCreateItem(stack);
        return stack;
    }

    // ── 锚点正路 ────────────────────────────────────────────────────────────

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

    // ── 权威列数（#3 槽位几何，2026-10-23）─────────────────────────────────
    //
    // 启发式对「槽数不是 9 的倍数」会猜错：54 格背包装 2 个占列升级后是 30 槽
    // ⇒ 启发式选 10，真值 5 ⇒ 邻居判定 / 流体渲染 / 漏斗传输全线错位。

    @AfterEach
    void resetStubSpec() {
        BackpackWrapper.testSlots = 27;
        BackpackWrapper.testRows = 3;
    }

    @Test
    @DisplayName("权威列数：30 槽 ÷ 6 行 = 5（启发式会给 10，错）")
    void columns_thirtySlotsSixRows_returnsFive() {
        BackpackWrapper.testSlots = 30;
        BackpackWrapper.testRows = 6;

        assertEquals(5, source.columns(backpack()),
            "54 格背包装 2 个占列升级 ⇒ 30 槽 / 6 行 = 5；启发式会选 10 ⇒ 邻居全错");
    }

    @Test
    @DisplayName("权威列数：27 槽 ÷ 3 行 = 9（无升级的常态）")
    void columns_standardBackpack_returnsNine() {
        assertEquals(9, source.columns(backpack()));
    }

    @Test
    @DisplayName("权威列数：除不尽 ⇒ 0（让调用方回落启发式，不比现在更差）")
    void columns_indivisible_returnsZero() {
        BackpackWrapper.testSlots = 20;
        BackpackWrapper.testRows = 3;

        assertEquals(0, source.columns(backpack()));
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

    @Test
    @DisplayName("不是背包菜单 ⇒ null（不把别的模组菜单误认成这个背包）")
    void handleOf_foreignMenu_returnsNull() {
        assertNull(ItemFormContainers.handleOf(mock(AbstractContainerMenu.class), player()),
            "非背包菜单不能扫物品栏猜 —— 玩家只带一个活背包时会把 AE2/ProjectE 的槽位错认成它");
    }

    // ── 边界：认不出就是不认，不猜 ──────────────────────────────────────────

    @Test
    @DisplayName("锚定物未活化 ⇒ null（没有身份就没有容器级数据）")
    void handleOf_anchoredBackpackNotActivated_returnsNull() {
        assertNull(ItemFormContainers.handleOf(menuOf(backpack()), playerWith()),
            "未活化的背包没有 cuuid 键 ⇒ 解析出来也无处落数据");
    }
}
