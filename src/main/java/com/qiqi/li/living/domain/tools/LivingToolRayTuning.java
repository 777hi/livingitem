package com.qiqi.li.living.domain.tools;

import javax.annotation.Nullable;

import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.components.LivingComponents;

/**
 * 活工具/活武器的<b>射线微调配置</b>（主动模式 · 仅玩家形态生效 · 2026-09-30 用户定）。
 *
 * <h3>两个可调项</h3>
 * <ul>
 *   <li><b>起点锚点</b>（{@link #anchor}）：null = 默认眼睛（{@code L3=a}）；
 *       否则 = 身体中轴上的一个部位高度（躯干中心 / 躯干底部）。
 *       锚点全在身体中轴上（x=0）⇒ 起点世界坐标 = {@code 玩家脚底 + (0, 高度, 0)}，
 *       <b>不随朝向转</b> —— 与「朝向跟随」彻底正交。</li>
 *   <li><b>朝向跟随</b>（{@link #followBody}）：true = 射线方向绑定玩家身体朝向
 *       （{@code followRefYaw} = 绑定时刻的视线朝向 {@code yRot}），
 *       与背后环的刚性跟随同款；false = 世界方向固定（原始行为）。</li>
 * </ul>
 *
 * <h3>数据与变换的分工（⭐ 不破坏「纯净射线」{@code L15}）</h3>
 * <pre>
 *   存储：{@code LivingToolMemory.offset} 永远是录制时的【世界系原始向量】，本组件不碰它
 *   回放/渲染：有效offset = followBody ? rotateY(原始offset, 当前视线朝向(yRot) − followRefYaw) : 原始offset
 * </pre>
 * 绑定时刻「射线此时的朝向」被参考 yaw 天然钉住；pitch 永不参与（身体朝向只有 yaw，
 * 与背后环一致，2026-09-30 用户定）。
 * ⚠️ 参考与 delta 一律用 {@code getYRot()}（视线朝向）：服务端玩家的
 * {@code yBodyRot} 从不随移动包更新（{@code handleMovePlayer} 只写 yRot），
 * 用它当参考 = 拿陈旧值当原点 ⇒ 绑定瞬间射线跳变（实测踩坑，详见
 * {@code ToolRayTuningPacket} 注释与 living-tool-tech.md §2.5）。
 *
 * <h3>生命周期</h3>
 * <ul>
 *   <li><b>仅玩家形态生效</b>（调用方按宿主形态判断）—— 容器/掉落物形态组件随物品走但不生效；
 *       朝向绑定同理（容器里没有"身体朝向"）。</li>
 *   <li><b>录制新记忆时整体重置</b>（{@code LivingToolRecorder} 的三个 record 入口）——
 *       新记忆是新录的线，旧配置对它没有意义。</li>
 *   <li>配置对物品的<b>全部记忆</b>（挖/用/攻击）一体生效，不做 per-memory 配置。</li>
 * </ul>
 */
public record LivingToolRayTuning(@Nullable RayAnchor anchor, boolean followBody, float followRefYaw) {

    public static final LivingToolRayTuning DEFAULT = new LivingToolRayTuning(null, false, 0.0F);

    /**
     * 起点锚点 —— 身体中轴上的部位高度（相对玩家脚底，单位：格）。
     *
     * <p>躯干 = 玩家模型的 0.75 ~ 1.5 段；脑袋<b>不是</b>锚点 —— 点击脑袋 = 清除锚点回默认
     * （GUI 交互的「重置键」，2026-09-30 用户定）。</p>
     */
    public enum RayAnchor implements StringRepresentable {
        TORSO_CENTER("torso_center", 1.125F),
        TORSO_BOTTOM("torso_bottom", 0.75F);

        public static final Codec<RayAnchor> CODEC = StringRepresentable.fromEnum(RayAnchor::values);

        /** 相对玩家脚底的高度（格）。 */
        public final float height;

        RayAnchor(String name, float height) {
            this.name = name;
            this.height = height;
        }

        private final String name;

        @Override
        public String getSerializedName() {
            return name;
        }

