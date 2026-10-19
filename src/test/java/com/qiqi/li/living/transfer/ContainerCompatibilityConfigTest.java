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
        var chest = ContainerCompatibilityConfig.findOrGenerateRule(27);
        assertEquals(27, chest.containerSize());
        assertEquals(ContainerLayoutType.RECTANGULAR_STANDARD, chest.layoutType());
        // 注册的箱子规则没有 description，自动生成的会带 "auto-generated"
        assertFalse(chest.description().contains("auto-generated"),
            "27 格应命中已注册的 minecraft:chest 规则");
    }

    @Test
    @DisplayName("漏斗 5 格：标准矩形布局，所有槽位均可作宿主（配置文件自动生成）")
    void hopperRule_allSlotsAreValidHosts() {
        var hopper = ContainerCompatibilityConfig.findRule(
            net.minecraft.resources.ResourceLocation
                .fromNamespaceAndPath("minecraft", "hopper")).orElseThrow();

        assertEquals(ContainerLayoutType.RECTANGULAR_STANDARD, hopper.layoutType());
        assertEquals(5, hopper.containerSize());
        for (int i = 0; i < 5; i++) {
            assertTrue(hopper.isValidHostSlot(i), "槽位 " + i + " 可作宿主");
        }
    }

    // ── Tier 2 兜底表 vs Tier 1 规则表：双源漂移守卫（2026-10-18）──────

    /**
     * 类型推断表（Tier 2）与 {@code container_rules.json}（Tier 1）**重叠的条目必须一致**。
     *
     * <p>为什么需要：规则优先于表 ⇒ 不一致不会当场报错，只会表现为「诊断命令显示的列数
     * 与实际生效不同」（实录：漏斗在表里写 1、规则写 5，{@code /livingitem container}
     * 因此显示 1）；规则文件加载失败时还会静默改变行为。</p>
     */
    @Test
    @DisplayName("类型推断表与规则文件一致：箱 9 / 漏 5 / 发 3 / 投 3（双源漂移守卫）")
    void typeTable_agreesWithRegisteredRules() {
        assertTableAgreesWithRule(new net.minecraft.world.level.block.entity.ChestBlockEntity(
            net.minecraft.core.BlockPos.ZERO,
            net.minecraft.world.level.block.Blocks.CHEST.defaultBlockState()));
        assertTableAgreesWithRule(new net.minecraft.world.level.block.entity.HopperBlockEntity(
            net.minecraft.core.BlockPos.ZERO,
            net.minecraft.world.level.block.Blocks.HOPPER.defaultBlockState()));
        assertTableAgreesWithRule(new net.minecraft.world.level.block.entity.DispenserBlockEntity(
            net.minecraft.core.BlockPos.ZERO,
            net.minecraft.world.level.block.Blocks.DISPENSER.defaultBlockState()));
        assertTableAgreesWithRule(new net.minecraft.world.level.block.entity.DropperBlockEntity(
            net.minecraft.core.BlockPos.ZERO,
            net.minecraft.world.level.block.Blocks.DROPPER.defaultBlockState()));

        // 熔炉族 / 酿造台：规则文件未覆盖 ⇒ 表是唯一来源（宽度 1 = 竖直列）
        assertEquals(1, ContainerCompatibilityConfig.resolveColumns(3,
            new net.minecraft.world.level.block.entity.FurnaceBlockEntity(
                net.minecraft.core.BlockPos.ZERO,
                net.minecraft.world.level.block.Blocks.FURNACE.defaultBlockState())));
    }

    private static void assertTableAgreesWithRule(net.minecraft.world.level.block.entity.BlockEntity be) {
        var container = (net.minecraft.world.Container) be;
        int table = ContainerCompatibilityConfig.resolveColumns(
            container.getContainerSize(), container);
        var id = net.minecraft.core.registries.BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType());
        int rule = ContainerCompatibilityConfig.findRule(id)
            .map(ContainerCompatibilityConfig.ContainerRule::columns)
            .orElse(-1);
        assertEquals(rule, table,
            "Tier 2 表与规则表不一致（" + id + "）：规则=" + rule + "，表=" + table
            + " —— 规则优先，表值只会在规则缺失时生效，必须同步");
    }

    @Test
    @DisplayName("大箱子 54 格：标准矩形布局，INVALIDATE 边界（配置文件自动生成）")
    void doubleChestRule_standardRectangularLayout() {
        var doubleChest = ContainerCompatibilityConfig.findRule(
            net.minecraft.resources.ResourceLocation
                .fromNamespaceAndPath("minecraft", "double_chest")).orElseThrow();

        assertEquals(ContainerCompatibilityConfig.EdgeBehavior.INVALIDATE, doubleChest.edgeBehavior());
        assertEquals(ContainerLayoutType.RECTANGULAR_STANDARD, doubleChest.layoutType());
        assertEquals(9, doubleChest.columns());
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
     * <p>精妙背包：27/45/54/81 是 9 列，108/120 是 <b>12 列</b>。
     * 旧实现 {@code Map<id, rule>} 一个 ID 只能存一条 ⇒ 两档必然互相挤掉。
     * 列数错的后果不是显示难看，而是<b>活熔炉燃料槽（{@code slot + width}）读错 ⇒ 不烧</b>；
     * 而活漏斗只用 {@code slot ± 1} ⇒ 看起来正常 —— 这是最难自查的一类偏差。</p>
     */
    @Test
    @DisplayName("精妙背包多档容量：27/81 为 9 列，108/120 为 12 列（可共存）")
    void sophisticatedBackpack_multiTier_coexist() {
        var id = net.minecraft.resources.ResourceLocation
            .fromNamespaceAndPath("sophisticatedbackpacks", "backpack");

        assertEquals(9, ContainerCompatibilityConfig.findRule(id, 27).orElseThrow().columns(),
            "皮革 27 格 = 9 列");
        assertEquals(9, ContainerCompatibilityConfig.findRule(id, 81).orElseThrow().columns(),
            "金 81 格 = 9 列");
        assertEquals(12, ContainerCompatibilityConfig.findRule(id, 108).orElseThrow().columns(),
            "钻石 108 格 = 12 列");
        assertEquals(12, ContainerCompatibilityConfig.findRule(id, 120).orElseThrow().columns(),
            "下界合金 120 格 = 12 列");

        // 两档必须同时存在 —— 旧实现下后注册的会挤掉先注册的
        assertTrue(ContainerCompatibilityConfig.findRule(id, 108).isPresent()
                && ContainerCompatibilityConfig.findRule(id, 120).isPresent(),
            "同一 ID 的两档容量必须能共存");
    }
}