package com.qiqi.li.living.api;

/**
 * 模组标识常量 —— 让<b>领域层</b>（L3）也能拿到命名空间，而不必依赖根包（L4）的 {@code LivingItem}。
 *
 * <p>背景（2026-10-08 计划 ④）：网络包从 {@code network/}（L4）拆回各领域（L3）后，
 * 它们原先用的 {@code LivingItem.MOD_ID} 会变成 L3 → L4 的违规依赖 ⇒ 把常量下移到契约层。</p>
 *
 * <p>⚠️ {@code LivingItem.MOD_ID} 仍保留（{@code @Mod} 注解等需要），但<b>值从这里取</b>，
 * 保证单一事实源。</p>
 */
public final class LivingMod {

    /** 模组命名空间（与 {@code gradle.properties} 的 {@code mod_id} 一致）。 */
    public static final String ID = "living_item";

    private LivingMod() {}
}
