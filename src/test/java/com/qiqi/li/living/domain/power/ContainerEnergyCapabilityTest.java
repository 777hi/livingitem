package com.qiqi.li.living.domain.power;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.IItemHandlerModifiable;

import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.container.ContainerLivingItemHandler;
import com.qiqi.li.living.container.SimpleContainerContext;

/**
 * 对外能量能力（{@code Capabilities.EnergyStorage.BLOCK}）的<b>存在性</b>守卫
 * （§3.6 对外接口，2026-10-31 对齐流体 §10.4）。
 *
 * <p>规则：能力只在「容器里真有活涂蜡铜灯」时存在 —— 线缆类模组按「能力是否非 null」
 * 判邻居是不是储能（Flux {@code ForgeEnergyConnector#hasCapability} / Mekanism
 * {@code acceptorCache}），恒发实例会把任何 BE 认成储能。</p>
 *
 * <p>存在性由电力 tick 通告（{@link ContainerEnergyStorage#announcePresence}），
 * 变化时 {@code Level#invalidateCapabilities} 一次；边界两条：
 * ①「放进第一盏灯」由功能 tick 报名；②「拿走最后一盏灯」时功能已不在分组里，
 * 由写回钩子兜底撤名（本类专门守这条）。</p>
 */
class ContainerEnergyCapabilityTest {

    private static int counter;

    private BlockPos pos;
    private ItemStack[] slots;
    private Level level;
    private BlockEntity be;
    private SimpleContainerContext ctx;

    /** 最小可读写 handler：本类只关心槽位内容与「是不是容器」。 */
    private static final class FakeHandler implements IItemHandlerModifiable {
        private final ItemStack[] slots;

        FakeHandler(ItemStack[] slots) {
            this.slots = slots;
        }

