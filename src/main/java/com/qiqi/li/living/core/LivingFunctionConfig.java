package com.qiqi.li.living.core;

import java.util.ArrayList;
import java.util.List;
import com.qiqi.li.living.core.components.ILivingComponent;

public class LivingFunctionConfig {

    private Direction2D inputDirection = Direction2D.LEFT;
    private Direction2D fuelDirection = Direction2D.DOWN;
    private Direction2D outputDirection = Direction2D.RIGHT;
    private final List<ComponentEntry> components = new ArrayList<>();
    private boolean enableStackMultiplier = true;
    private String functionId = "unknown";

    public record ComponentEntry(
        Class<? extends ILivingComponent> componentClass,
        ComponentConfig config
    ) {}

    public LivingFunctionConfig withInput(Direction2D dir) {
        this.inputDirection = dir;
        return this;
    }

    public LivingFunctionConfig withFuel(Direction2D dir) {
        this.fuelDirection = dir;
        return this;
    }

    public LivingFunctionConfig withOutput(Direction2D dir) {
        this.outputDirection = dir;
        return this;
    }

    public LivingFunctionConfig addComponent(Class<? extends ILivingComponent> clazz, ComponentConfig config) {
        components.add(new ComponentEntry(clazz, config));
        return this;
    }

    public LivingFunctionConfig withStackMultiplier(boolean enabled) {
        this.enableStackMultiplier = enabled;
        return this;
    }

    public LivingFunctionConfig withFunctionId(String id) {
        this.functionId = id;
        return this;
    }

    public Direction2D getInputDirection() { return inputDirection; }
    public Direction2D getFuelDirection() { return fuelDirection; }
    public Direction2D getOutputDirection() { return outputDirection; }
    public List<ComponentEntry> getComponents() { return components; }
    public boolean isStackMultiplierEnabled() { return enableStackMultiplier; }
    public String getFunctionId() { return functionId; }
}