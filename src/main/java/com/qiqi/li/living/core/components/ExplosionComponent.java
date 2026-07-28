package com.qiqi.li.living.core.components;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import com.mojang.datafixers.util.Pair;
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
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import com.qiqi.li.LivingItem;
import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.container.SimpleContainerContext;
import com.qiqi.li.living.LivingItemManager;

/*
 * ╔══════════════════════════════════════════════════════════════╗
 * ║                    活TNT爆炸组件                            ║
 * ║              ExplosionComponent 全景图                      ║
 * ╠══════════════════════════════════════════════════════════════╣
 * ║                                                            ║
 * ║  整个文件只做一件事：让容器里的活TNT爆炸。                    ║
 * ║  但"爆炸"分成了几个步骤，所以代码看起来很长：                ║
 * ║                                                            ║
 * ║  ┌─────────────┐                                           ║
 * ║  │ 1. 引信倒计时 │  tick() 每帧检查，倒计时归零才爆炸        ║
 * ║  └──────┬──────┘                                           ║
 * ║         ▼                                                  ║
 * ║  ┌─────────────┐                                           ║
 * ║  │ 2. 点燃入口   │  ignite() 统计TNT数量、算半径、清空物品   ║
 * ║  └──────┬──────┘                                           ║
 * ║         ▼                                                  ║
 * ║  ┌─────────────┐                                           ║
 * ║  │ 3. 选择模式   │  ≤64→普通  >64→大当量  >3456→超级爆炸  ║
 * ║  └──┬───┬───┬──┘                                         ║
 * ║     ▼   ▼   ▼                                            ║
 * ║  ┌────┐┌────┐┌──────┐                                    ║
 * ║  │普通 ││大当量││超级爆炸│  三模式都做：①破坏方块 ②伤害实体 ③音效/粒子 ║
 * ║  └──┬─┘└──┬─┘└──┬───┘                                    ║
 * ║     ▼         ▼          ② 伤害实体（共用）                 ║
 * ║  ┌───────────────┐       ③ 播放音效/粒子                   ║
 * ║  │ 4. 实体伤害     │                                        ║
 * ║  └───────────────┘                                         ║
 * ║                                                            ║
 * ║  三种模式的区别只在"怎么破坏方块"：                          ║
 * ║  - 普通模式：用原版setBlock()，慢但精确，有掉落物             ║
 * ║  - 大当量模式：直接改区块Section数据，快但没掉落物            ║
 * ║  - 超级爆炸模式：每 tick 消除一个区块，从中心向外扩散          ║
 * ║                                                            ║
 * ╚══════════════════════════════════════════════════════════════╝
 */

public class ExplosionComponent {

    public static final String ID = "explosion";

    private static final float DEFAULT_BASE_RADIUS = 4.0f;
    private static final boolean DEFAULT_VANILLA_DROPS = true;
    private static final int SUPER_EXPLOSION_THRESHOLD = 54 * 64;

    private static final Map<UUID, SuperExplosionTask> PENDING_SUPER_EXPLOSIONS = new LinkedHashMap<>();

    private static final class SuperExplosionTask {
        final ServerLevel level;
        final Vec3 center;
        final double radius;
        final List<ChunkPos> sortedChunks;
        int index;

        SuperExplosionTask(ServerLevel level, Vec3 center, double radius, List<ChunkPos> sortedChunks) {
            this.level = level;
            this.center = center;
            this.radius = radius;
            this.sortedChunks = sortedChunks;
            this.index = 0;
        }
    }

    private ExplosionComponent() {}

    public static boolean startFuseOnStack(ItemStack tntStack) {
        com.qiqi.li.living.data.LivingTntData data = com.qiqi.li.living.LivingItemManager.getTntData(tntStack);
        com.qiqi.li.living.data.ExplosionData explosion = data.explosion();
        if (!explosion.ignited()) {
            explosion = explosion.ignite();
            com.qiqi.li.living.LivingItemManager.setTntData(tntStack, data.withExplosion(explosion));
        }
        return true;
    }

    public static boolean ignite(ContainerContext containerCtx, Level level) {
        return ignite(containerCtx, level, DEFAULT_BASE_RADIUS, DEFAULT_VANILLA_DROPS);
    }

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

        double radius = baseRadius * Math.sqrt(totalTntCount);

