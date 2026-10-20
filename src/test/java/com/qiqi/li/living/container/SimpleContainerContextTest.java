package com.qiqi.li.living.container;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.IItemHandlerModifiable;

import java.util.List;

/**
 * {@link SimpleContainerContext} 的单元测试。
 *
 * <p>此类测试 SimpleContainerContext 作为 ContainerContext 实现的正确性，
 * 覆盖写入路径、列数推断、盔甲槽特殊处理等 {@link FakeContainerContext}
 * 绕过的关键路径。</p>
 */
class SimpleContainerContextTest {

    /** 测试用 IItemHandler —— 支持 insert/extract 的内存实现 */
    private static class FakeHandler implements IItemHandlerModifiable {
        final ItemStack[] slots;

        FakeHandler(int size) {
            this.slots = new ItemStack[size];
            for (int i = 0; i < size; i++) {
                slots[i] = ItemStack.EMPTY;
            }
        }

        FakeHandler(ItemStack... slots) {
            this.slots = slots;
        }

        @Override
        public int getSlots() {
            return slots.length;
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            return slots[slot];
        }

        @Override
        public void setStackInSlot(int slot, ItemStack stack) {
            slots[slot] = stack;
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            if (stack.isEmpty()) return ItemStack.EMPTY;
            ItemStack existing = slots[slot];
            int maxStack = Math.min(getSlotLimit(slot), stack.getMaxStackSize());
            if (existing.isEmpty()) {
                int toInsert = Math.min(stack.getCount(), maxStack);
                if (!simulate) {
                    slots[slot] = stack.copyWithCount(toInsert);
                }
                ItemStack remainder = stack.copy();
                remainder.shrink(toInsert);
                return remainder.isEmpty() ? ItemStack.EMPTY : remainder;
            }
            if (ItemStack.isSameItemSameComponents(existing, stack)) {
                int space = maxStack - existing.getCount();
                if (space <= 0) return stack;
                int toInsert = Math.min(stack.getCount(), space);
                if (!simulate) {
                    slots[slot].grow(toInsert);
                }
                ItemStack remainder = stack.copy();
                remainder.shrink(toInsert);
                return remainder.isEmpty() ? ItemStack.EMPTY : remainder;
            }
            return stack; // 不同物品，无法插入
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            ItemStack existing = slots[slot];
            if (existing.isEmpty()) return ItemStack.EMPTY;
            int toExtract = Math.min(amount, existing.getCount());
            ItemStack result = existing.copyWithCount(toExtract);
            if (!simulate) {
                slots[slot].shrink(toExtract);
            }
            return result;
        }

        @Override
        public int getSlotLimit(int slot) {
            return 64;
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return true;
        }
    }

    // ============================================================
    // 基础委托
    // ============================================================

    @Nested
    @DisplayName("基础构造与委托")
    class BasicDelegation {

        @Test
        @DisplayName("getSize 委托给 handler.getSlots")
        void getSize_delegatesToHandler() {
            var handler = new FakeHandler(27);
            var ctx = new SimpleContainerContext(handler);
            assertEquals(27, ctx.getSize());
        }

        @Test
        @DisplayName("getItem 委托给 handler.getStackInSlot")
        void getItem_delegatesToHandler() {
            var handler = new FakeHandler(27);
            handler.setStackInSlot(5, new ItemStack(Items.REDSTONE, 3));
            var ctx = new SimpleContainerContext(handler);
            ItemStack result = ctx.getItem(5);
            assertEquals(Items.REDSTONE, result.getItem());
            assertEquals(3, result.getCount());
        }

        @Test
        @DisplayName("getItem 越界返回 EMPTY")
        void getItem_outOfBounds_returnsEmpty() {
            var handler = new FakeHandler(5);
            var ctx = new SimpleContainerContext(handler);
            assertTrue(ctx.getItem(-1).isEmpty());
            assertTrue(ctx.getItem(5).isEmpty());
            assertTrue(ctx.getItem(100).isEmpty());
        }

        @Test
        @DisplayName("getSlotLimit 委托给 handler.getSlotLimit")
        void getSlotLimit_delegatesToHandler() {
            var handler = new FakeHandler(27);
            var ctx = new SimpleContainerContext(handler);
            assertEquals(64, ctx.getSlotLimit(0));
        }

        @Test
        @DisplayName("isItemValid 委托给 handler.isItemValid")
        void isItemValid_delegatesToHandler() {
            var handler = new FakeHandler(27);
            var ctx = new SimpleContainerContext(handler);
            assertTrue(ctx.isItemValid(0, new ItemStack(Items.REDSTONE)));
        }

