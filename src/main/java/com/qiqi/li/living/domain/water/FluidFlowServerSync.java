package com.qiqi.li.living.domain.water;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
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
@EventBusSubscriber
public final class FluidFlowServerSync {

    /**
     * 曾向客户端下发过快照的容器键 —— 用于「数据刚清空」的边沿检测：
     * 汲走最后一个源后 fluidData 变空，驱动的 {@code !isEmpty()} 门会跳过 tick，
     * 若不同步下发一次<b>空快照</b>，客户端缓存里的旧水将永远不被清除。
     * 无流体容器不在此集合 ⇒ 每拍只多一次 Set 查询，零发包开销。
     */
    private static final Set<String> CLIENT_ACTIVE = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private FluidFlowServerSync() {}

    /** 边沿状态机（包级私有供测试）：返回「本次是否需要下发清空包」。 */
    static boolean markActiveAndCheckClear(String key, boolean hasData) {
        boolean wasActive = CLIENT_ACTIVE.remove(key);
        if (hasData) CLIENT_ACTIVE.add(key);
        return !hasData && wasActive;
    }

    /**
     * 玩家关闭容器（2026-10-04）：立即向**该玩家**下发一次空快照（若此容器曾向其同步）。
     *
     * <p>覆盖关闭后的**在途包竞态**：客户端 {@code removed()} 清缓存之后，服务端最后一拍
     * 仍可能把玩家当 viewer 发出快照包并被无条件写入 ⇒ 残留跨界面/跨破坏存活
     * （实测：关箱子快开背包，合成槽位渲染旧水；破坏重放同键容器首开残留）。
     * 本包在服务端处理完关闭**之后**发出 ⇒ 同连接 FIFO 保证最后落地的是空快照。
     * 其它仍在查看的玩家不受影响（正常 per-tick 同步继续）。</p>
     */
    @SubscribeEvent
    public static void onContainerClose(net.neoforged.neoforge.event.entity.player.PlayerContainerEvent.Close event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        AbstractContainerMenu menu = event.getContainer();
        for (Slot slot : menu.slots) {
            if (slot.container == player.getInventory()) continue;
            TickableContainerContext ctx = com.qiqi.li.living.container.ContainerContexts.resolve(player, slot);
            if (ctx == null) continue;   // 末影箱等解析不出 ⇒ 跳过
            String key = ctx.getContainerKey();
            if (key == null) return;
            if (!CLIENT_ACTIVE.contains(key)) return;   // 从未向其同步过 ⇒ 无残留可清
            player.connection.send(new FluidFlowSyncPacket(
                key, Math.max(1, ctx.getWidth()), List.of(), Map.of()));
            return;   // 一个容器一条键，首个可解析槽位即定
        }
    }

    /** 测试用：清空边沿状态。 */
    public static void clearForTest() {
        CLIENT_ACTIVE.clear();
    }

    /** 生产清理（ServerStopped 挂钩）：单人「退存档→进另一存档」不重启 JVM，跨存档必须清。 */
    public static void clearRuntimeState() {
        CLIENT_ACTIVE.clear();
    }

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
        Level level = ctx.getLevel();
        if (level == null || level.isClientSide) return;
        String key = ctx.getContainerKey();
        if (key == null) return;

        boolean hasData = fluidData != null && fluidData != ContainerFluidData.EMPTY && !fluidData.isEmpty();
        boolean needClear = markActiveAndCheckClear(key, hasData);
        if (!hasData) {
            if (needClear) {
                // 数据刚清空（最后一个源被汲走/挤没）⇒ 下发一次空快照，清掉客户端残留渲染
                dispatch(level, ctx, new FluidFlowSyncPacket(key, ctx.getWidth(), List.of(), Map.of()));
            }
            return;
        }

        CustomPacketPayload packet = buildPacket(key, ctx.getWidth(), fluidData);
        if (packet == null) return;
        dispatch(level, ctx, packet);
    }

    /** 按容器归属派发：玩家背包直发本人；BE 容器按菜单匹配查看者（含大箱子特判）。 */
    private static void dispatch(Level level, TickableContainerContext ctx, CustomPacketPayload packet) {

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
