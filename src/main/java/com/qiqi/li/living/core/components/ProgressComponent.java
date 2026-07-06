package com.qiqi.li.living.core.components;

import java.util.function.Consumer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import com.qiqi.li.living.core.ComponentConfig;
import com.qiqi.li.living.core.ComponentContext;
import com.qiqi.li.living.core.ComponentState;

public class ProgressComponent implements ILivingComponent {

    public static final String ID = "progress";
    private static final String KEY_PROGRESS = "progress";
    private static final String KEY_TOTAL = "total";

    @Override
    public String getComponentId() { return ID; }

    @Override
    public void tick(ComponentContext ctx, int hostSlot, ItemStack hostStack,
                     ComponentState state, ComponentConfig config) {
        int total = config.get("total_ticks", Integer.class, 200);
        int multiplier = Math.max(1, hostStack.getCount());

        state.setInt(KEY_TOTAL, total);

        int current = state.getInt(KEY_PROGRESS, 0);
        current += multiplier;
        state.setInt(KEY_PROGRESS, current);
    }

    @Override
    public ComponentState createDefaultState() {
        return new ComponentState();
    }

    @Override
    public void appendTooltip(ComponentState state, Consumer<Component> tooltipAdder) {
        int progress = state.getInt(KEY_PROGRESS, 0);
        int total = state.getInt(KEY_TOTAL, 0);

        if (total > 0) {
            int percent = (int) ((progress * 100.0f) / total);
            tooltipAdder.accept(Component.translatable(
                "tooltip.livingitem.progress",
                percent,
                String.format("%.1f", progress / 20.0),
                String.format("%.1f", total / 20.0)
            ));
        } else if (progress > 0) {
            tooltipAdder.accept(Component.translatable(
                "tooltip.livingitem.progress_wait",
                String.format("%.1f", progress / 20.0)
            ));
        }
    }

    public boolean isComplete(ComponentState state, ComponentConfig config) {
        int progress = state.getInt(KEY_PROGRESS, 0);
        int total = state.getInt(KEY_TOTAL, config.get("total_ticks", Integer.class, 200));
        return progress >= total;
    }

    public void reset(ComponentState state) {
        state.setInt(KEY_PROGRESS, 0);
        state.setInt(KEY_TOTAL, 0);
    }

    public void pauseTick(ComponentState state) {
        int current = state.getInt(KEY_PROGRESS, 0);
        if (current > 0) {
            int paused = Math.max(0, current - 1);
            state.setInt(KEY_PROGRESS, paused);
        }
    }
}