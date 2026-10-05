package com.qiqi.li.living.domain.water;

import com.qiqi.li.living.api.LivingItemManager;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluids;

/**
 * 活水域注册入口（活桶 / 活水车 / 流体行为）—— 见 {@code RedstoneRegistration} 的类注释了解为何有这个类（A1）。
 */
public final class WaterRegistration {

    private WaterRegistration() {}

    public static void register() {
        // 容器级流体 tick 驱动（1b-2b，框架）：自维持 + HasContainerData prio 0
        LivingItemManager.registerFunction(new LivingFluidFunction());
        // 活桶（流体侧批次二，2026-10-03）：交互型，无 tick —— 汲/倒走 GUI 交互管道。
        // ⚠️ 旧 LivingWaterBucketFunction（WATER_BUCKET 宿主 + 桶源注册）已随「桶源退役」删除。
        LivingItemManager.registerFunction(new LivingBucketFunction());
        LivingItemManager.registerFunction(new LivingWaterWheelFunction());

        // 交互处理器（汲/倒）：客户端拦截不走规则 JSON（目标条件是「空槽位 + 容器级源状态」，
        // 物品中心规则表达不了，见 LivingBucketInteractHandlers 类注释），直接发
        // GuiInteractionPacket 走本注册的 handler
        com.qiqi.li.living.interaction.InteractionRegistry.registerHandler(
            "living_bucket_pour", LivingBucketInteractHandlers.POUR);
        com.qiqi.li.living.interaction.InteractionRegistry.registerHandler(
            "living_bucket_scoop", LivingBucketInteractHandlers.SCOOP);

        // 流体流动行为（1b-2 契约 + 流体侧 F2/F4）：
        //  - 会流动，level 上限 7（= 原版 dropOff 派生值）
        //  - 流速：不覆写 ⇒ 派生自原版 getTickDelay（水 5t/格，与原版对齐，2026-10-05 时序化）
        //  - 晋升（F2）：四邻中 ≥2 源 → 升格为派生源（原版无限水；岩浆等其它流体吃默认永不晋升）
        //  - 转化（F4）：委托 FluidTransformTable 按 JSON 转化表查（水：空桶→水桶、
        //    混凝土粉末→混凝土；缩容堆叠等待规则在表内实施）
        FluidFlowBehaviors.register(Fluids.WATER.getFluidType(), new FluidFlowBehavior() {
            @Override public boolean canFlow() { return true; }
            @Override public int maxLevel() { return ContainerFluidData.MAX_FLOW_LEVEL; }
            @Override public boolean shouldPromote(int slot, int sourceNeighborCount) {
                return sourceNeighborCount >= 2;
            }
            @Override public ItemStack transformItem(ItemStack item) {
                return FluidTransformTable.transform(Fluids.WATER.getFluidType(), item);
            }
            @Override public boolean consumesSourceOnTransform(ItemStack item) {
                return FluidTransformTable.consumesSource(Fluids.WATER.getFluidType(), item);
            }
        });
    }
}
