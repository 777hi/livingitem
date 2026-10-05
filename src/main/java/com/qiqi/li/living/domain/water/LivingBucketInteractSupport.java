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

    /** FluidType → 代表 Fluid（FluidStack 构造需要；优先 still 态），缓存反查结果。 */
    private static final Map<FluidType, net.minecraft.world.level.material.Fluid> REPRESENTATIVE_FLUID =
        new java.util.concurrent.ConcurrentHashMap<>();

    private static net.minecraft.world.level.material.Fluid representativeFluid(FluidType type) {
        if (REPRESENTATIVE_FLUID.containsKey(type)) return REPRESENTATIVE_FLUID.get(type);
        net.minecraft.world.level.material.Fluid found = null;
        for (net.minecraft.world.level.material.Fluid f : net.minecraft.core.registries.BuiltInRegistries.FLUID) {
            if (f.getFluidType() != type) continue;
            if (found == null || f.defaultFluidState().isSource()) found = f;
            if (found != null && found.defaultFluidState().isSource()) break;
        }
        REPRESENTATIVE_FLUID.put(type, found);
        return found;
    }

    private LivingBucketInteractSupport() {}

    /** 反查目标槽位所属容器的活流体数据；容器类型不支持（末影箱/模组非 BE 容器）返回 null。 */
    public static ContainerFluidData resolveFluidData(ServerPlayer player, Slot slot) {
        ContainerContext ctx = resolveContext(player, slot);
        if (ctx == null) return null;
        ContainerFluidData fluidData = ContainerLivingItemHandler.getFluidData(ctx);
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
     */
    public static void pour(ServerPlayer player, Slot slot, ItemStack carried) {
        Fluid fluid = LivingBucketFunction.getBucketFluid(carried);
        if (fluid == Fluids.EMPTY) return;

        ContainerFluidData fluidData = resolveFluidData(player, slot);
        if (fluidData == null) return;

        int containerSlot = slot.getContainerSlot();
        if (!fluidData.isSource(containerSlot)) {
            fluidData.registerGeneratedSource(containerSlot, fluid.getFluidType());
        }
        player.containerMenu.setCarried(LivingBucketFunction.withFluid(carried, Fluids.EMPTY));
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
