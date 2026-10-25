package com.qiqi.li.client.gui;

import java.util.ArrayList;
import java.util.List;
import com.qiqi.li.living.container.ContainerLayout;
import com.qiqi.li.living.container.ContainerMenuInfo;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

/** 容器提示只消费服务端结果，不根据菜单槽数重算布局。 */
public final class ContainerInfoTooltip {
    private ContainerInfoTooltip() {}

    public static List<Component> lines(ContainerMenuInfo info, boolean advanced) {
        List<Component> out = new ArrayList<>();
        out.add(Component.empty());
        if (info.status() == ContainerMenuInfo.Status.NON_HOST) {
            out.add(Component.translatable("text.livingitem.container_info.non_host").withStyle(ChatFormatting.GRAY));
            return out;
        }
        if (info.status() == ContainerMenuInfo.Status.UNAVAILABLE) {
            out.add(Component.translatable("text.livingitem.container_info.unavailable").withStyle(ChatFormatting.GRAY));
            return out;
        }
        String kind = info.kind().name().toLowerCase(java.util.Locale.ROOT);
        out.add(Component.translatable("text.livingitem.container_info.kind",
            Component.translatable("text.livingitem.container_info.kind." + kind)));
        out.add(Component.translatable("text.livingitem.container_info.size", info.size()).withStyle(ChatFormatting.GRAY));
        out.add(Component.translatable("text.livingitem.container_info.layout",
            info.layout().columns(), info.rows()).withStyle(ChatFormatting.GRAY));
        if (info.layout().source() == ContainerLayout.Source.HEURISTIC) {
            out.add(Component.translatable("text.livingitem.container_info.heuristic").withStyle(ChatFormatting.YELLOW));
            if (info.kind() == com.qiqi.li.living.container.ContainerKind.WORLD_BLOCK) {
                out.add(Component.translatable("text.livingitem.container_info.calibrate").withStyle(ChatFormatting.GRAY));
            }
        }
        if (advanced) {
            out.add(Component.translatable("text.livingitem.container_info.source",
                Component.translatable("text.livingitem.container_info.source."
                    + info.layout().source().name().toLowerCase(java.util.Locale.ROOT))).withStyle(ChatFormatting.DARK_GRAY));
            if (!info.typeId().isEmpty()) out.add(Component.literal(info.typeId()).withStyle(ChatFormatting.DARK_GRAY));
        }
        return out;
    }
}
