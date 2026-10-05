package com.qiqi.li.living.util;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import net.minecraft.server.MinecraftServer;

import com.qiqi.li.living.container.ContainerChunkCache;
import com.qiqi.li.living.container.ContainerLivingItemHandler;
import com.qiqi.li.living.domain.ender.EnderChannelRegistry;
import com.qiqi.li.living.domain.runtime.LivingItemClientCache;
import com.qiqi.li.living.domain.tnt.ExplosionLedger;
import com.qiqi.li.living.domain.tools.LivingToolFakePlayerCache;
import com.qiqi.li.living.domain.tools.LivingToolHostSync;
import com.qiqi.li.living.domain.water.FluidFlowClientCache;
import com.qiqi.li.living.domain.water.FluidFlowServerSync;

/**
 * <b>static 缓存清理注册表</b>（F-2，2026-10-05）—— 治本：把「自愿挂靠」改为「登记一行即被覆盖」。
 *
 * <h3>为什么存在</h3>
 * <p>单机「退出存档 → 进另一个存档」<b>不重启 JVM</b>：static 字段跨存档存活。此前清理是
 * <b>自愿挂靠</b> —— 清理散落在 {@code LivingItem.onServerStopped}（服务端）与
 * {@code FluidClientCacheCleanup.onLoggingOut}（客户端）两处，靠记性挂。两次漏挂事故
 * （Q3「测试也是调用者」、流体侧 {@code CLIENT_ACTIVE} 未挂 {@code ServerStopped}）
 * 都源于此。</p>
 *
 * <h3>🔴 铁律（A1 成文）</h3>
 * <p><b>新增 static 缓存 ⇒ 必须在本表登记一行</b>（服务端 {@link #onServerStop} /
 * 客户端 {@link #onClientLogout}）。登记即被对应生命周期覆盖，无需再去改事件处理器。</p>
 *
 * <p>表结构：服务端 / 客户端各一张，登记顺序 = 执行顺序。服务端项可读
 * {@link MinecraftServer} 上下文（如待炸账本按维度清调度表）。</p>
 */
public final class StaticCacheRegistry {

    /** 服务端关闭（{@code ServerStoppedEvent}）时执行的清理项，按登记顺序。 */
    private static final List<Consumer<MinecraftServer>> SERVER = new ArrayList<>();

    /** 客户端断连（{@code ClientPlayerNetworkEvent.LoggingOut}）时执行的清理项，按登记顺序。 */
    private static final List<Runnable> CLIENT = new ArrayList<>();

    static {
        // ── 服务端：ServerStopped 覆盖（原 LivingItem.onServerStopped 的 7 项）──
        onServerStop(s -> EnderChannelRegistry.getInstance().clearAll());
        onServerStop(s -> ContainerChunkCache.getInstance().clear());
        onServerStop(s -> ContainerLivingItemHandler.clearAllCaches());
        // 流体快照同步的「曾下发过」边沿集（2026-10-04）：跨存档不清会在新世界对同键容器误发一次空快照
        onServerStop(s -> FluidFlowServerSync.clearRuntimeState());
        // 待炸账本的**内存调度表**要清（条目本身随存档走，不需要清）
        onServerStop(ExplosionLedger::clearAllRuntimeState);
        // 活工具的 FakePlayer 缓存（L26）：维度+主人 keyed，跨存档必须清
        onServerStop(s -> LivingToolFakePlayerCache.clear());
        // 活工具容器同步（K2）：每个玩家的「上次发出内容」，跨存档必须清
        onServerStop(s -> LivingToolHostSync.clear());

        // ── 客户端：LoggingOut 覆盖（原 FluidClientCacheCleanup 的 2 项）──
        // 界面 removed() 清缓存与「退出存档」之间有竞态窗口（服务端最后一拍仍可能发包）
        onClientLogout(FluidFlowClientCache::clear);
        onClientLogout(LivingItemClientCache::clear);   // 运行时遥测缓存同病同修
    }

    private StaticCacheRegistry() {}

    /** 登记一个「服务端关闭」清理项（可读 server 上下文）。 */
    public static void onServerStop(Consumer<MinecraftServer> cleanup) {
        SERVER.add(cleanup);
    }

    /** 登记一个「客户端断连」清理项。 */
    public static void onClientLogout(Runnable cleanup) {
        CLIENT.add(cleanup);
    }

    /** 服务端关闭：执行全部登记项（由 {@code LivingItem.onServerStopped} 调用）。 */
    public static void runServer(MinecraftServer server) {
        for (Consumer<MinecraftServer> c : SERVER) c.accept(server);
    }

    /** 客户端断连：执行全部登记项（由 {@code FluidClientCacheCleanup.onLoggingOut} 调用）。 */
    public static void runClient() {
        for (Runnable c : CLIENT) c.run();
    }
}
