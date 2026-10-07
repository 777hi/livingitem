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
            // ⚠️ 2026-10-07 收尾审查：这两处原为 return ⇒ 首个槽位不满足就放弃整个清理；
            //    改 continue 继续找下一个可解析槽位（大箱子 / 多容器菜单）。
            if (key == null) continue;
            if (!CLIENT_ACTIVE.contains(key)) continue;   // 从未向其同步过 ⇒ 无残留可清
            player.connection.send(new FluidFlowSyncPacket(
                key, Math.max(1, ctx.getWidth()), List.of(), Map.of(), renderTargetOf(ctx)));
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

    /**
     * 快照的渲染目标（服务端权威判定，2026-10-06）—— 客户端<b>无法</b>从界面推断容器身份：
     * 末影箱 GUI 在客户端是 {@code GENERIC_9x3} + 27 格 {@code SimpleContainer} 替身，
     * 与普通 3 行箱子同形 ⇒ 只能由服务端告知。
     */
    public static com.qiqi.li.living.domain.water.FluidFlowClientCache.RenderTarget renderTargetOf(
            TickableContainerContext ctx) {
        if (ctx instanceof com.qiqi.li.living.container.EnderChestContainerContext) {
            return com.qiqi.li.living.domain.water.FluidFlowClientCache.RenderTarget.ENDER_CHEST;
        }
        if (ctx.getInventory() != null) {
            return com.qiqi.li.living.domain.water.FluidFlowClientCache.RenderTarget.PLAYER_INV;
        }
        return com.qiqi.li.living.domain.water.FluidFlowClientCache.RenderTarget.BLOCK;
    }

    /** 构建 flow 快照包；无流体数据时返回 null。流体按调色板去重（只用 Registry.getKey 接口）。 */
    public static FluidFlowSyncPacket buildPacket(String containerKey, int width, ContainerFluidData fluidData,
                                                  com.qiqi.li.living.domain.water.FluidFlowClientCache.RenderTarget target) {
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
        return new FluidFlowSyncPacket(containerKey, width, palette, cells, target);
    }

    /**
     * 流体 tick 后调用：向正在查看该容器的玩家下发快照。
     *
     * <p>⚠️ 2026-10-07 性能收尾（零行为变化）：两道短路提到最前面 ——
     * <b>① 空容器</b>（无数据且从未下发过 ⇒ 直接返回；真实存档里绝大多数容器没流体）；
     * <b>② 无 viewer</b>（先收集查看者，空则<b>连包都不建</b>）。
     * 详见 {@code docs/archive/living-fluid-perf-2026-10-07.md}。</p>
     */
    public static void flushAfterTick(TickableContainerContext ctx, ContainerFluidData fluidData) {
        Level level = ctx.getLevel();
        if (level == null || level.isClientSide) return;
        String key = ctx.getContainerKey();
        if (key == null) return;

        boolean hasData = fluidData != null && fluidData != ContainerFluidData.EMPTY && !fluidData.isEmpty();

        // ① 空容器短路：无数据 **且** 从未向其下发过 ⇒ 没有任何事要做（零开销）
        if (!hasData && !CLIENT_ACTIVE.contains(key)) return;

        if (!hasData) {
            // 数据刚清空（最后一个源被汲走/挤没）⇒ 下发一次空快照，清掉客户端残留渲染
            if (markActiveAndCheckClear(key, false)) {
                dispatch(level, ctx, new FluidFlowSyncPacket(
                    key, ctx.getWidth(), List.of(), Map.of(), renderTargetOf(ctx)));
            }
            return;
        }

        // ② 无 viewer 短路：没人看 ⇒ 不构建快照包（真实场景里同时被看的容器是个位数）
        List<ServerPlayer> viewers = viewersOf(level, ctx);
        if (viewers.isEmpty()) {
            markActiveAndCheckClear(key, true);   // 仍登记"活跃"，供将来打开时/清空边沿使用
            return;
        }

        CustomPacketPayload packet = buildPacket(key, ctx.getWidth(), fluidData, renderTargetOf(ctx));
        if (packet == null) return;
        markActiveAndCheckClear(key, true);
        for (ServerPlayer player : viewers) {
            player.connection.send(packet);
        }
    }

    /**
     * 收集该容器的<b>查看者</b>（2026-10-07：从 {@code dispatch} 拆出，供"无 viewer 不建包"复用）。
     *
     * <p>按容器归属：玩家背包 ⇒ 主人本人；末影箱 ⇒ 主人本人 <b>且必须正在看末影箱</b>；
     * BE 容器 ⇒ 按菜单槽位匹配（大箱子 {@code CompoundContainer} 特判）。</p>
     *
     * <p>未打开容器菜单的玩家在 {@code ContainerContexts.isViewing} 首行 O(1) 短路 ⇒
     * 百人服的实际开销只在"开着菜单的那几个人"身上。</p>
     */
    private static List<ServerPlayer> viewersOf(Level level, TickableContainerContext ctx) {
        List<ServerPlayer> out = new ArrayList<>(2);

        // 玩家背包：背包没有 BE 实例可匹配，直发主人本人（同 ContainerRuntimeCache 的玩家路径）
        Inventory inv = ctx.getInventory();
        if (inv != null && inv.player instanceof ServerPlayer owner) {
            out.add(owner);
            return out;
        }

        // 末影箱（F-1 配套，2026-10-05）：context 持有 player（inventory 为 null）。
        // ⚠️ 2026-10-06 第 ③ 次泄漏修复：**必须判 viewer**。此前无条件每 tick 直发 ⇒
        //   玩家关掉末影箱打开普通箱子后包仍在来 ⇒ 客户端 chestLikeTarget 提示被翻成
        //   ENDER_CHEST ⇒ 末影箱的水渲染到别的箱子界面上。
        //   末影箱无关联 BE ⇒ 必须 return，不能落到下面的 BE 匹配分支。
        if (ctx instanceof com.qiqi.li.living.container.EnderChestContainerContext ender
                && ender.getOwner() instanceof ServerPlayer owner) {
            if (shouldDispatch(ender, owner)) out.add(owner);
            return out;
        }

        // BE 容器：关联 BlockEntity 中的 Container 实例 ↔ 玩家菜单匹配
        List<Container> containers = new ArrayList<>();
        for (BlockEntity be : ctx.getAssociatedBlockEntities()) {
            if (be instanceof Container c) containers.add(c);
        }
        if (containers.isEmpty()) return out;

        for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
            if (isViewingContainer(player, containers)) out.add(player);
        }
        return out;
    }

    /** 按容器归属派发（保留：关闭清理等单次场景用）。 */
    private static void dispatch(Level level, TickableContainerContext ctx, CustomPacketPayload packet) {
        for (ServerPlayer player : viewersOf(level, ctx)) {
            player.connection.send(packet);
        }
    }

    /**
     * 末影箱快照是否应发给该玩家（包级私有，供单测）—— <b>必须判 viewer</b>。
     *
     * <p>2026-10-06 第 ③ 次泄漏修复的<b>接线点</b>：此前无条件每 tick 直发主人 ⇒
     * 玩家关掉末影箱打开普通箱子后包仍在来 ⇒ 客户端 {@code chestLikeTarget} 提示被翻成
     * {@code ENDER_CHEST} ⇒ 末影箱的水渲染到别的箱子界面上。判据见
     * {@code ContainerContexts.isViewingEnderChest}（唯一实现点）。</p>
     */
    static boolean shouldDispatch(TickableContainerContext ctx, ServerPlayer player) {
        if (ctx instanceof com.qiqi.li.living.container.EnderChestContainerContext) {
            return com.qiqi.li.living.container.ContainerContexts.isViewingEnderChest(player);
        }
        return true;   // 背包 / BE 容器走 dispatch 里各自的分支
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
