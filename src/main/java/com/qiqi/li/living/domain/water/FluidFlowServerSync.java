package com.qiqi.li.living.domain.water;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
import net.neoforged.neoforge.fluids.FluidType;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import com.qiqi.li.living.container.ContainerViewers;
import com.qiqi.li.living.container.TickableContainerContext;
import com.qiqi.li.living.util.StaticCacheRegistry;

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
     * 曾向客户端下发过快照的<b>缓存键</b>—— 用于「数据刚清空」的边沿检测：
     * 汲走最后一个源后 fluidData 变空，驱动的 {@code !isEmpty()} 门会跳过 tick，
     * 若不同步下发一次<b>空快照</b>，客户端缓存里的旧水将永远不被清除。
     * 无流体容器不在此集合 ⇒ 每拍只多一次 Set 查询，零发包开销。
     *
     * <p>⚠️ 键取 {@code ctx.getCacheKey()}（2026-10-12 改，原为裸 containerKey）——
     * 主世界 / 下界同坐标的两个容器会共用裸键 ⇒ 一边沿检测互相打脸
     * （一维度的「刚清空」被另一维度的「仍有数据」吞掉 ⇒ 客户端旧水残留）。</p>
     */
    private static final Set<String> CLIENT_ACTIVE = java.util.concurrent.ConcurrentHashMap.newKeySet();

    static {
        // static 缓存清理：登记与 clearRuntimeState() 同文件（见 StaticCacheRegistry 类注释「登记位置」）
        StaticCacheRegistry.onServerStop(s -> FluidFlowServerSync.clearRuntimeState());
    }

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

        // 物品形态容器（活背包，#6 菜单解析）：解析结果**与具体槽位无关**（它是菜单级的）
        // ⇒ 必须在循环外问一次。
        //
        // ⚠️ 2026-10-23 真机实录：这段原本放在下面的逐槽循环里 ⇒ 同一个菜单被问 N 遍
        // （N = 菜单槽位数，实测 60 次）。每次都会调到精妙的
        // `BackpackContext.Item#getBackpackWrapper`，而锚点失效时它**每次都打一条 ERROR**
        // ⇒ 关一次界面刷 60 条别人家的错误日志。更糟的是那 60 次里没有一次能解析出容器
        // （锚点失效 ⇒ 全部返回 null）⇒ 纯浪费。
        com.qiqi.li.living.container.ItemFormContainers.Handle itemForm =
            com.qiqi.li.living.container.ItemFormContainers.handleOf(menu, player);
        if (itemForm != null) {
            // 与 ContainerContexts.resolve 的物品形态分支同构（同一构造式 ⇒ 同一缓存键）
            TickableContainerContext ctx = new com.qiqi.li.living.container.ItemFormContainerContext(
                itemForm.handler(), itemForm.containerUuid(), player.level(), itemForm.columns());
            String key = ctx.getCacheKey();
            if (key != null && CLIENT_ACTIVE.contains(key)) {
                player.connection.send(new FluidFlowSyncPacket(
                    key, Math.max(1, ctx.getWidth()), List.of(), Map.of(), renderTargetOf(ctx)));
            }
            return;   // 物品形态容器是菜单级的：问过一次就够了，别再逐槽问
        }

        for (Slot slot : menu.slots) {
            if (slot.container == player.getInventory()) continue;
            TickableContainerContext ctx = com.qiqi.li.living.container.ContainerContexts.resolve(player, slot);
            if (ctx == null) continue;   // 末影箱等解析不出 ⇒ 跳过
            // 缓存键（世界容器带维度前缀）—— 必须与 flushAfterTick 登记的键同源
            String key = ctx.getCacheKey();
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
    public static FluidFlowSyncPacket buildPacket(com.qiqi.li.living.container.ContainerContext ctx,
                                                  String containerKey, int width, ContainerFluidData fluidData,
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
            // ⚠️ 槽号口径：flows 的键是【逻辑槽】，而客户端按菜单槽的
            // {@code getContainerSlot()}（物理）分组匹配 ⇒ 必须映射，否则整层水画偏一格
            int physicalSlot = ctx.toPhysicalSlot(e.getKey());
            int physicalFrom = e.getValue().fromSlot() < 0
                ? -1 : ctx.toPhysicalSlot(e.getValue().fromSlot());
            cells.put(physicalSlot, new int[]{e.getValue().level(), physicalFrom, idx});
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
     * 详见 {@code docs/tech/living-fluid-tech.md §11}。</p>
     */
    public static void flushAfterTick(TickableContainerContext ctx, ContainerFluidData fluidData) {
        Level level = ctx.getLevel();
        if (level == null || level.isClientSide) return;
        // 缓存键（世界容器带维度前缀，见 ContainerIdentity.getCacheKey）——
        // 与 onContainerClose 的清理键同源，跨维度同坐标不互相打脸
        String key = ctx.getCacheKey();
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

        CustomPacketPayload packet = buildPacket(ctx, key, ctx.getWidth(), fluidData, renderTargetOf(ctx));
        if (packet == null) return;
        markActiveAndCheckClear(key, true);
        for (ServerPlayer player : viewers) {
            player.connection.send(packet);
        }
    }

    /**
     * 收集该容器的<b>查看者</b> —— 薄委托到 {@link ContainerViewers}（唯一判定入口）。
     *
     * <p>⚠️ 这里<b>不再自己按 kind 分发</b>：那条 if-chain 与
     * {@code LivingItemRuntimeSync.flush} 各有一份，2026-10-23 因此漏修过一次
     * （流体轨修了 #7、运行时轨没修 ⇒ tooltip 不显示容器信息）。
     * 现在两条轨都调 {@link ContainerViewers#of}，新形态只要改一处。</p>
     */
    private static List<ServerPlayer> viewersOf(Level level, TickableContainerContext ctx) {
        // ⚠️ getServer() 可能为 null（客户端 / 测试替身）⇒ 空玩家表而不是 NPE。
        // 旧实现只在「需要遍历在线玩家」的分支里才调它，玩家态分支从 ctx 拿主人 ⇒ 对
        // mock Level 不崩；统一入口需要玩家表，故在此兜住。
        net.minecraft.server.MinecraftServer server = level.getServer();
        List<ServerPlayer> players =
            server == null ? List.of() : server.getPlayerList().getPlayers();
        return ContainerViewers.of(players, ctx.getCacheKey(), ctx.getAssociatedBlockPositions());
    }

    /** 按容器归属派发（保留：关闭清理等单次场景用）。 */
    private static void dispatch(Level level, TickableContainerContext ctx, CustomPacketPayload packet) {
        for (ServerPlayer player : viewersOf(level, ctx)) {
            player.connection.send(packet);
        }
    }
}
