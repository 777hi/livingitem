package com.qiqi.li.living.compat.sophisticated;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.items.SlotItemHandler;
import net.neoforged.neoforge.items.wrapper.RangedWrapper;
import net.p3pp3rf1y.sophisticatedbackpacks.backpack.BackpackBlockEntity;
import net.p3pp3rf1y.sophisticatedbackpacks.backpack.BackpackItem;
import net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.BackpackWrapper;
import net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.IBackpackWrapper;
import net.p3pp3rf1y.sophisticatedbackpacks.common.gui.BackpackContainer;
import net.p3pp3rf1y.sophisticatedbackpacks.common.gui.BackpackContext;

import com.qiqi.li.living.container.ContainerAnchor;
import com.qiqi.li.living.container.ContainerContexts;
import com.qiqi.li.living.container.ContainerKeys;
import com.qiqi.li.living.container.ContainerLivingItemHandler;
import com.qiqi.li.living.container.ContainerUuids;
import com.qiqi.li.living.container.ContainerViewers;
import com.qiqi.li.living.container.ItemFormContainerContext;
import com.qiqi.li.living.container.ItemFormContainers;
import com.qiqi.li.living.container.MenuSlotOwners;
import com.qiqi.li.living.container.TickContext;
import com.qiqi.li.living.container.TickableContainerContext;

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

    // ── 放置形态的权威列数（2026-10-24 用户实测：物品形态对、放置态错）─────────
    //
    // 方块容器走 SimpleContainerContext，它的三层推断（规则表 / 已知类型表 / 启发式）
    // 都不认识第三方 BE ⇒ 精妙背包装储罐后 30 存储槽被启发式猜成 10 列（真值 5）。
    // ⇒ 来源必须也能答「这个 BE 是我的容器、几列」。

    @Test
    @DisplayName("放置态：30 槽 ÷ 6 行 = 5（与物品形态同一套算法）")
    void columnsOfBlockEntity_thirtySlotsSixRows_returnsFive() {
        BackpackWrapper.testSlots = 30;
        BackpackWrapper.testRows = 6;

        assertEquals(5, source.columnsOfBlockEntity(placedBackpack()),
            "放置态与物品形态必须同值 —— 否则同一个背包装在手里对、放在地上错");
    }

    @Test
    @DisplayName("放置态：27 槽 ÷ 3 行 = 9（无升级的常态）")
    void columnsOfBlockEntity_standardBackpack_returnsNine() {
        assertEquals(9, source.columnsOfBlockEntity(placedBackpack()));
    }

    @Test
    @DisplayName("放置态：不是背包 BE ⇒ 0（不认领别人的方块实体）")
    void columnsOfBlockEntity_foreignBe_returnsZero() {
        assertEquals(0, source.columnsOfBlockEntity(mock(BlockEntity.class)),
            "别的模组的 BE 必须让给别的来源 / 规则表，不能瞎认");
    }

    /** 造一个「放置态背包」BE —— wrapper 里裹着同一个物品栈。 */
    private static BackpackBlockEntity placedBackpack() {
        return new BackpackBlockEntity(BackpackWrapper.fromStack(backpack()));
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

    @Test
    @DisplayName("两个活背包：菜单内容、真实宿主槽与 tick 同源")
    void contextOf_twoBackpacks_matchesTickHostAndContents() {
        ItemStack first = livingBackpack();
        ItemStack second = livingBackpack();
        Player owner = playerWith(first, ItemStack.EMPTY, second);
        Level level = mock(Level.class);
        when(owner.level()).thenReturn(level);
        BackpackContainer menu = new BackpackContainer(
            new BackpackContext.Item(BackpackWrapper.fromStack(second), 2));

        ItemFormContainerContext context = ItemFormContainers.contextOf(menu, owner);
        ItemFormContainerContext tickContext = new ItemFormContainerContext(source.contentHandler(second),
            ContainerUuids.ofItem(second), level, source.columns(second), new ContainerAnchor.AtPlayer(owner), 2);

        assertNotNull(context);
        assertEquals(tickContext.getContainerKey(), context.getContainerKey());
        assertEquals(tickContext.getHostAnchor(), context.getHostAnchor());
        assertEquals(tickContext.getHostSlot(), context.getHostSlot());
        assertEquals(tickContext.getLiveColumns(), context.getLiveColumns());
        assertSame(source.contentHandler(second), ItemFormContainers.handleOf(menu, owner).handler());
        assertNull(context.getAnchor(), "借宿主信息不能变成物品容器自身的世界位置");
    }

    @Test
    @DisplayName("重复容器 UUID：旧内容解析保留，但不给宿主借位")
    void contextOf_duplicateUuid_hasNoHostProof() {
        ItemStack stack = livingBackpack();
        Player owner = playerWith(stack, stack.copy());
        BackpackContainer menu = new BackpackContainer(
            new BackpackContext.Item(BackpackWrapper.fromStack(stack), 0));

        assertUnknownHost(ItemFormContainers.contextOf(menu, owner));
        assertNotNull(ItemFormContainers.handleOf(menu, owner));
    }

    @Test
    @DisplayName("AnotherPlayer 只证明真实主人，不能借查看者的同 UUID 背包")
    void contextOf_anotherPlayer_usesActualOwnerInventory() {
        ItemStack stack = livingBackpack();
        Player viewer = playerWith(stack.copy());
        Player owner = playerWith(ItemStack.EMPTY, stack);
        Level level = mock(Level.class);
        when(viewer.level()).thenReturn(level);
        when(owner.level()).thenReturn(level);
        BackpackContainer menu = new BackpackContainer(
            new BackpackContext.AnotherPlayer(BackpackWrapper.fromStack(stack), 0, owner));

        ItemFormContainerContext context = ItemFormContainers.contextOf(menu, viewer);

        assertNotNull(context);
        assertEquals(new ContainerAnchor.AtPlayer(owner), context.getHostAnchor());
        assertEquals(1, context.getHostSlot(), "原生分区槽不等于真实物品栏平面槽");
    }

    @Test
    @DisplayName("AnotherPlayer 主人物品栏无背包：即使查看者有相同 UUID 也不借位")
    void contextOf_anotherPlayerMissingHost_doesNotFallBackToViewer() {
        ItemStack stack = livingBackpack();
        Player viewer = playerWith(stack);
        Player owner = playerWith();
        BackpackContainer menu = new BackpackContainer(
            new BackpackContext.AnotherPlayer(BackpackWrapper.fromStack(stack), 0, owner));

        assertUnknownHost(ItemFormContainers.contextOf(menu, viewer));
    }

    @Test
    @DisplayName("官方 Item 原槽失效：不以物品栏唯一背包替代菜单证据")
    void contextOf_invalidOriginalSlot_hasNoHostProof() {
        ItemStack stack = livingBackpack();
        Player owner = playerWith(stack);
        BackpackContainer menu = new BackpackContainer(
            new BackpackContext.Item(BackpackWrapper.fromStack(stack), -1));

        assertUnknownHost(ItemFormContainers.contextOf(menu, owner));
    }

    @Test
    @DisplayName("菜单背包不在真实物品栏：不使用默认 owner/position")
    void contextOf_itemMissingFromInventory_hasNoHostProof() {
        ItemStack stack = livingBackpack();
        BackpackContainer menu = new BackpackContainer(
            new BackpackContext.Item(BackpackWrapper.fromStack(stack), 0));

        assertUnknownHost(ItemFormContainers.contextOf(menu, player()));
    }

    @Test
    @DisplayName("未知来源的基类默认主人与位置不是证据")
    void contextOf_unknownType_hasNoHostProof() {
        ItemStack stack = livingBackpack();
        assertUnknownHost(ItemFormContainers.contextOf(menuOf(stack), playerWith(stack)));
    }

    @Test
    @DisplayName("物品子背包：不穿透直接物品 parent 借玩家锚点")
    void contextOf_itemSubBackpack_doesNotPierceParent() {
        ItemStack parent = livingBackpack();
        ItemStack child = livingBackpack();
        BackpackContext parentContext = new BackpackContext.Item(BackpackWrapper.fromStack(parent), 0);
        BackpackContainer menu = new BackpackContainer(
            new BackpackContext.ItemSubBackpack(BackpackWrapper.fromStack(child), 0, parentContext));

        assertUnknownHost(ItemFormContainers.contextOf(menu, playerWith(parent, child)));
    }

    @Test
    @DisplayName("方块子背包：明确物品上下文隔离菜单 parent BE 的位置回退")
    void resolve_blockSubBackpack_doesNotBecomeParentBe() {
        ItemStack parent = livingBackpack();
        ItemStack child = livingBackpack();
        BlockPos position = new BlockPos(4, 65, 7);
        BackpackContext parentContext = new BackpackContext.Block(BackpackWrapper.fromStack(parent), position);
        BackpackContainer menu = new BackpackContainer(
            new BackpackContext.BlockSubBackpack(BackpackWrapper.fromStack(child), position, parentContext));
        ServerPlayer viewer = mock(ServerPlayer.class);
        when(viewer.level()).thenReturn(mock(Level.class));
        viewer.containerMenu = menu;
        MenuSlotOwners.registerMenuResolver(candidate -> candidate == menu ? position : null);
        IItemHandler parentContents = new ItemStackHandler(27);
        BackpackBlockEntity parentBe = placedBackpack();
        when(viewer.level().getBlockEntity(position)).thenReturn(parentBe);
        when(viewer.level().getCapability(org.mockito.Mockito.eq(
            net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK),
            org.mockito.Mockito.eq(position), org.mockito.Mockito.any())).thenReturn(parentContents);
        Slot slot = new Slot(new SimpleContainer(1), 0, 0, 0);

        var resolved = ContainerContexts.resolve(viewer, slot);

        assertNotNull(resolved);
        assertEquals(ContainerUuids.ofItem(child), resolved.getContainerUuid());
        assertNull(resolved.getAnchor());
        assertNull(resolved.getHostAnchor());
        assertEquals(-1, resolved.getHostSlot());
    }

    @Test
    @DisplayName("子背包缺容器 UUID：Handle 为空仍不回落 parent BE")
    void resolve_blockSubBackpackWithoutUuid_rejectsParentBeFallback() {
        assertBrokenSubBackpackRejectsParent(BackpackWrapper.fromStack(backpack()));
    }

    @Test
    @DisplayName("子背包 wrapper 失效：Handle 为空仍不回落 parent BE")
    void resolve_blockSubBackpackWithUnavailableWrapper_rejectsParentBeFallback() {
        assertBrokenSubBackpackRejectsParent(null);
    }

    @Test
    @DisplayName("子背包 wrapper 抛异常：菜单类型认领不依赖内容视图")
    void resolve_blockSubBackpackWithThrowingWrapper_rejectsParentBeFallback() {
        IBackpackWrapper unavailable = () -> { throw new IllegalStateException("wrapper unavailable"); };
        assertBrokenSubBackpackRejectsParent(unavailable);
    }

    private void assertBrokenSubBackpackRejectsParent(IBackpackWrapper wrapper) {
        BlockPos position = new BlockPos(4, 65, 7);
        BackpackContext parent = new BackpackContext.Block(
            BackpackWrapper.fromStack(livingBackpack()), position);
        BackpackContainer menu = new BackpackContainer(
            new BackpackContext.BlockSubBackpack(wrapper, position, parent));
        ServerPlayer viewer = mock(ServerPlayer.class);
        Level level = mock(Level.class);
        when(viewer.level()).thenReturn(level);
        viewer.containerMenu = menu;
        MenuSlotOwners.registerMenuResolver(candidate -> candidate == menu ? position : null);
        BackpackBlockEntity parentBe = placedBackpack();
        when(level.getBlockEntity(position)).thenReturn(parentBe);
        when(level.getCapability(org.mockito.Mockito.eq(
            net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK),
            org.mockito.Mockito.eq(position), org.mockito.Mockito.any())).thenReturn(new ItemStackHandler(27));
        Slot slot = new Slot(new SimpleContainer(1), 0, 0, 0);

        assertNotNull(level.getBlockEntity(position));
        assertEquals(java.util.List.of(position), MenuSlotOwners.resolve(menu, slot));
        assertNull(ItemFormContainers.handleOf(menu, viewer));
        assertNull(ItemFormContainers.contextOf(menu, viewer));
        assertTrue(ItemFormContainers.isItemContentMenu(menu, viewer));
        assertNull(ContainerContexts.resolve(viewer, slot), "明确子菜单不能回落到可用的 parent BE");
    }

    @Test
    @DisplayName("顶层方块背包不认作借位物品上下文，保留 BE 解析")
    void contextOf_topLevelBlock_leavesBePathIntact() {
        ItemStack stack = livingBackpack();
        BackpackContainer menu = new BackpackContainer(
            new BackpackContext.Block(BackpackWrapper.fromStack(stack), BlockPos.ZERO));

        assertNull(ItemFormContainers.contextOf(menu, playerWith(stack)));
        assertFalse(ItemFormContainers.isItemContentMenu(menu, playerWith(stack)));
        assertNotNull(ItemFormContainers.handleOf(menu, playerWith(stack)));
    }

    @Test
    @DisplayName("外来菜单即使玩家只有一个活背包也不被认领")
    void contextOf_foreignMenuWithOneBackpack_returnsNull() {
        ItemStack stack = livingBackpack();
        Player owner = playerWith(stack);
        AbstractContainerMenu menu = mock(AbstractContainerMenu.class);

        assertNull(ItemFormContainers.contextOf(menu, owner));
        assertFalse(ItemFormContainers.isItemContentMenu(menu, owner));
        assertNull(ItemFormContainers.handleOf(menu, owner));
    }

    @ParameterizedTest(name = "子菜单有 UUID：{0}")
    @ValueSource(booleans = {true, false})
    @DisplayName("精妙子背包的原生位置不能订阅 parent BE 快照或槽位")
    void snapshotViewer_blockSubBackpackNeverViewsParent(boolean hasUuid) throws ReflectiveOperationException {
        ItemStack parent = livingBackpack();
        ItemStack child = hasUuid ? livingBackpack() : backpack();
        PositionedBackpack placed = new PositionedBackpack(parent);
        var parentContext = new BackpackContext.Block(BackpackWrapper.fromStack(parent), placed.pos);
        BackpackContainer menu = new BackpackContainer(
            new BackpackContext.BlockSubBackpack(BackpackWrapper.fromStack(child), placed.pos, parentContext));
        ItemStack original = new ItemStack(Items.APPLE, 2);
        placed.open(menu, child, original);
        assertEquals(placed.pos, menu.getBlockPosition().orElseThrow(), "上游会报告外层坐标");

        withMenuResolver(menu, () -> {
            if (hasUuid) {
                String childKey = ContainerKeys.cuuidKey(ContainerUuids.ofItem(child));
                assertEquals(List.of(placed.viewer), ContainerViewers.of(List.of(placed.viewer), childKey, List.of()));
            } else {
                assertNull(ItemFormContainers.handleOf(menu, placed.viewer));
            }
            placed.flushDirtySlot();
            assertAll(
                () -> assertEquals(List.of(), placed.blockViewers()),
                () -> verifyNoInteractions(placed.viewer.connection),
                () -> assertTrue(ItemStack.matches(original, menu.remoteSlots.get(0)),
                    "子菜单的远端快照不得被外层背包覆盖"));
        });
    }

    @ParameterizedTest(name = "顶层方块菜单有 UUID：{0}")
    @ValueSource(booleans = {true, false})
    @DisplayName("精妙顶层方块背包仍接收自身快照和槽位包，不依赖 UUID")
    void snapshotViewer_topLevelBlockBackpackViewsOwnBlock(boolean hasUuid) throws ReflectiveOperationException {
        ItemStack stack = hasUuid ? livingBackpack() : backpack();
        PositionedBackpack placed = new PositionedBackpack(stack);
        BackpackContainer menu = new BackpackContainer(
            new BackpackContext.Block(BackpackWrapper.fromStack(stack), placed.pos));
        placed.open(menu, stack, new ItemStack(Items.APPLE, 2));

        withMenuResolver(menu, () -> {
            assertEquals(List.of(placed.viewer), placed.blockViewers());
            placed.flushDirtySlot();
            var packet = ArgumentCaptor.forClass(ClientboundContainerSetSlotPacket.class);
            verify(placed.viewer.connection).send(packet.capture());
            assertEquals(menu.containerId, packet.getValue().getContainerId());
            assertEquals(0, packet.getValue().getSlot());
            assertTrue(ItemStack.matches(placed.updated, packet.getValue().getItem()));
            assertTrue(ItemStack.matches(placed.updated, menu.remoteSlots.get(0)));
        });
    }

    private static final class PositionedBackpack {
        final ServerLevel level = mock(ServerLevel.class);
        final ServerPlayer viewer = mock(ServerPlayer.class);
        final ItemStack updated = new ItemStack(Items.DIAMOND, 3);
        final BlockPos pos;
        final TickableContainerContext context;

        PositionedBackpack(ItemStack parent) {
            var wrapper = BackpackWrapper.fromStack(parent);
            var be = new BackpackBlockEntity(wrapper);
            pos = be.getBlockPos();
            be.setLevel(level);
            when(level.dimension()).thenReturn(Level.OVERWORLD);
            when(level.getBlockEntity(pos)).thenReturn(be);
            when(level.getBlockState(pos)).thenReturn(Blocks.CHEST.defaultBlockState());
            // 方块能力包装与菜单中的存储视图不必是同一实例。
            var inventory = (ItemStackHandler) wrapper.getInventoryHandler();
            var contents = new RangedWrapper(inventory, 0, inventory.getSlots());
            when(level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null)).thenReturn(contents);
            when(viewer.level()).thenReturn(level);
            when(viewer.serverLevel()).thenReturn(level);
            viewer.connection = mock(ServerGamePacketListenerImpl.class);
            MinecraftServer server = mock(MinecraftServer.class);
            PlayerList players = mock(PlayerList.class);
            when(level.getServer()).thenReturn(server);
            when(server.getPlayerList()).thenReturn(players);
            when(players.getPlayers()).thenReturn(List.of(viewer));
            context = ContainerLivingItemHandler.resolveContextAt(level, pos, null);
            assertNotNull(context);
        }

        void open(BackpackContainer menu, ItemStack stack, ItemStack initial) {
            var contents = (ItemStackHandler) BackpackWrapper.fromStack(stack).getInventoryHandler();
            contents.setStackInSlot(0, initial);
            menu.appendSlot(new SlotItemHandler(contents, 0, 0, 0));
            menu.setRemoteSlot(0, initial);
            viewer.containerMenu = menu;
        }

        List<ServerPlayer> blockViewers() {
            return ContainerViewers.of(List.of(viewer), context.getCacheKey(), context.getAssociatedBlockPositions());
        }

        void flushDirtySlot() {
            context.setTickContext(new TickContext(context));
            try {
                context.setItem(0, updated);
                assertTrue(ItemStack.matches(updated, context.getItem(0)));
                context.syncSlotToClients(0, updated);
                verifyNoInteractions(viewer.connection);
                context.flushDirtySlots();
            } finally {
                context.setTickContext(null);
                ContainerLivingItemHandler.clearAllCaches();
            }
        }
    }

    private static void withMenuResolver(BackpackContainer menu, Runnable assertions)
            throws ReflectiveOperationException {
        var resolver = new SophisticatedMenuResolver();
        MenuSlotOwners.MenuPositionResolver registration = candidate -> candidate == menu ? resolver.resolve(candidate) : null;
        Field field = MenuSlotOwners.class.getDeclaredField("MENU_RESOLVERS");
        field.setAccessible(true);
        List<?> resolvers = (List<?>) field.get(null);
        MenuSlotOwners.registerMenuResolver(registration);
        try {
            assertions.run();
        } finally {
            resolvers.remove(registration);
        }
    }

    private static void assertUnknownHost(ItemFormContainerContext context) {
        assertNotNull(context, "宿主证据不足不能丢掉旧内容上下文");
        assertNull(context.getHostAnchor());
        assertEquals(-1, context.getHostSlot());
        assertNull(context.getAnchor());
    }

    // ── 边界：认不出就是不认，不猜 ──────────────────────────────────────────

    @Test
    @DisplayName("锚定物未活化 ⇒ null（没有身份就没有容器级数据）")
    void handleOf_anchoredBackpackNotActivated_returnsNull() {
        assertNull(ItemFormContainers.handleOf(menuOf(backpack()), playerWith()),
            "未活化的背包没有 cuuid 键 ⇒ 解析出来也无处落数据");
    }
}