        executeExplosion(level, center.x, center.y, center.z, radius, totalTntCount, vanillaDrops);
        return true;
    }

    // ──────────────────────────────────────────────
    //  第3步：选择爆炸模式
    // ──────────────────────────────────────────────
    // 根据TNT数量选模式：
    //   ≤64 → 普通模式（用原版setBlock，有掉落物）
    //   >64 → 大当量模式（直接改区块数据，没掉落物，但快得多）
    //
    // 普通模式的掉落物行为由 vanillaDrops 控制：
    //   true  → 原版逻辑（战利品表 + 爆炸衰减）
    //   false → 100%掉落（所有方块完整掉落，无衰减）

    private static void executeExplosion(Level level, double centerX, double centerY,
                                          double centerZ, double radius, int totalTntCount,
                                          boolean vanillaDrops) {
        LivingItem.LOGGER.info("爆炸触发: TNT数量={}, radius={}, 位置=({},{},{}), 掉落模式={}",
            totalTntCount, radius, centerX, centerY, centerZ,
            vanillaDrops ? "原版衰减" : "100%掉落");

        if (totalTntCount <= 64) {
            LivingItem.LOGGER.info("使用普通模式 (≤64 TNT)");
            executeNormalExplosion(level, centerX, centerY, centerZ, (float) radius, vanillaDrops);
        } else if (totalTntCount <= SUPER_EXPLOSION_THRESHOLD) {
            LivingItem.LOGGER.info("使用大当量模式 (>64 TNT)");
            executeHighYieldExplosion(level, centerX, centerY, centerZ, (float) radius);
        } else {
            LivingItem.LOGGER.info("使用超级爆炸模式 (>{} TNT)", SUPER_EXPLOSION_THRESHOLD);
            scheduleSuperExplosion(level, centerX, centerY, centerZ, radius);
            // 超级爆炸模式下，实体伤害立即生效，区块消除渐进执行
            applyExplosionDamage(level, centerX, centerY, centerZ, radius);
            return;
        }

        applyExplosionDamage(level, centerX, centerY, centerZ, radius);
    }

    // ══════════════════════════════════════════════
    //  实体伤害（两种模式共用）
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
    //  普通模式（≤64 TNT）
    // ══════════════════════════════════════════════
    // 遍历球体范围内每个坐标 → 判断该不该炸 → 替换成空气
    //
    // 掉落物有两种模式（由 vanillaDrops 控制）：
    //
    // ┌──────────────────────────────────────────────────────────────┐
    // │ 原版模式 (vanillaDrops=true)                                 │
    // │                                                              │
    // │ 调用 BlockState.onExplosionHit()，走原版的完整掉落链路：     │
    // │   ① canDropFromExplosion()  → 方块是否允许被爆炸掉落        │
    // │   ② getDrops(lootParams)    → 战利品表生成掉落物             │
    // │      └ ApplyExplosionDecay  → 每个物品独立存活概率 1/radius  │
    // │      └ SurvivesExplosion    → 整组掉落物存活概率 1/radius    │
    // │   ③ spawnAfterBreak()       → 额外物品（如萤石碎片）        │
    // │   ④ onBlockExploded()       → 通知方块被炸，然后setBlock     │
    // │   ⑤ 掉落物合并              → 相邻物品自动合并（最多16个）   │
    // │                                                              │
    // │ 效果：1个TNT(radius=4)炸100个石头 → 约25个掉落物             │
    // ├──────────────────────────────────────────────────────────────┤
    // │ 100%模式 (vanillaDrops=false)                                │
    // │                                                              │
    // │ 调用 Block.dropResources()，简单粗暴：                       │
    // │   每个被炸方块100%掉落自身，无衰减、无合并                   │
    // │                                                              │
    // │ 效果：1个TNT(radius=4)炸100个石头 → 100个掉落物              │
    // └──────────────────────────────────────────────────────────────┘

    private static void executeNormalExplosion(Level level, double centerX, double centerY,
                                                double centerZ, double radius, boolean vanillaDrops) {
        int rInt = (int) Math.ceil(radius);

        List<BlockPos> blocksToBlow = new ArrayList<>();

        for (int x = -rInt; x <= rInt; x++) {
            for (int y = -rInt; y <= rInt; y++) {
                for (int z = -rInt; z <= rInt; z++) {
                    double dist = Math.sqrt(x * x + y * y + z * z);
                    if (dist > radius) continue;

                    BlockPos pos = new BlockPos(
                        (int) centerX + x, (int) centerY + y, (int) centerZ + z
                    );
                    if (!level.isInWorldBounds(pos)) continue;

                    BlockState state = level.getBlockState(pos);
                    if (state.isAir()) continue;

                    FluidState fluidState = level.getFluidState(pos);
                    float blockResistance = state.getExplosionResistance(level, pos, null);
                    float fluidResistance = fluidState.getExplosionResistance();
                    float effectiveResistance = Math.max(blockResistance, fluidResistance);

                    if (effectiveResistance >= 100.0F) continue;

                    float effectiveRadius = (float) (radius * (0.7F + level.random.nextFloat() * 0.6F));
                    if (dist > effectiveRadius && effectiveResistance > 0.0F) continue;

                    blocksToBlow.add(pos.immutable());
                }
            }
        }

        // 根据掉落模式选择不同的破坏方式
        if (vanillaDrops) {
            destroyBlocksWithVanillaDrops(level, blocksToBlow, (float) radius);
        } else {
            destroyBlocksWithFullDrops(level, blocksToBlow);
        }

        // 音效和粒子
        level.playSound(null, centerX, centerY, centerZ, SoundEvents.GENERIC_EXPLODE,
            SoundSource.BLOCKS, 4.0F, (1.0F + (level.random.nextFloat() - level.random.nextFloat()) * 0.2F) * 0.7F);
        if (radius >= 2.0F) {
            level.addParticle(ParticleTypes.EXPLOSION_EMITTER, true, centerX, centerY, centerZ, 0, 0, 0);
        } else {
            level.addParticle(ParticleTypes.EXPLOSION, true, centerX, centerY, centerZ, 0, 0, 0);
        }
    }

    // ──────────────────────────────────────────────
    //  原版掉落模式
    // ──────────────────────────────────────────────
    // 完全复刻原版 Explosion.finalizeExplosion() 的掉落逻辑：
    //
    //   1. 创建一个虚构的 Explosion 对象（只用来提供 radius 和 BlockInteraction）
    //   2. 对每个方块调用 onExplosionHit()，走原版完整掉落链路
    //   3. 收集所有掉落物，合并同类项（最多16个一组）
    //   4. 用 Block.popResource() 生成到世界中
    //
    // 为什么要虚构Explosion？因为 onExplosionHit() 需要一个Explosion参数
    // 来判断 BlockInteraction（是否启用衰减）和提供 radius（衰减概率）。
    // 我们只需要这两个信息，其他字段无所谓。

    private static void destroyBlocksWithVanillaDrops(Level level, List<BlockPos> blocksToBlow, float radius) {
        // 创建虚构的Explosion对象，使用 DESTROY_WITH_DECAY 模式
        // 这会让原版在生成掉落物时应用爆炸衰减（1/radius概率存活）
        Explosion fakeExplosion = new Explosion(
            level, null, 0, 0, 0, radius, false, Explosion.BlockInteraction.DESTROY_WITH_DECAY
        );

        // 收集所有掉落物，格式：(物品栈, 生成位置)
        // 和原版 finalizeExplosion 一样用 List<Pair<ItemStack, BlockPos>>
        List<Pair<ItemStack, BlockPos>> drops = new ArrayList<>();

        // 打乱顺序，和原版一样（影响掉落物合并的优先级）
        net.minecraft.Util.shuffle(blocksToBlow, level.random);

        for (BlockPos pos : blocksToBlow) {
            BlockState state = level.getBlockState(pos);
            // 调用原版的 onExplosionHit，它会：
            //   ① 检查 canDropFromExplosion
            //   ② 用战利品表生成掉落物（含衰减）
            //   ③ 调用 spawnAfterBreak（额外掉落）
            //   ④ 调用 onBlockExploded（通知方块被炸，内部会setBlock为空气）
            state.onExplosionHit(level, pos, fakeExplosion, (stack, dropPos) -> {
                // 这个回调就是原版的 addOrAppendStack 逻辑：
                // 尝试和已有的同类掉落物合并，最多16个一组
                mergeOrAppendDrop(drops, stack, dropPos);
            });
        }

        // 生成所有掉落物到世界中
        for (Pair<ItemStack, BlockPos> pair : drops) {
            Block.popResource(level, pair.getSecond(), pair.getFirst());
        }
    }

    // 原版 Explosion.addOrAppendStack 的复刻
    // 尝试把新掉落物和已有的同类物品合并（最多16个一组）
    // 如果无法合并，就追加到列表末尾
    private static void mergeOrAppendDrop(List<Pair<ItemStack, BlockPos>> drops,
                                           ItemStack stack, BlockPos pos) {
        for (int i = 0; i < drops.size(); i++) {
            Pair<ItemStack, BlockPos> existing = drops.get(i);
            ItemStack existingStack = existing.getFirst();
            // ItemEntity.areMergable 检查：同种物品、同NBT、未满16个
            if (ItemEntity.areMergable(existingStack, stack)) {
                // ItemEntity.merge 返回合并后的物品栈
                drops.set(i, Pair.of(ItemEntity.merge(existingStack, stack, 16), existing.getSecond()));
                if (stack.isEmpty()) return;
            }
        }
        drops.add(Pair.of(stack, pos));
    }

    // ──────────────────────────────────────────────
    //  100%掉落模式
    // ──────────────────────────────────────────────
    // 简单粗暴：每个被炸方块直接 dropResources，然后 setBlock 为空气
    // 没有衰减、没有合并、没有战利品表

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
    //  大当量模式（>64 TNT）
    // ══════════════════════════════════════════════
    //
    // 为什么不用普通模式？因为TNT太多时方块太多。
    // 1728个TNT半径166，球体约1900万方块，逐个setBlock()会卡死。
    //
    // 大当量模式的思路：跳过原版的所有回调，直接修改区块底层数据。
    //
    // 分三个阶段：
    //
    //   阶段1-收集：遍历球体，把"该炸的方块"按Section分组记录下来
    //   阶段2-修改：按Section批量修改，直接把方块数据改成空气
    //   阶段3-同步：通知客户端"这些位置变了"，让客户端重新渲染
    //
    // ┌──────────────────────────────────────────────┐
    // │ 什么是Section？                               │
    // │                                              │
    // │ Minecraft世界按16×16×16的立方体存储方块，     │
    // │ 这个立方体叫"Section"（区块段）。             │
    // │ 一个Chunk（区块）在垂直方向叠了多个Section。  │
    // │                                              │
    // │  主世界高度 -64~319，共384格 = 24个Section    │
    // │  Section[0]  → Y=-64 ~ Y=-49                 │
    // │  Section[1]  → Y=-48 ~ Y=-33                 │
    // │  ...                                         │
    // │  Section[23] → Y=304 ~ Y=319                 │
    // │                                              │
    // │  ⚠️ 关键：Section的世界坐标 ≠ 数组索引        │
    // │  世界Section Y=-4 对应 数组索引 0             │
    // │  转换公式：index = sectionY - minSection      │
    // └──────────────────────────────────────────────┘

    private static void executeHighYieldExplosion(Level level, double centerX, double centerY,
                                                   double centerZ, double radius) {
        if (level.isClientSide) return;

        int rInt = (int) Math.ceil(radius);

        // ── 阶段1：收集待破坏方块 ──
        // 遍历球体范围内所有方块，判断哪些该炸，
        // 把它们的坐标按Section分组存到 sectionUpdates 里。
        //
        // sectionUpdates 的结构：
        //   key   = SectionPos（哪个16×16×16的区块段）
        //   value = ShortSet（该Section内哪些局部坐标要炸）
        //
        // 为什么要按Section分组？因为阶段2修改方块时，
        // 同一个Section只需要获取一次，避免重复查找。

        Map<SectionPos, ShortSet> sectionUpdates = new HashMap<>();
        BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();

        for (int x = -rInt; x <= rInt; x++) {
            for (int y = -rInt; y <= rInt; y++) {
                for (int z = -rInt; z <= rInt; z++) {
                    double distSq = x * x + y * y + z * z;
                    if (distSq > radius * radius) continue;

                    mutablePos.set(centerX + x, centerY + y, centerZ + z);
                    if (!level.isInWorldBounds(mutablePos)) continue;

                    BlockState state = level.getBlockState(mutablePos);
                    if (state.isAir()) continue;

                    FluidState fluidState = level.getFluidState(mutablePos);
                    float blockResistance = state.getExplosionResistance(level, mutablePos, null);
                    float fluidResistance = fluidState.getExplosionResistance();
                    float effectiveResistance = Math.max(blockResistance, fluidResistance);

                    double dist = Math.sqrt(distSq);
                    float power = (float) (radius * (1.0 - dist / radius) * 2.0);
                    if (power <= effectiveResistance) continue;

                    int chunkX = SectionPos.blockToSectionCoord(mutablePos.getX());
                    int chunkY = SectionPos.blockToSectionCoord(mutablePos.getY());
                    int chunkZ = SectionPos.blockToSectionCoord(mutablePos.getZ());
                    SectionPos sectionPos = SectionPos.of(chunkX, chunkY, chunkZ);

                    int localX = SectionPos.sectionRelative(mutablePos.getX());
                    int localY = SectionPos.sectionRelative(mutablePos.getY());
                    int localZ = SectionPos.sectionRelative(mutablePos.getZ());

                    short packedPos = (short)(localX | (localZ << 4) | (localY << 8));

                    sectionUpdates.computeIfAbsent(sectionPos, k -> new ShortOpenHashSet())
                        .add(packedPos);
                }
            }
        }

        LivingItem.LOGGER.info("大当量爆炸-阶段1收集完成: {}个Section, 共{}个方块待破坏",
            sectionUpdates.size(),
            sectionUpdates.values().stream().mapToInt(ShortSet::size).sum());

        // ── 阶段2：批量修改方块 ──
        // 遍历每个Section，直接操作底层数据把方块改成空气。
        // LevelChunkSection.setBlockState() 只改内存中的方块数据，
        // 不触发任何回调/光照更新/客户端同步。

        BlockState airState = Blocks.AIR.defaultBlockState();

        for (var entry : sectionUpdates.entrySet()) {
            SectionPos sectionPos = entry.getKey();
            ShortSet positions = entry.getValue();

            LevelChunk chunk = level.getChunk(sectionPos.x(), sectionPos.z());

            // ⚠️ 核心修复：世界Section坐标 → 数组索引
            // sectionPos.y() 是世界坐标（比如-4），但getSection()要的是数组索引（0）
            // 转换：index = 世界坐标 - 最低Section坐标
            int sectionIndex = sectionPos.y() - chunk.getMinSection();

            if (sectionIndex < 0 || sectionIndex >= chunk.getSectionsCount()) continue;

            LevelChunkSection section = chunk.getSection(sectionIndex);

            for (short packedPos : positions) {
                int localX = packedPos & 0xF;
                int localZ = (packedPos >> 4) & 0xF;
                int localY = (packedPos >> 8) & 0xF;

                int worldX = sectionPos.minBlockX() + localX;
                int worldY = sectionPos.minBlockY() + localY;
                int worldZ = sectionPos.minBlockZ() + localZ;
                BlockPos blockPos = new BlockPos(worldX, worldY, worldZ);

                BlockEntity blockEntity = chunk.getBlockEntity(blockPos);
                if (blockEntity != null) {
                    chunk.removeBlockEntity(blockPos);
                }

                section.setBlockState(localX, localY, localZ, airState, false);
            }

            chunk.setUnsaved(true);
        }

        // ── 阶段3：通知客户端 ──
        // 阶段2只改了服务端内存，客户端还不知道方块变了。
        // 收集受影响的区块，整区块打包发送，比逐方块通知高效得多。

        if (level instanceof ServerLevel serverLevel) {
            Set<ChunkPos> affectedChunks = new HashSet<>();
            for (SectionPos sp : sectionUpdates.keySet()) {
                affectedChunks.add(new ChunkPos(sp.x(), sp.z()));
            }
            for (ChunkPos cp : affectedChunks) {
                LevelChunk chunk = serverLevel.getChunk(cp.x, cp.z);
                var packet = new ClientboundLevelChunkWithLightPacket(chunk, serverLevel.getLightEngine(), null, null);
                for (ServerPlayer player : serverLevel.getChunkSource().chunkMap.getPlayers(cp, false)) {
                    player.connection.send(packet);
                }
            }
        }

        // ── 视觉效果 ──
        if (!level.isClientSide) {
            float volume = Math.min(4.0f, (float) (radius / 4.0) * 4.0f);
            level.playSound(null, centerX, centerY, centerZ,
                SoundEvents.GENERIC_EXPLODE, SoundSource.BLOCKS, volume,
                (1.0f + (level.random.nextFloat() - level.random.nextFloat()) * 0.2f) * 0.7f);

            if (level instanceof ServerLevel serverLevel) {
                serverLevel.sendParticles(
                    ParticleTypes.EXPLOSION_EMITTER,
                    centerX, centerY, centerZ, 1,
                    0, 0, 0, 0
                );
            }
        }
    }

    // ══════════════════════════════════════════════
    //  超级爆炸模式（>3456 TNT）
    // ══════════════════════════════════════════════
    //
    // 超过 54×64 个 TNT 时启用。一次性清除会卡死服务器，
    // 所以改为逐 tick 渐进消除：每 tick 消除一个区块，
    // 从爆炸中心逐步向外扩散。
    //
    // 分三步：
    //   1. scheduleSuperExplosion — 收集区块列表，按距离排序，加入调度队列
    //   2. tickAll — 每 tick 处理一个区块，从队列中取出并删除
    //   3. deleteChunkContent — 清除单个区块的所有方块和方块实体

    private static void scheduleSuperExplosion(Level level, double centerX, double centerY,
                                                double centerZ, double radius) {
        if (!(level instanceof ServerLevel serverLevel)) return;

        int chunkRadius = (int) Math.ceil(radius / 16.0);
        int centerChunkX = SectionPos.blockToSectionCoord((int) centerX);
        int centerChunkZ = SectionPos.blockToSectionCoord((int) centerZ);

        List<ChunkPos> chunks = new ArrayList<>();
        for (int cx = -chunkRadius; cx <= chunkRadius; cx++) {
            for (int cz = -chunkRadius; cz <= chunkRadius; cz++) {
                int chunkX = centerChunkX + cx;
                int chunkZ = centerChunkZ + cz;
                double chunkCenterX = (chunkX << 4) + 8;
                double chunkCenterZ = (chunkZ << 4) + 8;
                double dx = chunkCenterX - centerX;
                double dz = chunkCenterZ - centerZ;
                double dist = Math.sqrt(dx * dx + dz * dz);
                if (dist <= radius) {
                    chunks.add(new ChunkPos(chunkX, chunkZ));
                }
            }
        }

        chunks.sort(Comparator.comparingDouble((ChunkPos pos) -> {
            double cx = (pos.x << 4) + 8;
            double cz = (pos.z << 4) + 8;
            double dx = cx - centerX;
            double dz = cz - centerZ;
            return dx * dx + dz * dz;
        }).thenComparingInt(pos -> Math.abs(pos.x - centerChunkX) + Math.abs(pos.z - centerChunkZ)));

        Vec3 center = new Vec3(centerX, centerY, centerZ);
        PENDING_SUPER_EXPLOSIONS.put(UUID.randomUUID(), new SuperExplosionTask(serverLevel, center, radius, chunks));

        LivingItem.LOGGER.info("超级爆炸已调度: 半径={}, 区块数={}, 预计{}tick完成",
            String.format("%.1f", radius), chunks.size(), chunks.size());

        serverLevel.playSound(null, centerX, centerY, centerZ,
            SoundEvents.GENERIC_EXPLODE, SoundSource.BLOCKS, 4.0F,
            (1.0F + (serverLevel.random.nextFloat() - serverLevel.random.nextFloat()) * 0.2F) * 0.7F);
        serverLevel.sendParticles(ParticleTypes.EXPLOSION_EMITTER, centerX, centerY, centerZ, 1, 0, 0, 0, 0);
    }

    public static void tickAll() {
        if (PENDING_SUPER_EXPLOSIONS.isEmpty()) return;

        var it = PENDING_SUPER_EXPLOSIONS.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            var task = entry.getValue();

            if (task.index >= task.sortedChunks.size()) {
                it.remove();
                LivingItem.LOGGER.info("超级爆炸完成: 共消除{}个区块", task.sortedChunks.size());
                continue;
            }

            ChunkPos chunkPos = task.sortedChunks.get(task.index);
            ServerLevel level = task.level;

            if (level.hasChunk(chunkPos.x, chunkPos.z)) {
                deleteChunkContent(level, chunkPos);
            }

            task.index++;
        }
    }

    private static void deleteChunkContent(ServerLevel level, ChunkPos chunkPos) {
        LevelChunk chunk = level.getChunk(chunkPos.x, chunkPos.z);
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

        notifyChunkClients(level, chunkPos, chunk);
    }

    private static void notifyChunkClients(ServerLevel level, ChunkPos chunkPos, LevelChunk chunk) {
        var packet = new ClientboundLevelChunkWithLightPacket(chunk, level.getLightEngine(), null, null);
        for (ServerPlayer player : level.getChunkSource().chunkMap.getPlayers(chunkPos, false)) {
            player.connection.send(packet);
        }
    }
}