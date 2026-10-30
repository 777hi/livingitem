package com.qiqi.li.living.compat.iris;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.irisshaders.iris.layer.BlockEntityRenderStateShard;
import net.irisshaders.iris.layer.OuterWrappedRenderType;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BeaconRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 给光束 RenderType 添加 Iris 的方块实体阶段标签。
 *
 * <p>仅由 {@link ModIris} 在探测到加载器时使用；依赖 Iris 内部类，版本变化可能降级。
 * 包装实例按原 RenderType 复用，标签不改变顶点颜色或 alpha。</p>
 */
final class IrisBeamTagging {

    private static final Logger LOGGER = LoggerFactory.getLogger("LivingItem/Iris");

    /** 包装名 —— Iris 内部仅用于调试显示。 */
    private static final String WRAP_NAME = "living_item:beacon_beam";

    /**
     * 原类型 → 包后类型缓存：{@code RenderType.beaconBeam} 的两个原类型是 memoized 单例，
     * 缓存让全部颜色分段 / 全帧共享同一份包装实例（不缓存则每帧每段各造一个新键）。
     */
    private static final Map<RenderType, RenderType> WRAPPED = new ConcurrentHashMap<>();

    private IrisBeamTagging() {}

    /** 包装源：所有经它 {@code getBuffer} 的 RenderType 先打标签再转交真实源。 */
    static MultiBufferSource wrapSource(MultiBufferSource source) {
        return type -> source.getBuffer(wrap(type));
    }

    /**
     * 预链接自检：用「探针源」真实走一遍完整链路（本类加载 + lambda 合成方法链接 +
     * Iris 两个内部类解析 + 包装调用），任何一步失败都由门面接住并永久降级 ——
     * 不让 {@code NoClassDefFoundError} 穿透到渲染主循环。
     */
    static boolean selfCheck() {
        MultiBufferSource probe = wrapSource(type -> null);
        probe.getBuffer(RenderType.beaconBeam(BeaconRenderer.BEAM_LOCATION, false));
        return true;
    }

    private static RenderType wrap(RenderType type) {
        try {
            return WRAPPED.computeIfAbsent(type, IrisBeamTagging::wrapOnce);
        } catch (Throwable t) {
            LOGGER.warn("[ModIris] RenderType 包装异常，本次退回无标签: {}", t.toString());
            return type;
        }
    }

    private static RenderType wrapOnce(RenderType type) {
        try {
            return OuterWrappedRenderType.wrapExactlyOnce(WRAP_NAME, type, BlockEntityRenderStateShard.INSTANCE);
        } catch (Throwable t) {
            // ⚠️ 失败结果缓存为「原类型」⇒ 稳定降级，不再重试也不再刷日志
            LOGGER.warn("[ModIris] OuterWrappedRenderType 包装失败（Iris 版本变化?），该类型降级为无标签: {}",
                t.toString());
            return type;
        }
    }
}
