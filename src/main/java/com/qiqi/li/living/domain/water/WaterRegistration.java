package com.qiqi.li.living.domain.water;

import java.util.Set;

import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.container.TickContext;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidType;

import com.qiqi.li.living.domain.water.FluidFlowBehavior.IncinerateResult;
import com.qiqi.li.living.util.StaticCacheRegistry;

/**
 * 活水域注册入口（活桶 / 活水车 / 流体行为）—— 见 {@code RedstoneRegistration} 的类注释了解为何有这个类（A1）。
 */
public final class WaterRegistration {

    private WaterRegistration() {}

    /**
     * 石头系物品（机制四「新配方」的投喂物：满组喂养活熔岩 → 诞生活熔岩源）。
     * v1 = 代码内集合（FML 单测不加载 item tags，可测性优先）；tag 迁移挂起。
     */
    private static final Set<Item> STONE_FAMILY = Set.of(
        Items.COBBLESTONE, Items.STONE, Items.DEEPSLATE, Items.COBBLED_DEEPSLATE,
        Items.BLACKSTONE, Items.TUFF, Items.GRANITE, Items.DIORITE, Items.ANDESITE);

    private static boolean isStoneFamily(ItemStack stack) {
        return STONE_FAMILY.contains(stack.getItem());
    }

    public static void register() {
        // ── 框架中继：流体存在性视图解析器（2026-10-08 计划 ⑤）──
        // 让 TickContext 只认契约层 FluidPresence，不认识 ContainerFluidData
        // ⇒ container 包不再 import 水领域；跨领域消费者（活耕地判湿）也不必 import。
        TickContext.registerFluidPresenceResolver(
            tick -> tick.data(ContainerFluidData.KEY));

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

        // ── 活熔岩（2026-10-06，与活水源对称的第三条流体）──────
        //  - 会流动：maxLevel 3（dropOff 2 派生值）、30t/格（getTickDelay 派生值，下界 10）
        //  - 晋升：永不（原版岩浆无无限源；gamerule 口径不实现）
        //  - 焚毁（机制一）：**含源格**——防火物品（下界合金系）存活共存；
        //    石头系满组 → SPAWN_SOURCE（源已存在时只消耗石头）；其余 BURN
        //  - 前沿反应（刷石机）：熔岩格四邻有水 → 圆石（对齐原版 shouldSpreadLiquid）
        //  - ⚠️ **不覆写 transformItem ⇒ 岩浆不转化**：源格上的空桶走焚毁而非变岩浆桶
        //    （转化是活水源的机制三；活岩浆桶只从世界汲取 / GUI 汲获得）
        FluidFlowBehaviors.register(Fluids.LAVA.getFluidType(), new FluidFlowBehavior() {
            @Override public boolean canFlow() { return true; }
            @Override public int maxLevel() { return 3; }   // dropOff 2 派生值
            @Override public IncinerateResult incinerateResult(ItemStack item) {
                if (isStoneFamily(item) && item.getCount() >= item.getMaxStackSize()) {
                    return IncinerateResult.SPAWN_SOURCE;   // 新配方：满组石头系 → 活熔岩源
                }
                if (item.has(net.minecraft.core.component.DataComponents.FIRE_RESISTANT)) return IncinerateResult.SURVIVE;
                return IncinerateResult.BURN;
            }
            @Override public ItemStack frontierReaction(FluidType neighbor) {
                return neighbor == Fluids.WATER.getFluidType()
                    ? new ItemStack(Items.COBBLESTONE)   // 刷石机：流动熔岩遇水 → 圆石
                    : null;
            }
            // 黑曜石循环（2026-10-06）：**源格**遇水 → 黑曜石 + 源湮灭（原版 shouldSpreadLiquid）
            @Override public ItemStack frontierSourceReaction(FluidType neighbor) {
                return neighbor == Fluids.WATER.getFluidType()
                    ? new ItemStack(Items.OBSIDIAN)
                    : null;
            }
            // 源格替换（2026-10-06 A 档对齐原版）：原版倒桶对**任何液体格**一律替换
            // （blockstate.canBeReplaced(f) = ... || !isSolid() ⇒ 液体块 legacySolid=false），
            // 无「源/流动」之分 ⇒ 熔岩源允许被水顶替（浇灭矿脉）。
            // ⚠️ 反向也允许：见水行为的对称覆写（活岩浆桶倒进水格 ⇒ 该格变岩浆源）。
            @Override public boolean canBeReplacedBy(FluidType incoming) {
                return incoming == Fluids.WATER.getFluidType();
            }
        });

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
            // 源格替换的**对称**一半（2026-10-06 A 档）：原版倒桶对任何液体格一律替换 ⇒
            // 活岩浆桶倒进活水源格 ⇒ 该格变成岩浆源（原岩浆被覆盖、同格改写）。
            @Override public boolean canBeReplacedBy(FluidType incoming) {
                return incoming == Fluids.LAVA.getFluidType();
            }
        });

        // ── static 缓存清理（登记点归属见 StaticCacheRegistry 类注释）──
        // 流体快照同步的「曾下发过」边沿集（2026-10-04）：跨存档不清会在新世界对同键容器误发一次空快照
        StaticCacheRegistry.onServerStop(s -> FluidFlowServerSync.clearRuntimeState());
        // 客户端：界面 removed() 清缓存与「退出存档」之间有竞态窗口（服务端最后一拍仍可能发包）
        StaticCacheRegistry.onClientLogout(FluidFlowClientCache::clear);
    }
}
