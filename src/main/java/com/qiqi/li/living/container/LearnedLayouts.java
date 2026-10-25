package com.qiqi.li.living.container;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.qiqi.li.logging.ModLog;

import net.minecraft.resources.ResourceLocation;

/**
 * <b>自动学到的容器布局</b>（2026-10-25，Tier 1.5）。
 *
 * <h3>它是什么</h3>
 * <p>玩家打开容器 GUI 时，服务端从那个菜单的<b>容器槽坐标</b>读出真实行列
 * （见 {@code ContainerLayoutLearner}），按 {@code (容器 id, 槽数)} 存到这里。
 * {@code SimpleContainerContext#getWidth()} 在规则表（Tier 1）落空后查它，
 * 排在原版家族（Tier 2）与加权启发式（Tier 3）之前。</p>
 *
 * <h3>为什么单独一个文件，不和规则表合</h3>
 * <ul>
 *   <li><b>provenance 即位置</b> —— 内置（jar 内）/ 玩家（register）/ 自动学（本文件）
 *       三种来源分三个地方，不必在数据里再打标记；</li>
 *   <li>玩家<b>删掉这个文件就重置全部学习</b>；</li>
 *   <li>格式损坏也不会污染玩家手写的规则。</li>
 * </ul>
 *
 * <h3>为什么不登记「服务端停止时清空」</h3>
 * <p>{@code StaticCacheRegistry} 的约定是「跨存档会串的静态缓存要在自己类里登记清理」。
 * 本类<b>刻意不登记</b>：容器布局是<b>已装模组的属性</b>，不是某个存档的属性 ——
 * 同一个 JVM 里换存档，模组没变，学到的布局依然成立。清了反而每次都要重学一遍。
 * （对比：区块缓存、容器级数据都是世界相关的，那些必须清。）</p>
 *
 * <h3>写入策略：值变化才写</h3>
 * <p>学习事件极稀有（只有「打开一个规则表答不上、且值变了」的容器才触发，
 * 一个服务器一生几十次）⇒ 不加 {@code dirty} 标记 + 停止时兜底 flush：
 * 攒批省不下什么，还多一个状态和「停止时写失败怎么办」的分支。
 * 崩溃最多丢正在写的那条，下次开门重新学，自愈。<b>写失败会打日志</b>（I-C6：降级可观测）。</p>
 */
public final class LearnedLayouts {

    private static final String FILE_NAME = "learned_layouts.json";

    /** {@code "容器id|槽数" → 列数}。 */
    private static final Map<String, Integer> LEARNED = new ConcurrentHashMap<>();

    /** 上次写入失败时，同值与歧义拒绝分支也必须重试整表保存。 */
    private static boolean diskStale;

    /**
     * 学过但<b>自相矛盾</b>的键（同一个键先后学到两个不同列数）—— 有歧义就不再写。
     *
     * <p>宁可继续猜，也不能把「已经确定的」变成「会变的」。</p>
     */
    private static final java.util.Set<String> AMBIGUOUS = ConcurrentHashMap.newKeySet();

    @Nullable
    private static Path configFile;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private LearnedLayouts() {}

    /** 记下配置文件位置（与 {@code ContainerRuleConfig} 同一个目录）。 */
    public static void init(Path configDir) {
        configFile = configDir.resolve("living_item").resolve(FILE_NAME);
    }

