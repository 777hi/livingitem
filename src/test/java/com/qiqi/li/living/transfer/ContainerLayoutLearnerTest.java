package com.qiqi.li.living.transfer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;


import com.qiqi.li.living.container.LearnedLayouts;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;

/**
 * 容器布局自动学的守卫（2026-10-25，Tier 1.5）。
 *
 * <h3>测什么</h3>
 * <ol>
 *   <li><b>安全阀的形状判据</b> —— 干净矩形收、非矩形拒。这是整套机制的核心：
 *       它决定“学”只在网格语义成立的地方发生。</li>
 *   <li><b>容器身份的取得</b> —— 只认「槽的 container 是方块实体」；拿不到身份就不学
 *       （用 {@code SlotItemHandler} 的模组、大箱、背包 GUI 都在此列）。</li>
 *   <li><b>与规则表的关系</b> —— 规则答得上就不学（= 注册规则即锁定）；冲突只告警不覆盖。</li>
 *   <li><b>歧义</b> —— 同键两个不同值 ⇒ 标记后不再学。</li>
 *   <li><b>落盘往返</b> —— 写 ⇒ 重载 ⇒ 读回一致。</li>
 * </ol>
 *
 * <h3>为什么能测菜单</h3>
 * <p>{@code AbstractContainerMenu} 的构造器是 {@code protected}、{@code addSlot} 也是
 * ⇒ 测试里建一个极简子类就能填 {@code slots}。{@code Slot} 的构造器不调用 container 的
 * 任何方法 ⇒ container 可以用 mock，按实例身份比就行（与生产代码的判据同源）。</p>
 *
 * <h3>刻意不测的</h3>
 * <ul>
 *   <li><b>事件本身</b>（{@code PlayerContainerEvent.Open} 由 NeoForge 发）—— 测它等于测框架；
 *       入口方法 {@link ContainerLayoutLearner#onContainerOpen} 只做转调。</li>
 *   <li><b>{@code getWidth()} 的 Tier 1.5 接线</b> —— 需要真 BE + 能力面，
 *       由 {@code SimpleContainerContextTest} 的既有宽度用例覆盖链路，这里只验存储与判据。</li>
 * </ul>
 */
class ContainerLayoutLearnerTest {

    @TempDir
    Path tempDir;

    /** 事件入口只认 {@code ServerPlayer}（生产上该事件本就由 {@code ServerPlayer} 发）。 */
    private Player player;

    /** 极简菜单：能加槽就行。 */
    public static final class TestMenu extends AbstractContainerMenu {
        TestMenu() {
            super(null, 0);
        }

        @Override
        public boolean stillValid(Player player) {
            return true;
        }

        @Override
        public net.minecraft.world.item.ItemStack quickMoveStack(Player player, int index) {
            return net.minecraft.world.item.ItemStack.EMPTY;
        }

        public void add(Slot slot) {
            addSlot(slot);
        }
    }

    @BeforeEach
    void setUp() {
        LearnedLayouts.clearForTest();
        LearnedLayouts.init(tempDir);
        player = mock(net.minecraft.server.level.ServerPlayer.class);
        Inventory inventory = mock(Inventory.class);
        when(player.getInventory()).thenReturn(inventory);
    }

    private Slot containerSlot(Container container, int x, int y) {
        return new Slot(container, 0, x, y);
    }

    private Slot playerSlot(int x, int y) {
        return new Slot(player.getInventory(), 0, x, y);
    }

    private ChestBlockEntity chest() {
        return new ChestBlockEntity(BlockPos.ZERO, Blocks.CHEST.defaultBlockState());
    }

    /**
     * 熔炉 BE —— 选它因为<b>规则表里没有它</b>（只有 Tier 2 家族兜底），
     * 所以能干净地验"没有规则 ⇒ 学"。它的三槽坐标也是安全阀的经典通过形状（3 行 × 1 列）。
     */
    private net.minecraft.world.level.block.entity.FurnaceBlockEntity furnace() {
        return new net.minecraft.world.level.block.entity.FurnaceBlockEntity(
            BlockPos.ZERO, Blocks.FURNACE.defaultBlockState());
    }

    private static final ResourceLocation FURNACE_ID =
        ResourceLocation.withDefaultNamespace("furnace");

    /** 原版熔炉菜单的三个容器槽坐标（读 merged 源码核过）。 */
    private TestMenu furnaceMenu() {
        TestMenu menu = new TestMenu();
        menu.add(containerSlot(furnace(), 56, 17));
        menu.add(containerSlot(furnace(), 56, 53));
        menu.add(containerSlot(furnace(), 116, 35));
        return menu;
    }

    /** 保证熔炉在测试期间没有规则（内置表可能被别的测试加载过，静态表是全 JVM 的）。 */
    private void ensureNoFurnaceRule() {
        ContainerCompatibilityConfig.remove(FURNACE_ID);
    }

