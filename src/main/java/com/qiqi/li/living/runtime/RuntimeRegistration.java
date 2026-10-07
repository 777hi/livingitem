package com.qiqi.li.living.runtime;

/**
 * 运行时数据机制注册入口（档 2：本包由 {@code living/domain/runtime/} 上移为 L2 机制层）。
 *
 * <p>本类登记两件事：</p>
 * <ol>
 *   <li><b>static 缓存清理</b> —— 客户端遥测缓存（跨存档不清会把上个存档的 tooltip
 *       数据带到新世界）；</li>
 *   <li><b>片段类型登记</b> —— ⚠️ 由各领域在自己的 {@code XxxRegistration} 里登记
 *       （本类<b>不认识任何领域</b>，见 {@link RuntimeSegmentRegistry} 的归属说明）。
 *       三处：{@code GeneratorSegment}（power）/ {@code HopperSegment}（hopper）/
 *       {@code FurnaceSegment}（furnace）。</li>
 * </ol>
 */
public final class RuntimeRegistration {

    private RuntimeRegistration() {}

    public static void register() {
        // ── static 缓存清理（登记点归属见 StaticCacheRegistry 类注释）──
        com.qiqi.li.living.util.StaticCacheRegistry.onClientLogout(LivingItemClientCache::clear);
    }
}
