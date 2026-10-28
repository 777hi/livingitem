package com.qiqi.li.living.compat.sophisticated;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * 精妙背包的 Mixin 配置插件（{@link IMixinConfigPlugin}）—— 与 {@code CreateMixinPlugin} 同构。
 *
 * <p>精妙是<b>软依赖</b>（只有 dev 环境的运行期 jar，没有编译期依赖）⇒ 没装时必须
 * 整个 mixin 配置不应用，否则类缺失会让加载失败。</p>
 *
 * <p>探测方式：资源存在性检查（{@code BackpackBlock.class} 是否在类路径上），
 * 不触发类加载、不依赖 FML 生命周期顺序。</p>
 *
 * <h3>⚠️ 失效可观测（I-C6）</h3>
 * <p>{@code BackpackBlockMixin} 的注入器是 {@code require = 0}（精妙改版改名时不崩服，
 * 退回「数据随方块消失」的旧行为）。但「静默失效」不可接受 ⇒
 * {@link #postApply} 检查目标类里还有没有 {@code putInPlayersHandAndRemove}，
 * 缺失就 <b>ERROR 日志</b>。这样版本变化在启动日志里就能看见。</p>
 */
public class SophisticatedMixinPlugin implements IMixinConfigPlugin {

    private static final Logger LOGGER = LoggerFactory.getLogger("LivingItem/SophisticatedMixinPlugin");

    /**
     * 每个 Mixin 期望的注入目标方法（缺失 ⇒ ERROR）。
     *
     * <p>⚠️ 必须**按 Mixin 分别登记**：第一版对所有 Mixin 都检查
     * {@code putInPlayersHandAndRemove}，结果 {@code BackpackItemMixin}（目标是 {@code tryPlace}）
     * 每次启动都误报「精妙版本可能变了」—— 日志里一条假 ERROR 比没有日志更糟。</p>
     */
    private static final Map<String, String> EXPECTED_METHODS = Map.of(
        "BackpackBlockMixin", "putInPlayersHandAndRemove",
        "BackpackContainerMixin", "onStorageItemStackChanged",
        "BackpackItemMixin", "tryPlace");

    private static final String PROBE_RESOURCE =
        "net/p3pp3rf1y/sophisticatedbackpacks/backpack/BackpackBlock.class";

    private static Boolean loaded;

    private static boolean isSophisticatedLoaded() {
        if (loaded == null) {
            try {
                ClassLoader cl = SophisticatedMixinPlugin.class.getClassLoader();
                loaded = cl.getResource(PROBE_RESOURCE) != null;
                LOGGER.debug("[SophisticatedMixinPlugin] 精妙背包探测（资源检查）: {}", loaded);
            } catch (Exception e) {
                loaded = false;
                LOGGER.debug("[SophisticatedMixinPlugin] 精妙背包未探测到（异常），Mixin 跳过");
            }
        }
        return loaded;
    }

    @Override
    public void onLoad(String mixinPackage) {
        isSophisticatedLoaded();
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        boolean result = isSophisticatedLoaded();
        LOGGER.debug("[SophisticatedMixinPlugin] shouldApplyMixin: target={} mixin={} result={}",
            targetClassName, mixinClassName, result);
        return result;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        String expected = EXPECTED_METHODS.get(simpleName(mixinClassName));
        if (expected == null) return;

        boolean present = targetClass.methods.stream().anyMatch(m -> expected.equals(m.name));
        if (present) {
            LOGGER.debug("[SophisticatedMixinPlugin] 已注入 {}#{}", targetClassName, expected);
        } else {
            LOGGER.error("[SophisticatedMixinPlugin] {} 里找不到 {} —— 精妙版本可能变了；"
                + "{} 负责的兼容行为会静默失效", targetClassName, expected, simpleName(mixinClassName));
        }
    }

    private static String simpleName(String className) {
        int dot = className.lastIndexOf('.');
        return dot < 0 ? className : className.substring(dot + 1);
    }
}
