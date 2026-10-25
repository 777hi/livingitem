package com.qiqi.li.living.container;

import com.qiqi.li.logging.ModLog;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;

/** 只解析当前玩家菜单，不从 GUI 名称或客户端槽数推断宿主。 */
public final class ContainerMenuInfos {
    private ContainerMenuInfos() {}

    public static ContainerMenuInfo resolve(ServerPlayer player) {
        AbstractContainerMenu menu = player.containerMenu;
        if (menu == null) return ContainerMenuInfo.UNAVAILABLE;
        try {
            MenuType<?> type = null;
            if (menu != player.inventoryMenu) {
                try {
                    type = menu.getType();
                } catch (UnsupportedOperationException ignored) {
                    // 无注册类型的菜单仍可有运行宿主，继续按槽位解析。
                }
            }
            if (type != null && isNonHost(type)) {
                return ContainerMenuInfo.NON_HOST;
            }
            for (var slot : menu.slots) {
                if (menu != player.inventoryMenu && slot.container == player.getInventory()) continue;
                var ctx = ContainerContexts.resolve(player, slot);
                if (ctx == null) continue;
                ContainerKind kind = ContainerKeys.kindOf(ctx.getContainerKey());
                if (kind == null) return ContainerMenuInfo.UNAVAILABLE;
                String typeId = "";
                if (!ctx.getAssociatedBlockEntities().isEmpty()) {
                    var id = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(
                        ctx.getAssociatedBlockEntities().getFirst().getType());
                    if (id != null) typeId = id.toString();
                } else if (ctx instanceof EntityContainerContext entityCtx) {
                    typeId = BuiltInRegistries.ENTITY_TYPE.getKey(entityCtx.getEntity().getType()).toString();
                }
                return new ContainerMenuInfo(ContainerMenuInfo.Status.AVAILABLE,
                    kind, ctx.getSize(), ctx.getLayout(), typeId);
            }
            return ContainerMenuInfo.UNAVAILABLE;
        } catch (RuntimeException e) {
            ModLog.CONTAINER.warn("菜单基本信息解析失败：{}：{}", menu.getClass().getName(), e.toString());
            return ContainerMenuInfo.UNAVAILABLE;
        }
    }

    static boolean isNonHost(MenuType<?> type) {
        return type == MenuType.CRAFTING || type == MenuType.ANVIL || type == MenuType.MERCHANT
            || type == MenuType.LOOM || type == MenuType.STONECUTTER || type == MenuType.CARTOGRAPHY_TABLE
            || type == MenuType.GRINDSTONE || type == MenuType.SMITHING || type == MenuType.ENCHANTMENT
            || type == MenuType.BEACON || type == MenuType.LECTERN;
    }
}
