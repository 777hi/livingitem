package com.qiqi.li.living.transfer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.qiqi.li.living.model.Pos2D;
import com.qiqi.li.living.transfer.ContainerCompatibilityConfig.ContainerLayoutType;

/**
 * {@link ContainerCompatibilityConfig} 布局推断测试。
 *
 * <p>此类是容器兼容性的唯一入口：任何未注册的模组容器都靠
 * {@code findOrGenerateRule} 推断列数。推断错误会导致 WASD 方向配置
 * 和红石信号传播的网格全部错位，因此列数推断是关键路径。</p>
 */
class ContainerCompatibilityConfigTest {

    @Test
    @DisplayName("已注册容器：返回注册的规则而非自动生成")
    void findOrGenerateRule_registeredSize_returnsRegisteredRule() {
        var id = net.minecraft.resources.ResourceLocation
            .fromNamespaceAndPath("testmod", "registered_chest");
        ContainerCompatibilityConfig.register(id, ContainerCompatibilityConfig.ContainerRule.builder()
            .containerSize(27).columns(9).description("test").build());
        try {
            var chest = ContainerCompatibilityConfig.findOrGenerateRule(27);
            assertEquals(27, chest.containerSize());
            assertEquals(ContainerLayoutType.RECTANGULAR_STANDARD, chest.layoutType());
            // 注册的规则没有 description，自动生成的会带 "auto-generated"
            assertFalse(chest.description().contains("auto-generated"),
                "27 格应命中已注册的 testmod:registered_chest 规则");
        } finally {
            ContainerCompatibilityConfig.remove(id);
        }
    }

    /**
     * 内置规则的形状校验（2026-11-12 起内置表只留第三方条目，原版家族由
     * {@code BuiltInContainerLayouts} 代码表给出 —— 见 {@code typeTable_isSoleSourceForVanilla}）。
     */
    @Test
    @DisplayName("内置规则形状：标准矩形布局，所有槽位均可作宿主")
    void bundledRule_allSlotsAreValidHosts() {
        var rule = ContainerCompatibilityConfig.findRule(
            net.minecraft.resources.ResourceLocation
                .fromNamespaceAndPath("ironchest", "iron_chest")).orElseThrow();

        assertEquals(ContainerLayoutType.RECTANGULAR_STANDARD, rule.layoutType());
        assertEquals(54, rule.containerSize());
        for (int i = 0; i < 54; i++) {
            assertTrue(rule.isValidHostSlot(i), "槽位 " + i + " 可作宿主");
        }
    }

    // ── 原版家族表（2026-11-12 起是原版列数的唯一来源）──────────────

