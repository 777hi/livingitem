package com.qiqi.li.living.compat.iris;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.client.renderer.MultiBufferSource;
import net.neoforged.fml.ModList;

/**
 * 活物品与 Iris / Oculus（光影加载器）的集成门面。
 *
 * <p>唯一能力：{@link #tagBeamSource} —— 给光束绘制源包一层「方块实体」渲染段标签。
 * 背景（2026-10-30 实证，Iris 1.8.12 源码）：光影包靠 {@code renderStage ==
 * MC_RENDER_STAGE_BLOCK_ENTITIES} 认信标光束（Iris 给<b>方块实体渲染器</b>路径缓冲的
 * 每个 RenderType 包 {@code OuterWrappedRenderType} + BE shard），我们的光束挂在
 * {@code RenderLevelStageEvent} 里画 ⇒ 无标签 ⇒ 光影包按不明几何处理，判错即丢弃
 * ⇒ 光束不可见（原版信标有标签所以正常）。包装后两条路径在光影眼里完全同类。</p>
 *
 * <p>Iris 未加载时一切原样返回，零参与；集成实现委托给 {@link IrisBeamTagging}
 * （只在 Iris 在装时被加载，直接引用 Iris 内部类）。加载失败一律降级为无标签，
 * 绝不让 {@link LinkageError} 穿透到渲染主循环。</p>
 */
public final class ModIris {

    private static final Logger LOGGER = LoggerFactory.getLogger("LivingItem/Iris");

    /** iris 与 oculus 都探：Oculus 是 Iris 的移植，包名类名一致，运行时同样能接上。 */
    private static Boolean loaded;

    /** 标签类预链接自检结果（null = 未探测）。 */
    private static Boolean taggingUsable;

    private ModIris() {}

    /**
     * 光束绘制源：Iris 在装 ⇒ 返回给 RenderType 打「方块实体」标签的包装源；
     * 否则（含 Iris 在装但包装类不可用）原样返回。
     *
     * <p>⚠️ 只把「画光束几何」的调用经此源发出；{@code endBatch} 等显式 flush
     * 必须仍走原 bufferSource（Iris 批处理下显式 flush 本就被禁用，见
     * {@code FullyBufferedMultiBufferSource}）。</p>
     */
    public static MultiBufferSource tagBeamSource(MultiBufferSource source) {
        if (!isLoaded()) {
            return source;
        }
        if (!ensureTaggingUsable()) {
            return source;
        }
        try {
            return IrisBeamTagging.wrapSource(source);
        } catch (Throwable t) {
            taggingUsable = false;
            LOGGER.warn("[ModIris] 标签包装不可用（{}），光束退回无标签路径", t.toString());
            return source;
        }
    }

    public static boolean isLoaded() {
        if (loaded == null) {
            boolean b = false;
            try {
                ModList modList = ModList.get();
                b = modList.isLoaded("iris") || modList.isLoaded("oculus");
            } catch (Throwable ignored) {
                // 单测环境无 FML ⇒ 视为未加载（与 SableCompat 不同：这里不能让异常穿透到调用方）
            }
            loaded = b;
        }
        return loaded;
    }

    /**
     * 预链接探测：真实加载 {@link IrisBeamTagging} 并走一遍包装链（同 {@code ModSable}
     * 的 {@code Class.forName} 守卫，多走一步方法调用）—— 确保后续热路径上不会再冒
     * {@link NoClassDefFoundError}。
     */
    private static boolean ensureTaggingUsable() {
        if (taggingUsable == null) {
            try {
                taggingUsable = IrisBeamTagging.selfCheck();
            } catch (Throwable t) {
                taggingUsable = false;
                LOGGER.warn("[ModIris] IrisBeamTagging 不可用（{}），光束无标签（光影下可能不可见）",
                    t.toString());
            }
        }
        return taggingUsable;
    }
}
