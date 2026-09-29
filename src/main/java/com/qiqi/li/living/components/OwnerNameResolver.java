package com.qiqi.li.living.components;

import javax.annotation.Nullable;

import java.util.UUID;
import java.util.function.Function;

/**
 * 主人 UUID → 玩家名的<b>可注入解析器</b>（公共侧桥，无客户端类引用）。
 *
 * <p><b>为什么需要间接层</b>：tooltip 在客户端组装（{@code LivingItemTooltip}），
 * 但调用方 {@code LivingToolFunction} 是服务端也会加载的公共类 ——
 * 直接 import {@code Minecraft} 等客户端类有专用服务器类加载崩溃的风险。
 * 故公共侧只留一个静态挂钩，由客户端入口（{@code LivingItemClient}）在
 * {@code FMLClientSetupEvent} 时注入真实实现；服务端永远不注入（服务端不渲染 tooltip）。</p>
 *
 * <p><b>未注入 / 解析不到时</b>返回 {@code null} —— 调用方自行回退到短 UUID 显示。</p>
 */
public final class OwnerNameResolver {

    private static Function<UUID, String> resolver = uuid -> null;

    private OwnerNameResolver() {
    }

    /** 客户端启动时注入真实解析实现（在线玩家 tab 列表 → UsernameCache）。 */
    public static void install(Function<UUID, String> clientResolver) {
        resolver = clientResolver;
    }

    /** 解析主人 UUID 对应的玩家名；{@code null} = 当前环境解析不到（调用方回退短 UUID）。 */
    @Nullable
    public static String resolve(UUID owner) {
        return resolver.apply(owner);
    }
}