    /**
     * 原版容器的列数约定现在<b>只住在代码表</b> {@code BuiltInContainerLayouts}
     * （2026-11-12 清理：{@code container_rules.json} 里的原版条目已删 —— 它们与代码表
     * 完全重叠，其中 {@code minecraft:double_chest} 更是从未匹配过的死键，resolver 查的是
     * BE 类型键 {@code minecraft:chest}）。这里守住代码表自身的数值，
     * 防止 instanceof 家族表回归时静默改值。
     *
     * <p>历史备注：本测试前身是「Tier 2 表 vs Tier 1 规则」双源漂移守卫（2026-10-18），
     * 实录过漏斗表 1 / 规则 5 的漂移；双源合并成单源后，守卫对象改为代码表本身。</p>
     */
    @Test
    @DisplayName("原版家族表：箱 9 / 漏 5 / 发 3 / 投 3 / 熔炉族 1 / 酿造台 1")
    void typeTable_isSoleSourceForVanilla() {
        assertEquals(9, ContainerCompatibilityConfig.resolveColumns(27,
            new net.minecraft.world.level.block.entity.ChestBlockEntity(
                net.minecraft.core.BlockPos.ZERO,
                net.minecraft.world.level.block.Blocks.CHEST.defaultBlockState())));
        assertEquals(5, ContainerCompatibilityConfig.resolveColumns(5,
            new net.minecraft.world.level.block.entity.HopperBlockEntity(
                net.minecraft.core.BlockPos.ZERO,
                net.minecraft.world.level.block.Blocks.HOPPER.defaultBlockState())));
        assertEquals(3, ContainerCompatibilityConfig.resolveColumns(9,
            new net.minecraft.world.level.block.entity.DispenserBlockEntity(
                net.minecraft.core.BlockPos.ZERO,
                net.minecraft.world.level.block.Blocks.DISPENSER.defaultBlockState())));
        assertEquals(3, ContainerCompatibilityConfig.resolveColumns(9,
            new net.minecraft.world.level.block.entity.DropperBlockEntity(
                net.minecraft.core.BlockPos.ZERO,
                net.minecraft.world.level.block.Blocks.DROPPER.defaultBlockState())));
        // 熔炉族 / 酿造台：宽度 1 = 竖直列（启发式对发射器 9 槽会算成 9，表才是对的）
        assertEquals(1, ContainerCompatibilityConfig.resolveColumns(3,
            new net.minecraft.world.level.block.entity.FurnaceBlockEntity(
                net.minecraft.core.BlockPos.ZERO,
                net.minecraft.world.level.block.Blocks.FURNACE.defaultBlockState())));
        assertEquals(1, ContainerCompatibilityConfig.resolveColumns(5,
            new net.minecraft.world.level.block.entity.BrewingStandBlockEntity(
                net.minecraft.core.BlockPos.ZERO,
                net.minecraft.world.level.block.Blocks.BREWING_STAND.defaultBlockState())));
    }

    /**
     * 列数推断：{@code resolveColumns} 按加权启发式从常见宽度优先匹配。
     *
     * <p>候选宽度排序：9, 10, 12, 13, 8, 7, 6, 11, 5, 4, 3, 2, 1。
     * 40 → 10（40%10=0，10 优先于 8），
     * 90 → 9（90%9=0，9 优先于 10），
     * 96 → 12（96%12=0，12 优先于 8）。</p>
     *
     * <p>注意：使用故意避开模组自带规则的尺寸，确保走自动生成路径。</p>
     */
    @ParameterizedTest(name = "{0} 格容器 → 推断 {1} 列")
    @CsvSource({
        "40, 10",
        "90, 9",
        "99, 9",
        "96, 12",
        "121, 11",
        "7, 7"
    })
    @DisplayName("未注册尺寸：按加权启发式从常见宽度优先匹配")
    void findOrGenerateRule_unregisteredSize_inferesColumns(int size, int expectedColumns) {
        var rule = ContainerCompatibilityConfig.findOrGenerateRule(size);
        assertEquals(expectedColumns, rule.columns());
        assertEquals(size, rule.containerSize());
        assertTrue(rule.description().contains("auto-generated"));
    }

    @Test
    @DisplayName("自动生成规则：上下方向偏移量等于推断出的列数")
    void generatedRule_verticalOffsetMatchesColumns() {
        var rule = ContainerCompatibilityConfig.findOrGenerateRule(96);
        int columns = rule.columns();

        assertEquals(-1, rule.getDirectionOffset(Pos2D.LEFT));
        assertEquals(1, rule.getDirectionOffset(Pos2D.RIGHT));
        assertEquals(-columns, rule.getDirectionOffset(Pos2D.UP));
        assertEquals(columns, rule.getDirectionOffset(Pos2D.DOWN));
    }

    @Test
    @DisplayName("质数尺寸：退化为单行（列数等于尺寸本身）")
    void findOrGenerateRule_primeSize_degradesToSingleRow() {
        // 17 是质数且 > 13，从 13 向下只有 1 能整除
        var rule = ContainerCompatibilityConfig.findOrGenerateRule(17);
        assertEquals(1, rule.columns(),
            "质数尺寸只能被 1 整除，退化为单列");
    }

    @Test
    @DisplayName("自动生成规则：所有槽位均可作宿主")
    void generatedRule_allSlotsAreValidHosts() {
        var rule = ContainerCompatibilityConfig.findOrGenerateRule(40);
        for (int i = 0; i < 40; i++) {
            assertTrue(rule.isValidHostSlot(i), "槽位 " + i + " 应可作宿主");
        }
        assertFalse(rule.isValidHostSlot(40), "越界槽位不可作宿主");
    }

