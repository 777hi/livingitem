package com.qiqi.li.living.domain.beacon;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonParser;
import com.qiqi.li.living.runtime.LivingItemClientCache;
import com.qiqi.li.living.runtime.RuntimeSegments;

import net.minecraft.ChatFormatting;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.util.StringDecomposer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;

/**
 * 活信标 tooltip 文本层守卫（2026-11-08 用户定：条目三态配色 + 满级再生改「账本末尾授予行」）。
 *
 * <p>钉的是<b>选键、落位与状态行分母口径</b>，不是颜色字面量——颜色住 lang（{@link #langPlaceholdersMatchRenderedArgs}
 * 守双语键存在与占位符对齐），代码只负责「哪个状态用哪个键」。三态口径同待喂入菜单：
 * 生效 / 休眠 / <b>遥测缺失 ⇒ 中性不猜</b>。</p>
 *
 * <p>照 {@code LivingPistonTooltipTest} 手法：{@link LivingItemClientCache} 塞遥测 +
 * 收集 {@code Consumer<Component>}，断言 {@link TranslatableContents} 的 key 与 args。</p>
 */
class BeaconTooltipTest {

    private static final String PREFIX = "tooltip.livingitem.beacon.";
    private static final Pattern PLACEHOLDER = Pattern.compile("%[sd]");

    private final BeaconFunction function = new BeaconFunction();

    @AfterEach
    void clearTooltipState() {
        LivingItemClientCache.clearCurrentTooltipData();
    }

    private static ItemStack beacon(int count, BeaconConfig config) {
        ItemStack stack = new ItemStack(Items.BEACON, count);
        BeaconConfig.set(stack, config);
        return stack;
    }

    private static BeaconConfig config(OreType... ores) {
        BeaconConfig cfg = BeaconConfig.EMPTY;
        for (OreType ore : ores) {
            cfg = cfg.add(ore);
        }
        return cfg;
    }

    private static BeaconConfig fullConfig() {
        return config(OreType.IRON, OreType.GOLD, OreType.DIAMOND, OreType.EMERALD, OreType.NETHERITE);
    }

    /** 柱高/列存在性只喂图形层，文本层不读 ⇒ 全 0 + 全存在即可。 */
    private static BeaconSegment.BeaconRuntime runtime(int level) {
        boolean[] exists = new boolean[BeaconPyramid.COLUMNS];
        Arrays.fill(exists, true);
        return new BeaconSegment.BeaconRuntime(level, new long[BeaconPyramid.COLUMNS], exists);
    }

    private List<Component> tooltip(ItemStack stack, BeaconSegment.BeaconRuntime rt) {
        LivingItemClientCache.setCurrentTooltipData(rt == null
            ? RuntimeSegments.EMPTY
            : RuntimeSegments.EMPTY.with(BeaconSegment.INSTANCE, rt));
        List<Component> lines = new ArrayList<>();
        function.addToTooltip(Item.TooltipContext.EMPTY, lines::add, TooltipFlag.NORMAL, stack);
        return lines;
    }

    private static List<String> keys(List<Component> lines) {
        List<String> out = new ArrayList<>();
        for (Component line : lines) {
            if (line.getContents() instanceof TranslatableContents contents
                && contents.getKey().startsWith(PREFIX)) {
                out.add(contents.getKey());
            }
        }
        return out;
    }

    private static TranslatableContents contents(List<Component> lines, String key) {
        return lines.stream().map(Component::getContents)
            .filter(TranslatableContents.class::isInstance)
            .map(TranslatableContents.class::cast)
            .filter(c -> c.getKey().equals(key))
            .findFirst().orElseThrow(() -> new AssertionError("Missing tooltip key: " + key));
    }

