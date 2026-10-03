package com.qiqi.li.living.domain.water;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.fluids.FluidType;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import com.qiqi.li.living.container.TickableContainerContext;
import com.qiqi.li.network.FluidFlowSyncPacket;

/**
 * 服务端容器流体快照同步（Q5 渲染轨，2026-10-03）。
 *
 * <p>把容器级 flow 表（slot → level / fromSlot / 流体类型）下发给正在查看该容器的玩家，
 * 供客户端水流渲染读取。<b>不依赖活水桶物品</b> —— 纯源容器（无任何活物品）的水也能渲染。
 * 与 {@code ContainerRuntimeCache} 同构的匹配逻辑（玩家背包 key 直发本人；BE 容器按
 * 菜单槽位匹配，大箱子 {@code CompoundContainer} 特判）。</p>
 *
 * <p>调用点：{@link LivingFluidFunction#tickContainerData} 尾部，流体非空即发（每 tick）。
 * 包很小（≤54 格 × 3 varint），且「打开后 1 tick 内出图、状态静止也持续刷新」的自愈语义
 * 比脏标记省包更重要。</p>
 */
public final class FluidFlowServerSync {

    private FluidFlowServerSync() {}

    /** 构建 flow 快照包；无流体数据时返回 null。流体按调色板去重（只用 Registry.getKey 接口）。 */
    public static FluidFlowSyncPacket buildPacket(String containerKey, int width, ContainerFluidData fluidData) {
        var flows = fluidData.getFlows();
        if (flows.isEmpty()) return null;

        Map<FluidType, Integer> paletteIndex = new HashMap<>();
        List<String> palette = new ArrayList<>();
        Map<Integer, int[]> cells = new HashMap<>(flows.size());
        for (var e : flows.entrySet()) {
            FluidType fluid = e.getValue().fluid();
            if (fluid == null) continue;
            Integer idx = paletteIndex.get(fluid);
            if (idx == null) {
                ResourceLocation key = NeoForgeRegistries.FLUID_TYPES.getKey(fluid);
                if (key == null) continue;
                idx = palette.size();
                paletteIndex.put(fluid, idx);
                palette.add(key.toString());
            }
            cells.put(e.getKey(), new int[]{e.getValue().level(), e.getValue().fromSlot(), idx});
        }
        if (cells.isEmpty()) return null;
        return new FluidFlowSyncPacket(containerKey, width, palette, cells);
    }

    /**
     * 流体 tick 后调用：向正在查看该容器的玩家下发快照。
     * 玩家背包（key = "player_<uuid>"）直发本人；BE 容器按菜单槽位匹配查看者。
     */
    public static void flushAfterTick(TickableContainerContext ctx, ContainerFluidData fluidData) {
        if (fluidData == null || fluidData == ContainerFluidData.EMPTY || fluidData.isEmpty()) return;
        Level level = ctx.getLevel();
        if (level == null || level.isClientSide) return;
        String key = ctx.getContainerKey();
        if (key == null) return;

        CustomPacketPayload packet = buildPacket(key, ctx.getWidth(), fluidData);
        if (packet == null) return;

        // 玩家背包：背包没有 BE 实例可匹配，直发主人本人（同 ContainerRuntimeCache 的玩家路径）
        Inventory inv = ctx.getInventory();
        if (inv != null && inv.player instanceof ServerPlayer owner) {
            owner.connection.send(packet);
            return;
        }

        // BE 容器：关联 BlockEntity 中的 Container 实例 ↔ 玩家菜单匹配
        List<Container> containers = new ArrayList<>();
        for (BlockEntity be : ctx.getAssociatedBlockEntities()) {
            if (be instanceof Container c) containers.add(c);
        }
        if (containers.isEmpty()) return;

        for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
            if (isViewingContainer(player, containers)) {
                player.connection.send(packet);
            }
        }
    }

    /**
     * 判断玩家菜单里是否包含「该容器关联的 Container 实例」。
     *
     * <p>Q6 收编（2026-10-04）：与 {@code ContainerRuntimeCache.isViewingContainer} 的
     * 同构实现已合并到 {@link com.qiqi.li.living.container.ContainerContexts#isViewing}，
     * 此处保留薄委托。</p>
     */
    private static boolean isViewingContainer(ServerPlayer player, List<Container> containerInstances) {
        return com.qiqi.li.living.container.ContainerContexts.isViewing(player, containerInstances);
    }
}