    @Test
    @DisplayName("Builder：容器尺寸非正数时抛异常")
    void builder_rejectsNonPositiveSize() {
        var builder = ContainerCompatibilityConfig.ContainerRule.builder().containerSize(0);
        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalStateException.class, builder::build);
    }

    @Test
    @DisplayName("Builder：未指定宿主槽位时默认全部可用")
    void builder_defaultsToAllSlotsWhenHostSlotsOmitted() {
        var rule = ContainerCompatibilityConfig.ContainerRule.builder()
            .containerSize(5)
            .build();
        assertEquals(5, rule.validHostSlots().size());
    }

    // ── 多档容量（S4，2026-10-18）────────────────────────────────

    /**
     * 同一个方块实体类型可以有多档容量，且<b>列数不同</b>。
     *
     * <p>精妙背包的真实形态就是如此：27/45/54/81 是 9 列，108/120 是 <b>12 列</b>。
     * 旧实现 {@code Map<id, rule>} 一个 ID 只能存一条 ⇒ 两档必然互相挤掉。
     * 列数错的后果不是显示难看，而是<b>活熔炉燃料槽（{@code slot + width}）读错 ⇒ 不烧</b>；
     * 而活漏斗只用 {@code slot ± 1} ⇒ 看起来正常 —— 这是最难自查的一类偏差。</p>
     *
     * <p>2026-11-12 起精妙背包不再内置规则（联动模块 {@code SophisticatedBackpackSource}
     * 按 槽数÷行数 给权威列数，且 LIVE 层优先于规则），本测试改为程序化注册来守
     * 注册表自身的多档共存语义。</p>
     */
    @Test
    @DisplayName("多档容量：同一 ID 的 9 列档与 12 列档可共存（旧实现会挤掉）")
    void multiTier_coexist() {
        var id = net.minecraft.resources.ResourceLocation
            .fromNamespaceAndPath("sophisticatedbackpacks", "backpack");

        ContainerCompatibilityConfig.register(id, rule(27, 9));
        ContainerCompatibilityConfig.register(id, rule(81, 9));
        ContainerCompatibilityConfig.register(id, rule(108, 12));
        ContainerCompatibilityConfig.register(id, rule(120, 12));

        assertEquals(9, ContainerCompatibilityConfig.findRule(id, 27).orElseThrow().columns(),
            "27 格 = 9 列");
        assertEquals(9, ContainerCompatibilityConfig.findRule(id, 81).orElseThrow().columns(),
            "81 格 = 9 列");
        assertEquals(12, ContainerCompatibilityConfig.findRule(id, 108).orElseThrow().columns(),
            "108 格 = 12 列");
        assertEquals(12, ContainerCompatibilityConfig.findRule(id, 120).orElseThrow().columns(),
            "120 格 = 12 列");

        // 同容量的重复注册是覆盖（玩家修正数据是预期行为），不是挤掉别的档
        ContainerCompatibilityConfig.register(id, rule(108, 13));
        assertEquals(13, ContainerCompatibilityConfig.findRule(id, 108).orElseThrow().columns(),
            "同 (ID, 容量) 再注册应覆盖该档");
        assertEquals(12, ContainerCompatibilityConfig.findRule(id, 120).orElseThrow().columns(),
            "覆盖 108 档不应影响 120 档");
    }

    /** 本类会向全局静态注册表注册规则，收尾时重建，避免污染其它测试类。 */
    @org.junit.jupiter.api.AfterEach
    void restoreSharedRegistry() throws java.io.IOException {
        ContainerRuleConfig.init(java.nio.file.Files.createTempDirectory("living-rules-reset"));
        ContainerRuleConfig.load();
    }

    private static ContainerCompatibilityConfig.ContainerRule rule(int size, int columns) {
        return ContainerCompatibilityConfig.ContainerRule.builder()
            .containerSize(size)
            .columns(columns)
            .description("test " + columns + "x" + (size / columns))
            .build();
    }
}