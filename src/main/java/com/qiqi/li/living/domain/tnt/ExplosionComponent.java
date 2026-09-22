package com.qiqi.li.living.domain.tnt;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import com.mojang.datafixers.util.Pair;
import com.qiqi.li.living.api.LivingItemManager;
import it.unimi.dsi.fastutil.shorts.ShortOpenHashSet;
import it.unimi.dsi.fastutil.shorts.ShortSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.container.SimpleContainerContext;

/*
 * ╔══════════════════════════════════════════════════════════════╗
 * ║                    活TNT爆炸组件                            ║
 * ║              ExplosionComponent 全景图                      ║
 * ╠══════════════════════════════════════════════════════════════╣
 * ║                                                            ║
 * ║  整个文件只做一件事：让容器里的活TNT爆炸。                    ║
 * ║                                                            ║
 * ║  ┌─────────────┐                                           ║
 * ║  │ 1. 引信倒计时 │  tick() 每帧检查，倒计时归零才爆炸        ║
 * ║  └──────┬──────┘                                           ║
 * ║         ▼                                                  ║
 * ║  ┌─────────────┐                                           ║
 * ║  │ 2. 点燃入口   │  ignite() 统计TNT数量、算半径、清空物品   ║
 * ║  └──────┬──────┘                                           ║
 * ║         ▼                                                  ║
 * ║  ┌──────────────────────────────┐                          ║
 * ║  │ 3. 立即部分（本 tick 做完）    │  声光 + 实体伤害          ║
 * ║  └──────┬───────────────────────┘                          ║
 * ║         ▼                                                  ║
 * ║  ┌──────────────────────────────┐                          ║
 * ║  │ 4. 方块破坏 → 交给待炸账本     │  ExplosionLedger.schedule ║
 * ║  │    · 已加载区块 → 分帧应用     │  每 tick 32 个区块        ║
 * ║  │    · 未加载区块 → 等它自然加载 │  原版"世界只在观测处演化" ║
 * ║  └──────────────────────────────┘                          ║
 * ║                                                            ║
 * ║  ⚠️ 为什么破坏不在这里做完：                                  ║
 * ║  爆炸半径 = 4.0 × √TNT数，64 个 TNT 就跨 5×5 区块、           ║
 * ║  3456 个达 31×31 = 961 区块。其中一部分可能未加载 ——          ║
 * ║  当场去读会走 getChunk(requireChunk=true) ⇒ 强制加载          ║
 * ║  （单次 289 区块足迹 + 主线程阻塞 + 票据钉住）；              ║
 * ║  直接跳过又会让爆炸语义残缺。所以：按区块分帧 + 账本补齐。     ║
 * ║  详见 docs/system-design/living-item-infrastructure.md §3.2.2 ║
 * ║                                                            ║
 * ║  三种模式的区别只在"怎么破坏一个区块"：                       ║
 * ║  - NORMAL      ≤64 TNT：逐个 setBlock，有掉落物              ║
 * ║  - HIGH_YIELD  >64 TNT：直接改 Section 数据，无掉落物         ║
 * ║  - SUPER       >3456 TNT：整区块清空                          ║
 * ║                                                            ║
 * ║  ⚠️ 后两种直接改 Section 数据 ⇒ **绕过原版的方块变更回调**，   ║
 * ║  光照引擎不会自己更新 ⇒ 必须补 refreshLightAfterBulkEdit()。  ║
 * ║  （否则炸完坑里一片漆黑 —— 天光/方块光都还是炸之前的值）      ║
 * ║                                                            ║
 * ╚══════════════════════════════════════════════════════════════╝
 */

public class ExplosionComponent {

    public static final String ID = "explosion";

    private static final float DEFAULT_BASE_RADIUS = 4.0f;
    private static final boolean DEFAULT_VANILLA_DROPS = true;

    /** ≤ 此数量走普通模式（逐个 setBlock）。 */
    private static final int NORMAL_THRESHOLD = 64;

    /** > 此数量走超级爆炸模式（整区块清空）。 */
    private static final int SUPER_EXPLOSION_THRESHOLD = 54 * 64;

    private ExplosionComponent() {}

