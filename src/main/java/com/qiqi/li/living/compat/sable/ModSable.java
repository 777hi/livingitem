package com.qiqi.li.living.compat.sable;

import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 活物品与 Sable 模组（飞艇 / SubLevel 物理）的集成门面。
 *
 * <p>在 Sable 未加载或集成类不可用时安全地降级（返回 false / 禁用传送），
 * 对外提供「玩家是否在 SubLevel」「是否存在软连接」「SubLevel 传送」三类能力，
 * 实际实现委托给 {@link SableIntegration}。</p>
 */
public class ModSable {

    private static final Logger LOGGER = LoggerFactory.getLogger("LivingItem/Sable");

    private static boolean integrationChecked = false;
    private static boolean integrationAvailable = false;

    private ModSable() {}

    private static boolean isIntegrationAvailable() {
        if (!integrationChecked) {
            integrationChecked = true;
            try {
                Class.forName("com.qiqi.li.living.compat.sable.SableIntegration");
                integrationAvailable = true;
                LOGGER.info("[ModSable] SableIntegration class available");
            } catch (ClassNotFoundException | LinkageError e) {
                // ⚠️ LinkageError 一并接（2026-10-27 审查 P3）：Sable 按 ModList 在、但
                //    集成类链接失败的窄场景下，异常会穿透三个入口方法的 catch（它们只包
                //    SableIntegration.* 调用，不包本方法）直达传送主流程
                integrationAvailable = false;
                LOGGER.warn("[ModSable] SableIntegration class NOT available ({}), airship teleport disabled",
                    e.getClass().getSimpleName());
            }
        }
        return integrationAvailable;
    }

    public static boolean isPlayerOnSubLevel(ServerPlayer player) {
        if (!SableCompat.isLoaded()) return false;
        if (!isIntegrationAvailable()) return false;
        try {
            return SableIntegration.isPlayerOnSubLevel(player);
        } catch (NoClassDefFoundError e) {
            integrationAvailable = false;
            integrationChecked = false;
            LOGGER.error("[ModSable] Failed to call SableIntegration, disabling airship teleport", e);
            return false;
        }
    }

    public static boolean teleportSubLevel(ServerPlayer player, double destX, double destY, double destZ) {
        if (!SableCompat.isLoaded()) return false;
        if (!isIntegrationAvailable()) return false;
        try {
            return SableIntegration.teleportSubLevel(player, destX, destY, destZ);
        } catch (NoClassDefFoundError e) {
            integrationAvailable = false;
            integrationChecked = false;
            LOGGER.error("[ModSable] Failed to call SableIntegration, disabling airship teleport", e);
            return false;
        }
    }

    /**
     * 检测玩家所在载具是否存在软连接（绳索 / 关节）。
     *
     * <p>Sable 未加载或集成不可用时返回 false（无软连接），交由原版传送路径处理。
     */
    public static boolean hasSoftConnection(ServerPlayer player) {
        if (!SableCompat.isLoaded()) return false;
        if (!isIntegrationAvailable()) return false;
        try {
            return SableIntegration.hasSoftConnection(player);
        } catch (NoClassDefFoundError e) {
            integrationAvailable = false;
            integrationChecked = false;
            LOGGER.error("[ModSable] Failed to check soft connection, treating as none", e);
            return false;
        }
    }
}