    /** 休眠原因 / 升 II 指令是条目行的<b>末位 arg</b>（子 Component），不是独立行。 */
    private static List<String> suffixKeys(List<Component> lines, String entryKey) {
        List<String> out = new ArrayList<>();
        for (Component line : lines) {
            if (line.getContents() instanceof TranslatableContents contents
                && contents.getKey().equals(entryKey)) {
                Object[] args = contents.getArgs();
                if (args.length > 0 && args[args.length - 1] instanceof Component suffix
                    && suffix.getContents() instanceof TranslatableContents sc) {
                    out.add(sc.getKey());
                }
            }
        }
        return out;
    }

    /** 取指定 key 的所有行（顺序不变）。 */
    private static List<Component> linesWithKey(List<Component> lines, String key) {
        List<Component> out = new ArrayList<>();
        for (Component line : lines) {
            if (line.getContents() instanceof TranslatableContents contents && contents.getKey().equals(key)) {
                out.add(line);
            }
        }
        return out;
    }

    /** 行级颜色（null = 未染色，即中性的白）。 */
    private static Integer lineColor(Component line) {
        return line.getStyle().getColor() == null ? null : line.getStyle().getColor().getValue();
    }

    /** 第 entryIndex 条条目的末位 arg（休眠原因 / 升 II 指令）自身颜色。 */
    private static Integer suffixColor(List<Component> lines, int entryIndex) {
        Component line = linesWithKey(lines, PREFIX + "config_entry").get(entryIndex);
        Object[] args = assertInstanceOf(TranslatableContents.class, line.getContents()).getArgs();
        Component suffix = (Component) args[args.length - 1];
        return suffix.getStyle().getColor() == null ? null : suffix.getStyle().getColor().getValue();
    }

    @Test
    @DisplayName("① 条目三态：生效整行青、休眠整行暗灰，休眠两因与红后缀不变")
    void entryKeyFollowsActiveState() {
        var lines = tooltip(beacon(2, fullConfig()), runtime(2));
        var entries = linesWithKey(lines, PREFIX + "config_entry");
        assertEquals(5, entries.size());

        assertEquals(ChatFormatting.AQUA.getColor(), lineColor(entries.get(0)), "堆叠 2 ⇒ 前 2 条生效整行青");
        assertEquals(ChatFormatting.AQUA.getColor(), lineColor(entries.get(1)));
        for (int i = 2; i < entries.size(); i++) {
            assertEquals(ChatFormatting.DARK_GRAY.getColor(), lineColor(entries.get(i)),
                "第 " + (i + 1) + " 条休眠应整行暗灰");
        }

        var reasons = suffixKeys(lines, PREFIX + "config_entry");
        assertEquals(2, reasons.stream().filter(k -> k.equals(PREFIX + "sleep_ingot")).count(),
            "抗性/跳跃：信标堆叠数不足");
        assertEquals(1, reasons.stream().filter(k -> k.equals(PREFIX + "sleep_level")).count(),
            "力量：档位不足（需 L3）");
        assertEquals(ChatFormatting.RED.getColor(), suffixColor(lines, 2), "红后缀不被行色盖掉");
    }

    @Test
    @DisplayName("② 遥测缺失 ⇒ 中性无色，不猜生效/休眠，也不画再生")
    void missingTelemetryStaysNeutral() {
        var lines = tooltip(beacon(2, config(OreType.IRON, OreType.GOLD, OreType.DIAMOND)), null);
        var keys = keys(lines);
        var entries = linesWithKey(lines, PREFIX + "config_entry");

        assertEquals(3, entries.size());
        entries.forEach(entry -> assertNull(lineColor(entry), "遥测缺失 ⇒ 不染色（不猜生效与否）"));
        assertTrue(suffixKeys(lines, PREFIX + "config_entry").isEmpty(), "遥测缺失 ⇒ 不写休眠原因");
        assertFalse(keys.contains(PREFIX + "level"), "遥测缺失不画状态行");
        assertTrue(keys.stream().noneMatch(k -> k.startsWith(PREFIX + "regen")));
    }