    public static boolean startFuseOnStack(ItemStack tntStack) {
        com.qiqi.li.living.domain.tnt.LivingTntData data = LivingItemManager.getTntData(tntStack);
        com.qiqi.li.living.domain.tnt.ExplosionData explosion = data.explosion();
        if (!explosion.ignited()) {
            explosion = explosion.ignite();
            LivingItemManager.setTntData(tntStack, data.withExplosion(explosion));
        }
        return true;
    }

    public static boolean ignite(ContainerContext containerCtx, Level level) {
        return ignite(containerCtx, level, DEFAULT_BASE_RADIUS, DEFAULT_VANILLA_DROPS);
    }

    /**
     * 点燃：统计 TNT → 算半径 → 选模式 → 立即部分（声光 / 实体伤害）→ 方块破坏交给账本。
     *
     * <p>本方法<b>不读任何方块</b> —— 爆炸范围可能跨未加载区块，读它会强制加载。
     * 方块破坏由 {@link ExplosionLedger} 按区块分帧推进（已加载的）或等区块自然加载（未加载的）。</p>
     */
    public static boolean ignite(ContainerContext containerCtx, Level level, float baseRadius, boolean vanillaDrops) {
        BlockPos pos = containerCtx.getBlockPos();
        Vec3 center;

        if (pos != null) {
            center = new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        } else if (containerCtx instanceof SimpleContainerContext scc && scc.getInventory() != null) {
            var player = scc.getInventory().player;
            center = player.position();
        } else {
            return false;
        }

        int totalTntCount = 0;
        int containerSize = containerCtx.getSize();
        for (int i = 0; i < containerSize; i++) {
            ItemStack stack = containerCtx.getItem(i);
            if (!stack.isEmpty() && stack.is(Items.TNT) && LivingItemManager.isLivingItem(stack)) {
                totalTntCount += stack.getCount();
                containerCtx.setItem(i, ItemStack.EMPTY);
            }
        }
        if (totalTntCount <= 0) return false;
        if (!(level instanceof ServerLevel serverLevel)) return false;

        double radius = baseRadius * Math.sqrt(totalTntCount);
        ExplosionParams.Mode mode = totalTntCount <= NORMAL_THRESHOLD ? ExplosionParams.Mode.NORMAL
            : totalTntCount <= SUPER_EXPLOSION_THRESHOLD ? ExplosionParams.Mode.HIGH_YIELD
            : ExplosionParams.Mode.SUPER;
        ExplosionParams params = new ExplosionParams(center.x, center.y, center.z, radius, mode, vanillaDrops);

        com.qiqi.li.LivingItem.LOGGER.info(
            "爆炸触发: TNT数量={}, radius={}, 位置=({},{},{}), 模式={}, 掉落模式={}, 覆盖区块={}",
            totalTntCount, String.format("%.1f", radius), center.x, center.y, center.z,
            mode, vanillaDrops ? "原版衰减" : "100%掉落", params.chunkCount());

        // ── 立即部分：声光 + 实体伤害（都不碰方块）──
        playExplosionEffects(serverLevel, center, radius);
        applyExplosionDamage(level, center.x, center.y, center.z, radius);

        // ── 方块破坏：登记进账本，按区块分帧执行 ──
        if (!ExplosionLedger.schedule(serverLevel, params)) {
            // 账本已满（极端：在未探索区域连续引爆数百次且从未回去过）。
            // **不能什么都不做** —— 声光和实体伤害已经生效，方块却没坏，玩家会以为模组坏了。
            // 降级为"只炸当前已加载的世界"：语义有缺口（未加载部分残缺），但有 WARN 日志说明，
            // 且**绝不**为此强制加载区块。
            applyToLoadedChunks(serverLevel, params);
        }
        return true;
    }

    /**
     * 降级路径：只处理**当前已加载**的受影响区块（账本满时使用）。
     *
     * <p>与账本的差别只有一处：未加载区块不是"等它自然加载时补上"，而是**直接放弃**。
     * 用 {@code getChunkNow} 而非 {@code getChunk} —— 降级不等于可以强制加载区块。</p>
     */
    private static void applyToLoadedChunks(ServerLevel level, ExplosionParams params) {
        for (int bit = 0; bit < params.chunkCount(); bit++) {
            ChunkPos cp = params.chunkAt(bit);
            if (!params.affects(cp)) continue;
            LevelChunk chunk = level.getChunkSource().getChunkNow(cp.x, cp.z);
            if (chunk == null) continue;
            applyToChunk(level, params, chunk);
        }
    }

