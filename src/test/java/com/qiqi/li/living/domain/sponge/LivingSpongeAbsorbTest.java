package com.qiqi.li.living.domain.sponge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.qiqi.li.living.api.LivingItemFunction.SlotEntry;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.container.ContainerLivingItemHandler;
import com.qiqi.li.living.container.SimpleContainerContext;
import com.qiqi.li.living.container.TickContext;
import com.qiqi.li.living.domain.water.ContainerFluidData;
import com.qiqi.li.living.domain.water.FluidFlowBehavior;
import com.qiqi.li.living.domain.water.FluidFlowBehaviors;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidType;
import net.neoforged.neoforge.items.IItemHandlerModifiable;

/**
 * 活海绵吸收（第二段）——四邻直吸 + 逐源原子的行为契约
 * （设计稿 docs/buffer/活海绵.md §2.2 / §9.4 规则 1~5、8、10、11）。
 *
 * <p>驱动方式：除 ⑨ 外全部<b>直接调用 {@code tick}</b>（不依赖跨测试类的注册顺序，
 * ContainerFluidDataTest 同款）；⑨ 为 processContext 端到端冒烟（分组路径真驱动，
 * 注册带守卫 —— {@code registerFunction} 不去重，双注册 = 双吸收）。</p>
 */
class LivingSpongeAbsorbTest {

    private static final FluidType WATER = Fluids.WATER.getFluidType();
    private static final FluidType LAVA = Fluids.LAVA.getFluidType();
    private static final long SOURCE = 1_000L * 1_000L; // 一源 = 10⁶ mmB

    private final LivingSpongeFunction fn = new LivingSpongeFunction();

    @BeforeAll
    static void setup() {
        // 源账本 resolver —— WaterRegistration 同款（测试自足，不拖整个 Registration 的副作用）
        TickContext.registerSourceLedgerResolver(t -> t.data(ContainerFluidData.KEY));
        // 分组驱动需要功能在册；守卫防与 ComponentOwnershipTest 的全量注册撞车（不去重）
        if (LivingItemManager.getAllFunctions().stream()
                .noneMatch(f -> "living_sponge".equals(f.getFunctionId()))) {
            LivingItemManager.registerFunction(new LivingSpongeFunction());
        }
    }

    @BeforeEach
    void baselineWaterBehavior() {
        // 测试自足（同 ContainerFluidIntegrationTest）：水「会流动，上限 7」（⑨ 的引擎侧需要）
        FluidFlowBehaviors.register(WATER, FluidFlowBehavior.flowing(ContainerFluidData.MAX_FLOW_LEVEL, 0));
    }

    // ── 脚手架 ──────────────────────────────────────────────

