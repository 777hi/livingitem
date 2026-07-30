package com.qiqi.li.living.compat.create;

import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

public class CreateMixinPlugin implements IMixinConfigPlugin {

    private static final Logger LOGGER = LoggerFactory.getLogger("LivingItem/CreateMixinPlugin");

    private static Boolean createLoaded;

    private static boolean isCreateLoaded() {
        if (createLoaded == null) {
            try {
                ClassLoader cl = CreateMixinPlugin.class.getClassLoader();
                var url = cl.getResource("com/simibubi/create/content/kinetics/base/KineticBlockEntity.class");
                createLoaded = url != null;
                LOGGER.info("[CreateMixinPlugin] Create detection via resource check: {}", createLoaded);
            } catch (Exception e) {
                createLoaded = false;
                LOGGER.info("[CreateMixinPlugin] Create NOT detected (exception), Mixin will be skipped");
            }
        }
        return createLoaded;
    }

    @Override
    public void onLoad(String mixinPackage) {
        isCreateLoaded();
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        boolean result = isCreateLoaded();
        LOGGER.info("[CreateMixinPlugin] shouldApplyMixin: target={}, mixin={}, result={}",
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
        LOGGER.info("[CreateMixinPlugin] postApply: target={}, mixin={} - Mixin applied successfully",
            targetClassName, mixinClassName);
    }
}