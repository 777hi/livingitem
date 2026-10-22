package com.qiqi.li.living.container;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 容器键的<b>唯一解析入口</b>（2026-10-13）—— 「这个键是什么容器 / 属于谁 / 在哪」只在这里回答。
 *
 * <h3>⭐ 立此类的理由</h3>
 * <p>键是<b>字符串协议</b>（见 {@link ContainerIdentity#getContainerKey()} 与
 * {@link ContainerIdentity#getCacheKey()}），而消费方曾各自 {@code startsWith} / {@code substring}
 * 反推 ⇒ 反推规则不统一，2026-10-13 一次性暴露 3 处同源 bug（详见 {@link ContainerKind} 类注释）。</p>
 *
 * <p>⇒ <b>禁止在别处再写键形解析</b>（{@code startsWith("player_")} / {@code substring} + UUID 解析等）：
 * 需要判断种类用 {@link #kindOf}，需要玩家用 {@link #ownerOf}，需要坐标用 {@link #positionsOf}。
 * 新增键形 ⇒ 只改本类。<b>检查脚本</b> {@code tools/check_container_keys.py} 会拦住散落的 UUID 解析。</p>
 *
 * <h3>键形全集（6 种，见 {@link ContainerKind}）</h3>
 * <pre>
 *   player_&lt;uuid&gt;                    玩家背包
 *   player_&lt;uuid&gt;_ender_chest         玩家末影箱
 *   chest_x_y_z                       世界方块容器（大箱 = chest_x1_y1_z1_x2_y2_z2）
 *   container_&lt;dim&gt;_x_y_z             世界实体容器
 *   item_entity_&lt;entityUUID&gt;          掉落物容器
 *   entity_&lt;entityUUID&gt;                实体容器（船 / 矿车 / 带箱驴骡，2026-10-21）
 *   cuuid_&lt;containerUUID&gt;             物品形态容器（活背包，2026-10-22）
 * </pre>
 * <p>⚠️ 缓存键（{@link ContainerIdentity#getCacheKey()}）在世界容器键前多一段 {@code <维度>|} 前缀
 * ⇒ 本类所有入口<b>先剥前缀</b>，故<b>裸键与缓存键都能吃</b>（裸键不含 {@code |} ⇒ 原样）。</p>
 *
 * <p>⚠️ {@code cuuid_} 与 {@code container_} <b>前缀不同形</b>（{@code cu} vs {@code co}）——
 * 早先方案想用 {@code container_<uuid>}，会与 {@code container_<dim>_x_y_z} 撞
 * {@link #kindOf} 的分支（同名不同义 ⇒ 解析必然出错）⇒ 换前缀。</p>
 */
public final class ContainerKeys {

    /** 玩家容器前缀（背包与末影箱共用）。 */
    public static final String PLAYER_PREFIX = "player_";

    /** 末影箱后缀（与 {@link #PLAYER_PREFIX} 组合区分背包 / 末影箱）。 */
    public static final String ENDER_CHEST_SUFFIX = "_ender_chest";

    private static final String CHEST_PREFIX = "chest_";
    private static final String CONTAINER_PREFIX = "container_";
    private static final String ITEM_ENTITY_PREFIX = "item_entity_";
    private static final String ENTITY_PREFIX = "entity_";

    /**
     * 物品形态容器前缀（2026-10-22，活背包）—— {@code cu} 而不是 {@code co}：
     * 与 {@link #CONTAINER_PREFIX} 区分，避免 {@link #kindOf} 在两个分支间猜。
     */
    private static final String CUUID_PREFIX = "cuuid_";

    private ContainerKeys() {}

    // ============================================================
    // 种类
    // ============================================================

    /**
     * 判定容器种类（唯一入口）。无法识别的形态返回 {@code null}（调用方自行决定拒绝策略）。
     *
     * <p>⚠️ 顺序有意义：末影箱与背包<b>前缀相同</b>，必须先判后缀 —— 这正是 2026-10-13 那次
     * 事故的形态（消费方只判前缀 ⇒ 末影箱被当成背包）。</p>
     */
    public static ContainerKind kindOf(String containerKey) {
        if (containerKey == null) return null;
        String key = stripDimPrefix(containerKey);
        if (key.startsWith(PLAYER_PREFIX)) {
            return key.endsWith(ENDER_CHEST_SUFFIX) ? ContainerKind.ENDER_CHEST : ContainerKind.PLAYER_INV;
        }
        if (key.startsWith(CHEST_PREFIX) || key.startsWith(CONTAINER_PREFIX)) {
            return ContainerKind.WORLD_BLOCK;
        }
        if (key.startsWith(ITEM_ENTITY_PREFIX)) return ContainerKind.ITEM_ENTITY;
        if (key.startsWith(ENTITY_PREFIX)) return ContainerKind.ENTITY;
        if (key.startsWith(CUUID_PREFIX)) return ContainerKind.ITEM_FORM;
        return null;
    }

    // ============================================================
    // 归属（玩家）
    // ============================================================

    /**
     * 键是否属于某个玩家本人（背包 / 末影箱）；未知键形返回 {@code false}。
     *
     * <p>替代散落的 {@code key.startsWith("player_")} —— 那种写法把「末影箱」与「背包」
     * 混为一谈（前缀相同），是本类要消灭的反推模式。</p>
     */
    public static boolean isOwnedByPlayer(String containerKey) {
        ContainerKind kind = kindOf(containerKey);
        return kind != null && kind.isOwnedByPlayer();
    }

    /**
     * 解析玩家容器的所有者 UUID（背包 / 末影箱）；非玩家容器或形态异常返回 {@code null}。
     *
     * <p>唯一 UUID 解析点 —— 项目里曾有三份实现，其中 {@code LivingItemRuntimeSync} 那份
     * 没剥 {@code _ender_chest} 后缀 ⇒ {@code UUID.fromString} 抛异常被 catch 吞掉
     * ⇒ 末影箱<b>从不发包</b>（2026-10-13 修）。</p>
     */
    public static UUID ownerOf(String containerKey) {
        if (containerKey == null) return null;
        String key = stripDimPrefix(containerKey);
        if (!key.startsWith(PLAYER_PREFIX)) return null;
        String s = key.substring(PLAYER_PREFIX.length());
        if (s.endsWith(ENDER_CHEST_SUFFIX)) {
            s = s.substring(0, s.length() - ENDER_CHEST_SUFFIX.length());
        }
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    // ============================================================
    // 位置（世界容器）
    // ============================================================

    /**
     * 解析世界容器的方块坐标：大箱子返回<b>两段</b>；非世界容器或形态异常返回<b>空列表</b>。
     *
     * <p>覆盖 {@code chest_…}（可多段）与 {@code container_<dim>_…}（取末三段 —— 维度 id 里可能含
     * {@code _}，故不能按固定下标切）。</p>
     */
    public static List<BlockPos> positionsOf(String containerKey) {
        List<BlockPos> positions = new ArrayList<>(2);
        if (containerKey == null) return positions;
        String key = stripDimPrefix(containerKey);

        if (key.startsWith(CHEST_PREFIX)) {
            // chest_x1_y1_z1[_x2_y2_z2]：每 3 段一组
            String[] parts = key.substring(CHEST_PREFIX.length()).split("_");
            for (int i = 0; i + 2 < parts.length; i += 3) {
                BlockPos pos = parsePos(parts[i], parts[i + 1], parts[i + 2]);
                if (pos != null) positions.add(pos);
            }
        } else if (key.startsWith(CONTAINER_PREFIX)) {
            // container_<dim>_x_y_z：维度 id 可含 '_' ⇒ 取末三段
            String[] parts = key.substring(CONTAINER_PREFIX.length()).split("_");
            if (parts.length >= 3) {
                BlockPos pos = parsePos(parts[parts.length - 3], parts[parts.length - 2],
                    parts[parts.length - 1]);
                if (pos != null) positions.add(pos);
            }
        }
        return positions;
    }

    // ============================================================
    // 维度前缀（缓存键）
    // ============================================================

    /**
     * 取缓存键的维度前缀（{@code <dim>|…} 的 {@code <dim>} 部分）；裸键返回 {@code null}。
     * 形如 {@code minecraft:overworld}。
     */
    public static String dimPrefixOf(String containerKey) {
        if (containerKey == null) return null;
        int bar = containerKey.lastIndexOf('|');
        return bar >= 0 ? containerKey.substring(0, bar) : null;
    }

    /** 剥掉可选的 {@code <维度>|} 前缀（裸键不含 {@code |} ⇒ 原样返回）。 */
    static String stripDimPrefix(String containerKey) {
        int bar = containerKey.lastIndexOf('|');
        return bar >= 0 ? containerKey.substring(bar + 1) : containerKey;
    }

    private static BlockPos parsePos(String x, String y, String z) {
        try {
            return new BlockPos(Integer.parseInt(x), Integer.parseInt(y), Integer.parseInt(z));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
    // ============================================================
    // 实体（容器实体的 UUID）
    // ============================================================

    /**
     * 解析实体容器的实体 UUID（{@code entity_<uuid>}）；非实体容器或形态异常返回 {@code null}。
     *
     * <p>第二个 UUID 解析点（第一个是 {@link #ownerOf} 玩家容器）。设计稿
     * {@code docs/buffer/container-menu-introspection-topic.md} §2.2：实体容器键只依赖 UUID
     * ⇒ 实体移动不漂移（位置每 tick 从 {@code ServerLevel#getEntity(UUID)} 反查）。</p>
     */
    public static UUID entityOf(String containerKey) {
        if (containerKey == null) return null;
        String key = stripDimPrefix(containerKey);
        if (!key.startsWith(ENTITY_PREFIX)) return null;
        try {
            return UUID.fromString(key.substring(ENTITY_PREFIX.length()));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    // ============================================================
    // 容器 UUID（物品形态容器）
    // ============================================================

    /**
     * 解析物品形态容器的容器 UUID（{@code cuuid_<uuid>}）；非物品形态或形态异常返回 {@code null}。
     *
     * <p>第三个 UUID 解析点（前两个是 {@link #ownerOf} 玩家、{@link #entityOf} 实体）。
     * 2026-10-22 活背包：容器级数据按这个 UUID 存 SavedData ⇒ 形态变化时数据零搬运，
     * 只需让 UUID 本身跟着走（{@code ContainerHandoff}）。</p>
     */
    public static UUID containerUuidOf(String containerKey) {
        if (containerKey == null) return null;
        String key = stripDimPrefix(containerKey);
        if (!key.startsWith(CUUID_PREFIX)) return null;
        try {
            return UUID.fromString(key.substring(CUUID_PREFIX.length()));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    /** 构造物品形态容器键（{@code cuuid_<uuid>}）。 */
    public static String cuuidKey(UUID containerUuid) {
        return CUUID_PREFIX + containerUuid;
    }
}
