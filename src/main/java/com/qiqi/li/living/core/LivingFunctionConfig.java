package com.qiqi.li.living.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import com.qiqi.li.living.core.components.ILivingComponent;

public class LivingFunctionConfig {

    private final List<ComponentEntry> components = new ArrayList<>();
    private final Map<Class<? extends ILivingComponent>, ILivingComponent> configuredInstances = new HashMap<>();
    private boolean enableStackMultiplier = true;
    private String functionId = "unknown";

    public record ComponentEntry(
        Class<? extends ILivingComponent> componentClass,
        ComponentConfig config,
        @Nullable ILivingComponent preconfiguredInstance
    ) {}

    public LivingFunctionConfig addComponent(Class<? extends ILivingComponent> clazz, ComponentConfig config) {
        components.add(new ComponentEntry(clazz, config, null));
        return this;
    }

    public LivingFunctionConfig addComponent(ILivingComponent instance) {
        components.add(new ComponentEntry(instance.getClass(), ComponentConfig.empty(), instance));
        configuredInstances.put(instance.getClass(), instance);
        return this;
    }

    @Nullable
    public ILivingComponent getConfiguredInstance(Class<? extends ILivingComponent> clazz) {
        return configuredInstances.get(clazz);
    }

    public LivingFunctionConfig withStackMultiplier(boolean enabled) {
        this.enableStackMultiplier = enabled;
        return this;
    }

    public LivingFunctionConfig withFunctionId(String id) {
        this.functionId = id;
        return this;
    }

    public List<ComponentEntry> getComponents() { return components; }
    public boolean isStackMultiplierEnabled() { return enableStackMultiplier; }
    public String getFunctionId() { return functionId; }
}