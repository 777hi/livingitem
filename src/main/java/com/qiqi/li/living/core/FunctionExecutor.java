package com.qiqi.li.living.core;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import com.qiqi.li.living.ContainerContext;
import com.qiqi.li.living.LivingItemManager;
import com.qiqi.li.living.core.components.*;
import com.qiqi.li.living.core.model.Pos2D;

public final class FunctionExecutor {

    public static final FunctionExecutor INSTANCE = new FunctionExecutor();

    private final Map<Class<? extends ILivingComponent>, ILivingComponent> componentCache = new HashMap<>();
    private final Set<String> occupiedSlotsThisTick = new HashSet<>();

    private FunctionExecutor() {}

    public void tick(ContainerContext context, int slot, ItemStack stack,
                     LivingFunctionConfig config, Level level) {

        int containerSize = context.getSize();

        if (slot < 0 || slot >= containerSize) {
            return;
        }

        Map<String, ComponentState> states = loadOrCreateStates(stack, config);

        int inputSlot = -1, fuelSlot = -1, outputSlot = -1;

        DirectionModeComponent dirComp = findComponent(config, DirectionModeComponent.class);
        if (dirComp != null) {
            ComponentState dirState = states.get(DirectionModeComponent.ID);
            Pos2D inputDir = dirComp.getDirection(dirState, "input");
            Pos2D fuelDir = dirComp.getDirection(dirState, "fuel");
            Pos2D outputDir = dirComp.getDirection(dirState, "output");

            inputSlot = SlotResolver.resolve(slot, inputDir, containerSize);
            fuelSlot = SlotResolver.resolve(slot, fuelDir, containerSize);
            outputSlot = SlotResolver.resolve(slot, outputDir, containerSize);
        }

        ComponentContext ctx = new ComponentContext(context, inputSlot, fuelSlot, outputSlot, level, states);

        String slotKey = context.getStableKey(inputSlot, "global_occupancy");
        if (inputSlot != -1 && occupiedSlotsThisTick.contains(slotKey)) {
            return;
        }

        FuelConsumeComponent fuelComp = findComponent(config, FuelConsumeComponent.class);
        ItemTransformComponent transformComp = findComponent(config, ItemTransformComponent.class);
        ComponentState fuelState = fuelComp != null ? states.get(fuelComp.getComponentId()) : null;
        ComponentConfig fuelConfig = null;
        if (fuelComp != null) {
            for (var entry : config.getComponents()) {
                if (entry.componentClass() == FuelConsumeComponent.class) {
                    fuelConfig = entry.config();
                    break;
                }
            }
        }

        boolean canProgress = true;

        if (fuelComp != null && !fuelComp.isBurning(fuelState)) {
            if (!fuelComp.hasUsableFuel(ctx, fuelConfig)) {
                canProgress = false;
            }
        }

        if (canProgress && transformComp != null) {
            if (!transformComp.canProcess(ctx)) {
                canProgress = false;
            }
        }

        for (LivingFunctionConfig.ComponentEntry entry : config.getComponents()) {
            ILivingComponent component = resolveComponent(config, entry);
            ComponentState state = states.get(component.getComponentId());

            if (!canProgress) {
                if (component instanceof ProgressComponent progressComp) {
                    progressComp.pauseTick(state);
                } else if (component instanceof FuelConsumeComponent fuelPauseComp) {
                    fuelPauseComp.pauseTick(state);
                } else {
                    component.tick(ctx, slot, stack, state, entry.config());
                }
            } else {
                component.tick(ctx, slot, stack, state, entry.config());
            }
        }

        handleCompletion(ctx, slot, stack, config, states);

        if (inputSlot != -1) {
            occupiedSlotsThisTick.add(slotKey);
        }

        saveStatesToStack(stack, config, states);

        context.syncSlotToClients(slot, stack);
    }

    public void resetOccupiedSlots() {
        occupiedSlotsThisTick.clear();
    }

    public ILivingComponent getComponent(Class<? extends ILivingComponent> clazz) {
        return componentCache.computeIfAbsent(clazz, c -> {
            try {
                return c.getDeclaredConstructor().newInstance();
            } catch (Exception e) {
                throw new RuntimeException("Failed to instantiate component: " + c.getName(), e);
            }
        });
    }

    public ILivingComponent resolveComponent(LivingFunctionConfig config, LivingFunctionConfig.ComponentEntry entry) {
        if (entry.preconfiguredInstance() != null) {
            return entry.preconfiguredInstance();
        }

        ILivingComponent configured = config.getConfiguredInstance(entry.componentClass());
        if (configured != null) {
            return configured;
        }

        return getComponent(entry.componentClass());
    }

    @SuppressWarnings("unchecked")
    public <T extends ILivingComponent> T findComponent(LivingFunctionConfig config, Class<T> type) {
        for (var entry : config.getComponents()) {
            ILivingComponent comp = resolveComponent(config, entry);
            if (type.isInstance(comp)) {
                return (T) comp;
            }
        }
        return null;
    }

    private Map<String, ComponentState> loadOrCreateStates(ItemStack stack, LivingFunctionConfig config) {
        Map<String, ComponentState> states = new HashMap<>();

        CompoundTag functionTag = LivingItemManager.getFunctionData(stack, config.getFunctionId());

        for (var entry : config.getComponents()) {
            ILivingComponent component = resolveComponent(config, entry);
            String compId = component.getComponentId();

            if (functionTag.contains(compId)) {
                states.put(compId, ComponentState.fromNBT(functionTag.getCompound(compId)));
            } else {
                states.put(compId, component.createDefaultState());
            }
        }

        return states;
    }

    private void saveStatesToStack(ItemStack stack, LivingFunctionConfig config,
                                    Map<String, ComponentState> states) {
        CompoundTag functionTag = new CompoundTag();

        for (var entry : states.entrySet()) {
            functionTag.put(entry.getKey(), entry.getValue().toNBT());
        }

        LivingItemManager.setFunctionData(stack, config.getFunctionId(), functionTag);
    }

    private void handleCompletion(ComponentContext ctx, int slot, ItemStack stack,
                                   LivingFunctionConfig config,
                                   Map<String, ComponentState> states) {

        ProgressComponent progress = findComponent(config, ProgressComponent.class);
        ItemTransformComponent transform = findComponent(config, ItemTransformComponent.class);

        if (progress == null || transform == null) return;

        ComponentConfig progressConfig = null;
        ComponentConfig transformConfig = null;

        for (var entry : config.getComponents()) {
            ILivingComponent comp = resolveComponent(config, entry);
            if (comp instanceof ProgressComponent) progressConfig = entry.config();
            else if (comp instanceof ItemTransformComponent) transformConfig = entry.config();
        }

        ComponentState progressState = states.get(progress.getComponentId());

        if (progress.isComplete(progressState, progressConfig)) {
            FuelConsumeComponent fuel = findComponent(config, FuelConsumeComponent.class);
            ComponentState fuelState = fuel != null ? states.get(fuel.getComponentId()) : null;
            ComponentState transformState = states.get(transform.getComponentId());

            if (fuel == null || fuel.isBurning(fuelState)) {
                boolean success = transform.executeTransform(ctx, stack, transformConfig, progress, transformState);
                if (success) {
                    progress.reset(progressState);
                }
            }
        }
    }
}