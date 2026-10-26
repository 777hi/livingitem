package com.qiqi.li.living.domain.backpack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;

import com.qiqi.li.living.container.ContainerUuids;

/**
 * 活背包 tooltip 的<b>容器 ID 行</b>（2026-10-26，用户定）。
 *
 * <h3>守什么</h3>
 * <p>创造模式中键复制会把 {@code CONTAINER_UUID} 一起复制 ⇒ 两个背包共享同一份容器级数据。
 * 我们拦不住所有复制路径（原版中键 / {@code /give} / NBT 粘贴），但必须给玩家一条
 * <b>可自查</b>的线索：两个背包 ID 相同 ⇒ 是复制品 ⇒ 取消活化再重新活化即可。
 *
 * <p>⇒ 这里钉三件事：短码固定 8 位（前导零不能丢，否则 {@code substring} 越界）、
 * 没身份时不输出空 ID 行、<b>复制品的短码必须相同</b>（否则这个 tooltip 就没意义）。</p>
 *
 * <p>⚠️ 被测方法不查 {@code canApply}，所以用普通物品构造即可 —— 它只读容器 UUID。</p>
 */
class LivingBackpackTooltipTest {

    private static List<Component> tooltip(ItemStack stack, TooltipFlag flag) {
        List<Component> out = new ArrayList<>();
        new LivingBackpackFunction().addToTooltip(Item.TooltipContext.EMPTY, out::add, flag, stack);
        return out;
    }

    private static String keyOf(Component c) {
        return assertInstanceOf(TranslatableContents.class, c.getContents()).getKey();
    }

    private static Object argOf(Component c, int index) {
        return assertInstanceOf(TranslatableContents.class, c.getContents()).getArgs()[index];
    }

    private static ItemStack anyStack() {
        return new ItemStack(Items.DIAMOND_PICKAXE);
    }

    @Test
    @DisplayName("活化后 ⇒ 标题 + 容器 ID 短码（8 位十六进制）")
    void activated_showsShortId() {
        ItemStack stack = anyStack();
        ContainerUuids.getOrCreateItem(stack);

        List<Component> out = tooltip(stack, TooltipFlag.NORMAL);

        assertEquals(3, out.size(), "分隔空行 + 标题 + 一行 ID");
        assertEquals("tooltip.livingitem.backpack.status", keyOf(out.get(1)));
        assertEquals("tooltip.livingitem.backpack.container_id", keyOf(out.get(2)));

        String id = (String) argOf(out.get(2), 0);
        assertEquals(8, id.length(), "短码固定 8 位（前导零丢了会 substring 越界）");
        assertTrue(id.matches("[0-9a-f]{8}"), "应当是 8 位小写十六进制：" + id);
    }

    @Test
    @DisplayName("未活化（没有 UUID）⇒ 只有标题，不输出空 ID 行")
    void notActivated_titleOnly() {
        List<Component> out = tooltip(anyStack(), TooltipFlag.NORMAL);

        assertEquals(2, out.size(), "分隔空行 + 标题（没有 ID 行）");
        assertEquals("tooltip.livingitem.backpack.status", keyOf(out.get(1)));
    }

    @Test
    @DisplayName("高级提示 ⇒ 追加完整 UUID + 重新活化提示")
    void advanced_showsFullUuidAndHint() {
        ItemStack stack = anyStack();
        UUID uuid = ContainerUuids.getOrCreateItem(stack);

        List<Component> out = tooltip(stack, TooltipFlag.ADVANCED);

        assertEquals(5, out.size(), "空行 + 标题 + 短码 + 完整 UUID + 提示");
        assertEquals(uuid.toString(), argOf(out.get(3), 0));
        assertEquals("tooltip.livingitem.backpack.uuid_hint", keyOf(out.get(4)));
    }

    @Test
    @DisplayName("复制品（组件一起被 copy）⇒ 短码相同（玩家能肉眼认出是同一身份）")
    void copies_shareShortId() {
        ItemStack first = anyStack();
        ContainerUuids.getOrCreateItem(first);
        ItemStack copy = first.copy();   // 中键复制的效果：UUID 组件跟着走

        String a = (String) argOf(tooltip(first, TooltipFlag.NORMAL).get(2), 0);
        String b = (String) argOf(tooltip(copy, TooltipFlag.NORMAL).get(2), 0);

        assertEquals(a, b, "复制品共享同一身份 ⇒ 短码必须相同，否则这个 tooltip 就失去意义");
    }

    @Test
    @DisplayName("重新活化（剥掉 UUID）⇒ 短码变了（这就是玩家该做的操作）")
    void reactivated_getsNewId() {
        ItemStack stack = anyStack();
        ContainerUuids.getOrCreateItem(stack);
        String before = (String) argOf(tooltip(stack, TooltipFlag.NORMAL).get(2), 0);

        ContainerUuids.stripForCopy(stack);
        ContainerUuids.getOrCreateItem(stack);
        String after = (String) argOf(tooltip(stack, TooltipFlag.NORMAL).get(2), 0);

        assertTrue(!before.equals(after),
            "剥掉后重新生成必须拿到新身份，否则玩家按提示操作也没用");
    }
}
