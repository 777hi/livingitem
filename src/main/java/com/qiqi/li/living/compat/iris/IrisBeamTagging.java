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
 * 给光束 RenderType 打 Iris 的「方块实体」渲染段标签。
 *
 * <p>⚠️ 本类只在 Iris / Oculus 在装时经 {@link ModIris} 加载（直接引用
 * {@code net.irisshaders.iris.layer} 内部类，编译依赖 {@code maven.modrinth:iris}）；
 * 任何一步失败都降级为「返回原类型」，由门面兜底，绝不冒异常到渲染主循环。</p>
 *
 * <p>机制：Iris 的 {@code MixinBlockEntityRenderDispatcher} 会给<b>方块实体渲染期间</b>
 * 缓冲的每个 RenderType 包一层 {@code OuterWrappedRenderType}，其上的
 * {@link BlockEntityRenderStateShard} 在真正绘制时把 {@code renderStage} 统一值置为
 * {@code MC_RENDER_STAGE_BLOCK_ENTITIES} —— 光影包据此识别信标光束并按全亮发光体处理。
 * 本类给我们的光束 RenderType 包上<b>同一个 shard</b>，与原版信标同标签同批次。</p>
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