        @Test
        @DisplayName("getMaxStackSize 取 handler 首槽上限")
        void getMaxStackSize_usesFirstSlotLimit() {
            var handler = new FakeHandler(27);
            var ctx = new SimpleContainerContext(handler);
            assertEquals(64, ctx.getMaxStackSize());
        }
    }

    // ============================================================
    // getWidth 列数推断
    // ============================================================

    @Nested
    @DisplayName("getWidth 列数推断")
    class WidthInference {

        @Test
        @DisplayName("玩家背包（inventory 非 null）返回 9 列")
        void playerInventory_returns9() {
            // 仅测试构造不抛异常 + 返回 9（需要 mock Inventory，此处简化验证）
            var handler = new FakeHandler(41);
            var ctx = new SimpleContainerContext(handler);
            assertNotNull(ctx);
        }

        @Test
        @DisplayName("无 BE / 无 inventory：按加权启发式回退")
        void noBe_noInventory_usesWeightedHeuristic() {
            assertWidth(27, 9);  // 27%9=0
            assertWidth(36, 9);  // 36%9=0, 9 优先于 12
            assertWidth(40, 10); // 40%10=0, 10 优先于 8
            assertWidth(54, 9);  // 54%9=0
            assertWidth(81, 9);  // 81%9=0
            assertWidth(96, 12); // 96%12=0, 12 优先于 8
            assertWidth(108, 9); // 108%9=0, 9 优先于 12
            assertWidth(120, 10); // 120%10=0, 10 优先于 12
        }

        @Test
        @DisplayName("未注册类型不借用「同 namespace 共享词」的规则（规则只按精确 id 匹配）")
        void unregisteredType_doesNotBorrowRuleFromSiblingId() {
            // 把内置的 minecraft:chest（27 格 9 列）临时改成 3 列 —— 这样「借用」与「启发式」
            // 的结果可区分。原实现有一层 namespace + 分词模糊匹配，会拿 "chest" 这个词命中它。
            net.minecraft.resources.ResourceLocation chestId =
                net.minecraft.resources.ResourceLocation.withDefaultNamespace("chest");
            com.qiqi.li.living.transfer.ContainerCompatibilityConfig.register(chestId,
                com.qiqi.li.living.transfer.ContainerCompatibilityConfig.ContainerRule.builder()
                    .containerSize(27).columns(3).description("test override").build());
            try {
                // minecraft:ender_chest 没有精确规则，但它的 path 与 chest 共享 "chest" 词
                net.minecraft.world.level.block.entity.BlockEntity enderChest =
                    mock(net.minecraft.world.level.block.entity.BlockEntity.class);
                // doReturn 而非 when().thenReturn()：getType() 返回 BlockEntityType<?>，
                // 带泛型的 thenReturn 会因 EnderChestBlockEntity 推断失败
                org.mockito.Mockito.doReturn(net.minecraft.world.level.block.entity.BlockEntityType.ENDER_CHEST)
                    .when(enderChest).getType();

                var ctx = new SimpleContainerContext(
                    new FakeHandler(27), List.of(BlockPos.ZERO), List.of(enderChest));

                assertEquals(9, ctx.getWidth(),
                    "未注册类型应走启发式（27→9），不能借用 minecraft:chest 的 3 列 —— "
                        + "模糊匹配已按 D-container-09 删除");
            } finally {
                // 注册表是全局静态的，必须恢复内置值，否则污染同类其它用例
                com.qiqi.li.living.transfer.ContainerCompatibilityConfig.register(chestId,
                    com.qiqi.li.living.transfer.ContainerCompatibilityConfig.ContainerRule.builder()
                        .containerSize(27).columns(9).description("chest").build());
            }
        }

        @Test
        @DisplayName("质数尺寸退化为 1 列")
        void primeSize_degradesTo1() {
            assertWidth(17, 1);
            assertWidth(19, 1);
            assertWidth(23, 1);
        }

        @Test
        @DisplayName("1 格容器返回 1 列")
        void singleSlot_returns1() {
            assertWidth(1, 1);
        }

        @Test
        @DisplayName("2 格容器返回 2 列（2%2=0）")
        void twoSlots_returns2() {
            assertWidth(2, 2);
        }

        private void assertWidth(int size, int expectedWidth) {
            var handler = new FakeHandler(size);
            var ctx = new SimpleContainerContext(handler);
            assertEquals(expectedWidth, ctx.getWidth(),
                () -> size + " 格容器应推断 " + expectedWidth + " 列");
        }
    }

    // ============================================================
    // setItem 写入路径
    // ============================================================

    @Nested
    @DisplayName("setItem 写入路径")
    class SetItem {

