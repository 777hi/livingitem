package com.qiqi.li.living.core.components;

import java.util.function.Consumer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import com.qiqi.li.living.core.ComponentConfig;
import com.qiqi.li.living.core.ComponentContext;
import com.qiqi.li.living.core.ComponentState;

/**
 * 进度组件 —— 管理活熔炉的熔炼进度。
 *
 * 职责：
 * 1. 每 tick 推进进度（受堆叠数加速）
 * 2. 检查进度是否完成
 * 3. 暂停时回退进度（防止无输入时进度卡在临界值）
 * 4. 完成后重置进度
 *
 * 堆叠加速机制：
 *   progress += multiplier（multiplier = hostStack.getCount()）
 *   例如：8 个活熔炉堆叠时，每 tick 进度 +8，200 ticks 的配方只需 25 ticks
 *
 * 暂停回退机制：
 *   当活熔炉无法继续处理（无输入/无燃料/输出满）时，
 *   FunctionExecutor 调用 pauseTick() 而非正常 tick()，
 *   使进度缓慢回退（每 tick -1），模拟余热消散。
 *   这避免了进度卡在 99% 等待燃料的尴尬状态。
 *
 * 配置参数（通过 ComponentConfig）：
 *   - total_ticks: int, 完成所需的总 ticks（默认 200，即 10 秒）
 */
public class ProgressComponent implements ILivingComponent {

    /** 组件 ID，用于在 ComponentState 和 NBT 中标识此组件 */
    public static final String ID = "progress";

    /** NBT 键名：当前进度值 */
    private static final String KEY_PROGRESS = "progress";

    /** NBT 键名：总进度值 */
    private static final String KEY_TOTAL = "total";

    @Override
    public String getComponentId() { return ID; }

    /**
     * 每 tick 执行一次：推进进度。
     *
     * 进度增量 = 活熔炉堆叠数（stackMultiplier），
     * 实现堆叠加速效果。
     */
    @Override
    public void tick(ComponentContext ctx, int hostSlot, ItemStack hostStack,
                     ComponentState state, ComponentConfig config) {
        int total = config.get("total_ticks", Integer.class, 200);
        int multiplier = Math.max(1, hostStack.getCount());

        state.setInt(KEY_TOTAL, total);

        int current = state.getInt(KEY_PROGRESS, 0);
        current = Math.min(total, current + multiplier);
        state.setInt(KEY_PROGRESS, current);
    }

    @Override
    public ComponentState createDefaultState() {
        return new ComponentState();
    }

    /**
     * 追加 Tooltip 信息：显示当前进度百分比和时间。
     *
     * 显示格式：
     * - 有总进度："进度: XX% (X.Xs/X.Xs)"
     * - 无总进度但有进度："等待中: X.Xs"
     */
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

    /**
     * 检查进度是否已完成。
     *
     * @param state 组件状态
     * @param config 组件配置（包含 total_ticks）
     * @return 如果当前进度 >= 总进度返回 true
     */
    public boolean isComplete(ComponentState state, ComponentConfig config) {
        int progress = state.getInt(KEY_PROGRESS, 0);
        int total = state.getInt(KEY_TOTAL, config.get("total_ticks", Integer.class, 200));
        return progress >= total;
    }

    /**
     * 重置进度（转化成功后调用）。
     *
     * @param state 组件状态
     */
    public void reset(ComponentState state) {
        state.setInt(KEY_PROGRESS, 0);
        state.setInt(KEY_TOTAL, 0);
    }

    /**
     * 暂停 tick 时回退进度（每 tick -1）。
     *
     * 当活熔炉无法继续处理时调用，模拟余热消散。
     * 避免进度卡在临界值（如 199/200）等待燃料的尴尬状态。
     *
     * @param state 组件状态
     */
    public void pauseTick(ComponentState state) {
        int current = state.getInt(KEY_PROGRESS, 0);
        if (current > 0) {
            int paused = Math.max(0, current - 1);
            state.setInt(KEY_PROGRESS, paused);
        }
    }
}