    /** 爆炸瞬间的声光 —— 立即播放，让玩家先感知到爆炸，破坏随后分帧出现。 */
    private static void playExplosionEffects(ServerLevel level, Vec3 center, double radius) {
        float volume = Math.min(4.0f, (float) (radius / 4.0) * 4.0f);
        level.playSound(null, center.x, center.y, center.z,
            SoundEvents.GENERIC_EXPLODE, SoundSource.BLOCKS, volume,
            (1.0f + (level.random.nextFloat() - level.random.nextFloat()) * 0.2f) * 0.7f);

        if (radius >= 2.0F) {
            level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, center.x, center.y, center.z, 1, 0, 0, 0, 0);
        } else {
            level.sendParticles(ParticleTypes.EXPLOSION, center.x, center.y, center.z, 1, 0, 0, 0, 0);
        }
    }

    // ══════════════════════════════════════════════
    //  实体伤害（三模式共用，爆炸瞬间立即生效）
    // ══════════════════════════════════════════════
    //   1. 在爆炸半径范围内搜索所有实体
    //   2. 离爆炸中心越近，伤害越高
    //   3. 顺便给实体一个击退效果
    //
    // 伤害公式（和原版一样）：
    //   impact = 1 - (距离 / 半径)
    //   damage = (impact² + impact)/2 × 7 × 半径 + 1

    private static void applyExplosionDamage(Level level, double centerX, double centerY,
                                              double centerZ, double radius) {
        Vec3 center = new Vec3(centerX, centerY, centerZ);
        float range = (float) radius;

        AABB searchBox = new AABB(
            centerX - range, centerY - range, centerZ - range,
            centerX + range, centerY + range, centerZ + range
        );
        List<Entity> entities = level.getEntities(null, searchBox);

        for (Entity entity : entities) {
            if (entity instanceof ItemEntity) continue;

            double dist = Math.sqrt(entity.distanceToSqr(center));
            if (dist > range) continue;

            double impact = (1.0 - dist / range);
            float damage = (float) ((impact * impact + impact) / 2.0 * 7.0 * range + 1.0);

            if (damage > 0 && entity instanceof LivingEntity living) {
                DamageSource explosionSource;
                if (entity instanceof ServerPlayer player) {
                    explosionSource = level.damageSources().explosion(player, player);
                } else {
                    explosionSource = level.damageSources().explosion(entity, entity);
                }
                living.hurt(explosionSource, damage);
            }

            double dx = entity.getX() - centerX;
            double dy = entity.getY() - centerY;
            double dz = entity.getZ() - centerZ;
            double distNorm = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (distNorm > 0.0) {
                double knockback = impact * 2.0;
                entity.setDeltaMovement(
                    entity.getDeltaMovement().add(
                        dx / distNorm * knockback,
                        dy / distNorm * knockback + 0.5 * impact,
                        dz / distNorm * knockback
                    )
                );
            }
        }
    }

    // ══════════════════════════════════════════════
    //  逐区块应用 —— 破坏的唯一入口
    // ══════════════════════════════════════════════
    //
    // 立即阶段（账本 flush）与延迟阶段（区块自然加载后）都走这里，
    // 保证「同一场爆炸，无论区块什么时候加载，破坏结果一致」。

    /**
     * 把这次爆炸作用于**指定区块**（调用方必须保证该区块已加载）。
     *
     * <p>包级可见：这是"破坏的唯一入口"，只应由 {@link ExplosionLedger} 调用
     * （通过 {@code ChunkApplier} seam）。不公开是为了让"绕过账本直接破坏"在编译期就做不到。</p>
     *
     * @param level  服务端世界
     * @param params 爆炸参数（含模式）
     * @param chunk  已加载的目标区块
     */
    static void applyToChunk(ServerLevel level, ExplosionParams params, LevelChunk chunk) {
        switch (params.mode()) {
            case SUPER -> deleteChunkContent(level, chunk);
            case HIGH_YIELD -> applyHighYieldToChunk(level, params, chunk);
            case NORMAL -> applyNormalToChunk(level, params, chunk);
        }
    }

    /**
     * 遍历「球体 ∩ 指定区块」的所有方块位置。
     *
     * <p>只走球体在该 (x,z) 列上的 y 跨度（{@code |dy| ≤ √(r²−dx²−dz²)}），
     * <b>不扫整列</b> —— 否则半径 235 时每个区块要多扫十几万格空气。</p>
     *
     * <p>用的是与旧实现一致的整数中心（{@code (int) center}）与 {@code ceil(radius)} 边界，
     * 保证选出的方块集合与重构前逐字节相同。</p>
     */
    private static void forEachBlockInChunk(ServerLevel level, ExplosionParams p, ChunkPos cp,
                                            Consumer<BlockPos.MutableBlockPos> action) {
        int cx = (int) p.centerX();
        int cy = (int) p.centerY();
        int cz = (int) p.centerZ();
        int rInt = (int) Math.ceil(p.radius());
        double r2 = p.radius() * p.radius();

        int minX = Math.max(cp.getMinBlockX(), cx - rInt);
        int maxX = Math.min(cp.getMaxBlockX(), cx + rInt);
        int minZ = Math.max(cp.getMinBlockZ(), cz - rInt);
        int maxZ = Math.min(cp.getMaxBlockZ(), cz + rInt);

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = minX; x <= maxX; x++) {
            double dx = x - cx;
            for (int z = minZ; z <= maxZ; z++) {
                double dz = z - cz;
                double col2 = r2 - dx * dx - dz * dz;
                if (col2 < 0) continue;
                int half = (int) Math.floor(Math.sqrt(col2));
                int minY = Math.max(level.getMinBuildHeight(), cy - half);
                int maxY = Math.min(level.getMaxBuildHeight() - 1, cy + half);
                for (int y = minY; y <= maxY; y++) {
                    pos.set(x, y, z);
                    action.accept(pos);
                }
            }
        }
    }

    // ──────────────────────────────────────────────
    //  普通模式（≤64 TNT）：逐个 setBlock，有掉落物
    // ──────────────────────────────────────────────
    // 掉落物有两种模式（由 vanillaDrops 控制）：
    //
    // ┌──────────────────────────────────────────────────────────────┐
    // │ 原版模式 (vanillaDrops=true)                                 │
    // │  调用 BlockState.onExplosionHit()，走原版完整掉落链路：       │
    // │   ① canDropFromExplosion() → 是否允许被爆炸掉落              │
    // │   ② getDrops(lootParams)   → 战利品表（含爆炸衰减）           │
    // │   ③ spawnAfterBreak()      → 额外物品（如萤石碎片）          │
    // │   ④ onBlockExploded()      → 通知方块被炸，然后 setBlock     │
    // │   ⑤ 掉落物合并             → 相邻物品自动合并（最多16个）     │
    // ├──────────────────────────────────────────────────────────────┤
    // │ 100%模式 (vanillaDrops=false)                                │
    // │  调用 Block.dropResources()：每个被炸方块 100% 掉落自身       │
    // └──────────────────────────────────────────────────────────────┘

    private static void applyNormalToChunk(ServerLevel level, ExplosionParams p, LevelChunk chunk) {
        int cx = (int) p.centerX();
        int cy = (int) p.centerY();
        int cz = (int) p.centerZ();

        List<BlockPos> blocksToBlow = new ArrayList<>();

        forEachBlockInChunk(level, p, chunk.getPos(), pos -> {
            double dx = pos.getX() - cx;
            double dy = pos.getY() - cy;
            double dz = pos.getZ() - cz;
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (dist > p.radius()) return;

            BlockState state = level.getBlockState(pos);
            if (state.isAir()) return;

            FluidState fluidState = level.getFluidState(pos);
            float blockResistance = state.getExplosionResistance(level, pos, null);
            float fluidResistance = fluidState.getExplosionResistance();
            float effectiveResistance = Math.max(blockResistance, fluidResistance);

            if (effectiveResistance >= 100.0F) return;

            float effectiveRadius = (float) (p.radius() * (0.7F + level.random.nextFloat() * 0.6F));
            if (dist > effectiveRadius && effectiveResistance > 0.0F) return;

            blocksToBlow.add(pos.immutable());
        });

        if (blocksToBlow.isEmpty()) return;
        if (p.vanillaDrops()) {
            destroyBlocksWithVanillaDrops(level, blocksToBlow, (float) p.radius());
        } else {
            destroyBlocksWithFullDrops(level, blocksToBlow);
        }
    }

    /**
     * 原版掉落模式 —— 完全复刻原版 {@code Explosion.finalizeExplosion()} 的掉落逻辑。
     *
     * <p>虚构一个 {@link Explosion} 对象只为提供 radius 与 BlockInteraction
     * （{@code onExplosionHit} 需要它们来判断衰减概率）。</p>
     */
    private static void destroyBlocksWithVanillaDrops(Level level, List<BlockPos> blocksToBlow, float radius) {
        Explosion fakeExplosion = new Explosion(
            level, null, 0, 0, 0, radius, false, Explosion.BlockInteraction.DESTROY_WITH_DECAY
        );

        List<Pair<ItemStack, BlockPos>> drops = new ArrayList<>();

        // 打乱顺序，和原版一样（影响掉落物合并的优先级）
        net.minecraft.Util.shuffle(blocksToBlow, level.random);

        for (BlockPos pos : blocksToBlow) {
            BlockState state = level.getBlockState(pos);
            state.onExplosionHit(level, pos, fakeExplosion, (stack, dropPos) ->
                mergeOrAppendDrop(drops, stack, dropPos));
        }

        for (Pair<ItemStack, BlockPos> pair : drops) {
            Block.popResource(level, pair.getSecond(), pair.getFirst());
        }
    }

    // 原版 Explosion.addOrAppendStack 的复刻：尝试合并同类掉落物（最多16个一组）
    private static void mergeOrAppendDrop(List<Pair<ItemStack, BlockPos>> drops,
                                           ItemStack stack, BlockPos pos) {
        for (int i = 0; i < drops.size(); i++) {
            Pair<ItemStack, BlockPos> existing = drops.get(i);
            ItemStack existingStack = existing.getFirst();
            if (ItemEntity.areMergable(existingStack, stack)) {
                drops.set(i, Pair.of(ItemEntity.merge(existingStack, stack, 16), existing.getSecond()));
                if (stack.isEmpty()) return;
            }
        }
        drops.add(Pair.of(stack, pos));
    }

    /** 100% 掉落模式：每个被炸方块直接 dropResources，然后 setBlock 为空气。 */
    private static void destroyBlocksWithFullDrops(Level level, List<BlockPos> blocksToBlow) {
        Set<LevelChunk> dirtyChunks = new HashSet<>();

        for (BlockPos pos : blocksToBlow) {
            BlockState state = level.getBlockState(pos);
            Block.dropResources(state, (ServerLevel) level, pos, level.getBlockEntity(pos));
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
            dirtyChunks.add(level.getChunkAt(pos));
        }

        for (LevelChunk chunk : dirtyChunks) {
            chunk.setUnsaved(true);
        }
    }

    // ══════════════════════════════════════════════
    //  大当量模式（>64 TNT）：直接改 Section 数据
    // ══════════════════════════════════════════════
    //
    // 为什么不用普通模式？TNT 太多时方块太多：1728 个 TNT 半径 166，
    // 球体约 1900 万方块，逐个 setBlock() 会卡死。
    //
    // 思路：跳过原版所有回调，直接修改区块底层数据。分两阶段（按区块执行）：
    //   阶段1-收集：遍历「球体 ∩ 本区块」，把该炸的方块按 Section 分组
    //   阶段2-修改：按 Section 批量改成空气 + 通知客户端
    //
    // ┌──────────────────────────────────────────────┐
    // │ 什么是 Section？                               │
    // │ Minecraft 世界按 16×16×16 的立方体存储方块，   │
    // │ 这个立方体叫 Section（区块段）。一个 Chunk 在  │
    // │ 垂直方向叠了多个 Section。                    │
    // │ ⚠️ Section 的世界 Y ≠ 数组索引：               │
    // │    index = sectionY - chunk.getMinSection()    │
    // └──────────────────────────────────────────────┘

    private static void applyHighYieldToChunk(ServerLevel level, ExplosionParams p, LevelChunk chunk) {
        int cx = (int) p.centerX();
        int cy = (int) p.centerY();
        int cz = (int) p.centerZ();
        double r2 = p.radius() * p.radius();

        // ── 阶段1：收集（只扫本区块）──
        Map<SectionPos, ShortSet> sectionUpdates = new HashMap<>();
        // 被炸掉的"发光方块"（火把/萤石/岩浆…）：光照引擎需要显式给它们减光，
        // 因为下面的 propagateLightSources 只负责"增光"。此处顺手记录，零额外扫描成本。
        List<BlockPos> removedLightSources = new ArrayList<>();

        forEachBlockInChunk(level, p, chunk.getPos(), pos -> {
            double dx = pos.getX() - cx;
            double dy = pos.getY() - cy;
            double dz = pos.getZ() - cz;
            double distSq = dx * dx + dy * dy + dz * dz;
            if (distSq > r2) return;

            BlockState state = level.getBlockState(pos);
            if (state.isAir()) return;

            FluidState fluidState = level.getFluidState(pos);
            float blockResistance = state.getExplosionResistance(level, pos, null);
            float fluidResistance = fluidState.getExplosionResistance();
            float effectiveResistance = Math.max(blockResistance, fluidResistance);

            double dist = Math.sqrt(distSq);
            float power = (float) (p.radius() * (1.0 - dist / p.radius()) * 2.0);
            if (power <= effectiveResistance) return;

            if (state.getLightEmission(level, pos) > 0) {
                removedLightSources.add(pos.immutable());
            }

            SectionPos sectionPos = SectionPos.of(pos);
            short packedPos = (short) (SectionPos.sectionRelative(pos.getX())
                | (SectionPos.sectionRelative(pos.getZ()) << 4)
                | (SectionPos.sectionRelative(pos.getY()) << 8));
            sectionUpdates.computeIfAbsent(sectionPos, k -> new ShortOpenHashSet()).add(packedPos);
        });

        if (sectionUpdates.isEmpty()) return;

        // ── 阶段2：批量修改 + 通知客户端 ──
        // setBlockState 只改内存中的方块数据，不触发任何回调/光照更新/客户端同步
        BlockState airState = Blocks.AIR.defaultBlockState();
        int minSection = chunk.getMinSection();
        int sectionCount = chunk.getSectionsCount();

        for (var entry : sectionUpdates.entrySet()) {
            SectionPos sectionPos = entry.getKey();
            int sectionIndex = sectionPos.y() - minSection;
            if (sectionIndex < 0 || sectionIndex >= sectionCount) continue;
            LevelChunkSection section = chunk.getSection(sectionIndex);
            if (section == null) continue;

            for (short packedPos : entry.getValue()) {
                int localX = packedPos & 0xF;
                int localZ = (packedPos >> 4) & 0xF;
                int localY = (packedPos >> 8) & 0xF;
                BlockPos bePos = new BlockPos(
                    sectionPos.minBlockX() + localX,
                    sectionPos.minBlockY() + localY,
                    sectionPos.minBlockZ() + localZ);

                if (chunk.getBlockEntity(bePos) != null) {
                    chunk.removeBlockEntity(bePos);
                }
                section.setBlockState(localX, localY, localZ, airState, false);
            }
        }

        chunk.setUnsaved(true);
        // ⚠️ 顺序：先发方块/渲染同步，再补光照。
        // 方块同步是**用户直接看得见**的那件事；光照刷新是入队的、且原版随后会用
        // ClientboundLightUpdatePacket 覆盖成正确值 ⇒ 它不该有机会挡住方块同步
        // （万一将来光照刷新出问题，也不能连累"方块没坏"）。
        notifyChunkClients(level, chunk.getPos(), chunk);
        refreshLightAfterBulkEdit(level, chunk, removedLightSources);
    }

    // ══════════════════════════════════════════════
    //  超级爆炸模式（>3456 TNT）：整区块清空
    // ══════════════════════════════════════════════
    //
    // 超过 54×64 个 TNT 时启用。半径可达 235 格（31×31 = 961 区块），
    // 一次性清除会卡死服务器 —— 由账本按区块分帧推进（每 tick 32 个区块），
    // 从爆炸中心向外扩散。

    /**
     * 清空整区块 —— 调用方保证 {@code chunk} 已加载（避免重复查一次区块）。
     */
    private static void deleteChunkContent(ServerLevel level, LevelChunk chunk) {
        ChunkPos chunkPos = chunk.getPos();
        BlockState airState = Blocks.AIR.defaultBlockState();

        for (BlockPos pos : new ArrayList<>(chunk.getBlockEntities().keySet())) {
            chunk.removeBlockEntity(pos);
        }

        LevelChunkSection[] sections = chunk.getSections();
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection section = sections[i];
            if (section == null || section.hasOnlyAir()) continue;
            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        section.setBlockState(x, y, z, airState, false);
                    }
                }
            }
        }

        chunk.setUnsaved(true);
        // 先方块同步、后光照刷新（理由同 applyHighYieldToChunk 里的顺序说明）。
        // 超级爆炸把**整区块**清空 ⇒ 每个 section 都变成"空"，
        // 旧光数据会随 section 状态更新被丢弃，不需要逐个记录被炸掉的光源。
        notifyChunkClients(level, chunkPos, chunk);
        refreshLightAfterBulkEdit(level, chunk, List.of());
    }

    // ══════════════════════════════════════════════
    //  光照刷新 —— 批量改 Section 之后必须补
    // ══════════════════════════════════════════════
    //
    // ⚠️ 为什么必须补：普通模式走 level.setBlock()，最终进入 LevelChunk.setBlockState()，
    // 那里**原版已经替我们做了两件事**（LevelChunk.java:258~270）：
    //   ① section 从"非空"变"空" → lightEngine.updateSectionStatus(pos, true)
    //   ② 光照属性发生变化      → lightEngine.checkBlock(pos)
    //
    // 而大当量/超级模式为了性能**直接调 LevelChunkSection.setBlockState()**，
    // 绕过了 LevelChunk.setBlockState() ⇒ 光照引擎完全不知道方块没了：
    // 天光柱高图仍认为地下被堵死、光照数据仍是旧的 ⇒ 发出去的整区块包带着旧光照，
    // 表现就是"炸完之后坑里/坑壁一片漆黑"。
    //
    // 补法（按区块，与账本的分帧粒度天然对齐）：
    //   ① 按当前方块重建"天光柱高图"—— 爆炸把方块炸没了，天光应该能照得更深
    //   ② 告知光照引擎每个 section 现在是否为空
    //   ③ 重算本区块光照：天光按列重算 + 重新登记剩余光源（会向四周扩散）
    //   ④ 被炸掉的发光方块：显式触发"减光"（③ 只管增光，光源没了要靠 ④ 降下来）
    //
    // ⚠️ ②③④ 在 ThreadedLevelLightEngine 上都是**入队**而非同步执行 ——
    // 本 tick 只登记，真正的计算在光照线程跑；算完后原版会经
    // ChunkHolder.sectionLightChanged → broadcastChanges 自动发 ClientboundLightUpdatePacket
    // 同步给客户端。所以紧跟其后的 notifyChunkClients 里那份光照**这一瞬间仍是旧的**，
    // 下一 tick 会被光照包覆盖成正确的。
    // （这与原版区块加载是同一套机制：initializeLight / lightChunk 也都是异步的。）
    //
    // 参数 removedLightSources = 本次被炸掉的发光方块位置（超级模式传空表）。
    //
    // 包级可见（而非 private）：留作**可测性接缝** —— 光照重算本身要真世界才能验，
    // 但"是否按正确顺序调了原版那三个入口"可以在单测里用替身钉死
    // （见 ExplosionComponentLightTest）。与 applyToChunk 同一个理由。

    static void refreshLightAfterBulkEdit(ServerLevel level, LevelChunk chunk,
                                          List<BlockPos> removedLightSources) {
        ChunkPos chunkPos = chunk.getPos();
        LevelLightEngine lightEngine = level.getLightEngine();

        // ① 天光柱高图：按当前方块重建（原版 INITIALIZE_LIGHT 阶段同款调用）
        chunk.initializeLightSources();

        // ② section 空/非空状态（原版在 LevelChunk.setBlockState 里做同一件事）
        int minSection = chunk.getMinSection();
        for (int i = 0; i < chunk.getSectionsCount(); i++) {
            lightEngine.updateSectionStatus(
                SectionPos.of(chunkPos, minSection + i), chunk.getSection(i).hasOnlyAir());
        }

        // ③ 重算本区块光照（天光按列重算 + 重新登记方块光源）
        lightEngine.propagateLightSources(chunkPos);

        // ④ 被炸掉的发光方块：把残留的旧光降下来
        for (BlockPos pos : removedLightSources) {
            lightEngine.checkBlock(pos);
        }
    }

    private static void notifyChunkClients(ServerLevel level, ChunkPos chunkPos, LevelChunk chunk) {
        var packet = new ClientboundLevelChunkWithLightPacket(chunk, level.getLightEngine(), null, null);
        for (ServerPlayer player : level.getChunkSource().chunkMap.getPlayers(chunkPos, false)) {
            player.connection.send(packet);
        }
    }
}
