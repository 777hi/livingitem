package com.qiqi.li.living.transfer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.minecraft.resources.ResourceLocation;

import com.qiqi.li.living.transfer.ContainerCompatibilityConfig.ContainerLayoutType;
import com.qiqi.li.living.transfer.ContainerCompatibilityConfig.ContainerRule;
import com.qiqi.li.living.transfer.ContainerCompatibilityConfig.EdgeBehavior;

/**
 * {@link ContainerRuleConfig} 玩家差异持久化测试。
 *
 * <p><b>守住的语义</b>（2026-09-20 定案）：配置文件只保存<b>玩家产生的差异</b>——
 * 新增、覆盖内置、以及删除内置。若删除不落盘，玩家删掉的内置规则会在下次启动时
 * 被内置资源「复活」（{@code load()} 先读内置资源）；若覆盖不落盘，玩家对
 * 错误内置数据的修正同样会丢失。</p>
 *
 * <p>这里不用真实磁盘配置目录，而是直接驱动加载/保存的<b>内存逻辑</b>：
 * {@code applyUserData} 的效果通过 {@code getRemovedIds}/{@code isUserModified}
 * 等公开查询验证，避免测试依赖具体文件布局。</p>
 */
class ContainerRuleConfigTest {

    /** 一个确实存在于内置资源中的 ID，用于测试对内置规则的覆盖/删除 */
    private static final ResourceLocation BUNDLED_ID =
        ResourceLocation.fromNamespaceAndPath("minecraft", "chest");

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        ContainerRuleConfig.init(tempDir);
        // load() 会先 resetState()，因此每个测试都从干净状态开始
        // （静态注册表在同一 JVM 内跨测试类共享，必须显式重置）
        ContainerRuleConfig.load();
    }

    @Test
    @DisplayName("内置规则加载后：被识别为内置，且未被玩家修改")
    void bundledRule_isMarkedAsBundledAndUnmodified() {
        assertTrue(ContainerRuleConfig.isBundledRule(BUNDLED_ID),
            "minecraft:chest 应来自内置资源");
        assertFalse(ContainerRuleConfig.isUserModified(BUNDLED_ID),
            "刚加载时玩家还没动过它");
        assertFalse(ContainerRuleConfig.isRemovedByUser(BUNDLED_ID),
            "未被玩家移除");
    }

    @Test
    @DisplayName("玩家新增规则：进入增量集，可被导出")
    void userRegisteredRule_goesIntoUserRules() {
        ResourceLocation custom =
            ResourceLocation.fromNamespaceAndPath("testmod", "big_chest");
        var rule = buildRule(48, 12);

        ContainerRuleConfig.addAndSave(custom, rule);

        assertFalse(ContainerRuleConfig.isBundledRule(custom),
            "非内置 ⇒ 是玩家新增");
        assertTrue(ContainerRuleConfig.isUserModified(custom),
            "玩家动过 ⇒ 应被持久化与导出");

        Map<ResourceLocation, ContainerRule> userRules = ContainerRuleConfig.getUserRules();
        assertEquals(1, userRules.size(), "应恰好只有这一条玩家规则");
        assertEquals(12, userRules.get(custom).columns(), "列数应被保留");
    }

    @Test
    @DisplayName("覆盖内置规则：记为玩家修改，且导出内容反映新数值")
    void overridingBundledRule_isRecordedAsUserModified() {
        // 内置的 minecraft:chest 是 27 槽 9 列；玩家改成 3 列
        var overrides = buildRule(27, 3);

        boolean overwrote = ContainerRuleConfig.addAndSave(BUNDLED_ID, overrides);

        assertTrue(overwrote, "对已存在规则的注册应报告为『覆盖』");
        assertTrue(ContainerRuleConfig.isBundledRule(BUNDLED_ID),
            "来源判定不应因覆盖而漂移——它仍是内置 ID");
        assertTrue(ContainerRuleConfig.isUserModified(BUNDLED_ID),
            "覆盖内置 ⇒ 必须记入玩家差异，否则重启后丢失");

        var effective = ContainerCompatibilityConfig.findRule(BUNDLED_ID).orElseThrow();
        assertEquals(3, effective.columns(), "生效的应是玩家覆盖后的列数");
    }

    @Test
    @DisplayName("删除内置规则：必须留下移除记录，否则重启后被内置资源复活")
    void removingBundledRule_leavesRemovalRecord() {
        boolean removed = ContainerRuleConfig.removeAndSave(BUNDLED_ID);

        assertTrue(removed, "内置规则确实存在于注册表，应移除成功");
        assertTrue(ContainerRuleConfig.isRemovedByUser(BUNDLED_ID),
            "删除内置必须落盘，否则 load() 会把内置资源再读回来");
        assertFalse(ContainerCompatibilityConfig.findRule(BUNDLED_ID).isPresent(),
            "应从注册表中消失");

        // 再跑一次 load()，模拟重启：内置资源会重新加载，
        // 但 removed 记录必须把它再删掉
        ContainerRuleConfig.load();

        assertFalse(ContainerCompatibilityConfig.findRule(BUNDLED_ID).isPresent(),
            "重启后仍应保持删除状态（这条就是『复活』bug 的守卫）");
    }

    @Test
    @DisplayName("删除玩家自建规则：不留移除记录（它本就不在内置资源里）")
    void removingUserRule_doesNotLeaveRemovalRecord() {
        ResourceLocation custom =
            ResourceLocation.fromNamespaceAndPath("testmod", "temp_chest");
        ContainerRuleConfig.addAndSave(custom, buildRule(54, 9));
        assertEquals(1, ContainerRuleConfig.getUserRules().size());

        ContainerRuleConfig.removeAndSave(custom);

        assertFalse(ContainerRuleConfig.isRemovedByUser(custom),
            "自建规则没有『被内置资源复活』的风险，无需移除记录");
        assertFalse(ContainerRuleConfig.isUserModified(custom),
            "增了又删 ⇒ 玩家差异里不该残留它");
        assertTrue(ContainerRuleConfig.getUserRules().isEmpty(),
            "增量集应为空");
    }

    @Test
    @DisplayName("覆盖后再删除内置：以删除为准，且不再出现在导出里")
    void overrideThenRemove_removalWins() {
        ContainerRuleConfig.addAndSave(BUNDLED_ID, buildRule(27, 3));
        ContainerRuleConfig.removeAndSave(BUNDLED_ID);

        assertTrue(ContainerRuleConfig.isRemovedByUser(BUNDLED_ID));
        assertTrue(ContainerRuleConfig.getUserRules().isEmpty(),
            "删除后不应再有玩家规则，否则导出会与 removed 语义打架");
    }

    @Test
    @DisplayName("导出全量快照：含内置 + 玩家新增，条数 = 生效规则总数")
    void export_writesFullEffectiveSnapshot() throws Exception {
        int bundledOnly = ContainerRuleConfig.exportBundledFormat();
        assertTrue(bundledOnly > 0, "至少应导出内置资源里的规则");
        assertEquals(bundledOnly, ContainerRuleConfig.countBundled() + ContainerRuleConfig.countUser(),
            "导出条数应等于生效规则总数");

        ResourceLocation a = ResourceLocation.fromNamespaceAndPath("testmod", "a");
        ResourceLocation b = ResourceLocation.fromNamespaceAndPath("testmod", "b");
        ContainerRuleConfig.addAndSave(a, buildRule(18, 9));
        ContainerRuleConfig.addAndSave(b, buildRule(36, 9));

        int exported = ContainerRuleConfig.exportBundledFormat();
        assertEquals(bundledOnly + 2, exported,
            "玩家新增 2 条后，快照应包含内置 + 2");

        // 快照必须是「完整可用」的：能被解析且内置条目仍在其中，
        // 这样作者才能直接用它覆盖 assets 资源而不丢内置数据。
        assertTrue(ContainerRuleConfig.getExportFile().toFile().exists(), "导出文件应真实写盘");
        String json = java.nio.file.Files.readString(ContainerRuleConfig.getExportFile(), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(json.contains("\"minecraft:chest\""),
            "快照应保留内置规则（否则覆盖会丢数据）");
        assertTrue(json.contains("\"testmod:a\""), "快照应含玩家新增规则");
    }

    @Test
    @DisplayName("导出快照：被玩家删除的内置规则不应出现在快照里")
    void export_excludesRemovedBundledRule() throws Exception {
        assertTrue(ContainerRuleConfig.getRemovedIds().isEmpty(), "前置：无删除记录");

        ContainerRuleConfig.removeAndSave(BUNDLED_ID);
        ContainerRuleConfig.exportBundledFormat();

        String json = java.nio.file.Files.readString(ContainerRuleConfig.getExportFile(), java.nio.charset.StandardCharsets.UTF_8);
        assertFalse(json.contains("\"minecraft:chest\""),
            "玩家删掉的内置规则不应回到快照，否则覆盖后又复活了");
    }

    @Test
    @DisplayName("来源计数：内置/社区条数之和等于生效规则总数")
    void counts_addUpToEffectiveTotal() {
        ContainerRuleConfig.addAndSave(
            ResourceLocation.fromNamespaceAndPath("testmod", "extra"), buildRule(54, 9));

        int bundled = ContainerRuleConfig.countBundled();
        int user = ContainerRuleConfig.countUser();
        int total = ContainerCompatibilityConfig.getAllRules().size();

        assertEquals(total, bundled + user, "两个来源计数之和应等于注册表大小");
        assertTrue(bundled > 0, "内置条数应 > 0");
        assertEquals(1, user, "恰好新增了 1 条社区规则");
    }

    /**
     * 社区贡献闭环的核心假设：<b>导出的快照可以直接当作内置资源重新加载</b>。
     *
     * <p>这是「文件级复制粘贴」成立的前提——玩家导出的 `exported_rules.json` 被作者
     * 整文件覆盖到 {@code assets/} 后，重新加载必须得到与导出时完全一致的规则表。
     * 若快照缺了内置条目，覆盖就会丢数据。</p>
     */
    @Test
    @DisplayName("社区闭环：导出快照可原样当作内置资源重新加载（无丢失）")
    void exportedSnapshot_isLoadableAsBundledResource() throws Exception {
        // 玩家侧：新增一条、并修正一条内置规则
        ResourceLocation contributed =
            ResourceLocation.fromNamespaceAndPath("testmod", "community_chest");
        ContainerRuleConfig.addAndSave(contributed, buildRule(216, 18));
        ContainerRuleConfig.addAndSave(BUNDLED_ID, buildRule(27, 3)); // 覆盖内置

        int expectedTotal = ContainerCompatibilityConfig.getAllRules().size();
        ContainerRuleConfig.exportBundledFormat();

        // 作者侧：把快照当作内置资源重新加载
        ContainerRuleConfig.resetState();
        try (var reader = java.nio.file.Files.newBufferedReader(
                ContainerRuleConfig.getExportFile(), java.nio.charset.StandardCharsets.UTF_8)) {
            ContainerRuleConfig.loadRules(reader, "bundled", "<simulated override>");
        }

        assertEquals(expectedTotal, ContainerCompatibilityConfig.getAllRules().size(),
            "覆盖加载后的规则数应与导出时一致——一条不少");
        assertEquals(27, ContainerCompatibilityConfig.findRule(BUNDLED_ID).orElseThrow().containerSize(),
            "内置条目应仍在（覆盖不丢内置数据）");
        assertEquals(3, ContainerCompatibilityConfig.findRule(BUNDLED_ID).orElseThrow().columns(),
            "玩家对内置规则的修正应随快照一起发布");
        assertEquals(216,
            ContainerCompatibilityConfig.findRule(contributed).orElseThrow().containerSize(),
            "玩家新增的条目也应随快照发布");
    }

    /**
     * 合并多份社区贡献时，同一 ID 出现两条不同数值 ⇒ 先到先得 + WARN。
     *
     * <p>只比 {@code containerSize}/{@code columns}：描述措辞不同<b>不算</b>冲突，
     * 否则合并十几份玩家贡献会被「原版箱子 vs 标准箱子」这类假冲突淹没。</p>
     */
    @Test
    @DisplayName("合并冲突：同 ID 不同数值先到先得；描述不同不算冲突")
    void mergeConflict_keepsFirstAndWarns() throws Exception {
        String json = """
            {"version":1,"rules":[
              {"containerId":"testmod:crate","containerSize":216,"columns":18,"description":"A 家玩家实测"},
              {"containerId":"testmod:crate","containerSize":108,"columns":12,"description":"B 家玩家实测"},
              {"containerId":"testmod:descr_only","containerSize":54,"columns":9,"description":"甲"},
              {"containerId":"testmod:descr_only","containerSize":54,"columns":9,"description":"乙（措辞不同）"}
            ]}""";

        int loaded;
        try (var reader = new java.io.StringReader(json)) {
            loaded = ContainerRuleConfig.loadRules(reader, "bundled", "<merge test>");
        }

        // 两条唯一 ID 各注册一次（冲突的那条被丢弃）
        assertEquals(2, loaded, "只有 2 个不同 ID 应被注册");

        var crate = ContainerCompatibilityConfig.findRule(
            ResourceLocation.fromNamespaceAndPath("testmod", "crate")).orElseThrow();
        assertEquals(216, crate.containerSize(),
            "同 ID 数值冲突时应保留先出现的一条（先到先得）");
        assertEquals(18, crate.columns(), "列数同样取先出现的一条");

        var descrOnly = ContainerCompatibilityConfig.findRule(
            ResourceLocation.fromNamespaceAndPath("testmod", "descr_only")).orElseThrow();
        assertEquals(54, descrOnly.containerSize(),
            "仅描述不同 → 不算冲突，正常注册");
    }

    /** 构造一条标准矩形容器规则（与指令路径一致的形状） */
    private static ContainerRule buildRule(int size, int columns) {
        return ContainerRule.builder()
            .containerSize(size)
            .layoutType(ContainerLayoutType.RECTANGULAR_STANDARD)
            .columns(columns)
            .edgeBehavior(EdgeBehavior.INVALIDATE)
            .description("test " + columns + "x" + (size / columns))
            .build();
    }
}