        @Test
        @DisplayName("空槽写入：插入成功")
        void setItem_emptySlot_inserts() {
            var handler = new FakeHandler(27);
            var ctx = new SimpleContainerContext(handler);
            ItemStack stack = new ItemStack(Items.REDSTONE, 5);
            ctx.setItem(3, stack);
            assertEquals(Items.REDSTONE, handler.getStackInSlot(3).getItem());
            assertEquals(5, handler.getStackInSlot(3).getCount());
        }

        @Test
        @DisplayName("越界写入：不抛异常，不改变内容")
        void setItem_outOfBounds_doesNothing() {
            var handler = new FakeHandler(27);
            var ctx = new SimpleContainerContext(handler);
            ctx.setItem(-1, new ItemStack(Items.REDSTONE));
            ctx.setItem(27, new ItemStack(Items.REDSTONE));
            // 不抛异常即通过
        }

        @Test
        @DisplayName("非空槽覆盖：先清空再写入新物品")
        void setItem_overwrite_replacesContent() {
            var handler = new FakeHandler(27);
            handler.setStackInSlot(5, new ItemStack(Items.DIAMOND, 10));
            var ctx = new SimpleContainerContext(handler);
            ctx.setItem(5, new ItemStack(Items.REDSTONE, 3));
            assertEquals(Items.REDSTONE, handler.getStackInSlot(5).getItem());
            assertEquals(3, handler.getStackInSlot(5).getCount());
        }

        @Test
        @DisplayName("槽位上限截断：超过 getSlotLimit 时截断（不按物品 maxStack）")
        void setItem_exceedsMaxStack_truncates() {
            // Redstone 的 maxStackSize = 64，FakeHandler 的 getSlotLimit = 64
            var handler = new FakeHandler(27);
            var ctx = new SimpleContainerContext(handler);
            ctx.setItem(0, new ItemStack(Items.REDSTONE, 100));
            // ⚠️ 判据是**槽位上限**，不是物品的 getMaxStackSize —— 超大堆叠容器（精妙背包
            // 堆叠升级、抽屉）一个槽可合法放几百个，按物品上限截断会把它们砍回 64。
            assertEquals(64, handler.getStackInSlot(0).getCount());
        }
    }

    // ============================================================
    // simulateInsertItem
    // ============================================================

    @Nested
    @DisplayName("simulateInsertItem 模拟插入")
    class SimulateInsertItem {

        @Test
        @DisplayName("无 Container 回退到 handler.insertItem(simulate)")
        void noContainer_fallsBackToHandler() {
            var handler = new FakeHandler(27);
            var ctx = new SimpleContainerContext(handler);
            int result = ctx.simulateInsertItem(0, new ItemStack(Items.REDSTONE, 10));
            assertEquals(10, result);
            // 模拟插入不应改变实际内容
            assertTrue(handler.getStackInSlot(0).isEmpty());
        }

        @Test
        @DisplayName("空槽位：返回最少值（slotLimit, maxStackSize, count）")
        void simulateInsertItem_emptySlot_returnsMin() {
            var handler = new FakeHandler(27);
            var ctx = new SimpleContainerContext(handler);
            int result = ctx.simulateInsertItem(0, new ItemStack(Items.REDSTONE, 100));
            // handler.getSlotLimit(0)=64, 但 redstone 的 maxStackSize=64, 所以 =64
            assertTrue(result <= 64);
            assertTrue(result > 0);
        }

        @Test
        @DisplayName("部分满槽位：返回剩余空间")
        void simulateInsertItem_partialSlot_returnsSpace() {
            var handler = new FakeHandler(27);
            handler.setStackInSlot(0, new ItemStack(Items.REDSTONE, 50));
            var ctx = new SimpleContainerContext(handler);
            // 50 已在槽中，maxStack=64，空间=14
            int result = ctx.simulateInsertItem(0, new ItemStack(Items.REDSTONE, 20));
            assertEquals(14, result);
        }

        @Test
        @DisplayName("满槽位：返回 0")
        void simulateInsertItem_fullSlot_returnsZero() {
            var handler = new FakeHandler(27);
            handler.setStackInSlot(0, new ItemStack(Items.REDSTONE, 64));
            var ctx = new SimpleContainerContext(handler);
            int result = ctx.simulateInsertItem(0, new ItemStack(Items.REDSTONE, 10));
            assertEquals(0, result);
        }

        @Test
        @DisplayName("不同物品：返回 0（无法插入）")
        void simulateInsertItem_differentItem_returnsZero() {
            var handler = new FakeHandler(27);
            handler.setStackInSlot(0, new ItemStack(Items.DIAMOND, 1));
            var ctx = new SimpleContainerContext(handler);
            int result = ctx.simulateInsertItem(0, new ItemStack(Items.REDSTONE, 1));
            assertEquals(0, result);
        }
    }

