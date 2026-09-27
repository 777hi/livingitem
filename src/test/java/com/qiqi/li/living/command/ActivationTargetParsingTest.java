package com.qiqi.li.living.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.StringArgumentType;

import com.qiqi.li.living.api.ActivationRuleConfig;

import net.minecraft.commands.arguments.ResourceOrTagKeyArgument;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.server.command.ModIdArgument;

import org.junit.jupiter.api.Test;

/**
 * 活化指令「目标」参数的解析守卫。
 *
 * <p>⭐ <b>为什么需要这份测试</b>（2026-09-28 修掉的 bug，代码读不出这段历史）：
 * {@code target} 原先是 {@code StringArgumentType.string()}，而它走
 * {@code StringReader.readUnquotedString()}，允许字符集<b>不含</b> {@code :} {@code #} {@code @}。</p>
 *
 * <pre>
 *   minecraft:chest    -> 只解析出 "minecraft"（卡在 ':'）
 *   #minecraft:swords  -> 空串
 *   @somemod           -> 空串
 * </pre>
 *
 * <p>解析成空串后 Brigadier 抛「Expected whitespace to end one argument, but found trailing data」，
 * 命令<b>根本执行不到</b>。而当时手写的 {@code .suggests(...)} 补全<b>不经过</b> Brigadier 解析，
 * 于是现象是「Tab 能列出候选、回车却注册失败」——补全在骗人，极其隐蔽。</p>
 *
 * <p>⇒ 本测试锁住两件事：① 生产代码用的确实是原版 {@link ResourceOrTagKeyArgument}；
 * ② 解析结果的 {@code asPrintable()} 能直接喂给 {@link ActivationRuleConfig}（JSON 回写的契约）。</p>
 */
class ActivationTargetParsingTest {

    /** 物品 ID：解析结果必须原样回写，且与配置层的 selector 契约一致。 */
    @Test
    void itemIdParses() throws Exception {
        ResourceOrTagKeyArgument.Result<Item> r =
            LivingItemActivationCommand.targetArgument().parse(new StringReader("minecraft:chest"));
        assertEquals("minecraft:chest", r.asPrintable());
        assertTrue(ActivationRuleConfig.isValidSelector(r.asPrintable()),
            "解析结果必须能被配置层接受，否则规则写不进 JSON");
    }

    /** 标签：{@code asPrintable()} 自带 {@code #} 前缀，与 JSON 侧同形。 */
    @Test
    void tagParses() throws Exception {
        ResourceOrTagKeyArgument.Result<Item> r =
            LivingItemActivationCommand.targetArgument().parse(new StringReader("#minecraft:swords"));
        assertEquals("#minecraft:swords", r.asPrintable());
        assertTrue(ActivationRuleConfig.isValidSelector(r.asPrintable()));
    }

    /**
     * 命名空间（modid）走 NeoForge {@link ModIdArgument}；{@code @} 前缀只作为
     * {@link ActivationRuleConfig} 的<b>内部 key 表示</b>，玩家不输入它。
     */
    @Test
    void modIdParses() throws Exception {
        String mod = ModIdArgument.modIdArgument().parse(new StringReader("somemod"));
        assertEquals("somemod", mod);
        assertTrue(ActivationRuleConfig.isValidSelector("@" + mod));
    }

    /**
     * 反向守卫：记录 {@code StringArgumentType} 读不进资源标识符这一<b>现状</b>。
     *
     * <p>它防的是「有人把 {@code target} 改回 {@code StringArgumentType + 手写补全」——
     * 那种写法能编译、补全看着也正常，但命令永远执行不到。</p>
     *
     * <p>⚠️ 若将来 Brigadier 放开字符集导致本条失败，<b>不要直接删</b>：
     * 那意味着「改回 {@code StringArgumentType}」重新变得可行，应先重新评估再更新本说明。</p>
     */
    @Test
    void stringArgumentTypeCannotReadResourceLocations() throws Exception {
        assertNotEquals("minecraft:chest",
            StringArgumentType.string().parse(new StringReader("minecraft:chest")));
        assertNotEquals("#minecraft:swords",
            StringArgumentType.string().parse(new StringReader("#minecraft:swords")));
        assertNotEquals("@somemod",
            StringArgumentType.string().parse(new StringReader("@somemod")));
    }
}
