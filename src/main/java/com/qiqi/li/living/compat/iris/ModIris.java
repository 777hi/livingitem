package com.qiqi.li.living.compat.iris;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.client.renderer.MultiBufferSource;
import net.neoforged.fml.ModList;

/**
 * Iris 光束渲染源门面：可用时添加方块实体阶段标签，否则返回原源。
 *
 * <p>Iris 内部类引用仅限隔离实现 {@link IrisBeamTagging}；显式 flush 必须仍走原源。
 * 标签包装不能修复颜色 alpha，也不保证光影包中的可见性。</p>
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