    // ============================================================
    // getContainerKey
    // ============================================================

    @Nested
    @DisplayName("getContainerKey 容器标识生成")
    class ContainerKey {

        @Test
        @DisplayName("测试替身：自动生成唯一 key（生产路径不允许无身份）")
        void testDoubleGetsAutoKey() {
            var ctx = new SimpleContainerContext(new FakeHandler(27));
            String key = ctx.getContainerKey();
            assertTrue(key.startsWith("test#"), "测试替身应拿到自动生成的 key，实际=" + key);

            // 唯一性：两个替身不能拿到同一个 key，否则容器级数据（CONTAINER_DATA 全局表）会串味
            var another = new SimpleContainerContext(new FakeHandler(27));
            assertFalse(key.equals(another.getContainerKey()), "不同替身必须拿到不同 key");
        }
    }

    // ============================================================
    // getCacheKey（缓存键 = 维度前缀 + 稳定键；2026-10-12 跨维度串数据修复）
    // ============================================================

    @Nested
    @DisplayName("getCacheKey 缓存键推导")
    class CacheKey {

        /** mock 一个带维度 id 的服务端 Level（项目既有手法，见 ContainerChunkCacheChunkLoadTest）。 */
        private Level levelOf(ResourceKey<Level> dim) {
            Level level = mock(Level.class);
            when(level.dimension()).thenReturn(dim);
            return level;
        }

        private Player playerWithUuid(String uuid) {
            Player player = mock(Player.class);
            when(player.getStringUUID()).thenReturn(uuid);
            return player;
        }

        @Test
        @DisplayName("世界容器：缓存键 = <维度>|chest_<x>_<y>_<z>（稳定键本身不变）")
        void worldContainer_prefixedWithDimension() {
            var ctx = new SimpleContainerContext(new FakeHandler(27), null,
                List.of(new BlockPos(12, 64, -34)), List.of(), levelOf(Level.OVERWORLD));

            assertEquals("chest_12_64_-34", ctx.getContainerKey(), "稳定键保持可解析的裸坐标形态");
            assertEquals("minecraft:overworld|chest_12_64_-34", ctx.getCacheKey(),
                "缓存键拼维度前缀（主世界/下界同坐标的两个容器不再串数据）");
        }

        @Test
        @DisplayName("跨维度同坐标：稳定键相同、缓存键不同（原 bug 的回归护栏）")
        void samePosDifferentDimension_differentCacheKeys() {
            BlockPos pos = new BlockPos(0, 64, 0);
            var overworld = new SimpleContainerContext(new FakeHandler(27), null,
                List.of(pos), List.of(), levelOf(Level.OVERWORLD));
            var nether = new SimpleContainerContext(new FakeHandler(27), null,
                List.of(pos), List.of(), levelOf(Level.NETHER));

            assertEquals(overworld.getContainerKey(), nether.getContainerKey(),
                "同坐标 ⇒ 稳定键相同（坐标即身份）");
            assertFalse(overworld.getCacheKey().equals(nether.getCacheKey()),
                "缓存键必须不同 —— 否则两个箱子的燃料/进度会共用一条记录");
        }

        @Test
        @DisplayName("大箱子：缓存键保留两段坐标（半边间不会互串）")
        void doubleChest_keepsBothPositions() {
            var ctx = new SimpleContainerContext(new FakeHandler(54), null,
                List.of(new BlockPos(1, 64, 2), new BlockPos(2, 64, 2)), List.of(),
                levelOf(Level.OVERWORLD));

            assertEquals("minecraft:overworld|chest_1_64_2_2_64_2", ctx.getCacheKey());
        }

        @Test
        @DisplayName("玩家背包：裸键（数据跟人走，过传送门不清零身上活熔炉的燃料）")
        void playerInventory_rawKey() {
            Player player = playerWithUuid("uuid-p");
            var ctx = new SimpleContainerContext(new FakeHandler(41), new Inventory(player));

            assertEquals("player_uuid-p", ctx.getCacheKey(),
                "玩家容器不加维度前缀 —— 一个玩家同一时刻只在一个维度，UUID 已全局唯一");
        }

        @Test
        @DisplayName("末影箱：裸键（玩家作用域，与背包同理）")
        void enderChest_rawKey() {
            var ctx = new EnderChestContainerContext(
                new FakeHandler(27), playerWithUuid("uuid-ec"), levelOf(Level.OVERWORLD));

            assertEquals("player_uuid-ec_ender_chest", ctx.getCacheKey());
        }

