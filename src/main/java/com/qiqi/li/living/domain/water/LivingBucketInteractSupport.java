package com.qiqi.li.living.domain.water;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.CompoundContainer;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidType;
import net.neoforged.neoforge.fluids.SimpleFluidContent;
import net.neoforged.neoforge.items.IItemHandler;

import com.qiqi.li.living.container.ContainerLivingItemHandler;
import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.container.SimpleContainerContext;

/**
 * 活桶汲/倒的服务端支撑（流体侧批次二，2026-10-03）——
 * 从菜单槽位反查<b>活着的</b> {@link ContainerFluidData}（必须是容器 tick 循环里的那份实例，
 * 改别处的副本会被下一 tick 重算覆盖）+ 桶内容增减。
 *
 * <p>context 反查方式镜像 {@code ContainerLivingItemHandler.processContainerAt} 的构建规则
 * （containerKey 由 positions/BEs 推导 ⇒ 同一容器同一键 ⇒ {@link
 * ContainerLivingItemHandler#getFluidData} 命中同一条目）：</p>
 * <ul>
 *   <li>玩家背包：{@code slot.container == player.getInventory()}</li>
 *   <li>单 BE 容器：{@code slot.container instanceof BlockEntity}</li>
 *   <li>原版大箱子：{@code CompoundContainer}（半箱无公开访问器 ⇒ 反射取两半，
 *       项目已有 SlotWrapper 反射先例），顺序经 {@code DoubleChestPositions.find} 规范化</li>
 * </ul>
 * ⚠️ 末影箱菜单的 containerKey 由 {@code EnderChestContainerContext} 覆写为玩家键，
 * 本支撑解析不出 → 返回 null ⇒ 汲/倒静默无效（末影箱水网 = 已知缺口）。
 */
public final class LivingBucketInteractSupport {

    private static final Map<Class<?>, Field[]> COMPOUND_FIELDS = new java.util.concurrent.ConcurrentHashMap<>();

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

    /** 反查目标槽位所属容器的 tick 上下文（容器键与 tick 循环一致）。不支持返回 null。 */
    public static ContainerContext resolveContext(ServerPlayer player, Slot slot) {
        Level level = player.level();
        Container container = slot.container;

        if (container == player.getInventory()) {
            IItemHandler handler = player.getCapability(Capabilities.ItemHandler.ENTITY);
            if (handler == null) return null;
            return new SimpleContainerContext(handler, player.getInventory(), new ArrayList<>(),
                new ArrayList<>(), level);
        }

        if (container instanceof BlockEntity be) {
            if (be.getLevel() == null) return null;
            return beContext(level, List.of(be.getBlockPos()), List.of(be));
        }

        if (container instanceof CompoundContainer) {
            return compoundContext(level, container);
        }

        return null;
    }

    private static ContainerContext beContext(Level level, List<BlockPos> positions, List<BlockEntity> blockEntities) {
        for (BlockEntity be : blockEntities) {
            if (be instanceof RandomizableContainerBlockEntity rc && rc.getLootTable() != null) return null;
        }
        IItemHandler handler = level.getCapability(
            Capabilities.ItemHandler.BLOCK, positions.get(0), null);
        if (handler == null) return null;
        return new SimpleContainerContext(handler, null, new ArrayList<>(positions),
            new ArrayList<>(blockEntities), level);
    }

    /**
     * 大箱子：反射取 CompoundContainer 的两半（container1 / container2），
     * 顺序用 {@code DoubleChestPositions.find} 规范化（LEFT 在前，与 tick 构建的容器键一致）。
     */
    private static ContainerContext compoundContext(Level level, Container container) {
        BlockEntity[] halves = reflectHalves(container);
        if (halves == null) return null;

        // 顺序规范化：用 mod 自己的大箱子解析器（与 processContainerAt 同源），保证容器键一致
        List<BlockPos> doubleChestPos = com.qiqi.li.living.util.DoubleChestPositions
            .find(level, halves[0].getBlockPos());
        if (doubleChestPos.isEmpty()) return null;

        List<BlockEntity> blockEntities = new ArrayList<>();
        for (BlockPos pos : doubleChestPos) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be == null) return null;
            blockEntities.add(be);
        }
        return beContext(level, doubleChestPos, blockEntities);
    }

    private static BlockEntity[] reflectHalves(Container container) {
        Field[] fields = COMPOUND_FIELDS.computeIfAbsent(CompoundContainer.class, clazz -> {
            List<Field> found = new ArrayList<>();
            for (Field f : clazz.getDeclaredFields()) {
                if (f.getType() == Container.class) {
                    f.setAccessible(true);
                    found.add(f);
                }
            }
            return found.toArray(new Field[0]);
        });
        if (fields.length < 2) return null;
        try {
            Object a = fields[0].get(container);
            Object b = fields[1].get(container);
            if (a instanceof BlockEntity be1 && b instanceof BlockEntity be2) {
                return new BlockEntity[]{be1, be2};
            }
        } catch (IllegalAccessException e) {
            return null;
        }
        return null;
    }

    // ── 桶内容增减（倒/汲各一）────────────────────────────────

    /**
     * 倒水服务端执行：目标格空且无源 → 诞生派生源；已有源 → 源不变（原版语义）。
     * 两种情况桶都排空一桶。前置校验由调用方完成（光标是满活桶 + 目标格无物品）。
     */
    public static void pour(ServerPlayer player, Slot slot, ItemStack carried) {
        SimpleFluidContent content = LivingBucketFunction.getContent(carried);
        if (content.isEmpty() || content.getAmount() < FluidType.BUCKET_VOLUME) return;

        ContainerFluidData fluidData = resolveFluidData(player, slot);
        if (fluidData == null) return;

        int containerSlot = slot.getContainerSlot();
        if (!fluidData.isSource(containerSlot)) {
            fluidData.registerGeneratedSource(containerSlot, content.getFluidType());
        }
        LivingBucketFunction.setContent(carried, SimpleFluidContent.copyOf(
            content.copy().copyWithAmount(content.getAmount() - FluidType.BUCKET_VOLUME)));
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
        LivingBucketFunction.setContent(carried, SimpleFluidContent.copyOf(
            new net.neoforged.neoforge.fluids.FluidStack(fluidHolder, FluidType.BUCKET_VOLUME)));
    }
}
