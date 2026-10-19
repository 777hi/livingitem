package com.qiqi.li.living.compat.sophisticated;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.neoforged.fml.ModList;

import com.qiqi.li.living.container.MenuSlotOwners;

/**
 * 精妙背包联动模块的加载门面（与 {@code ModCreate} / {@code ModSable} 同构）。
 *
 * <p>只在模组存在时注册 {@link SophisticatedMenuResolver}；没装则<b>零开销、零影响</b>。</p>
 */
public final class ModSophisticated {

    public static final String MOD_ID = "sophisticatedbackpacks";

    private static final Logger LOGGER = LoggerFactory.getLogger("LivingItem/Sophisticated");

    private static Boolean loaded;

    private ModSophisticated() {}

    /** 缓存「是否装了精妙背包」。 */
    public static boolean isLoaded() {
        if (loaded == null) {
            loaded = ModList.get().isLoaded(MOD_ID);
        }
        return loaded;
    }

    /** 启动时调用一次（{@code LivingItem#commonSetup}）。 */
    public static void init() {
        if (!isLoaded()) return;
        MenuSlotOwners.registerMenuResolver(new SophisticatedMenuResolver());
        LOGGER.info("[ModSophisticated] 已注册菜单位置解析器（容器 GUI 的 tooltip / 流体渲染依赖它）");
    }
}
