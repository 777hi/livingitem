package com.qiqi.li.living.core.components;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import com.qiqi.li.living.LivingFunctionData;
import com.qiqi.li.living.LivingItemManager;
import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.container.ContainerFluidData;
import com.qiqi.li.living.container.ContainerFluidData.FlowEntry;
import com.qiqi.li.living.container.ContainerSnapshot;
import com.qiqi.li.living.core.ComponentConfig;
import com.qiqi.li.living.core.ComponentContext;
import com.qiqi.li.living.core.ComponentState;
import com.qiqi.li.living.function.LivingWaterBucketFunction;

/**
 * 水流蔓延组件 —— 活水桶的核心逻辑。
 *
 * 架构变更（v2）：
 * 水流蔓延逻辑已迁移到 {@link ContainerFluidData}（容器级数据），
 * 本组件只负责：
 * 1. 向容器级流体数据注册/注销水源
 * 2. 将流数据同步回 ComponentState 供渲染和 Tooltip 使用
 * 3. 处理水桶移动/移除时的水源变更
 *
 * 这样设计的好处：
 * - 水桶移除后水流继续在容器中干涸（fluidData 独立于活物品）
 * - 后续红石系统可复用 ContainerFluidData 作为容器级信号存储
 */
public class WaterSpreadComponent implements ILivingComponent {

    public static final String ID = "water_spread";

    private static final String KEY_FLOW = "flow";
    public static final String KEY_HOST_SLOT = "host_slot";
    public static final String KEY_HOST_COL = "host_col";
    public static final String KEY_HOST_ROW = "host_row";
    public static final String KEY_WIDTH = "width";
    private static final String KEY_LAST_TICK = "last_tick";
    private static final String KEY_CONTAINER_KEY = "container_key";

    @Override
    public String getComponentId() { return ID; }

    @Override
    public ComponentState createDefaultState() {
        return new ComponentState();
    }

    @Override
    public void tick(ComponentContext context, int hostSlot, ItemStack hostStack,
                     ComponentState state, ComponentConfig config) {
        ContainerContext ctx = context.containerCtx();
        int containerSize = ctx.getSize();
        if (containerSize <= 0) return;

        long gameTime = context.level().getGameTime();
        long lastTick = state.getLong(KEY_LAST_TICK, -1);
        String containerKey = ctx.getContainerKey();
        String prevContainerKey = state.getString(KEY_CONTAINER_KEY, null);

        int prevHostSlot = state.getInt(KEY_HOST_SLOT, -1);
        boolean needsReset = false;

        if (lastTick >= 0 && gameTime - lastTick > 2) {
            needsReset = true;
        }
        if (containerKey != null && !containerKey.equals(prevContainerKey)) {
            needsReset = true;
        }
        if (prevHostSlot >= 0 && prevHostSlot != hostSlot) {
            needsReset = true;
        }

        ContainerSnapshot snapshot = ctx.getSnapshot();
        ContainerFluidData fluidData = snapshot != null ? snapshot.getFluidData() : null;

        if (needsReset && fluidData != null && prevHostSlot >= 0) {
            fluidData.removeSource(prevHostSlot);
        }

        state.setLong(KEY_LAST_TICK, gameTime);
        state.setInt(KEY_HOST_SLOT, hostSlot);
        int containerWidth = ctx.getWidth();
        state.setInt(KEY_WIDTH, Math.max(1, containerWidth));
        state.setInt(KEY_HOST_COL, hostSlot % Math.max(1, containerWidth));
        state.setInt(KEY_HOST_ROW, hostSlot / Math.max(1, containerWidth));
        if (containerKey != null) state.setString(KEY_CONTAINER_KEY, containerKey);

        if (fluidData != null) {
            fluidData.registerSource(hostSlot);
        }
    }

    private static void syncFlowToState(ComponentState state, ContainerFluidData fluidData) {
        if (fluidData == null) {
            state.setString(KEY_FLOW, "");
            return;
        }

        Map<Integer, FlowEntry> flows = fluidData.getFlows();
        if (flows.isEmpty()) {
            state.setString(KEY_FLOW, "");
            return;
        }

        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Integer, FlowEntry> e : flows.entrySet()) {
            if (sb.length() > 0) sb.append(',');
            sb.append(e.getKey()).append(':').append(e.getValue().level());
        }
        state.setString(KEY_FLOW, sb.toString());
    }

    /**
     * 在 fluidData.tick() 之后调用，将最新水流数据同步到所有活水桶的 ComponentState。
     *
     * 解决的问题：
     * - 旧流程中 syncFlowToState 在 fluidData.tick() 之前执行，
     *   客户端永远看到上一 tick 的水流状态
     * - 多个活水桶在同一容器时，统一在此处同步，避免每个组件 tick 各自写入冗余数据
     */
    public static void postTickSync(ContainerContext ctx, ContainerFluidData fluidData) {
        for (int i = 0; i < ctx.getSize(); i++) {
            ItemStack stack = ctx.getItem(i);
            if (!LivingWaterBucketFunction.isLivingWaterBucket(stack)) continue;

            LivingFunctionData funcData = stack.get(LivingItemManager.LIVING_FUNCTION_DATA.value());
            if (funcData == null) continue;

            CompoundTag funcTag = funcData.getFunctionData(LivingWaterBucketFunction.ID);
            if (funcTag.isEmpty()) continue;

            CompoundTag compTag = funcTag.getCompound(ID);
            ComponentState state = new ComponentState(compTag);
            syncFlowToState(state, fluidData);
            funcTag.put(ID, compTag);
            ctx.syncSlotToClients(i, stack);
        }
    }

    /**
     * 从 ComponentState 读取水流数据（slot → level）。
     * 供渲染层使用。
     */
    public static Map<Integer, Integer> loadFlowData(ComponentState state) {
        String data = state.getString(KEY_FLOW, "");
        Map<Integer, Integer> map = new HashMap<>();
        if (data.isEmpty()) return map;
        for (String part : data.split(",")) {
            String[] kv = part.split(":");
            if (kv.length >= 2) {
                map.put(Integer.parseInt(kv[0]), Integer.parseInt(kv[1]));
            }
        }
        return map;
    }

    @Override
    public void appendTooltip(ComponentState state, Consumer<Component> tooltipAdder) {
        Map<Integer, Integer> flow = loadFlowData(state);
        if (!flow.isEmpty()) {
            int maxLevel = flow.values().stream().mapToInt(Integer::intValue).max().orElse(0);
            tooltipAdder.accept(Component.literal("水流: " + flow.size() + " 格 (最远" + maxLevel + "格)")
                .withStyle(net.minecraft.ChatFormatting.AQUA));
        }
    }
}