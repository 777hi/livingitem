package com.qiqi.li.living.domain.tools;

import com.qiqi.li.living.api.LivingMod;

import javax.annotation.Nullable;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 活工具 / 活武器领域的组件注册总线（2026-10-08：组件定义从 {@code LivingComponents} 拆回本领域）。
 *
 * <p>本类<b>只放总线</b> —— 具体组件定义在各 {@code XxxData} 类里，与使用它的
 * {@code of()} / {@code set()} 同属一个 {@code <clinit>} ⇒ 从结构上避免循环静态初始化。</p>
 */
public final class ToolComponents {

    public static final DeferredRegister<DataComponentType<?>> REG =
        DeferredRegister.create(Registries.DATA_COMPONENT_TYPE, LivingMod.ID);

    /** 活工具/活武器射线微调配置（起点锚点 + 朝向跟随，仅玩家形态生效 —— 2026-09-30）。 */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<LivingToolRayTuning>> LIVING_TOOL_RAY_TUNING =
        REG.register("living_tool_ray_tuning", () ->
            DataComponentType.<LivingToolRayTuning>builder()
                .persistent(LivingToolRayTuning.CODEC)
                .networkSynchronized(LivingToolRayTuning.STREAM_CODEC)
                .build());

    /** 挖掘预计总 tick（K 组动画用；派生数据，仅网络同步）。 */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Integer>> LIVING_TOOL_DIG_TICKS =
        REG.register("living_tool_dig_ticks", () ->
            DataComponentType.<Integer>builder()
                .networkSynchronized(ByteBufCodecs.VAR_INT)
                .build());

    /** 活工具记忆（录制的玩家操作序列）。 */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<LivingToolMemory>> LIVING_TOOL_MEMORY =
        REG.register("living_tool_memory", () ->
            DataComponentType.<LivingToolMemory>builder()
                .persistent(LivingToolMemory.CODEC)
                .networkSynchronized(LivingToolMemory.STREAM_CODEC)
                .build());

    /** 活工具回放进度。 */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<LivingToolProgress>> LIVING_TOOL_PROGRESS =
        REG.register("living_tool_progress", () ->
            DataComponentType.<LivingToolProgress>builder()
                .persistent(LivingToolProgress.CODEC)
                .networkSynchronized(LivingToolProgress.STREAM_CODEC)
                .build());

    /** 活工具最近一次动作（派生数据，仅网络同步）。 */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<LivingToolAction>> LIVING_TOOL_LAST_ACTION =
        REG.register("living_tool_last_action", () ->
            DataComponentType.<LivingToolAction>builder()
                .networkSynchronized(LivingToolAction.STREAM_CODEC)
                .build());

    private ToolComponents() {}

    /** 挖掘预计总 tick（{@code K} 组动画用）。 */
    public static Integer getDigTicks(ItemStack stack) {
        return stack.get(LIVING_TOOL_DIG_TICKS.value());
    }

    /** 写入挖掘预计总 tick（{@code null} 时移除组件）。 */
    public static void setDigTicks(ItemStack stack, @Nullable Integer ticks) {
        if (ticks == null) {
            stack.remove(LIVING_TOOL_DIG_TICKS.value());
        } else {
            stack.set(LIVING_TOOL_DIG_TICKS.value(), ticks);
        }
    }
}
