package com.qiqi.li.living.compat.sophisticated;

import java.io.IOException;
import java.util.List;
import java.util.Set;

import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * 精妙软依赖的 Mixin 门控：缺席时跳过，应用前从注入声明核验精确目标签名。
 * class 资源读取不触发类加载；注入位置由各 handler 的独立组约束核验，不保证字节码回滚。
 */
public class SophisticatedMixinPlugin implements IMixinConfigPlugin {

    private static final Logger LOGGER = LoggerFactory.getLogger("LivingItem/SophisticatedMixinPlugin");
    private static final String PROBE_RESOURCE =
        "net/p3pp3rf1y/sophisticatedbackpacks/backpack/BackpackBlock.class";

    private final ClassLoader loader;
    private Boolean loaded;

    public SophisticatedMixinPlugin() {
        this(SophisticatedMixinPlugin.class.getClassLoader());
    }

    SophisticatedMixinPlugin(ClassLoader loader) {
        this.loader = loader;
    }

    private boolean isSophisticatedLoaded() {
        if (loaded == null) {
            try {
                loaded = loader.getResource(PROBE_RESOURCE) != null;
                LOGGER.debug("[SophisticatedMixinPlugin] 精妙背包探测（资源检查）: {}", loaded);
            } catch (RuntimeException | LinkageError unavailable) {
                loaded = false;
                LOGGER.error("[SophisticatedMixinPlugin] 无法探测精妙背包资源，跳过联动", unavailable);
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
        try {
            if (!isSophisticatedLoaded()) return false;
            ClassNode target = MixinInjectionSignatureCheck.readClass(loader, targetClassName);
            ClassNode mixin = MixinInjectionSignatureCheck.readClass(loader, mixinClassName);
            var failures = MixinInjectionSignatureCheck.check(target, mixin);
            if (!failures.isEmpty()) {
                for (var failure : failures) {
                    LOGGER.error("[SophisticatedMixinPlugin] 跳过 {} -> {}：handler={} 目标={} 原因={}；该 Mixin 的兼容行为未启用",
                        mixinClassName, targetClassName, failure.handler(), failure.selector(), failure.reason());
                }
                return false;
            }
            LOGGER.debug("[SophisticatedMixinPlugin] 目标签名核验通过: target={} mixin={}",
                targetClassName, mixinClassName);
            return true;
        } catch (IOException | RuntimeException | LinkageError unavailable) {
            LOGGER.error("[SophisticatedMixinPlugin] 无法核验 {} -> {}，跳过该 Mixin 的兼容行为",
                mixinClassName, targetClassName, unavailable);
            return false;
        }
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
        LOGGER.debug("[SophisticatedMixinPlugin] 注入约束检查已完成: target={} mixin={}；运行行为仍需实机验收",
            targetClassName, mixinClassName);
    }
}