        @Override public int getSlots() { return slots.length; }
        @Override public ItemStack getStackInSlot(int slot) { return slots[slot]; }
        @Override public void setStackInSlot(int slot, ItemStack stack) { slots[slot] = stack; }
        @Override public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) { return stack; }
        @Override public ItemStack extractItem(int slot, int amount, boolean simulate) { return ItemStack.EMPTY; }
        @Override public int getSlotLimit(int slot) { return 64; }
        @Override public boolean isItemValid(int slot, ItemStack stack) { return true; }
    }

    private static ItemStack livingBulb(int count) {
        ItemStack stack = new ItemStack(Items.WAXED_COPPER_BULB, count);
        LivingItemManager.setLiving(stack, true);
        return stack;
    }

    @BeforeEach
    void setUp() {
        pos = new BlockPos(40_000 + counter++, 9, -5_432);
        slots = new ItemStack[9];
        Arrays.fill(slots, ItemStack.EMPTY);

        level = mock(Level.class);
        when(level.isClientSide()).thenReturn(false);
        when(level.getGameTime()).thenReturn(100L);
        doReturn(Blocks.AIR.defaultBlockState()).when(level).getBlockState(any());
        doReturn(null).when(level).getCapability(eq(Capabilities.EnergyStorage.BLOCK), any(), any());

        be = mock(BlockEntity.class);
        when(be.getBlockPos()).thenReturn(pos);
        when(be.getLevel()).thenReturn(level);
        doReturn(be).when(level).getBlockEntity(any());
        doReturn(new FakeHandler(slots)).when(level)
            .getCapability(eq(Capabilities.ItemHandler.BLOCK), any(), any());

        ctx = new SimpleContainerContext(new FakeHandler(slots),
            null, List.of(pos), List.of(be), level);
    }

    @Test
    @DisplayName("存在性跟着活铜灯走：无灯不报名 / 放入报名 / 拿走撤名")
    void presence_followsLivingBulb() {
        ContainerLivingItemHandler.processContext(ctx, level);
        assertNull(ContainerEnergyStorage.resolveProvider(be, null),
            "空容器不报名（线缆按「能力非 null」判储能）");

        slots[0] = livingBulb(4);
        ContainerLivingItemHandler.processContext(ctx, level);
        assertNotNull(ContainerEnergyStorage.resolveProvider(be, null), "有活铜灯 ⇒ 报名");

        slots[0] = ItemStack.EMPTY;
        ContainerLivingItemHandler.processContext(ctx, level);
        assertNull(ContainerEnergyStorage.resolveProvider(be, null),
            "最后一个铜块被拿走 ⇒ 撤名（电力功能已不在分组里，靠写回兜底）");
    }

    @Test
    @DisplayName("存在性边沿：精确失效一次（线缆缓存才肯重查）")
    void presenceEdge_invalidatesExactlyOnce() {
        ContainerLivingItemHandler.processContext(ctx, level);
        verify(level, never()).invalidateCapabilities(pos);

        slots[0] = livingBulb(4);
        ContainerLivingItemHandler.processContext(ctx, level);
        verify(level, times(1)).invalidateCapabilities(pos);

        // 存在性没变 ⇒ 不重复通告（每 tick 无效失效会把线缆缓存砸烂）
        ContainerLivingItemHandler.processContext(ctx, level);
        verify(level, times(1)).invalidateCapabilities(pos);

        slots[0] = ItemStack.EMPTY;
        ContainerLivingItemHandler.processContext(ctx, level);
        verify(level, times(2)).invalidateCapabilities(pos);
    }

    @Test
    @DisplayName("客户端恒不报名（容器级数据只存在服务端）")
    void provider_neverRegistersOnClient() {
        slots[0] = livingBulb(4);
        ContainerLivingItemHandler.processContext(ctx, level);
        assertNotNull(ContainerEnergyStorage.resolveProvider(be, null), "服务端已报名（前置）");

        when(level.isClientSide()).thenReturn(true);
        assertNull(ContainerEnergyStorage.resolveProvider(be, null),
            "客户端拿不到容器级数据 ⇒ 不报名");
    }

    @Test
    @DisplayName("provider 让位：直接实现者 / 战利品容器 / level=null / 已有主人")
    void provider_yieldsToRealOwners() {
        // ① 直接实现 IEnergyStorage 的 BE
        BlockEntity selfImpl = mock(BlockEntity.class,
            org.mockito.Mockito.withSettings().extraInterfaces(IEnergyStorage.class));
        when(selfImpl.getBlockPos()).thenReturn(pos);
        when(selfImpl.getLevel()).thenReturn(level);
        assertNull(ContainerEnergyStorage.resolveProvider(selfImpl, null), "自己实现 ⇒ 让位");

        // ② 随机战利品容器（未开箱）
        BlockEntity loot = mock(BlockEntity.class,
            org.mockito.Mockito.withSettings().extraInterfaces(RandomizableContainer.class));
        when(loot.getBlockPos()).thenReturn(pos);
        when(loot.getLevel()).thenReturn(level);
        when(((RandomizableContainer) (Object) loot).getLootTable()).thenReturn(net.minecraft.resources.ResourceKey.create(
            net.minecraft.core.registries.Registries.LOOT_TABLE,
            net.minecraft.resources.ResourceLocation.parse("minecraft:chests/simple_dungeon")));
        assertNull(ContainerEnergyStorage.resolveProvider(loot, null), "战利品容器 ⇒ 让位（与 tick 同款）");

        // ③ level == null
        BlockEntity orphan = mock(BlockEntity.class);
        when(orphan.getBlockPos()).thenReturn(pos);
        when(orphan.getLevel()).thenReturn(null);
        assertNull(ContainerEnergyStorage.resolveProvider(orphan, null), "无 level ⇒ null");

        // ④ 已有主人（模组机器自身储能）—— 先报名，否则在「无灯 ⇒ null」就早退了
        slots[0] = livingBulb(4);
        ContainerLivingItemHandler.processContext(ctx, level);
        doReturn(new ContainerEnergyStorage(be)).when(level)
            .getCapability(eq(Capabilities.EnergyStorage.BLOCK), any(), any());
        assertNull(ContainerEnergyStorage.resolveProvider(be, null), "已有主人 ⇒ 让位（不夺主）");
    }

    /** 只读能力面；组件提交不得借用数量抽插，即使读取返回活引用。 */
    private static class ReadOnlyHandler implements IItemHandler {
        final ItemStack[] contents;
        final boolean copyReads;
        int inserts;
        int extracts;

        ReadOnlyHandler(boolean copyReads, ItemStack... stacks) {
            this.copyReads = copyReads;
            contents = Arrays.stream(stacks).map(ItemStack::copy).toArray(ItemStack[]::new);
        }

        @Override public int getSlots() { return contents.length; }
        @Override public ItemStack getStackInSlot(int slot) {
            return copyReads ? contents[slot].copy() : contents[slot];
        }
        @Override public int getSlotLimit(int slot) { return 64; }
        @Override public boolean isItemValid(int slot, ItemStack stack) { return false; }
        @Override public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            inserts++;
            throw new AssertionError("组件充放电不得插入物品");
        }
        @Override public ItemStack extractItem(int slot, int amount, boolean simulate) {
            extracts++;
            throw new AssertionError("组件充放电不得抽取物品");
        }
    }

    private enum WriteBehavior { NORMAL, SILENT, UNSUPPORTED, REJECT_SECOND }

    /** setter 是唯一写入面；仅真正接受覆盖时发布 contentsChanged。 */
    private static final class CommittingHandler extends ReadOnlyHandler implements IItemHandlerModifiable {
        final WriteBehavior behavior;
        final int[] setCalls;
        final List<Integer> changedSlots = new ArrayList<>();

        CommittingHandler(boolean copies, WriteBehavior behavior, ItemStack... stacks) {
            super(copies, stacks);
            this.behavior = behavior;
            setCalls = new int[stacks.length];
        }

        @Override public void setStackInSlot(int slot, ItemStack stack) {
            setCalls[slot]++;
            if (behavior == WriteBehavior.UNSUPPORTED) throw new UnsupportedOperationException();
            if (behavior == WriteBehavior.SILENT || behavior == WriteBehavior.REJECT_SECOND && slot == 1) return;
            contents[slot] = stack.copy();
            onContentsChanged(slot);
        }

        private void onContentsChanged(int slot) { changedSlots.add(slot); }
    }

    private static ItemStack chargedBulb(int count, long q) {
        ItemStack stack = livingBulb(count);
        LivingWaxedBulbData.set(stack, new LivingWaxedBulbData(q));
        return stack;
    }

    private record BlockScene(ReadOnlyHandler items, BlockEntity be, Level level,
                              PlayerList players, SimpleContainerContext context,
                              ContainerEnergyStorage energy) {
        static BlockScene of(ReadOnlyHandler items) {
            BlockPos pos = new BlockPos(60_000 + counter++, 9, -5_432);
            Level level = mock(Level.class);
            BlockEntity be = mock(BlockEntity.class);
            when(level.isClientSide()).thenReturn(false);
            when(level.dimension()).thenReturn(Level.OVERWORLD);
            when(level.getBlockState(pos)).thenReturn(Blocks.STONE.defaultBlockState());
            when(level.getBlockEntity(pos)).thenReturn(be);
            when(level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null)).thenReturn(items);
            when(be.getLevel()).thenReturn(level);
            when(be.getBlockPos()).thenReturn(pos);
            when(be.getBlockState()).thenReturn(Blocks.STONE.defaultBlockState());
            MinecraftServer server = mock(MinecraftServer.class);
            PlayerList players = mock(PlayerList.class);
            when(level.getServer()).thenReturn(server);
            when(server.getPlayerList()).thenReturn(players);
            when(players.getPlayers()).thenReturn(List.of());
            SimpleContainerContext context = new SimpleContainerContext(items,
                null, List.of(pos), List.of(be), level);
            return new BlockScene(items, be, level, players, context, new ContainerEnergyStorage(be));
        }

        long amount(int slot) {
            ItemStack actual = items.contents[slot];
            return LivingWaxedBulbData.of(actual).totalChargeMilliFe(actual.getCount());
        }

        long revision() { return ContainerLivingItemHandler.getContainerRevision(context); }
    }

    private static void assertNoQuantityOps(ReadOnlyHandler handler) {
        assertAll(() -> assertEquals(0, handler.inserts), () -> assertEquals(0, handler.extracts));
    }

    private static void assertNotifications(BlockScene scene, long beforeRevision, int changedSlots) {
        assertAll(
            () -> verify(scene.be, times(changedSlots)).setChanged(),
            () -> assertEquals(beforeRevision + changedSlots, scene.revision(), "只同步实际提交的槽位"),
            () -> verify(scene.players, times(changedSlots)).getPlayers(),
            () -> verify(scene.level, never()).invalidateCapabilities(any(BlockPos.class)));
    }

    @ParameterizedTest(name = "copyReads={0}")
    @ValueSource(booleans = {false, true})
    @DisplayName("BLOCK充电：活引用与副本均须正式写回，模拟无写与通知")
    void blockReceive_commitsActualSlot(boolean copies) {
        CommittingHandler items = new CommittingHandler(copies, WriteBehavior.NORMAL, chargedBulb(1, 0));
        BlockScene scene = BlockScene.of(items);
        long revision = scene.revision();

        assertEquals(5, scene.energy.receiveEnergy(5, true));
        assertEquals(0L, scene.amount(0));
        assertEquals(0, items.setCalls[0]);
        assertTrue(items.changedSlots.isEmpty());
        assertNotifications(scene, revision, 0);

        assertEquals(5, scene.energy.receiveEnergy(5, false));
        assertAll(
            () -> assertEquals(5000L, scene.amount(0)),
            () -> assertEquals(1, items.setCalls[0]),
            () -> assertEquals(List.of(0), items.changedSlots),
            () -> assertEquals(5, scene.energy.getEnergyStored()),
            () -> assertNotifications(scene, revision, 1),
            () -> assertNoQuantityOps(items));
    }

    @ParameterizedTest(name = "copyReads={0}")
    @ValueSource(booleans = {false, true})
    @DisplayName("BLOCK取电：返回正FE必须有真实槽位扣减，模拟无写与通知")
    void blockExtract_commitsActualSlot(boolean copies) {
        CommittingHandler items = new CommittingHandler(copies, WriteBehavior.NORMAL, chargedBulb(1, 5000));
        BlockScene scene = BlockScene.of(items);
        long revision = scene.revision();

        assertEquals(3, scene.energy.extractEnergy(3, true));
        assertEquals(5000L, scene.amount(0));
        assertEquals(0, items.setCalls[0]);
        assertTrue(items.changedSlots.isEmpty());
        assertNotifications(scene, revision, 0);

        assertEquals(3, scene.energy.extractEnergy(3, false));
        assertAll(
            () -> assertEquals(2000L, scene.amount(0)),
            () -> assertEquals(1, items.setCalls[0]),
            () -> assertEquals(List.of(0), items.changedSlots),
            () -> assertEquals(2, scene.energy.getEnergyStored()),
            () -> assertNotifications(scene, revision, 1),
            () -> assertNoQuantityOps(items));
    }

    @ParameterizedTest(name = "copyReads={0}")
    @ValueSource(booleans = {false, true})
    @DisplayName("只读IItemHandler保留电量与容量查询，模拟及执行都不充放")
    void blockReadOnly_queriesRemainAvailableWithoutMutation(boolean copies) {
        ReadOnlyHandler items = new ReadOnlyHandler(copies, chargedBulb(1, 4000));
        BlockScene scene = BlockScene.of(items);
        long revision = scene.revision();
        assertEquals(4, scene.energy.getEnergyStored());
        assertEquals(PowerMath.BULB_UNIT_CAPACITY_MFE / 1000, scene.energy.getMaxEnergyStored());

        assertAll(
            () -> assertEquals(0, scene.energy.receiveEnergy(1, true)),
            () -> assertEquals(0, scene.energy.extractEnergy(1, true)),
            () -> assertEquals(0, scene.energy.receiveEnergy(1, false)),
            () -> assertEquals(0, scene.energy.extractEnergy(1, false)),
            () -> assertEquals(4000L, scene.amount(0)),
            () -> assertNotifications(scene, revision, 0),
            () -> assertNoQuantityOps(items));
    }

    @ParameterizedTest(name = "copyReads={0}, setter={1}, receive={2}")
    @CsvSource({"false,SILENT,true", "true,SILENT,false", "true,UNSUPPORTED,true", "false,UNSUPPORTED,false"})
    @DisplayName("空setter与UOE拒写：返回0，不误改活引用、不抽插、不通知")
    void blockRejectedWrite_reportsZero(boolean copies, WriteBehavior behavior, boolean receive) {
        CommittingHandler items = new CommittingHandler(copies, behavior, chargedBulb(1, receive ? 0 : 4000));
        BlockScene scene = BlockScene.of(items);
        long before = scene.amount(0);
        long revision = scene.revision();
        int reported = receive ? scene.energy.receiveEnergy(1, false) : scene.energy.extractEnergy(1, false);

        assertAll(
            () -> assertEquals(0, reported),
            () -> assertEquals(before, scene.amount(0)),
            () -> assertEquals(1, items.setCalls[0]),
            () -> assertTrue(items.changedSlots.isEmpty()),
            () -> assertNotifications(scene, revision, 0),
            () -> assertNoQuantityOps(items));
    }

    @Test
    @DisplayName("BLOCK比例充电保持2:1份额；所有提交成功返回原整FE声明量")
    void blockReceive_preservesProportionalPlan() {
        CommittingHandler items = new CommittingHandler(true, WriteBehavior.NORMAL,
            chargedBulb(16, 0), chargedBulb(16, PowerMath.BULB_UNIT_CAPACITY_MFE / 2));
        BlockScene scene = BlockScene.of(items);
        long firstBefore = scene.amount(0);
        long secondBefore = scene.amount(1);
        long revision = scene.revision();

        assertEquals(30, scene.energy.receiveEnergy(30, true));
        assertEquals(firstBefore, scene.amount(0));
        assertEquals(secondBefore, scene.amount(1));
        assertTrue(items.changedSlots.isEmpty());
        assertEquals(0, Arrays.stream(items.setCalls).sum());
        assertNotifications(scene, revision, 0);
        assertEquals(30, scene.energy.receiveEnergy(30, false));
        assertAll(
            () -> assertEquals(20_000L, scene.amount(0) - firstBefore),
            () -> assertEquals(10_000L, scene.amount(1) - secondBefore),
            () -> assertEquals(1, items.setCalls[0]),
            () -> assertEquals(1, items.setCalls[1]),
            () -> assertEquals(List.of(0, 1), items.changedSlots),
            () -> assertNotifications(scene, revision, 2),
            () -> assertNoQuantityOps(items));
    }

    @Test
    @DisplayName("充3FE计划各1500mFE，次槽拒写只报已充前缀向上取整的2FE")
    void blockReceive_secondRejectedRoundsConfirmedChargeUp() {
        CommittingHandler items = new CommittingHandler(true, WriteBehavior.REJECT_SECOND,
            chargedBulb(1, 0), chargedBulb(1, 0));
        BlockScene scene = BlockScene.of(items);
        long revision = scene.revision();
        assertEquals(3, scene.energy.receiveEnergy(3, true));
        assertEquals(0L, scene.amount(0) + scene.amount(1));
        assertEquals(0, Arrays.stream(items.setCalls).sum());
        assertTrue(items.changedSlots.isEmpty());
        assertNotifications(scene, revision, 0);

        int reported = scene.energy.receiveEnergy(3, false);
        assertAll(
            () -> assertEquals(2, reported),
            () -> assertEquals(1500L, scene.amount(0)),
            () -> assertEquals(0L, scene.amount(1)),
            () -> assertTrue(scene.amount(0) <= reported * 1000L && reported <= 3),
            () -> assertEquals(1, items.setCalls[0]),
            () -> assertEquals(1, items.setCalls[1]),
            () -> assertEquals(List.of(0), items.changedSlots),
            () -> assertNotifications(scene, revision, 1),
            () -> assertNoQuantityOps(items));
    }

    @ParameterizedTest(name = "copyReads={0}")
    @ValueSource(booleans = {false, true})
    @DisplayName("BLOCK取电合并0.6+0.6FE：交付1FE，首槽清空、次槽余200mFE")
    void blockExtract_poolsFractionalStacks(boolean copies) {
        CommittingHandler items = new CommittingHandler(copies, WriteBehavior.NORMAL,
            chargedBulb(1, 600), chargedBulb(1, 600));
        BlockScene scene = BlockScene.of(items);
        long revision = scene.revision();
        assertEquals(1, scene.energy.extractEnergy(1, true));
        assertEquals(1200L, scene.amount(0) + scene.amount(1));
        assertEquals(0, Arrays.stream(items.setCalls).sum());
        assertTrue(items.changedSlots.isEmpty());
        assertNotifications(scene, revision, 0);

        assertEquals(1, scene.energy.extractEnergy(1, false));
        assertAll(
            () -> assertEquals(0L, scene.amount(0)),
            () -> assertEquals(200L, scene.amount(1)),
            () -> assertEquals(1, items.setCalls[0]),
            () -> assertEquals(1, items.setCalls[1]),
            () -> assertEquals(List.of(0, 1), items.changedSlots),
            () -> assertNotifications(scene, revision, 2),
            () -> assertNoQuantityOps(items));
    }

    /** 能量入口的前缀损耗告警；通用槽位拒写日志不能替代电量差额告警。 */
    private static final class EnergyWarnings extends AbstractAppender implements AutoCloseable {
        final List<LogEvent> events = new ArrayList<>();
        final org.apache.logging.log4j.core.Logger logger =
            (org.apache.logging.log4j.core.Logger) LogManager.getLogger(ContainerEnergyStorage.class);

        EnergyWarnings() {
            super("copper-component-warning-" + counter++, null, null, true, Property.EMPTY_ARRAY);
            start();
            logger.addAppender(this);
        }

        @Override public void append(LogEvent event) { events.add(event.toImmutable()); }
        @Override public void close() { logger.removeAppender(this); stop(); }
    }

    @ParameterizedTest(name = "confirmed={0}mFE, requested={1}FE, delivered={2}FE")
    @CsvSource({"600,1,0", "1600,2,1"})
    @DisplayName("取电次槽拒写：已扣前缀向下整FE结算，零头损耗WARN且仍通知")
    void blockExtract_secondRejectedFloorsConfirmedPrefix(long prefix, int request, int delivery) {
        CommittingHandler items = new CommittingHandler(true, WriteBehavior.REJECT_SECOND,
            chargedBulb(1, prefix), chargedBulb(1, 600));
        BlockScene scene = BlockScene.of(items);
        long revision = scene.revision();
        assertEquals(request, scene.energy.extractEnergy(request, true));
        assertEquals(prefix + 600, scene.amount(0) + scene.amount(1));
        assertEquals(0, Arrays.stream(items.setCalls).sum());
        assertTrue(items.changedSlots.isEmpty());
        assertNotifications(scene, revision, 0);

        try (EnergyWarnings warnings = new EnergyWarnings()) {
            int reported = scene.energy.extractEnergy(request, false);
            assertAll(
                () -> assertEquals(delivery, reported),
                () -> assertEquals(0L, scene.amount(0)),
                () -> assertEquals(600L, scene.amount(1)),
                () -> assertTrue(reported * 1000L <= prefix),
                () -> assertEquals(1, items.setCalls[0]),
                () -> assertEquals(1, items.setCalls[1]),
                () -> assertEquals(List.of(0), items.changedSlots),
                () -> assertNotifications(scene, revision, 1),
                () -> assertNoQuantityOps(items),
                () -> assertTrue(warnings.events.stream().anyMatch(event ->
                    event.getLevel() == org.apache.logging.log4j.Level.WARN
                        && event.getMessage().getFormattedMessage().contains(Long.toString(prefix))),
                    "部分提交的非整FE前缀必须在能量入口以WARN记录mFE数量"));
        }
    }

    @ParameterizedTest(name = "receive={0}")
    @ValueSource(booleans = {false, true})
    @DisplayName("已有2048盏超堆叠组件充放不按64截断，名称与活化标记保留")
    void blockOversized_componentCommitPreservesCountAndComponents(boolean receive) {
        ItemStack bulb = chargedBulb(2048, 1000);
        bulb.set(DataComponents.CUSTOM_NAME, Component.literal("oversized battery"));
        CommittingHandler items = new CommittingHandler(true, WriteBehavior.NORMAL, bulb);
        BlockScene scene = BlockScene.of(items);
        long before = scene.amount(0);
        long revision = scene.revision();
        int request = receive ? 3 : 1;
        assertEquals(64, items.getSlotLimit(0));
        assertEquals(request, receive ? scene.energy.receiveEnergy(request, true)
            : scene.energy.extractEnergy(request, true));
        assertEquals(before, scene.amount(0));
        assertEquals(0, items.setCalls[0]);
        assertTrue(items.changedSlots.isEmpty());
        assertNotifications(scene, revision, 0);

        assertEquals(request, receive ? scene.energy.receiveEnergy(request, false)
            : scene.energy.extractEnergy(request, false));
        ItemStack expected = bulb.copy();
        LivingWaxedBulbData.set(expected, new LivingWaxedBulbData(receive ? 1001 : 999));
        assertAll(
            () -> assertTrue(ItemStack.matches(expected, items.contents[0]), "只允许电量组件改变"),
            () -> assertEquals(before + (receive ? 2048 : -2048), scene.amount(0)),
            () -> assertEquals(1, items.setCalls[0]),
            () -> assertEquals(List.of(0), items.changedSlots),
            () -> assertNotifications(scene, revision, 1),
            () -> assertNoQuantityOps(items));
    }

    @Test
    @DisplayName("BLOCK双箱组件提交：合并槽位写一次，两半BE都标脏一次")
    void blockDoubleChest_notifiesBothAssociatedBlockEntities() {
        CommittingHandler items = new CommittingHandler(true, WriteBehavior.NORMAL, chargedBulb(1, 0));
        BlockScene scene = BlockScene.of(items);
        BlockPos firstPos = scene.be.getBlockPos();
        var leftState = Blocks.CHEST.defaultBlockState()
            .setValue(ChestBlock.TYPE, ChestType.LEFT).setValue(ChestBlock.FACING, Direction.NORTH);
        BlockPos secondPos = firstPos.relative(ChestBlock.getConnectedDirection(leftState));
        BlockEntity second = mock(BlockEntity.class);
        when(second.getLevel()).thenReturn(scene.level);
        when(second.getBlockPos()).thenReturn(secondPos);
        when(scene.level.getBlockState(firstPos)).thenReturn(leftState);
        when(scene.level.getBlockState(secondPos)).thenReturn(leftState.setValue(ChestBlock.TYPE, ChestType.RIGHT));
        when(scene.level.getBlockEntity(secondPos)).thenReturn(second);
        SimpleContainerContext combined = new SimpleContainerContext(items, null,
            List.of(firstPos, secondPos), List.of(scene.be, second), scene.level);
        long revision = ContainerLivingItemHandler.getContainerRevision(combined);

        assertEquals(2, scene.energy.receiveEnergy(2, false));
        assertAll(
            () -> assertEquals(2000L, scene.amount(0)),
            () -> assertEquals(1, items.setCalls[0]),
            () -> assertEquals(List.of(0), items.changedSlots),
            () -> verify(scene.be, times(1)).setChanged(),
            () -> verify(second, times(1)).setChanged(),
            () -> assertEquals(revision + 1, ContainerLivingItemHandler.getContainerRevision(combined)),
            () -> verify(scene.players, times(1)).getPlayers(),
            () -> assertNoQuantityOps(items));
    }
}
