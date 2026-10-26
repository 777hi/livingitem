package com.qiqi.li.living.domain.tools;

import javax.annotation.Nullable;

import net.minecraft.world.item.ItemStack;

/**
 * 活工具 / 活武器「渲染依赖」的唯一提取点。
 *
 * <h3>为什么要有它</h3>
 * <p>同一组数据被两处读、两处各写一份清单：服务端 {@code LivingToolHostSync#sameTool}
 * 拿它做变化检测（决定发不发包），客户端 {@code LivingToolModelRenderer} 拿它画动画。
 * 两份清单分居两个文件 ⇒ 加一个渲染依赖时只要漏改一边就是<b>静默失效</b>
 * （2026-10-26 事故：去重键漏了 progress / action / digTicks ⇒ 方块容器里"有模型、不转圈"，
 * 而且从模型上线起就没通过）。</p>
 *
 * <p>收成 record 后：<b>加字段 = 改这一个文件</b> —— 字段定义、{@link #of} 的提取、
 * {@code equals}（record 自动生成）在 20 行内彼此相邻，漏不掉。</p>
 *
 * <h3>边界</h3>
 * <ul>
 *   <li>只收<b>渲染真正读的</b>组件。耐久<b>不进</b> —— 它是状态，但渲染不读它，
 *       进比较键只会白刷包（2026-09-19 实测：每 tick 一个包）。</li>
 *   <li><b>不进包</b>：这是"从 ItemStack 提取"的纯函数，服务端与客户端各自从手里的栈算
 *       ⇒ <b>协议零变更</b>。</li>
 *   <li>包级字段（{@code digLanded} / {@code useLanded}）不在这里 —— 它们不是组件，
 *       由 {@code LivingToolHostPacket.ToolRay} 自己带着。</li>
 *   <li>组件缺失时对应字段为 {@code null}（= 没在挖 / 没交互过）；{@code equals} 对
 *       {@code null} 也能正确比较，故不造默认值。</li>
 * </ul>
 */
public record ToolRenderState(
    LivingToolMemory memory,
    @Nullable LivingToolProgress progress,
    @Nullable LivingToolAction action,
    @Nullable Integer digTicks
) {

    /** 从栈里提取渲染依赖（两处消费方唯一的数据来源）。 */
    public static ToolRenderState of(ItemStack stack) {
        return new ToolRenderState(
            LivingToolMemory.of(stack),
            LivingToolProgress.of(stack),
            LivingToolAction.of(stack),
            ToolComponents.getDigTicks(stack));
    }
}