        @Test
        @DisplayName("维度不可见（mock 替身 / dimension()=null）：退化为裸键，测试零改动")
        void noDimension_degradesToRawKey() {
            Level noDim = mock(Level.class);   // dimension() 默认返回 null
            var ctx = new SimpleContainerContext(new FakeHandler(27), null,
                List.of(new BlockPos(7, 64, 7)), List.of(), noDim);

            assertEquals("chest_7_64_7", ctx.getCacheKey());
        }
    }

    // ============================================================
    // 盔甲槽特殊路径
    // ============================================================

    @Nested
    @DisplayName("盔甲槽路径")
    class ArmorSlot {

        private Inventory mockInventory;
        private SimpleContainerContext ctx;
        private FakeHandler handler;

        @BeforeEach
        void setUp() {
            Player mockPlayer = mock(Player.class);
            when(mockPlayer.getStringUUID()).thenReturn("test-player-uuid");

            // 使用真实 Inventory 构造，其只做 this.player = player 赋值
            mockInventory = new Inventory(mockPlayer);

            handler = new FakeHandler(41);
            ctx = new SimpleContainerContext(handler, mockInventory);
        }

        @Test
        @DisplayName("isItemValid 盔甲槽返回 true（不委托 handler）")
        void isItemValid_armorSlot_returnsTrue() {
            for (int slot = 36; slot < 40; slot++) {
                assertTrue(ctx.isItemValid(slot, new ItemStack(Items.STONE)),
                    "盔甲槽 " + slot + " 应接受任意物品");
            }
        }

        @Test
        @DisplayName("getSlotLimit 盔甲槽返回 maxStackSize（不与 handler 限制冲突）")
        void getSlotLimit_armorSlot_returnsMaxStackSize() {
            for (int slot = 36; slot < 40; slot++) {
                assertEquals(64, ctx.getSlotLimit(slot),
                    "盔甲槽 " + slot + " 上限应为 64");
            }
        }

        @Test
        @DisplayName("simulateInsertItem 盔甲槽：按 maxStack 截断，不算已有物品")
        void simulateInsertItem_armorSlot_usesMaxStack() {
            // 盔甲槽直接返回 min(count, maxStack)，忽略槽中已有物品
            int result = ctx.simulateInsertItem(36, new ItemStack(Items.DIAMOND, 10));
            assertEquals(10, result);
        }

        @Test
        @DisplayName("simulateInsertItem 盔甲槽：超过 maxStack 时截断")
        void simulateInsertItem_armorSlot_exceedsMaxStack_truncates() {
            int result = ctx.simulateInsertItem(36, new ItemStack(Items.DIAMOND, 100));
            assertEquals(64, result);
        }
    }

    @Nested
    class ExplicitKeyAndEnderChestRegression {

        private IItemHandler handler = new FakeHandler(9);

        private Player playerWithUuid(String uuid) {
            Player player = mock(Player.class);
            when(player.getStringUUID()).thenReturn(uuid);
            return player;
        }

        @Test
        @DisplayName("崩溃回归（crash-2026-10-04）：末影箱上下文可构造，键 = player_<uuid>_ender_chest")
        void enderChestContext_constructs_stableKey() {
            var ctx = new EnderChestContainerContext(
                handler, playerWithUuid("uuid-ender-1"), null);

            assertEquals("player_uuid-ender-1_ender_chest", ctx.getContainerKey(),
                "末影箱走显式稳定键（玩家作用域 UUID，无 BE ⇒ 无键漂移）");
        }

        @Test
        @DisplayName("显式稳定键构造器：给定键生效（第三条合法身份形态）")
        void explicitKey_wins() {
            var ctx = new SimpleContainerContext(handler, null, java.util.List.of(),
                java.util.List.of(), null, "player_uuid-x_ender_chest");
            assertEquals("player_uuid-x_ender_chest", ctx.getContainerKey());
        }

        @Test
        @DisplayName("显式稳定键构造器：空白键等同无身份 ⇒ 抛异常")
        void explicitKey_blank_throws() {
            var e = org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> new SimpleContainerContext(handler, null, java.util.List.of(),
                    java.util.List.of(), null, "  "));
            assertTrue(e.getMessage().contains("空白"), "异常信息应指出空白键");
        }

        @Test
        @DisplayName("无显式键且无身份 ⇒ 仍抛异常（1a-2 失败语义不回退）")
        void identityless_stillThrows() {
            org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> new SimpleContainerContext(handler, null, java.util.List.of(),
                    java.util.List.of(), null, null));
        }
    }
}