    private static final ResourceLocation CRAFTER_ID =
        ResourceLocation.withDefaultNamespace("crafter");

    /**
     * 一个<b>能过全部准入</b>的容器：mock 的 Crafter BE（类型 id 没有规则、不是原版家族），
     * 3×3 行优先网格，槽号 0..8 与坐标顺序一致，且 {@code getContainerSize()}=9。
     */
    private net.minecraft.world.level.block.entity.CrafterBlockEntity crafter() {
        return new net.minecraft.world.level.block.entity.CrafterBlockEntity(
            BlockPos.ZERO, Blocks.CRAFTER.defaultBlockState());
    }

    private TestMenu crafterMenu() {
        TestMenu menu = new TestMenu();
        var be = crafter();
        int i = 0;
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                menu.add(new Slot(be, i, 8 + col * 18, 18 + row * 18));
                i++;
            }
        }
        return menu;
    }



    // ── 安全阀：形状判据 ────────────────────────────────────────────────────

    @Test
    @DisplayName("干净矩形 ⇒ 收到列数（3 行 × 9 列 ⇒ 9）")
    void cleanRectangle_learnsColumns() {
        List<Slot> slots = new java.util.ArrayList<>();
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                slots.add(containerSlot(chest(), 8 + col * 18, 18 + row * 18));
            }
        }
        assertEquals(9, ContainerLayoutLearner.columnsFromSlots(slots));
    }

    @Test
    @DisplayName("单排 N 个 ⇒ 1 行 N 列（fxntstorage 那种一排摆开的）")
    void singleRow_learnsAllColumns() {
        List<Slot> slots = new java.util.ArrayList<>();
        for (int i = 0; i < 48; i++) {
            slots.add(containerSlot(chest(), i * 18, 0));
        }
        assertEquals(48, ContainerLayoutLearner.columnsFromSlots(slots));
    }

    @Test
    @DisplayName("熔炉的 L 形 ⇒ 三组各 1 个 ⇒ 3 行 × 1 列，收到 1（这正是手写表的答案）")
    void furnaceShape_learnsOne() {
        List<Slot> slots = List.of(
            containerSlot(chest(), 56, 17),
            containerSlot(chest(), 56, 53),
            containerSlot(chest(), 116, 35));
        assertEquals(1, ContainerLayoutLearner.columnsFromSlots(slots));
    }

    @Test
    @DisplayName("行数不等 ⇒ 拒（酿造台按 y 分组是 2/2/1）")
    void unevenRows_rejected() {
        List<Slot> slots = List.of(
            containerSlot(chest(), 17, 17),
            containerSlot(chest(), 79, 17),
            containerSlot(chest(), 56, 51),
            containerSlot(chest(), 102, 51),
            containerSlot(chest(), 79, 58));
        assertEquals(0, ContainerLayoutLearner.columnsFromSlots(slots));
    }

    @Test
    @DisplayName("行内 x 不等距 ⇒ 拒")
    void unevenSpacing_rejected() {
        List<Slot> slots = List.of(
            containerSlot(chest(), 8, 18),
            containerSlot(chest(), 26, 18),
            containerSlot(chest(), 80, 18));
        assertEquals(0, ContainerLayoutLearner.columnsFromSlots(slots));
    }

    @Test
    @DisplayName("空 / 单槽")
    void edgeCases() {
        assertEquals(0, ContainerLayoutLearner.columnsFromSlots(List.of()));
        assertEquals(1, ContainerLayoutLearner.columnsFromSlots(
            List.of(containerSlot(chest(), 0, 0))));
    }

    // ── 容器身份 ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("槽的 container 是方块实体 ⇒ 取得到 id")
    void blockEntityContainer_givesId() {
        ResourceLocation id = ContainerLayoutLearner.containerIdOf(containerSlot(chest(), 0, 0));
        assertEquals(ResourceLocation.withDefaultNamespace("chest"), id);
    }

    @Test
    @DisplayName("container 不是方块实体（SlotItemHandler 的共享空容器 / 玩家背包）⇒ null")
    void nonBlockEntityContainer_noId() {
        Container shared = mock(Container.class);   // 模拟 SlotItemHandler 的共享静态空容器
        assertNull(ContainerLayoutLearner.containerIdOf(containerSlot(shared, 0, 0)));
        assertNull(ContainerLayoutLearner.containerIdOf(playerSlot(0, 0)));
    }

    // ── 菜单取材：滤掉玩家自己的槽 ──────────────────────────────────────────

    @Test
    @DisplayName("菜单里玩家背包的槽被排除，只剩容器槽")
    void containerSlots_excludePlayerInventory() {
        TestMenu menu = new TestMenu();
        menu.add(playerSlot(8, 84));
        menu.add(playerSlot(26, 84));
        menu.add(containerSlot(chest(), 8, 18));

        List<Slot> only = ContainerLayoutLearner.containerSlotsOf(menu, player);
        assertEquals(1, only.size(), "只剩容器槽");
        assertEquals(8, only.get(0).x);
    }

    // ── 与规则表的关系 ─────────────────────────────────────────────────────

    @Test
    @DisplayName("规则表已经答得上 ⇒ 不学（= 注册规则即锁定）")
    void existingRule_blocksLearning() {
        ResourceLocation id = CRAFTER_ID;
        ContainerCompatibilityConfig.register(id,
            ContainerCompatibilityConfig.ContainerRule.builder()
                .containerSize(9).columns(3).description("test").build());
        try {
            // ⚠️ 菜单必须与注册的规则同 (id, 容量)，且能过准入 —— 否则早早 return，
            // 根本走不到"规则拦住学习"这条分支（审查 §10.1 指出的覆盖缺口）。
            ContainerLayoutLearner.onContainerOpen(
                new PlayerContainerEvent.Open(player, crafterMenu()));

            assertEquals(0, LearnedLayouts.columnsOf(id, 9),
                "有规则就不该学 —— 注册规则即锁定");
        } finally {
            ContainerCompatibilityConfig.remove(id);
            LearnedLayouts.clearForTest();
        }
    }

    @Test
    @DisplayName("没有规则 ⇒ 学，并落盘（能过准入的 3×3 容器）")
    void noRule_learnsAndPersists() {
        ContainerLayoutLearner.onContainerOpen(
            new PlayerContainerEvent.Open(player, crafterMenu()));

        assertEquals(3, LearnedLayouts.columnsOf(CRAFTER_ID, 9), "3 行 × 3 列 ⇒ 3 列");
        assertTrue(Files.exists(tempDir.resolve("living_item").resolve("learned_layouts.json")),
            "值变了就要落盘");
    }

    @Test
    @DisplayName("熔炉被行优先判据拒（它的槽号顺序 ≠ 坐标顺序）—— 收紧准入的预期后果")
    void furnace_rejectedByRowMajor() {
        ensureNoFurnaceRule();

        ContainerLayoutLearner.onContainerOpen(
            new PlayerContainerEvent.Open(player, furnaceMenu()));

        assertEquals(0, LearnedLayouts.columnsOf(FURNACE_ID, 3),
            "熔炉按槽号是 input→fuel→output，按 (y,x) 是 input→output→fuel ⇒ 不是行优先网格，"
                + "不能宣称复刻了 GUI；它的 1 列由 Tier 2 家族表给，不受影响");
    }

    @Test
    @DisplayName("值没变 ⇒ 不重复写盘")
    void sameValue_noRewrite() {
        ResourceLocation id = ResourceLocation.withDefaultNamespace("chest");
        assertTrue(LearnedLayouts.learn(id, 27, 9), "首次学习应写入");
        assertFalse(LearnedLayouts.learn(id, 27, 9), "值相同不该再写");
    }

    // ── 歧义 ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("同键两个不同列数 ⇒ 标记歧义，此后不再学")
    void conflictingValues_markAmbiguous() {
        ResourceLocation id = CRAFTER_ID;
        LearnedLayouts.learn(id, 27, 9);
        LearnedLayouts.markAmbiguous(id, 27);

        assertFalse(LearnedLayouts.learn(id, 27, 5), "歧义键不再接受新值");
        assertEquals(0, LearnedLayouts.columnsOf(id, 27), "歧义后查不到");
    }


    // ── 审查 §10.1 回归（2026-10-25 另一位 AI 提的三条已复现问题）────────────

    @Test
    @DisplayName("歧义不能跨重启复活：标记后磁盘旧值也要失效")
    void ambiguity_doesNotSurviveRestart() {
        ensureNoFurnaceRule();
        assertTrue(LearnedLayouts.learn(FURNACE_ID, 3, 1), "前提：先写盘一条");

        LearnedLayouts.markAmbiguous(FURNACE_ID, 3);
        assertEquals(0, LearnedLayouts.columnsOf(FURNACE_ID, 3), "内存里应立即失效");

        // 模拟重启：清内存 + 重新 init/load
        LearnedLayouts.clearForTest();
        LearnedLayouts.init(tempDir);
        LearnedLayouts.load();

        assertEquals(0, LearnedLayouts.columnsOf(FURNACE_ID, 3),
            "重启不该把已判歧义的旧值读回来");
        assertFalse(LearnedLayouts.learn(FURNACE_ID, 3, 1),
            "重启后这个键仍应拒绝学习");
    }

    @Test
    @DisplayName("写盘失败后，值没变也要能重试（否则自愈路径是空的）")
    void failedSave_retriesWhenValueUnchanged() throws Exception {
        ensureNoFurnaceRule();
        // 把 living_item 占成普通文件 ⇒ createDirectories 失败 ⇒ save 失败
        Files.createFile(tempDir.resolve("living_item"));
        assertTrue(LearnedLayouts.learn(FURNACE_ID, 3, 1), "首次学习（内存更新、写盘失败）");

        Files.delete(tempDir.resolve("living_item"));   // 阻挡移除
        assertTrue(LearnedLayouts.learn(FURNACE_ID, 3, 1),
            "文件系统恢复后，同值也应重试写盘 —— 不能因为内存里已是这个值就永远跳过");
        assertTrue(Files.exists(tempDir.resolve("living_item").resolve("learned_layouts.json")),
            "重试后文件应真的存在");
    }

    @Test
    @DisplayName("多个槽挤在同一坐标 ⇒ 不是网格，拒")
    void overlappingCoordinates_rejected() {
        List<Slot> three = List.of(
            containerSlot(chest(), 0, 0),
            containerSlot(chest(), 0, 0),
            containerSlot(chest(), 0, 0));
        assertEquals(0, ContainerLayoutLearner.columnsFromSlots(three),
            "同坐标的三个槽不是 1×3 网格");

        List<Slot> two = List.of(
            containerSlot(chest(), 0, 0),
            containerSlot(chest(), 0, 0));
        assertEquals(0, ContainerLayoutLearner.columnsFromSlots(two),
            "两个槽也要拒（原来 size<3 的分支直接放行）");
    }

    @Test
    @DisplayName("单槽仍合法（间距无从检查，不能误伤）")
    void singleSlotStillAccepted() {
        assertEquals(1, ContainerLayoutLearner.columnsFromSlots(
            List.of(containerSlot(chest(), 0, 0))));
    }

    @Test
    @DisplayName("Tier 1.5 接线：学到的值要能压过原版家族（Tier 2）")
    void tier15_wiring_beatsFamilyTable() {
        ensureNoFurnaceRule();
        assertTrue(LearnedLayouts.learn(CRAFTER_ID, 9, 7), "先学一个人为的 7 列");

        var be = crafter();
        var ctx = new com.qiqi.li.living.container.SimpleContainerContext(
            new net.neoforged.neoforge.items.ItemStackHandler(9),
            java.util.List.of(be.getBlockPos()), java.util.List.of(be));

        assertEquals(7, ctx.getWidth(),
            "Tier 1.5 要真的接进 getWidth() —— 学到 7 就该答 7，而不是掉到启发式的 9");
    }

    // ── 落盘往返 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("写 ⇒ 重载 ⇒ 读回一致")
    void diskRoundTrip() throws Exception {
        ResourceLocation id = CRAFTER_ID;
        assertTrue(LearnedLayouts.learn(id, 9, 3));

        LearnedLayouts.load();   // 重新读盘

        assertEquals(3, LearnedLayouts.columnsOf(id, 9), "重载后应读回刚写的值");
    }

    @Test
    @DisplayName("文件损坏 ⇒ 不崩，按空表开始")
    void corruptFile_degradesToEmpty() throws Exception {
        Path dir = tempDir.resolve("living_item");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("learned_layouts.json"), "{ 这不是 json");

        LearnedLayouts.load();   // 不该抛

        assertEquals(0, LearnedLayouts.columnsOf(CRAFTER_ID, 9));
    }

    @Test
    @DisplayName("自己的背包菜单 ⇒ 不学（没有容器槽）")
    void ownInventoryMenu_nothingToLearn() {
        TestMenu menu = new TestMenu();
        for (int i = 0; i < 9; i++) {
            menu.add(playerSlot(8 + i * 18, 142));
        }

        ContainerLayoutLearner.onContainerOpen(new PlayerContainerEvent.Open(player, menu));

        assertEquals(0, LearnedLayouts.columnsOf(CRAFTER_ID, 9));
    }

    @Test
    @DisplayName("拿不到容器身份 ⇒ 不学（fxntstorage 那种）")
    void noIdentity_noLearn() {
        Container shared = mock(Container.class);   // SlotItemHandler 的共享静态空容器
        TestMenu menu = new TestMenu();
        for (int i = 0; i < 20; i++) {
            menu.add(containerSlot(shared, i * 18, 0));
        }

        ContainerLayoutLearner.onContainerOpen(new PlayerContainerEvent.Open(player, menu));

        assertEquals(0, LearnedLayouts.columnsOf(CRAFTER_ID, 20),
            "拿不到 id 就学不了 —— 这不是缺陷，是已知局限（见 ContainerLayoutLearner 类注释）");
    }
}
