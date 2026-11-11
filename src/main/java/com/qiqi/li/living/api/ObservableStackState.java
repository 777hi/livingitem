package com.qiqi.li.living.api;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import javax.annotation.Nullable;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

/**
 * 单个槽位对活侦测器公开的语义状态。
 *
 * <p>通用层只包含物品身份、数量与活化身份；各功能可按 {@code functionId}
 * 追加明确声明的公开状态。所有标签在构造时复制，值对象不持有活栈或容器引用。</p>
 */
public record ObservableStackState(
    boolean empty,
    @Nullable ResourceLocation itemId,
    int count,
    boolean living,
    List<FunctionState> functions
) {

    public static final ObservableStackState EMPTY =
        new ObservableStackState(true, null, 0, false, List.of());

    public ObservableStackState {
        List<FunctionState> copied = new ArrayList<>(functions.size());
        for (FunctionState state : functions) {
            copied.add(new FunctionState(state.functionId(), state.state()));
        }
        copied.sort(Comparator.comparing(FunctionState::functionId));
        functions = List.copyOf(copied);
    }

    /** 一个功能按其唯一 ID 命名的公开状态。 */
    public record FunctionState(String functionId, CompoundTag state) {
        public FunctionState {
            state = state.copy();
        }

        @Override
        public CompoundTag state() {
            return state.copy();
        }
    }
}