    /** 从磁盘读回；文件不存在 / 损坏 ⇒ 静默按空表开始（不崩，I-C6 由调用方日志兜）。 */
    public static void load() {
        if (configFile == null) return;
        if (!Files.exists(configFile)) return;
        try {
            String json = Files.readString(configFile);
            Store store = GSON.fromJson(json, Store.class);
            LEARNED.clear();
            AMBIGUOUS.clear();
            if (store != null) {
                // 兼容"裸 Map"旧格式 / 手写文件：只有 layouts，没有 ambiguous
                Map<String, Integer> parsed = store.layouts != null
                    ? store.layouts : GSON.fromJson(json, MAP_TYPE);
                if (parsed != null) {
                    parsed.forEach((k, v) -> {
                        if (v != null && v > 0) LEARNED.put(k, v);
                    });
                }
                if (store.ambiguous != null) {
                    AMBIGUOUS.addAll(store.ambiguous);
                    LEARNED.keySet().removeAll(AMBIGUOUS);
                }
            }
            diskStale = false;
            ModLog.CONTAINER.debug("[布局学习] 已读回 {} 条（歧义 {} 个）",
                LEARNED.size(), AMBIGUOUS.size());
        } catch (IOException | RuntimeException e) {
            ModLog.CONTAINER.error("[布局学习] 读取 {} 失败，本次按空表开始：{}",
                configFile.getFileName(), e.toString());
        }
    }

    /** 查学到的列数；{@code 0} = 没学到。 */
    public static int columnsOf(ResourceLocation containerId, int size) {
        Integer columns = LEARNED.get(key(containerId, size));
        return columns == null ? 0 : columns;
    }

    /** 只看不改（给冲突检测用）。 */
    @Nullable
    public static Integer peek(ResourceLocation containerId, int size) {
        return LEARNED.get(key(containerId, size));
    }

    /**
     * 记一条学习结果。
     *
     * @return {@code true} = 值变了并已落盘；{@code false} = 无需写（值相同 / 有歧义）
     */
    public static boolean learn(ResourceLocation containerId, int size, int columns) {
        if (columns <= 0) return false;
        String key = key(containerId, size);
        if (AMBIGUOUS.contains(key)) {
            if (diskStale) save();
            return false;
        }
        Integer prev = LEARNED.get(key);
        // 值没变就不写盘 —— 但磁盘处于"没写成功"状态时必须重试，否则自愈路径是空的
        if (prev != null && prev == columns && !diskStale) return false;
        LEARNED.put(key, columns);
        save();
        return true;
    }

    /** 标记「这个键自相矛盾」—— 此后不再接受它的学习结果。 */
    public static void markAmbiguous(ResourceLocation containerId, int size) {
        String key = key(containerId, size);
        AMBIGUOUS.add(key);
        LEARNED.remove(key);
        save();   // ⚠️ 必须落盘：只改内存的话，重启后磁盘旧值会复活、歧义标记也会丢
        ModLog.CONTAINER.warn("[布局学习] {} ({} 槽) 先后学到两个不同列数 ⇒ 标记歧义，此后不再学它",
            containerId, size);
    }

    /** 测试用：清空内存态（不动磁盘文件）。 */
    public static void clearForTest() {
        LEARNED.clear();
        AMBIGUOUS.clear();
        configFile = null;
        diskStale = false;
    }

    private static void save() {
        if (configFile == null) return;
        try {
            Files.createDirectories(configFile.getParent());
            Store store = new Store();
            store.layouts = LEARNED;
            store.ambiguous = AMBIGUOUS;
            Files.writeString(configFile, GSON.toJson(store));
            diskStale = false;
        } catch (IOException | RuntimeException e) {
            // 写失败不静默（I-C6）；并置"磁盘陈旧"⇒ 下次同值学习会重试写盘
            diskStale = true;
            ModLog.CONTAINER.error("[布局学习] 写 {} 失败（恢复后会重试）：{}",
                configFile.getFileName(), e.toString());
        }
    }

    /** 磁盘格式：布局表 + 歧义键表。 */
    private static final class Store {
        Map<String, Integer> layouts;
        java.util.Set<String> ambiguous;
    }

    private static String key(ResourceLocation containerId, int size) {
        return containerId + "|" + size;
    }

    private static final com.google.gson.reflect.TypeToken<Map<String, Integer>> MAP_TYPE =
        new com.google.gson.reflect.TypeToken<>() {};
}
