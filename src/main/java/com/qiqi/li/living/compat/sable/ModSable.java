package com.qiqi.li.living.compat.sable;

import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
            } catch (ClassNotFoundException e) {
                integrationAvailable = false;
                LOGGER.warn("[ModSable] SableIntegration class NOT available, airship teleport disabled");
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