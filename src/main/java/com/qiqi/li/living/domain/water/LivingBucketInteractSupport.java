package com.qiqi.li.living.domain.water;

import java.util.Map;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUtils;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidType;

import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.container.ContainerLivingItemHandler;

/**
 * 活桶汲/倒的服务端支撑（流体侧批次二，2026-10-03）——
 * 从菜单槽位反查<b>活着的</b> {@link ContainerFluidData}（必须是容器 tick 循环里的那份实例，
 * 改别处的副本会被下一 tick 重算覆盖）+ 桶内容增减。
 *
 * <p>context 反查已收编到 {@link com.qiqi.li.living.container.ContainerContexts#resolve}
 * （Q6 批次 A，2026-10-04）—— 解析规则（背包 / 单 BE / 大箱子反射两半 + 规范化顺序）与
 * 已知缺口（末影箱解析不出 ⇒ 汲/倒静默无效）见该类 javadoc，此处不再复述。</p>
 */
public final class LivingBucketInteractSupport {

    /**
     * FluidType → 代表 {@code Fluid}（FluidStack 构造需要；优先 still 态）。
     *
     * <p>⚠️ 2026-10-07 收尾审查：此前这里有一份<b>重复实现</b>，且用
     * {@code ConcurrentHashMap} 缓存却可能 {@code put(type, null)}（未注册到
     * {@code BuiltInRegistries.FLUID} 的 FluidType）⇒ <b>NPE</b>。现统一委托
     * {@link ContainerFluidData#representativeFluidOf}（内部用 HashMap + containsKey，安全）。</p>
     */
    private static net.minecraft.world.level.material.Fluid representativeFluid(FluidType type) {
        return ContainerFluidData.representativeFluidOf(type);
    }

    private LivingBucketInteractSupport() {}

    /** 反查目标槽位所属容器的活流体数据；容器类型不支持（末影箱/模组非 BE 容器）返回 null。 */
    public static ContainerFluidData resolveFluidData(ServerPlayer player, Slot slot) {
        ContainerContext ctx = resolveContext(player, slot);
        if (ctx == null) return null;
        ContainerFluidData fluidData = ContainerFluidHandler.getOrCreateFluidData(ctx);
        return fluidData == null || fluidData == ContainerFluidData.EMPTY ? null : fluidData;
    }

    /**
     * 反查目标槽位所属容器的 tick 上下文（容器键与 tick 循环一致）。不支持返回 null。
     *
     * <p>Q6 收编（2026-10-04）：解析逻辑已迁至 {@link com.qiqi.li.living.container.ContainerContexts#resolve}
     * （多方块容器边界带共享内核），此处保留薄委托。</p>
     */
    public static ContainerContext resolveContext(ServerPlayer player, Slot slot) {
        return com.qiqi.li.living.container.ContainerContexts.resolve(player, slot);
    }

    // ── 桶内容增减（倒/汲各一）────────────────────────────────

    /**
     * 倒水服务端执行：目标格空且无源 → 诞生派生源；已有源 → 源不变（原版语义）。
     * 桶排空（换宿主 → 空桶形态）。前置校验由调用方完成（光标是满活桶 + 目标格无物品）。
     *
     * <p><b>异种源格</b>（2026-10-06 A 档对齐原版）：源格流体与倒进来的不同种时，问
     * {@link FluidFlowBehavior#canBeReplacedBy} —— 允许则<b>同键改写源类型</b>
     *（活水桶浇活熔岩源 ⇒ 该格变水源、原岩浆源湮灭，下游岩浆下一拍自然退走；
     * <b>反向亦然</b>：活岩浆桶倒进活水源格 ⇒ 该格变岩浆源）。
     * 倒进任何<b>流动</b>格 ⇒ 直接覆盖（原版对液体格一律替换，<b>无源/流动之分</b>）。</p>
     */
    public static void pour(ServerPlayer player, Slot slot, ItemStack carried) {
        Fluid fluid = LivingBucketFunction.getBucketFluid(carried);
        if (fluid == Fluids.EMPTY) return;

        ContainerFluidData fluidData = resolveFluidData(player, slot);
        if (fluidData == null) return;

        int containerSlot = slot.getContainerSlot();
        FluidType incoming = fluid.getFluidType();
        FluidType resident = fluidData.sourceFluid(containerSlot);   // 实际层；非源格返回 null
        if (resident == null) {
            // 空格 / 任意流动格（含同种流动格 ⇒ 升格为源）⇒ 直接诞生源（原版：液体块一律可替换）
            fluidData.registerGeneratedSource(containerSlot, incoming);
        } else if (replacesResidentSource(resident, incoming)) {
            // 异种源格且允许替换 ⇒ 同键覆盖（registerGeneratedSource 是 put，类型直接改写）
            fluidData.registerGeneratedSource(containerSlot, incoming);
        }
        // 同种源 / 不允许替换的异种源 ⇒ 源不变（原版语义：往源里倒同种流体只是排空桶）
        player.containerMenu.setCarried(LivingBucketFunction.withFluid(carried, Fluids.EMPTY));
    }

    /**
     * 倒桶判定：目标格已有的<b>源</b>是否被倒进来的流体<b>替换</b>（包级私有，供单测）。
     *
     * <p>同种源恒 {@code false}（原版语义：往源里倒同种流体 = 换成同一种块 = 无变化）。
     * 跨流体规则问<b>被替换方</b>的行为（{@code canBeReplacedBy}）—— 知识留在流体侧，
     * 与 {@code frontierReaction} / {@code incinerateResult} 同一接缝风格。</p>
     */
    static boolean replacesResidentSource(FluidType resident, FluidType incoming) {
        if (resident == null || resident == incoming) return false;
        return FluidFlowBehaviors.of(resident).canBeReplacedBy(incoming);
    }

    /**
     * 汲水服务端执行：目标格是源 → 源消失（removeGeneratedSource）+ 桶灌入该源的流体一桶。
     * 前置校验由调用方完成（光标是空活桶 + 目标格 level 0）。
     */
    public static void scoop(ServerPlayer player, Slot slot, ItemStack carried) {
        ContainerFluidData fluidData = resolveFluidData(player, slot);
        if (fluidData == null) return;

        int containerSlot = slot.getContainerSlot();
        FluidType fluid = fluidData.sourceFluid(containerSlot);
        if (fluid == null) return;

        net.minecraft.world.level.material.Fluid fluidHolder = representativeFluid(fluid);
        if (fluidHolder == null) return;   // 未知流体（理论上不可达）—— 保守放弃，不丢源
        fluidData.removeGeneratedSource(containerSlot);
        ItemStack filled = new ItemStack(fluidHolder.getBucket());
        filled.applyComponents(carried.getComponents());   // 活标记
        filled.set(net.minecraft.core.component.DataComponents.MAX_STACK_SIZE,
            new ItemStack(filled.getItem()).getMaxStackSize());   // 同 withFluid：形态自然堆叠
        if (player.hasInfiniteMaterials()) {
            // 创造模式：光标直接变身（活空桶 → 活水桶）——
            // createFilledResult 的创造分支（原桶保留 + 水桶进背包）不符合 GUI 汲水语义（2026-10-06 实测）
            player.containerMenu.setCarried(filled);
            return;
        }
        // 生存：数量语义对齐原版 createFilledResult——空桶栈只消耗一个空桶，
        // 装好的桶进背包（放不下掉落）—— 光标堆叠多个活空桶汲水不再丢桶
        player.containerMenu.setCarried(ItemUtils.createFilledResult(carried, player, filled));
    }
}