        public static RayAnchor byName(String name) {
            for (RayAnchor anchor : values()) {
                if (anchor.getSerializedName().equals(name)) {
                    return anchor;
                }
            }
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Codec / StreamCodec
    // ------------------------------------------------------------------

    public static final Codec<LivingToolRayTuning> CODEC = RecordCodecBuilder.create(instance ->
        instance.group(
RayAnchor.CODEC
                .optionalFieldOf("anchor").forGetter(t -> Optional.ofNullable(t.anchor)),
            Codec.BOOL.optionalFieldOf("follow_body", false).forGetter(LivingToolRayTuning::followBody),
            Codec.FLOAT.optionalFieldOf("follow_ref_yaw", 0.0F).forGetter(LivingToolRayTuning::followRefYaw)
        ).apply(instance, (anchor, follow, refYaw) ->
            new LivingToolRayTuning(anchor.orElse(null), follow, refYaw))
    );

    // ⚠️ 别把 ByteBufCodecs.optional(...) 存进中间字段再 .map —— 链式调用会把泛型 B
    //    固定成 ByteBuf，与 composite 要求的 RegistryFriendlyByteBuf 不兼容
    //    （LivingToolAction 手写编解码器注释里的同款坑）⇒ 在 composite 内联，
    //    Optional<String> 的解包放进构造函数式里。
    public static final StreamCodec<RegistryFriendlyByteBuf, LivingToolRayTuning> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.optional(ByteBufCodecs.STRING_UTF8),
            t -> t.anchor == null ? Optional.<String>empty() : Optional.of(t.anchor.getSerializedName()),
            ByteBufCodecs.BOOL, t -> t.followBody,
            ByteBufCodecs.FLOAT, t -> t.followRefYaw,
            (name, follow, refYaw) -> new LivingToolRayTuning(
                name.isPresent() ? RayAnchor.byName(name.get()) : null, follow, refYaw)
        );

    // ------------------------------------------------------------------
    // 生效逻辑（服务端回放与客户端渲染共用同一公式）
    // ------------------------------------------------------------------

    /**
     * 起点锚点的世界坐标；{@code null} = 无锚点（调用方回退默认起点）。
     * 锚点在中轴上 ⇒ 起点不随朝向转。
     */
    @Nullable
    public Vec3 resolveAnchorOrigin(Player bearer, float partialTick) {
        if (anchor == null) {
            return null;
        }
        // ⭐ 用插值坐标（帧间 lerp），不用 position()（每 tick 跳一次 = 一顿一顿）；
        //    服务端回放传 1.0F ⇒ 恰为当前坐标，行为不变。
        return bearer.getPosition(partialTick).add(0, anchor.height, 0);
    }

    /**
     * 施加朝向跟随：返回<b>衍生副本</b>（原始记忆组件不动）。
     * 未开启跟随 / 角度差可忽略时原样返回。
     */
    public LivingToolMemory transform(LivingToolMemory memory, Player bearer, float partialTick) {
        if (!followBody) {
            return memory;
        }
        // ⭐ 视角角同样取帧间插值（getViewYRot = lerp(yRotO, yRot, partialTick)）——
        //    转身时射线跟着平滑转；服务端 yRotO == yRot，传 1.0F 恰为当前值。
        float deltaYaw = bearer.getViewYRot(partialTick) - followRefYaw;
        if (Math.abs(deltaYaw) < 1.0E-4F) {
            return memory;
        }
        return memory
            .withDig(rotateRay(memory.dig(), deltaYaw))
            .withUse(rotateRay(memory.use(), deltaYaw))
            .withAttack(rotateAttack(memory.attack(), deltaYaw));
    }

    @Nullable
    private static LivingToolMemory.RayMemory rotateRay(@Nullable LivingToolMemory.RayMemory ray, float deltaYaw) {
        return ray == null ? null : new LivingToolMemory.RayMemory(rotateYaw(ray.offset(), deltaYaw), ray.block());
    }

    @Nullable
    private static LivingToolMemory.AttackMemory rotateAttack(@Nullable LivingToolMemory.AttackMemory attack, float deltaYaw) {
        return attack == null ? null : new LivingToolMemory.AttackMemory(rotateYaw(attack.offset(), deltaYaw), attack.entityType());
    }

    /**
     * 绕 Y 轴按 MC yaw 约定旋转（yaw 增大 = 右转）：人右转 δ，绑定射线的世界方向也应右转 δ。
     * 验证：朝北 (0,0,-1) 在 δ=+10° 时获得 +X（东）分量 = 右转 ✓。
     */
    private static Vec3 rotateYaw(Vec3 v, float deltaYawDegrees) {
        double rad = Math.toRadians(deltaYawDegrees);
        double cos = Math.cos(rad);
        double sin = Math.sin(rad);
        return new Vec3(v.x * cos - v.z * sin, v.y, v.x * sin + v.z * cos);
    }

    public boolean isDefault() {
        return anchor == null && !followBody;
    }

    // ------------------------------------------------------------------
    // 读取 / 写入
    // ------------------------------------------------------------------

    /** 读取：缺失 = 默认（无微调）。 */
    public static LivingToolRayTuning of(ItemStack stack) {
        return LivingItemManager.getData(stack, ToolComponents.LIVING_TOOL_RAY_TUNING.value(), DEFAULT);
    }

    /** 写入：等于 DEFAULT 时自动移除组件（与 LivingToolMemory.set 同款口径）。 */
    public static void set(ItemStack stack, LivingToolRayTuning tuning) {
        LivingItemManager.setData(stack, ToolComponents.LIVING_TOOL_RAY_TUNING.value(), tuning, DEFAULT);
    }
}
