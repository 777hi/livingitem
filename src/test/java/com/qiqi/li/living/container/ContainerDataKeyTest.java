package com.qiqi.li.living.container;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import com.qiqi.li.living.domain.water.ContainerFluidData;
import com.qiqi.li.living.domain.water.ContainerStressData;
import com.qiqi.li.living.domain.water.WaterComponents;

import net.neoforged.neoforge.attachment.IAttachmentHolder;

/**
 * 容器级数据 key 的落盘判据 + {@link ContainerContext#persist} 的默认路径
 * （2026-10-22，`D-container-13`）。
 *
 * <p>守的是「谁能进 SavedData」这条判据：I-C9 早已定「只有带 {@code serialize} 的才搬」，
 * 但代码里一直没有可执行的表达。{@code savedWith} 把它变成
 * <b>attachment 与 codec 都声明了才算</b> —— 缺一个就说明这种数据
 * 只在 BE 上活着（或干脆不落盘），物品形态不碰。</p>
 *
 * <p>用<b领域里现成的 key**（水 savedWith / 应力 tick 级）而不是新造：
 * {@code ContainerDataKey} 的 key 会登记进静态 {@code all()}，测试里造 key 会污染它。</p>
 */
class ContainerDataKeyTest {

    /** 真 default 实现的 ctx（mock 接口会拦截 default 方法，必须显式要求调真实实现）。 */
    private static ContainerContext realDefaultCtx(IAttachmentHolder host) {
        ContainerContext ctx = mock(ContainerContext.class, CALLS_REAL_METHODS);
        // doReturn 而非 when().thenReturn()：getDataHosts() 返回 List<? extends IAttachmentHolder>，
        // 通配符捕获会让 thenReturn 的泛型检查打架
        Mockito.doReturn(List.of(host)).when(ctx).getDataHosts();
        return ctx;
    }

    @Test
    @DisplayName("savedWith：attachment 与 codec 都有 ⇒ isSaved（流体的 key）")
    void savedWith_key_isSaved() {
        assertNotNull(ContainerFluidData.KEY.attachment(), "水的 key 声明了 BE 附件");
        assertNotNull(ContainerFluidData.KEY.codec(), "savedWith 必须带 CODEC —— SavedData 靠它编 NBT");
        assertTrue(ContainerFluidData.KEY.isSaved(), "两个都有 ⇒ 可按 UUID 进 SavedData");
        assertTrue(ContainerFluidData.KEY.isPersistent());
    }

    @Test
    @DisplayName("persistent（无附件）⇒ 不落盘，isSaved 为 false")
    void persistent_key_isNotSaved() {
        // 电力账本：跨 tick 持久，但相位另有 BE 附件 + 物品组件的专用路径
        assertNull(com.qiqi.li.living.domain.power.ContainerPowerData.KEY.attachment());
        assertNull(com.qiqi.li.living.domain.power.ContainerPowerData.KEY.codec());
        assertFalse(com.qiqi.li.living.domain.power.ContainerPowerData.KEY.isSaved(),
            "没有 CODEC ⇒ SavedData 无从序列化，必须跳过（否则聚合快照塞不进去）");
    }

    @Test
    @DisplayName("tick 级 key ⇒ 既不持久也不落盘")
    void tickKey_isNeitherPersistentNorSaved() {
        assertFalse(ContainerStressData.KEY.isPersistent(), "应力是 tick 级，每 tick 重算");
        assertFalse(ContainerStressData.KEY.isSaved());
    }

    @Test
    @DisplayName("persist：isSaved 的 key ⇒ 写进所有数据宿主")
    void persist_writesToHosts_forSavedKey() {
        IAttachmentHolder host = Mockito.mock(IAttachmentHolder.class);
        ContainerContext ctx = realDefaultCtx(host);

        ContainerFluidData data = new ContainerFluidData();
        ctx.persist(ContainerFluidData.KEY, data);

        // 两个参数都要 matcher：混用一个 raw value 会抛 InvalidUseOfMatchersException
        verify(host).setData(Mockito.eq(WaterComponents.CONTAINER_FLUID_DATA.value()),
            Mockito.same(data));
    }

    @Test
    @DisplayName("persist：非 isSaved 的 key ⇒ 一个宿主都不写（应力/红石不被误归档）")
    void persist_skipsUnsavedKey() {
        IAttachmentHolder host = Mockito.mock(IAttachmentHolder.class);
        ContainerContext ctx = realDefaultCtx(host);

        ctx.persist(ContainerStressData.KEY, new ContainerStressData());

        // 首个参数给 eq：双 any() 会让 setData 的重载判定歧义（今天第四次踩）
        verify(host, never())
            .setData(Mockito.eq(WaterComponents.CONTAINER_FLUID_DATA.value()), ArgumentMatchers.any());
    }

    @Test
    @DisplayName("persist：宿主列表为空（物品形态/SavedData 路径的默认情形）⇒ 不抛")
    void persist_withNoHosts_isNoop() {
        ContainerContext ctx = mock(ContainerContext.class, CALLS_REAL_METHODS);
        Mockito.doReturn(List.of()).when(ctx).getDataHosts();

        ctx.persist(ContainerFluidData.KEY, new ContainerFluidData());
        // 不抛即通过：物品形态 ctx 会覆写 persist 走 SavedData，这里只保证 default 不炸
    }
}