    @Test
    @DisplayName("③ 满级再生：授予行在账本末尾、待喂入菜单之前，效果名取原版")
    void regenGrantSitsAtLedgerEnd() {
        var lines = tooltip(beacon(5, config(OreType.IRON, OreType.GOLD, OreType.DIAMOND)),
            runtime(4));
        var keys = keys(lines);

        int lastEntry = keys.lastIndexOf(PREFIX + "config_entry");
        int grant = keys.indexOf(PREFIX + "regen.ii");
        int feed = keys.indexOf(PREFIX + "feed_title");
        assertTrue(lastEntry >= 0, "前提：得有生效条目");
        assertTrue(grant > lastEntry, "授予行必须在最后一条矿石条目之后（账本末尾）");
        assertTrue(feed > grant, "授予行必须在待喂入菜单之前");
        assertFalse(keys.contains(PREFIX + "regen"), "旧包装键已废（授予行自带颜色）");
        assertTrue(grant > keys.indexOf(PREFIX + "config_title"), "授予行不再挂在等级行下方");
        assertEquals(ChatFormatting.AQUA.getColor(),
            lineColor(linesWithKey(lines, PREFIX + "regen.ii").get(0)), "授予行整行青");
        assertEquals(ChatFormatting.GREEN.getColor(), suffixColor(lines, 0), "满级未加倍 ⇒ 绿色升 II 指令");

        Object[] args = contents(lines, PREFIX + "regen.ii").getArgs();
        assertEquals(1, args.length);
        assertEquals("effect.minecraft.regeneration",
            assertInstanceOf(TranslatableContents.class, ((Component) args[0]).getContents()).getKey(),
            "效果名走原版键 ⇒ 与玩家 HUD 上的名字一致，不在 lang 里硬编码");
    }

    @Test
    @DisplayName("④ 非满级 ⇒ 不画再生行（满级才有，画了是噪音）")
    void noRegenBelowMaxLevel() {
        var keys = keys(tooltip(beacon(5, config(OreType.IRON, OreType.GOLD)), runtime(3)));
        assertTrue(keys.stream().noneMatch(k -> k.startsWith(PREFIX + "regen")));
    }

    @Test
    @DisplayName("⑤ 满级恒给再生 II：加倍 / 无空槽 / 满配都不影响（2026-11-08 无条件奖励）")
    void regenUnconditionalAtMaxLevel() {
        // 5 槽全配 + 金锭加倍 ⇒ 承载位满且有加倍 —— 旧三档会判「无」，新规则恒为 II
        var lines = tooltip(beacon(5, fullConfig().amplify(OreType.GOLD)), runtime(4));
        var keys = keys(lines);

        assertTrue(keys.contains(PREFIX + "regen.ii"));
        assertFalse(keys.contains(PREFIX + "regen.i"), "旧两档键已删");
        assertFalse(keys.contains(PREFIX + "regen.none"), "「无」档已删");
        assertTrue(keys.indexOf(PREFIX + "regen.ii") > keys.lastIndexOf(PREFIX + "config_entry"),
            "授予行仍落在账本末尾");
        assertEquals(ChatFormatting.AQUA.getColor(),
            lineColor(linesWithKey(lines, PREFIX + "regen.ii").get(0)), "授予行整行青");
    }

    @Test
    @DisplayName("⑥ 未激活（L0）⇒ 账本折叠一行，无条目键、无再生行")
    void inactiveCollapsesLedger() {
        var keys = keys(tooltip(beacon(1, config(OreType.IRON, OreType.GOLD)), runtime(0)));

        assertTrue(keys.contains(PREFIX + "config_inactive"));
        assertTrue(keys.stream().noneMatch(k -> k.startsWith(PREFIX + "config_entry")));
        assertTrue(keys.stream().noneMatch(k -> k.startsWith(PREFIX + "regen")));
    }