    /** 模拟真实 IItemHandler 语义（ContainerFluidIntegrationTest 同款，精简版）。 */
    private static class FakeHandler implements IItemHandlerModifiable {
        final ItemStack[] slots;
        FakeHandler(int size) { this.slots = new ItemStack[size]; }
        void put(int slot, ItemStack stack) { slots[slot] = stack; }
        @Override public int getSlots() { return slots.length; }
        @Override public ItemStack getStackInSlot(int slot) {
            return slots[slot] != null ? slots[slot] : ItemStack.EMPTY;
        }
        @Override public void setStackInSlot(int slot, ItemStack stack) { slots[slot] = stack; }
        @Override public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) { return stack; }
        @Override public ItemStack extractItem(int slot, int amount, boolean simulate) { return ItemStack.EMPTY; }
        @Override public int getSlotLimit(int slot) { return 64; }
        @Override public boolean isItemValid(int slot, ItemStack stack) { return true; }
    }

    /** 场景：handler 数组直放物品（SimpleContainerContext 不暴露槽位写入）+ 预置源账本。 */
    private record Scene(FakeHandler handler, SimpleContainerContext ctx, ContainerFluidData fluidData) {
        static Scene of(int size) { return of(size, 100L); }

        /** 固定世界时钟：100 % 5 == 0 ⇒ 节拍门恒开；101 ⇒ 恒关。 */
        static Scene of(int size, long gameTime) {
            FakeHandler handler = new FakeHandler(size);
            Level level = org.mockito.Mockito.mock(Level.class);
            org.mockito.Mockito.when(level.isClientSide()).thenReturn(false);
            org.mockito.Mockito.when(level.getGameTime()).thenReturn(gameTime);
            SimpleContainerContext ctx = new SimpleContainerContext(handler, level);
            return new Scene(handler, ctx, ContainerFluidData.getOrCreate(ctx));
        }

        void source(int slot, FluidType fluid) { fluidData.registerGeneratedSource(slot, fluid); }
        boolean hasSource(int slot) { return fluidData.sourceAt(slot) != null; }

        ItemStack spongeAt(int slot) {
            ItemStack s = new ItemStack(Items.SPONGE);
            LivingItemManager.setLiving(s, true);
            handler.put(slot, s);
            return s;
        }

        ItemStack spongeAt(int slot, FluidType fluid, long mmB) {
            ItemStack s = spongeAt(slot);
            LivingSpongeData.set(s, new LivingSpongeData(fluid, mmB));
            return s;
        }

        Level level() { return ctx.getLevel(); }
        TickContext tick() { return new TickContext(ctx); }
    }

    // ── 用例 ────────────────────────────────────────────────

    @Test
    @DisplayName("① 四邻直吸：一拍吸净左右两源，每源 +10⁶ mmB（规则 1/3）")
    void absorbsBothNeighborsInOneBeat() {
        Scene scene = Scene.of(9);
        scene.source(3, WATER);
        scene.source(5, WATER);
        ItemStack sponge = scene.spongeAt(4);

        fn.tick(List.of(new SlotEntry(4, sponge)), scene.ctx(), scene.tick(), scene.level());

        assertFalse(scene.hasSource(3), "左源应被吸走");
        assertFalse(scene.hasSource(5), "右源应被吸走");
        LivingSpongeData data = LivingSpongeData.of(sponge);
        assertTrue(data.isWet());
        assertEquals(WATER, data.fluid());
        assertEquals(2 * SOURCE, data.mmB(), "一拍 ≤4 源：两源各 +10⁶");
    }

    @Test
    @DisplayName("② 节拍门：gameTime 不在 5t 锁相位上 ⇒ 整拍不动（§2.4）")
    void beatGateBlocksOffPhase() {
        Scene scene = Scene.of(9, 101L);
        scene.source(3, WATER);
        scene.source(5, WATER);
        ItemStack sponge = scene.spongeAt(4);

        fn.tick(List.of(new SlotEntry(4, sponge)), scene.ctx(), scene.tick(), scene.level());

        assertTrue(scene.hasSource(3), "关门拍不吸");
        assertFalse(LivingSpongeData.of(sponge).isWet());
    }

    @Test
    @DisplayName("③ 异种邻居跳过不挡其他邻居（规则 5 + ⑦：逐格独立）")
    void alienNeighborSkippedWithoutBlocking() {
        Scene scene = Scene.of(9);
        scene.source(3, LAVA);
        scene.source(5, WATER);
        ItemStack sponge = scene.spongeAt(4, WATER, SOURCE);

        fn.tick(List.of(new SlotEntry(4, sponge)), scene.ctx(), scene.tick(), scene.level());

        assertTrue(scene.hasSource(3), "岩浆邻居不吸（含水海绵）也不消失");
        assertFalse(scene.hasSource(5), "水源照常吸 —— 异种不挡其他邻居");
        assertEquals(WATER, LivingSpongeData.of(sponge).fluid(), "流体种类不变");
        assertEquals(2 * SOURCE, LivingSpongeData.of(sponge).mmB());
    }

    @Test
    @DisplayName("④ 空海绵遇混流体：按方向序（左先于右）取首个可吸流体（规则 4）")
    void drySpongeTakesFirstFluidInDirectionOrder() {
        Scene scene = Scene.of(9);
        scene.source(3, LAVA);
        scene.source(5, WATER);
        ItemStack sponge = scene.spongeAt(4);

        fn.tick(List.of(new SlotEntry(4, sponge)), scene.ctx(), scene.tick(), scene.level());

        assertEquals(LAVA, LivingSpongeData.of(sponge).fluid(), "上/下越界后左（slot 3）先于右");
        assertEquals(SOURCE, LivingSpongeData.of(sponge).mmB());
        assertTrue(scene.hasSource(5), "定流体后异种水源留存，当拍只吸一种");
    }

    @Test
    @DisplayName("⑤ 快满停拍：差一份均分份额装不下 ⇒ 整拍停；腾出后恢复（⑪）")
    void nearFullStopsUntilRoomFrees() {
        Scene scene = Scene.of(9);
        scene.source(3, WATER);
        ItemStack sponge = scene.spongeAt(4, WATER, LivingSpongeData.MAX_MM_B - 1);
        var tick = scene.tick();

        fn.tick(List.of(new SlotEntry(4, sponge)), scene.ctx(), tick, scene.level());
        assertTrue(scene.hasSource(3), "差 1 mmB 装不下完整源 ⇒ 本拍停（不吸半源）");
        assertEquals(LivingSpongeData.MAX_MM_B - 1, LivingSpongeData.of(sponge).mmB());

        // 腾出一份：排掉 10⁶ mmB（第三段前的模拟路径——直接改组件）
        LivingSpongeData.set(sponge, new LivingSpongeData(WATER, LivingSpongeData.MAX_MM_B - SOURCE));
        fn.tick(List.of(new SlotEntry(4, sponge)), scene.ctx(), tick, scene.level());
        assertFalse(scene.hasSource(3), "腾出后下一拍恢复吸收");
        assertEquals(LivingSpongeData.MAX_MM_B, LivingSpongeData.of(sponge).mmB());
    }

    @Test
    @DisplayName("⑥ 均分入堆：count=3 吸 1 源 ⇒ 每枚 +333,333，损耗 1 mmB ≤ count−1（规则 8）")
    void evenSplitWithBoundedLoss() {
        Scene scene = Scene.of(9);
        scene.source(5, WATER);
        ItemStack sponge = scene.spongeAt(4);
        sponge.setCount(3);

        fn.tick(List.of(new SlotEntry(4, sponge)), scene.ctx(), scene.tick(), scene.level());

        LivingSpongeData data = LivingSpongeData.of(sponge);
        assertEquals(333_333, data.mmB(), "floor(10⁶ / 3)");
        assertEquals(999_999, data.totalMmB(3));
        long loss = SOURCE - data.totalMmB(3);
        assertTrue(loss >= 0 && loss <= 2, "损耗向且有界（count−1）：实际 " + loss);
        assertFalse(scene.hasSource(5));
    }

    @Test
    @DisplayName("⑦ 同拍双海绵不双吃：账本即时删，后处理者看不见已被吃的源（规则 10）")
    void ledgerRemovalPreventsDoubleEatingSameBeat() {
        Scene scene = Scene.of(9);
        scene.source(4, WATER);
        ItemStack left = scene.spongeAt(3);   // RIGHT 邻 = 4
        ItemStack right = scene.spongeAt(5);  // LEFT 邻 = 4

        fn.tick(List.of(new SlotEntry(3, left), new SlotEntry(5, right)),
            scene.ctx(), scene.tick(), scene.level());

        assertFalse(scene.hasSource(4), "源只被吃一次");
        assertEquals(SOURCE, LivingSpongeData.of(left).mmB(), "先处理者（槽序在前）吃到");
        assertFalse(LivingSpongeData.of(right).isWet(), "后处理者看见空账本，不双吃");
    }

    @Test
    @DisplayName("⑧ 斜对角不是邻居（27 槽 3 行）：右邻（同行）吸，斜对不吸（⑦ 的空间语义）")
    void diagonalIsNotANeighbor() {
        Scene scene = Scene.of(27); // width 9 ⇒ 3 行
        scene.source(11, WATER);    // slot10 的右邻 = 11
        scene.source(2, WATER);     // 斜对（上行右列）= 2
        ItemStack sponge = scene.spongeAt(10);

        fn.tick(List.of(new SlotEntry(10, sponge)), scene.ctx(), scene.tick(), scene.level());

        assertFalse(scene.hasSource(11), "右邻（同行）照吸");
        assertTrue(scene.hasSource(2), "斜对角（上邻 1 的右邻）不是四邻");
        assertEquals(SOURCE, LivingSpongeData.of(sponge).mmB());
    }

    @Test
    @DisplayName("⑨ 端到端冒烟：processContext 真实分组驱动海绵吸收")
    void processContextDrivesAbsorption() {
        Scene scene = Scene.of(9);
        scene.source(3, WATER);
        scene.source(5, WATER);
        ItemStack sponge = scene.spongeAt(4);

        ContainerLivingItemHandler.processContext(scene.ctx(), scene.level());

        assertFalse(scene.hasSource(3), "分组 → tick 链路真实生效");
        assertFalse(scene.hasSource(5));
        assertTrue(LivingSpongeData.of(sponge).isWet());
    }
}
