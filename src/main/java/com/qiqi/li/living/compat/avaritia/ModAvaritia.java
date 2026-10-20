package com.qiqi.li.living.compat.avaritia;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.neoforged.fml.ModList;

import com.qiqi.li.living.container.MenuSlotOwners;

/**
 * 无尽箱子（AvaritiaNeo）联动模块的加载门面（与 {@code ModCreate} / {@code ModSable} /
 * {@code ModSophisticated} 同构）。
 *
 * <p>只在模组存在时注册 {@link AvaritiaMenuResolver}；没装则<b>零开销、零影响</b>。</p>
 *
 * <h3>为什么需要它</h3>
 * <p>无尽箱子的 {@code TileInfinityChest} <b>不实现</b> {@code Container}（内部是
 * {@code SimpleContainer}），菜单槽的 container 就是这个 {@code SimpleContainer} ⇒
 * {@code MenuSlotOwners} 的结构化分支拿不到位置；菜单本身也<b>没有</b>
 * {@code getBlockPosition()}。三条路都断 ⇒ viewer 匹配失败 ⇒ 图标 / tooltip / 流体渲染不刷新，
 * GUI 倒汲也会静默无效。</p>
 *
 * <p>它的菜单有 {@code getTile()} 返回 {@code TileInfinityChest}（{@code BlockEntity}），
 * 所以本模块只需把「菜单 → 方块实体 → 位置」这条链补上。</p>
 */
public final class ModAvaritia {

    public static final String MOD_ID = "avaritia";

    private static final Logger LOGGER = LoggerFactory.getLogger("LivingItem/Avaritia");

    private static Boolean loaded;

    private ModAvaritia() {}

    /** 缓存「是否装了 AvaritiaNeo」。 */
    public static boolean isLoaded() {
        if (loaded == null) {
            loaded = ModList.get().isLoaded(MOD_ID);
        }
        return loaded;
    }

    /** 启动时调用一次（{@code LivingItem#commonSetup}）。 */
    public static void init() {
        if (!isLoaded()) return;
        MenuSlotOwners.registerMenuResolver(new AvaritiaMenuResolver());
        LOGGER.info("[ModAvaritia] 已注册菜单位置解析器（无尽箱子的 tooltip / 流体渲染依赖它）");
    }
}