    @Test
    @DisplayName("⑦ 满级空配置 ⇒ 授予行照画（再生不占槽、不依赖矿石配置）")
    void regenGrantedEvenWithEmptyConfig() {
        var keys = keys(tooltip(beacon(1, BeaconConfig.EMPTY), runtime(4)));

        assertTrue(keys.contains(PREFIX + "config_empty"));
        assertTrue(keys.contains(PREFIX + "regen.ii"));
    }

    @Test
    @DisplayName("⑧ lang 双语：渲染出的每个键都存在，且占位符数 == 代码参数个数")
    void langPlaceholdersMatchRenderedArgs() throws IOException {
        Map<String, Integer> emitted = new HashMap<>();
        collect(emitted, tooltip(beacon(2, config(OreType.IRON, OreType.GOLD, OreType.DIAMOND)), null));
        collect(emitted, tooltip(beacon(2, fullConfig()), runtime(2)));
        collect(emitted, tooltip(beacon(2, config(OreType.IRON, OreType.GOLD)), runtime(2)));
        collect(emitted, tooltip(beacon(5, config(OreType.IRON, OreType.GOLD, OreType.DIAMOND)),
            runtime(4)));
        collect(emitted, tooltip(beacon(5, fullConfig()), runtime(4)));
        collect(emitted, tooltip(beacon(5, fullConfig().amplify(OreType.GOLD)), runtime(4)));
        collect(emitted, tooltip(beacon(1, BeaconConfig.EMPTY), runtime(0)));
        assertTrue(emitted.containsKey(PREFIX + "config_entry"), "前提：采样必须覆盖三态与满级授予行");
        assertTrue(emitted.containsKey(PREFIX + "regen.ii"));
        assertTrue(emitted.containsKey(PREFIX + "sleep_level"), "休眠原因住在条目 arg 里，采样必须递归");
        assertTrue(emitted.containsKey(PREFIX + "feed_entry_locked"));

        for (String language : List.of("zh_cn", "en_us")) {
            try (var stream = getClass().getResourceAsStream("/assets/living_item/lang/" + language + ".json")) {
                assertNotNull(stream, language);
                var json = JsonParser.parseString(
                    new String(stream.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                assertFalse(json.has(PREFIX + "regen"), language + ": 旧再生包装键应已删除");
                assertFalse(json.has(PREFIX + "regen.i"), language + ": 旧 I 档键应已删除");
                assertFalse(json.has(PREFIX + "regen.none"), language + ": 「无」档键应已删除");
                emitted.forEach((key, argCount) -> {
                    assertTrue(json.has(key), language + " 缺键 " + key);
                    String template = json.get(key).getAsString();
                    assertFalse(template.isBlank(), language + " 空文案 " + key);
                    assertEquals(argCount.intValue(), placeholders(template),
                        language + " 占位符数与代码参数不符（原版会把整条模板当纯文本渲染）: " + key);
                });
            }
        }
    }

    @Test
    @DisplayName("⑨ 状态行分母 = 承载位 min(堆叠数, 5)：不裸用堆叠数、也不用已配槽数")
    void levelLineDenominatorIsCarryCapacity() {
        // 堆 10 配 5 ⇒ 5/5：裸堆叠数会虚报成 5/10（生效数恒 ≤ 已配槽数 ≤ 5，那 5 个空位不存在）
        assertArrayEquals(new int[]{4, 5, 5},
            levelArgs(tooltip(beacon(10, fullConfig()), runtime(4))));
        // 堆 5 配 3 ⇒ 3/5：旧口径 config.size() 会显示 3/3，藏掉「还能再喂 2 种」与「加信标」这个杠杆
        assertArrayEquals(new int[]{4, 3, 5},
            levelArgs(tooltip(beacon(5, config(OreType.IRON, OreType.GOLD, OreType.DIAMOND)),
                runtime(4))));
        // 堆 2 配 5 ⇒ 2/2：承载位用满，另 3 条为何休眠由账本逐行说明（后缀「需 3 个 / 需 4 个 / 需 L3」）
        assertArrayEquals(new int[]{2, 2, 2},
            levelArgs(tooltip(beacon(2, fullConfig()), runtime(2))));
        // 堆 5 配 5 但 L1 ⇒ 2/5：只有铁/金档位合格，分母仍是承载位而不是合格槽数
        assertArrayEquals(new int[]{1, 2, 5},
            levelArgs(tooltip(beacon(5, fullConfig()), runtime(1))));
    }

    /** 状态行三个参数：等级 / 生效条数 / 承载位（数字是绿色 Component，故从文本取值）。 */
    private static int[] levelArgs(List<Component> lines) {
        Object[] args = contents(lines, PREFIX + "level").getArgs();
        assertEquals(3, args.length);
        return new int[]{
            Integer.parseInt(((Component) args[0]).getString()),
            Integer.parseInt(((Component) args[1]).getString()),
            Integer.parseInt(((Component) args[2]).getString())};
    }

    @Test
    @DisplayName("⑩ 渲染级取色：§ 不跨占位符 ⇒ 颜色必须真的落到参数文本上（实机踩坑守卫）")
    void renderedColorsReachArgumentText() {
        Language old = Language.getInstance();
        Language.inject(modLang());
        try {
            // 复现实机反馈那一摞：堆 4 · 配钻石+下界合金 · L4 ⇒ 2 条生效 + 再生 II + 菜单剩 3 种
            var lines = tooltip(beacon(4, config(OreType.DIAMOND, OreType.NETHERITE)),
                runtime(4));

            Component level = linesWithKey(lines, PREFIX + "level").get(0);
            assertEquals(ChatFormatting.GRAY.getColor(), renderedColor(level, "等级:"), "标签灰");
            assertEquals(ChatFormatting.GREEN.getColor(), renderedColor(level, "4 "), "等级数字绿");
            assertEquals(ChatFormatting.GREEN.getColor(), renderedColor(level, "2/4"), "生效数绿");

            var entries = linesWithKey(lines, PREFIX + "config_entry");
            // 注入的语言表只有本模组的键 ⇒ 效果 / 物品名渲染成原版键字符串，正好当稳定的取样锚点
            assertEquals(ChatFormatting.AQUA.getColor(), renderedColor(entries.get(0), "effect.minecraft.resistance"),
                "生效条目：效果名是参数文本，必须真变青");
            assertEquals(ChatFormatting.GRAY.getColor(), renderedColor(entries.get(0), "item.minecraft.diamond"),
                "矿石名压成灰（元信息退后，同 mock），不是跟着行色变青");
            assertEquals(ChatFormatting.GREEN.getColor(), renderedColor(entries.get(0), "→ 再喂 1 锭升 II"),
                "后缀自绿，不被行色覆盖");

            Component grant = linesWithKey(lines, PREFIX + "regen.ii").get(0);
            assertEquals(ChatFormatting.AQUA.getColor(), renderedColor(grant, "effect.minecraft.regeneration"),
                "授予行效果名青");
            assertEquals(ChatFormatting.AQUA.getColor(), renderedColor(grant, "满级授予"), "授予说明随行色");

            Component feed = linesWithKey(lines, PREFIX + "feed_entry").get(0);
            assertEquals(ChatFormatting.GREEN.getColor(), renderedColor(feed, "item.minecraft.iron_ingot"),
                "可喂项整行绿");
            assertEquals(ChatFormatting.GREEN.getColor(), renderedColor(feed, "→"), "箭头随行色");

            // 休眠行：整行暗灰，红后缀里的数字也必须红（%d 继承后缀自身的行色）
            var dormant = tooltip(beacon(2, fullConfig()), runtime(2));
            Component sleep = linesWithKey(dormant, PREFIX + "config_entry").get(4);
            assertEquals(ChatFormatting.DARK_GRAY.getColor(), renderedColor(sleep, "effect.minecraft.strength"),
                "休眠整行暗灰");
            assertEquals(ChatFormatting.RED.getColor(), renderedColor(sleep, "需 L3"), "休眠原因红");
            assertEquals(ChatFormatting.RED.getColor(), renderedColor(sleep, "3"), "档位数字不被吞成白色");
            assertEquals(ChatFormatting.DARK_GRAY.getColor(),
                renderedColor(sleep, "item.minecraft.netherite_ingot"), "休眠行矿石名与整行同色（不跳出来）");

            // 档位锁定的待喂入项：整行暗灰（含原因里的数字）
            var gated = tooltip(beacon(2, config(OreType.IRON, OreType.GOLD)), runtime(2));
            Component locked = linesWithKey(gated, PREFIX + "feed_entry_locked").get(0);
            assertEquals(ChatFormatting.DARK_GRAY.getColor(),
                renderedColor(locked, "item.minecraft.netherite_ingot"), "锁定项暗灰");
            assertEquals(ChatFormatting.DARK_GRAY.getColor(), renderedColor(locked, "需 L3"), "锁定原因随行色");
        } finally {
            Language.inject(old);
        }
    }

    /**
     * 只装本模组 zh_cn 的语言表：效果 / 物品名会渲染成原版键字符串（测试里不需要它们的译文），
     * 正好当取样锚点。{@link Language#inject} 是全局状态，用完必须还原。
     */
    private static Language modLang() {
        Map<String, String> map = new HashMap<>();
        try (var ours = BeaconTooltipTest.class.getResourceAsStream("/assets/living_item/lang/zh_cn.json")) {
            assertNotNull(ours, "mod zh_cn");
            Language.loadFromJson(ours, map::put);
        } catch (IOException ex) {
            throw new java.io.UncheckedIOException(ex);
        }
        return new Language() {
            @Override public String getOrDefault(String key, String def) { return map.getOrDefault(key, def); }
            @Override public boolean has(String id) { return map.containsKey(id); }
            @Override public boolean isDefaultRightToLeft() { return false; }
            @Override public net.minecraft.util.FormattedCharSequence getVisualOrder(
                net.minecraft.network.chat.FormattedText text) {
                throw new UnsupportedOperationException("取色走 StringDecomposer，不需要 visual order");
            }
        };
    }

    /** 模拟客户端渲染到屏幕：返回 needle 第一个字符实际画出来的颜色。 */
    private static Integer renderedColor(Component line, String needle) {
        StringBuilder text = new StringBuilder();
        List<Integer> colors = new ArrayList<>();
        StringDecomposer.iterateFormatted(line, Style.EMPTY, (pos, style, cp) -> {
            text.appendCodePoint(cp);
            colors.add(style.getColor() == null ? null : style.getColor().getValue());
            return true;
        });
        int at = text.indexOf(needle);
        assertTrue(at >= 0, "渲染文本里找不到「" + needle + "」：\n" + text);
        return colors.get(at);
    }

    private static void collect(Map<String, Integer> emitted, List<Component> lines) {
        for (Component line : lines) {
            collectComponent(emitted, line);
        }
    }

    /** 递归到 arg 里的子 Component —— 休眠原因 / 升 II 指令 / 待喂入项都可能是嵌套键。 */
    private static void collectComponent(Map<String, Integer> emitted, Component component) {
        if (!(component.getContents() instanceof TranslatableContents contents)) {
            return;
        }
        if (contents.getKey().startsWith(PREFIX)) {
            emitted.put(contents.getKey(), contents.getArgs().length);
        }
        for (Object arg : contents.getArgs()) {
            if (arg instanceof Component nested) {
                collectComponent(emitted, nested);
            }
        }
    }

    private static int placeholders(String template) {
        Matcher matcher = PLACEHOLDER.matcher(template);
        int n = 0;
        while (matcher.find()) {
            n++;
        }
        return n;
    }
}