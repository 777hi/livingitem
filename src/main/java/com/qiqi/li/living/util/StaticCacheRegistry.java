package com.qiqi.li.living.util;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import net.minecraft.server.MinecraftServer;

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
 * <p><b>新增 static 缓存 ⇒ 必须登记</b>（服务端 {@link #onServerStop} / 客户端 {@link #onClientLogout}）。
 * 登记即被对应生命周期覆盖，无需再去改事件处理器。</p>
 *
 * <h3>⭐ 登记位置：缓存类<b>自己</b>的 {@code static {}} 块（2026-10-12 改）</h3>
 * <p>此前约定「领域的缓存在该领域的 {@code XxxRegistration} 里登记」—— 实测出问题：
 * 缓存类与登记点在<b>两个文件</b>，写完 {@code clear()} 却忘接线时<b>静默失效</b>。
 * 2026-10-12 扫出 3 处（{@code LivingToolPlayerClientCache} / {@code LivingToolAssistState} /
 * {@code EnderChannelClientCache} 的 {@code clear()} 零调用者，javadoc 却都写着「XX 时调用」）。</p>
 * <p>⇒ 改为<b>缓存类在自己的 {@code static {}} 块里登记自己</b>：登记与 {@code clear()}
 * 在<b>同一文件、隔几行</b> ⇒ 「写了 clear 却忘登记」在结构上几乎不可能（判据见
 * {@code docs/README.md} 元定义：违反它的代价极高）。分层也干净 —— 本类在 <b>L1</b>，
 * 任何缓存类调它都是<b>向下依赖</b>（领域 L3 → L1 合法）。</p>
 * <pre>
 * public final class XxxCache {
 *     private static final Map&lt;K, V&gt; store = new HashMap&lt;&gt;();
 *     static {                                   // ← 与 clear() 同文件，忘不了
 *         StaticCacheRegistry.onServerStop(XxxCache::clear);
 *     }
 *     public static void clear() { store.clear(); }
 * }
 * </pre>
 * <p>⚠️ <b>注册表类不参与</b>：它们跨存档无害，本来就不写 {@code clear()}
 * （如 {@code InteractionRegistry} / {@code FluidTransformTable}）⇒ 天然不登记。
 * 「写了 {@code clear()}」本身就是「作者认为需要清」的信号。</p>
 * <p>⚠️ <b>静态块 = 类加载时登记</b>：若某缓存类从未被加载，它也就从未产生数据 ⇒ 不需要清
 * （登记缺失与「无数据」自洽）。</p>
 *
 * <p>表结构：服务端 / 客户端各一张，登记顺序 = 执行顺序。服务端项可读
 * {@link MinecraftServer} 上下文（如待炸账本按维度清调度表）。
 * 集中登记改为分散后，用 {@link #describe()} 补回「一览性」（列出各登记项所属类）。</p>
 */
public final class StaticCacheRegistry {

    /** 服务端关闭（{@code ServerStoppedEvent}）时执行的清理项，按登记顺序。 */
    private static final List<Consumer<MinecraftServer>> SERVER = new ArrayList<>();

    /** 客户端断连（{@code ClientPlayerNetworkEvent.LoggingOut}）时执行的清理项，按登记顺序。 */
    private static final List<Runnable> CLIENT = new ArrayList<>();

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

    /**
     * 诊断总览：列出已登记项<b>所属的类</b>（集中登记改为「各缓存类自己登记」后的一览入口）。
     *
     * <p>lambda / 方法引用的类名形如 {@code com.qiqi.li...XxxCache$$Lambda$123/0x...}
     * ⇒ 截到 {@code $$Lambda$} 之前即为登记点所属类（登记写在缓存类自己的静态块里）。</p>
     */
    public static List<String> describe() {
        List<String> out = new ArrayList<>();
        for (Consumer<MinecraftServer> c : SERVER) out.add("server  " + ownerOf(c));
        for (Runnable c : CLIENT) out.add("client  " + ownerOf(c));
        return out;
    }

    private static String ownerOf(Object lambda) {
        String name = lambda.getClass().getName();
        int mark = name.indexOf("$$Lambda$");
        return mark > 0 ? name.substring(0, mark) : name;
    }
}
