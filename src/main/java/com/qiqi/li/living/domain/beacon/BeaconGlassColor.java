package com.qiqi.li.living.domain.beacon;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntFunction;

import com.qiqi.li.living.model.Pos2D;
import com.qiqi.li.living.transfer.SlotResolver;

import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 容器槽位里的染色玻璃 ⇒ 光束颜色（设计稿 §2.2 U3 方案 A 的容器侧延伸，2026-10-16 用户需求
 * 「活信标上方槽位的玻璃也影响光束颜色」）。
 *
 * <p><b>语义</b>：容器 9 列网格里，信标<b>同列向上</b>的槽位序列等价于原版「信标正上方起
 * 可叠放的玻璃」——插在颜色扫描序列<b>最前面</b>（世界扫描接其后），按原版
 * 「第 1/2 个有色块都新建段」规则合成 ⇒ 与世界玻璃是<b>叠放关系，不冲突</b>。</p>
 *
 * <p><b>口径</b>：</p>
 * <ul>
 *   <li>取色 = {@code DyeColor.getTextureDiffuseColor()}——与 NeoForge 扩展点
 *       {@code IBlockStateExtension#getBeaconColorMultiplier} 对 {@code BeaconBeamBlock}
 *       的默认实现逐字一致；</li>
 *   <li>支持 16 色<b>染色玻璃</b> + <b>染色玻璃板</b>（对齐原版实现 {@code BeaconBeamBlock}
 *       的方块集）；普通玻璃 / 其他物品 ⇒ {@code null}（无色）；</li>
 *   <li><b>非玻璃物品不遮挡</b>（无色、继续往上扫）——容器是「染色配置层」不是世界层，
 *       不发明「物品透光性」概念；世界的遮挡归世界扫描（{@code blocksBeam}）管。</li>
 * </ul>
 */
public final class BeaconGlassColor {

    /** 物品 ⇒ 染料色（16 色 × 玻璃 / 玻璃板，共 32 项）。 */
    private static final Map<Item, DyeColor> BY_ITEM = buildMap();

    private BeaconGlassColor() {
    }

    private static Map<Item, DyeColor> buildMap() {
        Map<Item, DyeColor> map = new HashMap<>();
        for (DyeColor dye : DyeColor.values()) {
            String name = dye.name().toLowerCase(Locale.ROOT);   // white / light_blue / …
            map.put(glass(name), dye);
            map.put(pane(name), dye);
        }
        return Map.copyOf(map);
    }

    private static Item glass(String dyeName) {
        return switch (dyeName) {
            case "white" -> Items.WHITE_STAINED_GLASS;
            case "orange" -> Items.ORANGE_STAINED_GLASS;
            case "magenta" -> Items.MAGENTA_STAINED_GLASS;
            case "light_blue" -> Items.LIGHT_BLUE_STAINED_GLASS;
            case "yellow" -> Items.YELLOW_STAINED_GLASS;
            case "lime" -> Items.LIME_STAINED_GLASS;
            case "pink" -> Items.PINK_STAINED_GLASS;
            case "gray" -> Items.GRAY_STAINED_GLASS;
            case "light_gray" -> Items.LIGHT_GRAY_STAINED_GLASS;
            case "cyan" -> Items.CYAN_STAINED_GLASS;
            case "purple" -> Items.PURPLE_STAINED_GLASS;
            case "blue" -> Items.BLUE_STAINED_GLASS;
            case "brown" -> Items.BROWN_STAINED_GLASS;
            case "green" -> Items.GREEN_STAINED_GLASS;
            case "red" -> Items.RED_STAINED_GLASS;
            default -> Items.BLACK_STAINED_GLASS;   // black
        };
    }

    private static Item pane(String dyeName) {
        return switch (dyeName) {
            case "white" -> Items.WHITE_STAINED_GLASS_PANE;
            case "orange" -> Items.ORANGE_STAINED_GLASS_PANE;
            case "magenta" -> Items.MAGENTA_STAINED_GLASS_PANE;
            case "light_blue" -> Items.LIGHT_BLUE_STAINED_GLASS_PANE;
            case "yellow" -> Items.YELLOW_STAINED_GLASS_PANE;
            case "lime" -> Items.LIME_STAINED_GLASS_PANE;
            case "pink" -> Items.PINK_STAINED_GLASS_PANE;
            case "gray" -> Items.GRAY_STAINED_GLASS_PANE;
            case "light_gray" -> Items.LIGHT_GRAY_STAINED_GLASS_PANE;
            case "cyan" -> Items.CYAN_STAINED_GLASS_PANE;
            case "purple" -> Items.PURPLE_STAINED_GLASS_PANE;
            case "blue" -> Items.BLUE_STAINED_GLASS_PANE;
            case "brown" -> Items.BROWN_STAINED_GLASS_PANE;
            case "green" -> Items.GREEN_STAINED_GLASS_PANE;
            case "red" -> Items.RED_STAINED_GLASS_PANE;
            default -> Items.BLACK_STAINED_GLASS_PANE;   // black
        };
    }

    /** 单槽取色：染色玻璃 / 玻璃板 ⇒ ARGB 色；其他 / 空 ⇒ {@code null}。 */
    public static Integer of(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        DyeColor dye = BY_ITEM.get(stack.getItem());
        return dye != null ? dye.getTextureDiffuseColor() : null;
    }

    /**
     * 扫信标<b>同列向上</b>的玻璃序列（从紧贴信标上方那格开始，逐行往上直到容器顶）。
     *
     * @param slot   信标所在容器槽位
     * @param size   容器大小
     * @param width  容器宽（9 列网格）
     * @param getter 槽位 ⇒ 物品（服务端 {@code ContainerContext::getItem} / 客户端背包）
     * @return 色值列表（自下而上；非玻璃槽跳过不记，越界即停）
     */
    public static List<Integer> scanColumn(int slot, int size, int width,
                                           IntFunction<ItemStack> getter) {
        List<Integer> colors = new ArrayList<>(4);
        for (int row = 1; row <= size / width + 1; row++) {
            int target = SlotResolver.resolve(slot, new Pos2D(0, -row), size, width);
            if (target < 0) {
                break;
            }
            Integer color = of(getter.apply(target));
            if (color != null) {
                colors.add(color);
            }
        }
        return colors;
    }
}
