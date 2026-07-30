package com.qiqi.li.living.domain.water;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.function.LivingWaterWheelFunction;

import net.minecraft.world.item.ItemStack;

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
 * 应力计算在 ContainerFluidData.recalculate() 之后执行，
 * 读取 BFS 水流状态计算每个水车的力矩。
 */
public class ContainerStressData {

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
                if (fe == null || fe.fromSlot() < 0) continue;

                int fdx = (neighbor % width) - (fe.fromSlot() % width);
                int fdy = (neighbor / width) - (fe.fromSlot() / width);

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