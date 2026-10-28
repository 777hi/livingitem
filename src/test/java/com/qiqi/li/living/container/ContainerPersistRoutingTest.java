package com.qiqi.li.living.container;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mojang.serialization.Codec;
import com.qiqi.li.testutil.FakeContainerContext;

import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.attachment.IAttachmentHolder;

/**
 * 容器级数据的<b>持久层二分</b>（2026-10-29 口径）。
 *
 * <p>规则：<b>有身份（容器 UUID）⇒ 只写 SavedData；无身份 ⇒ 只写宿主附件</b>。</p>
 *
 * <p>为什么必须有这条：身份是<b>跨形态稳定</b>的，方块↔物品切换时只有它跟得过去。
 * 2026-10-29 之前的实现两个形态写<b>两个地方</b>（方块→附件、物品→SavedData），
 * 而 `ContainerHandoff` 只搬 UUID ⇒ 方块形态期间的变化随方块蒸发（真机：活背包拾取后活流体丢失）。
 * 这条测试钉住"落点二选一"，改回双写/单写都会红。</p>
 */
class ContainerPersistRoutingTest {

    /** 测试用附件类型：只需要非 null（{@link ContainerDataKey#isSaved()} 的判据之一）。 */
    private static final AttachmentType<String> ATTACHMENT =
        AttachmentType.builder(() -> "").build();

    private static final ContainerDataKey<String> KEY = ContainerDataKey.savedWith(
        "persist_routing_test", () -> "", () -> ATTACHMENT, () -> Codec.STRING, String::isEmpty);

    private final UUID uuid = UUID.randomUUID();

    /**
     * 该 mock 有没有收到过 {@code setData} 调用。
     *
     * <p>⚠️ 不用 {@code verify(host).setData(...)}：{@code IAttachmentHolder} 同时有
     * {@code setData(AttachmentType<T>, T)} 与 {@code setData(Supplier<AttachmentType<T>>, T)}
     * 两个重载，调用点会**编译期歧义**（{@code AttachmentType} 本身也实现了 {@code Supplier}）。
     * 按方法名查调用记录没有重载解析问题。</p>
     */
    private static boolean wroteAttachment(IAttachmentHolder host) {
        return org.mockito.Mockito.mockingDetails(host).getInvocations().stream()
            .anyMatch(i -> "setData".equals(i.getMethod().getName()));
    }

    @AfterEach
    void tearDown() {
        ContainerDataSavedData.get().remove(uuid, KEY);
    }

    /** 有身份 + 有宿主 ⇒ 走 SavedData，附件一次都不写。 */
    @Test
    @DisplayName("persist：有容器 UUID ⇒ 只写 SavedData（形态切换零搬运的前提）")
    void persist_withIdentity_writesSavedDataOnly() {
        IAttachmentHolder host = mock(IAttachmentHolder.class);
        FakeContainerContext ctx = new FakeContainerContext(9, 9) {
            @Override
            public UUID getContainerUuid() {
                return uuid;
            }

            @Override
            public List<IAttachmentHolder> getDataHosts() {
                return List.of(host);
            }
        };

        ctx.persist(KEY, "water");

        assertEquals("water", ContainerDataSavedData.get().get(uuid, KEY),
            "有身份的数据必须落在 SavedData —— 它才跟得着 UUID 跨形态走");
        assertFalse(wroteAttachment(host), "有身份不该写附件 —— 附件那份是陈旧副本");
    }

    /** 无身份 ⇒ 附件是唯一副本。 */
    @Test
    @DisplayName("persist：无容器 UUID ⇒ 只写宿主附件，不碰 SavedData")
    void persist_withoutIdentity_writesAttachmentOnly() {
        IAttachmentHolder host = mock(IAttachmentHolder.class);
        FakeContainerContext ctx = new FakeContainerContext(9, 9) {
            @Override
            public UUID getContainerUuid() {
                return null;
            }

            @Override
            public List<IAttachmentHolder> getDataHosts() {
                return List.of(host);
            }
        };

        ctx.persist(KEY, "water");

        assertTrue(wroteAttachment(host), "无身份的数据必须写附件 —— 那是它唯一的副本");
        assertNull(ContainerDataSavedData.get().get(uuid, KEY), "无身份不该产生 SavedData 条目");
    }

    /** 回填优先级：内存没命中时，有身份的容器从 SavedData 取回。 */
    @Test
    @DisplayName("getOrCreateContainerData：有身份且内存空 ⇒ 从 SavedData 回填（红石/电力此前只写不回读）")
    void getOrCreate_backfillsFromSavedData() {
        FakeContainerContext ctx = new FakeContainerContext(9, 9, "persist_routing_test#" + uuid) {
            @Override
            public UUID getContainerUuid() {
                return uuid;
            }

            @Override
            public List<IAttachmentHolder> getDataHosts() {
                return List.of();
            }
        };
        ContainerDataSavedData.get().put(uuid, KEY, "saved-value");

        try {
            assertEquals("saved-value", ContainerLivingItemHandler.getOrCreateContainerData(ctx, KEY),
                "有身份的数据必须能从 SavedData 回填 —— 否则 persist 写了却永远读不回来");
        } finally {
            ContainerLivingItemHandler.removeDataByKey(ctx.getCacheKey());
            // ⚠️ removeDataByKey 会往**全局**作废队列里登记一条；不消费掉会污染
            // ContainerRuntimeCacheLifecycleTest#nullContainerKey_isIgnored（断言队列为空）。
            ContainerLivingItemHandler.drainRuntimeCacheInvalidations();
        }
    }
}
