package com.qiqi.li.living.domain.tnt;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.lighting.LevelLightEngine;

/**
 * 爆炸后**光照刷新**守卫（2026-09-22）。
 *
 * <p>背景：玩家报告「活TNT爆炸后光照没有刷新，所有方块都黑黑的」。根因是大当量/超级模式
 * 为了性能**直接调 {@code LevelChunkSection.setBlockState()}**，绕过了
 * {@code LevelChunk.setBlockState()} —— 而原版把光照更新写在后者里
 * （{@code LevelChunk.java:258~270}）：
 * ① section 由非空变空 → {@code updateSectionStatus}；② 光照属性变化 → {@code checkBlock}。
 * 于是光照引擎完全不知道方块没了：天光柱高图仍认为地下被堵死 ⇒ 客户端收到的整区块包带着旧光照。</p>
 *
 * <p>本用例钉的是**接线与顺序**（真实光照计算要真世界，属游戏内验证范畴）：
 * 四条入口必须被按序调用，少一条光照就还是旧的。</p>
 *
 * <ol>
 *   <li><b>顺序红线</b>：{@code initializeLightSources()}（重建天光柱高图）**必须早于**
 *       {@code propagateLightSources()} —— 后者是按柱高图算天光的，高图是旧的 ⇒ 算出来还是黑的；</li>
 *   <li><b>section 空态</b>：按 {@code hasOnlyAir()} 上报，原版靠它丢弃已清空 section 的光数据；</li>
 *   <li><b>section Y ≠ 索引</b>：必须用 {@code minSection + i}（世界最低段为负时直接传索引会错位）；</li>
 *   <li><b>减光</b>：被炸掉的发光方块要逐个 {@code checkBlock} ——
 *       {@code propagateLightSources} 只管增光，光源没了得靠这条把旧光降下来。</li>
 * </ol>
 */
class ExplosionComponentLightTest {

    private static final ChunkPos CHUNK = new ChunkPos(3, 7);

    // ---------- 替身 ----------
    // ⚠️ 与 ExplosionLedgerTest 同样的纪律：stubbing 必须独立成句，
    //    塞进 when(...).thenReturn(...) 的参数里会触发 UnfinishedStubbingException。

    private static LevelChunkSection section(boolean onlyAir) {
        LevelChunkSection section = Mockito.mock(LevelChunkSection.class);
        when(section.hasOnlyAir()).thenReturn(onlyAir);
        return section;
    }

    private static LevelChunk chunk(int minSection, LevelChunkSection... sections) {
        LevelChunk chunk = Mockito.mock(LevelChunk.class);
        when(chunk.getPos()).thenReturn(CHUNK);
        when(chunk.getMinSection()).thenReturn(minSection);
        when(chunk.getSectionsCount()).thenReturn(sections.length);
        for (int i = 0; i < sections.length; i++) {
            when(chunk.getSection(i)).thenReturn(sections[i]);
        }
        return chunk;
    }

    private static ServerLevel level(LevelLightEngine lightEngine) {
        ServerLevel level = Mockito.mock(ServerLevel.class);
        when(level.getLightEngine()).thenReturn(lightEngine);
        return level;
    }

    // ---------- 1. 顺序红线 ----------

    @Test
    @DisplayName("四条入口按正确顺序调用：重建天光柱高图 → section 空态 → 重算本区块")
    void refresh_callsVanillaEntriesInOrder() {
        LevelLightEngine lightEngine = Mockito.mock(LevelLightEngine.class);
        ServerLevel level = level(lightEngine);
        LevelChunk chunk = chunk(0, section(true), section(false));

        ExplosionComponent.refreshLightAfterBulkEdit(level, chunk, List.of());

        InOrder order = inOrder(chunk, lightEngine);
        order.verify(chunk).initializeLightSources();
        order.verify(lightEngine).updateSectionStatus(SectionPos.of(CHUNK, 0), true);
        order.verify(lightEngine).updateSectionStatus(SectionPos.of(CHUNK, 1), false);
        order.verify(lightEngine).propagateLightSources(CHUNK);
    }

    // ---------- 2. section 空态 ----------

    @Test
    @DisplayName("section 空态按实际 hasOnlyAir 上报（原版靠它丢弃已清空 section 的光数据）")
    void refresh_reportsSectionEmptiness() {
        LevelLightEngine lightEngine = Mockito.mock(LevelLightEngine.class);
        ServerLevel level = level(lightEngine);
        LevelChunk chunk = chunk(0, section(false), section(true), section(false));

        ExplosionComponent.refreshLightAfterBulkEdit(level, chunk, List.of());

        verify(lightEngine).updateSectionStatus(SectionPos.of(CHUNK, 0), false);
        verify(lightEngine).updateSectionStatus(SectionPos.of(CHUNK, 1), true);
        verify(lightEngine).updateSectionStatus(SectionPos.of(CHUNK, 2), false);
    }

    // ---------- 3. section Y ≠ 索引 ----------

    @Test
    @DisplayName("section Y 用 minSection + 索引换算（最低段为负时直接传索引会错位）")
    void refresh_usesSectionYNotIndex() {
        LevelLightEngine lightEngine = Mockito.mock(LevelLightEngine.class);
        ServerLevel level = level(lightEngine);
        LevelChunk chunk = chunk(-4, section(true), section(true));

        ExplosionComponent.refreshLightAfterBulkEdit(level, chunk, List.of());

        verify(lightEngine).updateSectionStatus(SectionPos.of(CHUNK, -4), true);
        verify(lightEngine).updateSectionStatus(SectionPos.of(CHUNK, -3), true);
        verify(lightEngine, never()).updateSectionStatus(SectionPos.of(CHUNK, 0), true);
    }

    // ---------- 4. 减光 ----------

    @Test
    @DisplayName("被炸掉的发光方块逐个 checkBlock（把残留旧光降下来）")
    void refresh_decreasesLightForRemovedEmitters() {
        LevelLightEngine lightEngine = Mockito.mock(LevelLightEngine.class);
        ServerLevel level = level(lightEngine);
        LevelChunk chunk = chunk(0, section(true));

        BlockPos torch = new BlockPos(10, 64, 20);
        BlockPos lava = new BlockPos(11, 30, 21);
        ExplosionComponent.refreshLightAfterBulkEdit(level, chunk, List.of(torch, lava));

        verify(lightEngine).checkBlock(torch);
        verify(lightEngine).checkBlock(lava);
    }

    @Test
    @DisplayName("没有发光方块被炸掉时不做无谓的 checkBlock")
    void refresh_skipsCheckBlockWhenNoEmittersRemoved() {
        LevelLightEngine lightEngine = Mockito.mock(LevelLightEngine.class);
        ServerLevel level = level(lightEngine);
        LevelChunk chunk = chunk(0, section(true));

        ExplosionComponent.refreshLightAfterBulkEdit(level, chunk, List.of());

        verify(lightEngine, never()).checkBlock(Mockito.any(BlockPos.class));
    }
}
