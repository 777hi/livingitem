package com.qiqi.li.living.domain.water;

import java.util.List;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import com.qiqi.li.living.api.HasContainerData;
import com.qiqi.li.living.api.LivingItemFunction;
import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.container.TickContext;

/**
 * 容器级流体 tick 驱动（1b-2b，<b>框架提供</b>）。
 *
 * <h3>为什么需要它</h3>
 * <p>缘起（1b-2b）：容器级流体 tick 曾挂在<b>活水桶</b>上 ⇒ 桶不在场，容器级流体就瘫痪
 * （「数据是容器级的、驱动权却是桶的」）。本类把驱动权收归<b>容器</b>：
 * 它是<b>自维持</b>函数（{@link #shouldTickWithoutOwnItems} 恒真）⇒ 即使容器里一个活物品都没有
 * （纯源容器），流体照样推进。</p>
 *
 * <h3>分工</h3>
 * <ul>
 *   <li><b>框架（本类）</b>：驱动 BFS（水流蔓延 + 推动）。</li>
 *   <li><b>流体侧</b>：只填行为（{@link FluidFlowBehaviors}），<b>不写驱动</b>。</li>
 * </ul>
 *
 * <p>⚠️ 排序：{@link #getPriority()} = <b>0</b>（最先跑）—— BFS 必须先于下游
 * （水车读流体算应力 = prio 1、红石 = prio 2）。排序只能靠 {@code HasContainerData.getPriority()}。</p>
 */
public class LivingFluidFunction implements LivingItemFunction, HasContainerData {

    public static final String ID = "living_fluid";

    /** 不挂任何物品：本功能是纯容器级逻辑，只走自维持通道。 */
    @Override
    public boolean canApply(ItemStack stack) {
        return false;
    }

    @Override
    public String getFunctionId() {
        return ID;
    }

    /** 自维持：容器内没有本功能自己的活物品时也 tick（纯源容器的关键）。 */
    @Override
    public boolean shouldTickWithoutOwnItems(ContainerContext ctx) {
        return true;
    }

    /** 容器级数据顺序：0 = 最先（BFS 先于应力 / 红石）。 */
    @Override
    public int getPriority() {
        return 0;
    }

    /** 物品驱动部分为空（本功能不挂物品）。 */
    @Override
    public void tick(List<SlotEntry> entries, ContainerContext container, TickContext tick, Level level) {
        // no-op
    }

    /**
     * 驱动容器级流体 tick：BFS 重算流动 + 周期性推动物品。
     * 无流体数据的容器是廉价 no-op（{@code fluidData.isEmpty()}）。
     * tick 后向正在查看的玩家下发流体快照（Q5 渲染轨 —— 纯源容器的水也能渲染）。
     */
    @Override
    public void tickContainerData(List<SlotEntry> entries, ContainerContext ctx, TickContext tick) {
        ContainerFluidData fluidData = tick.data(ContainerFluidData.KEY);
        if (fluidData == null || fluidData == ContainerFluidData.EMPTY) return;
        if (!fluidData.isEmpty()) {
            fluidData.setLastTickTime(System.currentTimeMillis());
            fluidData.tick(ctx);
        }
        // flush 在 isEmpty 门之外：数据刚清空（最后一个源被汲走/挤没）时也要下发一次
        // 空快照清掉客户端残留渲染（边沿检测在 FluidFlowServerSync，无流体容器零发包）
        if (ctx instanceof com.qiqi.li.living.container.TickableContainerContext tickable) {
            FluidFlowServerSync.flushAfterTick(tickable, fluidData);
        }
    }
}
