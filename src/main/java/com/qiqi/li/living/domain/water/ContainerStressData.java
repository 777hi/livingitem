package com.qiqi.li.living.domain.water;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.IntFunction;
import java.util.function.IntUnaryOperator;

import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.container.ContainerDataKey;
import com.qiqi.li.living.domain.water.LivingWaterWheelFunction;

import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidType;

/**
 * 容器级应力数据 —— 管理容器内所有活水车的应力累加。
 *
 * 应力模型：
 * - 活水车阻挡水流，但观察周围 4 方向的水流
 * - 每个方向的水流对水车产生力矩（二维叉积）
 * - 力矩 > 0 → 顺时针 (CW)，力矩 < 0 → 逆时针 (CCW)
 * - 同方向应力叠加，反方向应力抵消
 * - 净应力 = CW应力 - CCW应力
 *
 * 应力在 {@code LivingWaterWheelFunction}（prio 1）里执行，读<b>本拍最终</b>的
 * {@link ContainerFluidData#getFlows()} 算每个水车的力矩。
 *
 * <p>⚠️ 水流方向读 {@link ContainerFluidData#flowDirection}（四邻力场）——
 * 与物品推动、渲染<b>同源</b>（2026-10-31）。不再读 {@code FlowEntry.fromSlot()}：
 * 源格 {@code fromSlot = -1} 会被跳过（源格旁的水车白转），分叉处也会与推/渲染给出不同方向。</p>
 */
public class ContainerStressData implements com.qiqi.li.living.api.StressSource {

    /**
     * 容器应力的 key（tick 级：每 tick 重建，tick 末写回 BE 供 Create 读取）。
     *
     * <p>key 归领域所有（计划 ⑤，2026-10-08）—— 原集中在 {@code container/ContainerDataKeys}。</p>
     */
    public static final ContainerDataKey<ContainerStressData> KEY =
        ContainerDataKey.of("stress", ContainerStressData::new);

    public static final ContainerStressData EMPTY = new ContainerStressData();

    private int netCWStress;
    private int netCCWStress;
    private int netStress;
    private final Map<Integer, int[]> wheelTorques = new LinkedHashMap<>();

    public int getNetCWStress() { return netCWStress; }
    public int getNetCCWStress() { return netCCWStress; }
    public int getNetStress() { return netStress; }
    public Map<Integer, int[]> getWheelTorques() { return wheelTorques; }

    public boolean isEmpty() {
        return netCWStress == 0 && netCCWStress == 0;
    }

    public void calculate(ContainerFluidData fluidData, ContainerContext ctx, Set<Integer> waterWheelSlots) {
        int containerSize = ctx.getSize();
        int width = ctx.getWidth();
        if (containerSize <= 0 || width <= 0) return;

        netCWStress = 0;
        netCCWStress = 0;
        wheelTorques.clear();

        var flows = fluidData.getFlows();

        for (int i : waterWheelSlots) {
            if (i < 0 || i >= containerSize) continue;
            ItemStack stack = ctx.getItem(i);
            if (!LivingWaterWheelFunction.isLivingWaterWheel(stack)) continue;

            // 任意流体都能驱动水车（Create 水车本来就可以由岩浆驱动）。
            // 每个邻居按**它自己的流体**建力场表：水与岩浆同容器时互不污染。
            int[] torque = wheelTorque(i, width, containerSize, nb -> {
                ContainerFluidData.FlowEntry fe = flows.get(nb);
                if (fe == null) return s -> -1;
                FluidType fluid = fe.fluid();
                return s -> {
                    ContainerFluidData.FlowEntry n = flows.get(s);
                    return n != null && n.fluid() == fluid ? n.level() : -1;
                };
            }, stack.getCount());

            int cwTorque = torque[0];
            int ccwTorque = torque[1];
            if (cwTorque > 0 || ccwTorque > 0) {
                wheelTorques.put(i, new int[] {cwTorque, ccwTorque});
            }

            netCWStress += cwTorque;
            netCCWStress += ccwTorque;
        }

        netStress = netCWStress - netCCWStress;
    }

    /**
     * 单个水车的力矩 —— <b>纯函数</b>（2026-10-31 抽出：不依赖 Create 物品，可直接单测）。
     *
     * <p>方向读 {@link FluidFlowVector#direction}（与推动 / 渲染同源）；
     * 力矩 = 二维叉积（位置向量 × 水流方向），按离源距离加权，堆叠数量放大 SU 容量。</p>
     *
     * <p><b>不按流体类型过滤</b>：任意流体都能驱动（Create 水车本来就可以由岩浆驱动，
     * 2026-10-31 用户确认）。每个邻居用**它自己的流体**建力场表 ⇒ 水与岩浆同容器时互不污染。</p>
     *
     * @param levelOfFor 邻格 → 该邻居所属流体的 level 查询器（无水 / 越界返回 -1）
     * @param count      堆叠数量
     * @return {@code {cw, ccw}}（均非负）
     */
    static int[] wheelTorque(int wheelSlot, int width, int containerSize,
                             IntFunction<IntUnaryOperator> levelOfFor, int count) {
        int cw = 0, ccw = 0;
        for (int neighbor : ContainerContext.getNeighbors(wheelSlot, containerSize, width)) {
            IntUnaryOperator levelOf = levelOfFor.apply(neighbor);
            int level = levelOf.applyAsInt(neighbor);
            if (level < 0) continue;

            int dir = FluidFlowVector.direction(neighbor, width, containerSize, levelOf);
            if (dir == FluidFlowVector.FLOW_NONE) continue;

            int px = (neighbor % width) - (wheelSlot % width);
            int py = (neighbor / width) - (wheelSlot / width);
            int torque = px * FluidFlowVector.dy(dir) - py * FluidFlowVector.dx(dir);

            float strength = (ContainerFluidData.MAX_FLOW_LEVEL - level + 1.0f)
                             / ContainerFluidData.MAX_FLOW_LEVEL;
            int weighted = Math.round(torque * strength * count);
            if (weighted > 0) cw += weighted;
            else if (weighted < 0) ccw += -weighted;
        }
        return new int[] {cw, ccw};
    }
}