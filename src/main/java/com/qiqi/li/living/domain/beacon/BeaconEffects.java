package com.qiqi.li.living.domain.beacon;

import java.util.List;

/**
 * 活信标效果计算的纯逻辑（设计稿 §2.5「buff 数量 / 档位门控」）。
 *
 * <p>两个入口：{@link #feed}（喂锭：门控 + 加槽/升级）、{@link #activeEntries}
 * （生效矿石效果：{@code min(堆叠数, 已配且档位≤等级槽数)} 取前 N）。
 * 全部无 MC 副作用 ⇒ 可全量单测。</p>
 *
 * <p>满级再生<b>不在这里</b>：它是<b>等级的直接函数</b>（L4 ⇒ 再生 II，2026-11-08 用户拍板
 * 「满级无条件奖励，不强迎合原版『II 吃掉次槽』」）⇒ 调用方直接判
 * {@code level >= BeaconPyramid.MAX_LEVEL}，没有可算的档位。</p>
 */
public final class BeaconEffects {

    private BeaconEffects() {
    }

    /**
     * 喂锭判定 —— 返回新配置；{@code null} = 拒绝（不消耗锭）。
     *
     * <ul>
     *   <li>档位门控：{@code ore.unlockTier > level} ⇒ 拒（低于当前档位的锭喂了会被拒，
     *       设计稿 §2.5「等级门控矿石池」）；</li>
     *   <li>已在配置：已是 II ⇒ 拒；是 I 但 {@code level < 4} ⇒ 拒（<b>II 升级仅满级</b>，
     *       2026-10-16 定，对齐原版「主=副升 II」需 L4）；满级 ⇒ 升级 II；</li>
     *   <li>新矿石：5 槽满 ⇒ 拒；否则追加 I 级槽。</li>
     * </ul>
     */
    public static BeaconConfig feed(BeaconConfig config, OreType ore, int level) {
        if (ore == null || level <= 0) {
            return null;
        }
        if (ore.unlockTier > level) {
            return null;
        }
        if (config.contains(ore)) {
            if (config.isAmplified(ore)) {
                return null;   // 已是 II，无变化
            }
            if (level < 4) {
                return null;   // II 升级仅满级
            }
            return config.amplify(ore);
        }
        if (config.isFull()) {
            return null;       // 5 槽满
        }
        return config.add(ore);
    }

    /**
     * 待喂入菜单 —— 尚未配置进 5 槽的矿石（tooltip 引导行用，§2.6.1）。
     *
     * <p>保持 {@link OreType} 枚举序：枚举序 = 解锁档位非降序（有契约测试钉住），
     * 过滤后天然「当前可喂的排在前、锁定在后」，锁定行标「需 L{n}」即可读。</p>
     */
    public static List<OreType> feedSuggestions(BeaconConfig config) {
        return java.util.Arrays.stream(OreType.values())
            .filter(ore -> !config.contains(ore))
            .toList();
    }

    /**
     * 生效矿石效果 —— {@code min(堆叠数, 已配且档位≤等级的槽数)}，按配置顺序取前 N。
     *
     * <p>高阶配置在等级不足时休眠（不施加），等级回升后自动恢复（配置留在组件里）。</p>
     */
    public static List<BeaconConfig.Entry> activeEntries(int level, int stackCount, BeaconConfig config) {
        long eligible = config.entries().stream()
            .filter(e -> e.ore().unlockTier <= level)
            .count();
        int n = (int) Math.min(stackCount, eligible);
        return config.entries().stream()
            .filter(e -> e.ore().unlockTier <= level)
            .limit(n)
            .toList();
    }

    /** 效果放大器：II 级 = 1，I 级 = 0（与 {@code MobEffectInstance} 的 amplifier 语义一致）。 */
    public static int amplifier(BeaconConfig.Entry entry) {
        return entry.amplified() ? 1 : 0;
    }

    /**
     * buff 时长（tick）—— 照原版 {@code BeaconBlockEntity.applyEffects}：
     * {@code (9 + 等级×2) 秒 × 20}。每 80 tick 重刷一次 ⇒ 范围内永不掉效。
     */
    public static int buffDurationTicks(int level) {
        return (9 + level * 2) * 20;
    }
}
