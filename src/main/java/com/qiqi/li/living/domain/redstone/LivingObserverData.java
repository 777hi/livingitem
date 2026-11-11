package com.qiqi.li.living.domain.redstone;

import java.util.function.Consumer;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipProvider;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.model.Pos2D;

/**
 * 活观测者的数据组件。
 *
 * <p>封装输出朝向（{@code direction}，{@link HasDirection} 配置）与脉冲状态机
 * （{@code powered} + {@code pendingTimer}：pendingTimer&gt;0 且未 powered = 开启延迟中，
 * pendingTimer&gt;0 且 powered = 脉冲输出中，二者皆 0 = 待机），以及上次受理时看到的
 * 容器内容签名（{@code lastSeenSig}）。</p>
 */
public record LivingObserverData(
    Pos2D direction,
    boolean powered,
    int pendingTimer,
    int lastSeenSig
) implements TooltipProvider {

    /**
     * 未观测哨兵：与 {@code ContainerEntry.contentSig} 的初值同款（{@code syncContentRevision}
     * 的「首见」语义）。任何真实签名都判为「已见」⇒ 活化后首拍必受理（对齐原版「放置即脉冲」）。
     * ⚠️ 理论上真实哈希可碰撞到此值（漏报一次变化），与内容签名既有用途同风险，接受。
     */
    public static final int UNSEEN_SIG = Integer.MIN_VALUE;

    public static final LivingObserverData DEFAULT = new LivingObserverData(Pos2D.UP, false, 0, UNSEEN_SIG);

    public static final Codec<LivingObserverData> CODEC = RecordCodecBuilder.create(instance ->
        instance.group(
            Pos2D.CODEC.fieldOf("direction").forGetter(LivingObserverData::direction),
            Codec.BOOL.fieldOf("powered").forGetter(LivingObserverData::powered),
            Codec.INT.fieldOf("pending_timer").forGetter(LivingObserverData::pendingTimer),
            Codec.INT.fieldOf("last_seen_sig").forGetter(LivingObserverData::lastSeenSig)
        ).apply(instance, LivingObserverData::new)
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, LivingObserverData> STREAM_CODEC = StreamCodec.composite(
        Pos2D.STREAM_CODEC, LivingObserverData::direction,
        ByteBufCodecs.BOOL, LivingObserverData::powered,
        ByteBufCodecs.VAR_INT, LivingObserverData::pendingTimer,
        ByteBufCodecs.VAR_INT, LivingObserverData::lastSeenSig,
        LivingObserverData::new
    );

    public LivingObserverData withDirection(Pos2D dir) {
        return new LivingObserverData(dir, powered, pendingTimer, lastSeenSig);
    }

    public LivingObserverData withPowered(boolean p) {
        return new LivingObserverData(direction, p, pendingTimer, lastSeenSig);
    }

    public LivingObserverData withPendingTimer(int t) {
        return new LivingObserverData(direction, powered, t, lastSeenSig);
    }

    public LivingObserverData withLastSeenSig(int sig) {
        return new LivingObserverData(direction, powered, pendingTimer, sig);
    }

    @Override
    public void addToTooltip(Item.TooltipContext context, Consumer<Component> tooltipAdder, TooltipFlag flag) {
    }

    /** 读取：缺失返回默认值。 */
    public static LivingObserverData of(ItemStack stack) {
        return LivingItemManager.getData(stack, RedstoneComponents.LIVING_OBSERVER_DATA.value(), LivingObserverData.DEFAULT);
    }

    /** 写入：等于默认值时移除组件。 */
    public static void set(ItemStack stack, LivingObserverData data) {
        LivingItemManager.setData(stack, RedstoneComponents.LIVING_OBSERVER_DATA.value(), data, LivingObserverData.DEFAULT);
    }
}
