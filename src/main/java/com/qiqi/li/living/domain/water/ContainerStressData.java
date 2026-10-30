package com.qiqi.li.living.domain.water;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

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

            int cwTorque = 0;
            int ccwTorque = 0;
            int stackSize = stack.getCount();

            int[] neighbors = ContainerContext.getNeighbors(i, containerSize, width);
            for (int neighbor : neighbors) {
                ContainerFluidData.FlowEntry fe = flows.get(neighbor);
                if (fe == null) continue;

                // 水流方向与推动 / 渲染同源（四邻力场，2026-10-31）；只认同流体邻居
                FluidType fluid = fe.fluid();
                int dir = ContainerFluidData.flowDirection(neighbor, width, containerSize, nb -> {
                    if (nb < 0 || nb >= containerSize) return -1;
                    ContainerFluidData.FlowEntry n = flows.get(nb);
                    return n != null && n.fluid() == fluid ? n.level() : -1;
                });
                if (dir == ContainerFluidData.FLOW_NONE) continue;

                int fdx = dir == ContainerFluidData.FLOW_RIGHT ? 1
                    : dir == ContainerFluidData.FLOW_LEFT ? -1 : 0;
                int fdy = dir == ContainerFluidData.FLOW_DOWN ? 1
                    : dir == ContainerFluidData.FLOW_UP ? -1 : 0;

                int px = (neighbor % width) - (i % width);
                int py = (neighbor / width) - (i / width);

                int torque = px * fdy - py * fdx;

                float strength = (ContainerFluidData.MAX_FLOW_LEVEL - fe.level() + 1.0f)
                                 / ContainerFluidData.MAX_FLOW_LEVEL;

                int weightedTorque = Math.round(torque * strength * stackSize);

                if (weightedTorque > 0) {
                    cwTorque += weightedTorque;
                } else if (weightedTorque < 0) {
                    ccwTorque += -weightedTorque;
                }
            }

            if (cwTorque > 0 || ccwTorque > 0) {
                wheelTorques.put(i, new int[]{cwTorque, ccwTorque});
            }

            netCWStress += cwTorque;
            netCCWStress += ccwTorque;
        }

        netStress = netCWStress - netCCWStress;
    }
}