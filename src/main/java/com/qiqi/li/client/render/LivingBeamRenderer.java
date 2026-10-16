package com.qiqi.li.client.render;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.domain.beacon.BeaconBaseBlocks;
import com.qiqi.li.living.domain.beacon.BeaconHostClientCache;
import com.qiqi.li.living.domain.beacon.BeaconHostPacket;
import com.qiqi.li.living.domain.beacon.BeaconPlayerHostClientCache;
import com.qiqi.li.living.domain.beacon.BeaconPlayerHostPacket;
import com.qiqi.li.living.domain.beacon.BeaconPyramid;
import com.qiqi.li.living.model.Pos2D;
import com.qiqi.li.living.transfer.SlotResolver;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BeaconRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * 活信标光束渲染（Phase 1/2）—— 设计稿 {@code docs/buffer/living-beacon-design.md}
 * §2.1 / §2.4 / §6。
 *
 * <p><b>两种宿主</b>（规则 1：仅玩家 / 箱子）：</p>
 * <ul>
 *   <li><b>玩家宿主</b>：本机背包（含快捷栏 / 副手）里的活信标，从<b>头颅顶面</b>沿<b>顶面法线</b>射出
 *       （随头俯仰转动：平视 = 竖直向上、低头 = 朝前上方、抬头 = 朝后上方）。</li>
 *       <b>本地判级门控</b>：客户端自己读下方 9 列柱高（本机背包可见）→ {@link BeaconPyramid}
 *       判级 → level ≥ 1 才亮（无条件亮是 Phase 1 临时口径，Phase 2 接入）。</li>
 *   <li><b>箱子宿主</b>：{@link BeaconHostClientCache}（S2C 同步的位置 + 等级），
 *       从箱口顶部垂直向上射出，本地 clip 截断。开盖由服务端 blockEvent 驱动。</li>
 * </ul>
 *
 * <h3>复用的原版函数</h3>
 * {@link BeaconRenderer#renderBeaconBeam} 是 public static，直接调。⚠️ 它内部
 * {@code translate(0.5, 0, 0.5)}（给方块坐标补中心用）⇒ 调用前必须做 −0.5 XZ 预补偿，
 * 否则光束轴偏离起点（设计稿 §2.1 已知坑）。
 *
 * <h3>方向对齐</h3>
 * 通用公式与 {@link LivingToolModelRenderer#drawModel} 同款：
 * {@code yaw = atan2(dir.x, dir.z)} 绕 Y + {@code pitch = atan2(h, dir.y)} 绕 X，
 * 把局部 +Y 对齐到给定方向（玩家宿主 = {@link #headNormal} 顶面法线；箱子宿主 = 竖直向上，
 * 局部 +Y 即向上、无需旋转）。
 */
public final class LivingBeamRenderer {

    /**
     * 无遮挡时光束长度上限（格）—— <b>1024，对齐原版</b>
     * （{@code BeaconRenderer.MAX_RENDER_Y}；2026-10-16 用户定「和原版差不多」，
     * 取代 U4 原值 256）。
     */
    public static final int MAX_BEAM_LENGTH = 1024;

    /** 光束半径 / 光晕半径 —— 与原版信标一致。 */
    private static final float BEAM_RADIUS = 0.2F;
    private static final float GLOW_RADIUS = 0.25F;

    /**
     * 颜色扫描上限（格）—— <b>两个宿主共用</b>（U3 方案 A：颜色一律取自<b>世界方块</b>，
     * 玩家宿主从头顶上方扫、箱子宿主从箱口上方扫）。玻璃叠层实际很短，超出部分由
     * "最后一段延伸到终点"兜住。
     *
     * <p>⚠️ 原版是方块实体、每 tick 增量扫 10 格；我们<b>每帧</b>渲染 ⇒ 不能无上限长扫。</p>
     */
    private static final int COLOR_SCAN_LIMIT = 32;

    /** 跳过透明方块时的推进步长（格）与最大重打次数（防死循环）。 */
    private static final double CLIP_STEP = 0.02;
    private static final int MAX_CLIP_STEPS = 64;

    private LivingBeamRenderer() {
    }

    /**
     * 渲染入口 —— 由 {@code LivingItemClient} 转发 {@link RenderLevelStageEvent}。
     *
     * <p>挂在 {@link RenderLevelStageEvent.Stage#AFTER_ENTITIES}：地形与实体的深度已写入，
     * 光束被正确遮挡（与本地 clip 形成双保险）。</p>
     */
    public static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.player == null) {
            return;
        }

        PoseStack poseStack = event.getPoseStack();
        Vec3 cameraPos = event.getCamera().getPosition();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);

        boolean drew = renderPlayerHost(mc, level, poseStack, buffers, cameraPos, partialTick);
        drew |= renderRemotePlayerHosts(mc, level, poseStack, buffers, cameraPos, partialTick);
        drew |= renderChestHosts(level, poseStack, buffers, cameraPos, partialTick);

        if (drew) {
            // 必须显式 flush，否则本帧不保证会画出来
            buffers.endBatch(RenderType.beaconBeam(BeaconRenderer.BEAM_LOCATION, false));
        }
    }

    /**
     * 玩家宿主：本机背包里第一个<b>已激活</b>（level ≥ 1）的活信标 ⇒ 从<b>头颅顶面</b>沿<b>顶面法线</b>射出。
     *
     * <p>多个信标同时存在时只画一条（同起点同方向，几何重合；第一个激活的优先）——
     * 若第一个信标未激活而后面有激活的，会继续找。</p>
     *
     * <p>颜色（U3 方案 A，贴合原版）：从<b>头颅顶面上方</b>逐格扫描<b>世界方块</b>
     * （{@link #scanAbove}）—— 与原版信标从自身往上扫同源，所以走进染色玻璃走廊会变色。</p>
     */
    private static boolean renderPlayerHost(Minecraft mc, ClientLevel level, PoseStack poseStack,
                                            MultiBufferSource.BufferSource buffers, Vec3 cameraPos,
                                            float partialTick) {
        Inventory inventory = mc.player.getInventory();
        int size = inventory.getContainerSize();

        for (int slot = 0; slot < size; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty() || !stack.is(Items.BEACON) || !LivingItemManager.isLivingItem(stack)) {
                continue;
            }
            if (BeaconPyramid.resolveLevel(localColumnHeights(inventory, slot)) < 1) {
                continue;   // 未激活 ⇒ 不亮（Phase 2 门控）
            }
            renderBeamAlong(poseStack, buffers, cameraPos, partialTick, level,
                headTop(mc.player, partialTick), headNormal(mc.player),
                playerColorSections(level, mc.player, partialTick));
            return true;
        }
        return false;
    }

    /** 头颅顶面相对<b>颈部枢轴</b>的高度（格）—— 站立 1.8 − 颈部 1.5。 */
    private static final double HEAD_TOP_FROM_PIVOT = 0.3;

    /**
     * 光束起点 = <b>头颅顶面中心</b>（像帽子一样贴在头顶 ⇒ <b>随头转动</b>）。
     *
     * <p>= 颈部枢轴 + 头部旋转后的"上方向"（{@link #headNormal}）× 顶面偏移。
     * 平视时退化为「脚底 + 实体高度」（与更早的实现一致），但<b>低头时头顶面中心会往前、往下移动</b>
     * —— 这才是"贴在头顶那个面上"的准确位置。潜行 / 坐骑的高度变化仍然保留。</p>
     */
    private static Vec3 headTop(Player player, float partialTick) {
        Vec3 pivot = player.getPosition(partialTick)
            .add(0.0, player.getBbHeight() - HEAD_TOP_FROM_PIVOT, 0.0);
        return pivot.add(headNormal(player).scale(HEAD_TOP_FROM_PIVOT));
    }

    /**
     * 光束方向 = <b>头颅顶面的法线</b>（世界坐标单位向量）—— 用户 2026-10-16 定。
     *
     * <p>几何：这个面<b>随头俯仰转动</b> ⇒ 平视时法线 = <b>竖直向上</b>；<b>低头</b>时头顶朝
     * <b>前上方</b>；<b>抬头</b>时朝<b>后上方</b>。它与视线方向<b>垂直</b>（相差 90°）—— 别把两者
     * 混为一谈（低头时视线朝前下、顶面法线朝前上）。</p>
     *
     * <p>推导（MC 约定：yaw 0 = +Z，pitch <b>低头为正</b>）：视线
     * {@code L = (−sin y·cos p, −sin p, cos y·cos p)}；取与之垂直、且 {@code p=0} 时等于
     * {@code (0,1,0)} 的那个方向 ⇒ {@code N = (−sin y·sin p, cos p, cos y·sin p)}。
     * 三个校验点：p=0 ⇒ (0,1,0)（竖直向上）；p=+90° ⇒ (−sin y, 0, cos y)（水平<b>前</b>方）；
     * p=−90° ⇒ 水平<b>后</b>方。且 {@code N·L = 0} 恒成立。</p>
     */
    private static Vec3 headNormal(Player player) {
        float yaw = player.getYRot() * Mth.DEG_TO_RAD;
        float pitch = player.getXRot() * Mth.DEG_TO_RAD;
        return new Vec3(-Mth.sin(yaw) * Mth.sin(pitch), Mth.cos(pitch), Mth.cos(yaw) * Mth.sin(pitch));
    }

    /**
     * 玩家宿主的颜色分段（U3 方案 A）：从<b>头颅顶面上方</b>逐格扫描世界方块
     * （与箱子宿主同一条路径 —— 两个宿主用同一套原版算法）。
     *
     * <p>⭐ 客户端<b>本地</b>就能算：世界方块本来就同步给所有客户端，别人的光束也一样算得出来
     * ⇒ 颜色<b>不需要</b>随 S2C 下发（这是方案 A 相对方案 B 的主要好处）。</p>
     *
     * <p>⚠️ 分段高度是<b>竖直格数</b>，而玩家光束沿<b>顶面法线</b> —— 两者不严格对应；因"最后一段一直画到
     * 终点"，实际观感仍是"第一块染色玻璃决定整根颜色"（与原版一致）。</p>
     */
    private static List<BeamColorSections.Section> playerColorSections(ClientLevel level, Player player,
                                                                      float partialTick) {
        return BeamColorSections.build(
            scanAbove(level, BlockPos.containing(headTop(player, partialTick)).above(), COLOR_SCAN_LIMIT),
            BeamColorSections.DEFAULT_COLOR);
    }

    /**
     * 从 {@code from} 起<b>竖直向上</b>逐格扫描世界方块的颜色（U3 方案 A，两宿主共用：
     * 玩家宿主从头顶上方、箱子宿主从箱口上方）。
     *
     * <p>取色走 NeoForge {@code BlockState#getBeaconColorMultiplier}（原版同一入口：染色玻璃 /
     * 玻璃板 / 信标自身都实现 {@code BeaconBeamBlock}，第三方彩色玻璃只要实现该接口也自动生效）。</p>
     *
     * <p>⚠️ 上限 {@link #COLOR_SCAN_LIMIT} 格：原版是方块实体、每 tick 增量扫 10 格；我们是
     * <b>每帧</b>渲染 ⇒ 长扫代价高。玻璃叠层实际都很短，超出部分由"最后一段延伸到终点"兜住。</p>
     */
    private static List<BeamColorSections.Observation> scanAbove(ClientLevel level, BlockPos from, int limit) {
        List<BeamColorSections.Observation> out = new ArrayList<>(Math.min(limit, 16));
        BlockPos.MutableBlockPos pos = from.mutable();
        for (int i = 0; i < limit; i++) {
            BlockState state = level.getBlockState(pos);
            Integer color = state.getBeaconColorMultiplier(level, pos.immutable(), from);
            // 截断判据与光束截断同一处口径（原版 tick 循环里就是同一支判断）
            boolean blocking = blocksBeam(level, pos.immutable());
            out.add(new BeamColorSections.Observation(color, blocking));
            if (blocking) {
                break;   // 遮光 ⇒ 后面的颜色不算（与原版同口径）
            }
            pos.move(Direction.UP);
        }
        return out;
    }

    /**
     * 「遮光」判据 —— **逐字对齐原版** {@code BeaconBlockEntity.tick}：
     * {@code blockstate.getLightBlock(level, pos) >= 15 && !blockstate.is(Blocks.BEDROCK)}。
     *
     * <p>2026-10-16 用户要求对齐。此前我们用 {@code canOcclude()}——绝大多数方块结论一致
     * （石 / 土 / 木板挡，玻璃 / 玻璃板 / 铁栏 / 树叶 / 台阶过），但有两个边缘差异：
     * ① <b>基岩</b>：原版特意排除 {@code BEDROCK} ⇒ 原版光束<b>能穿过基岩层</b>，
     * canOcclude 会把它挡住；② 遮光等级口径比「能否遮挡邻面」更宽（存在 canOcclude=false
     * 但遮光 ≥15 的方块，原版挡、我们不挡）。</p>
     *
     * <p>⚠️ 表现层**有意不对齐**（用户 2026-10-16 拍板）：原版被遮光块盖住时 {@code clear()} 让
     * {@code beamSections} 变空 ⇒ <b>整根消失</b>，且连带 buff 停 / 等级重算停（原版"必须通天"）；
     * 我们只截断到遮挡面、保留盖下一截，<b>buff 与等级完全不受影响</b>——密闭箱子 / 地下室也生效
     * 是玩法增强，不强制玩家搭通天柱。</p>
     */
    private static boolean blocksBeam(ClientLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.getLightBlock(level, pos) >= 15 && !state.is(Blocks.BEDROCK);
    }

    /**
     * 其它玩家宿主（联机可见 · 设计稿规则 6）：S2C 的 UUID + 等级 ⇒ 查实体取
     * <b>他的</b>头顶与顶面法线（实体旋转本来就同步给客户端）⇒ 与自己的光束同款几何。
     *
     * <p>排除自己（本机渲染更实时）与等级 0（服务端只发 ≥ 1，双保险）。</p>
     */
    private static boolean renderRemotePlayerHosts(Minecraft mc, ClientLevel level, PoseStack poseStack,
                                                   MultiBufferSource.BufferSource buffers, Vec3 cameraPos,
                                                   float partialTick) {
        List<BeaconPlayerHostPacket.Entry> hosts =
            BeaconPlayerHostClientCache.get(level.dimension().location());
        boolean drew = false;
        for (BeaconPlayerHostPacket.Entry entry : hosts) {
            Player owner = level.getPlayerByUUID(entry.playerId());
            if (owner == null || owner == mc.player || entry.level() < 1) {
                continue;
            }
            // 颜色同样本地算（U3 方案 A：读世界方块，别人的也读得到）
            drew |= renderBeamAlong(poseStack, buffers, cameraPos, partialTick, level,
                headTop(owner, partialTick), headNormal(owner),
                playerColorSections(level, owner, partialTick));
        }
        return drew;
    }

    /** 箱子宿主：S2C 位置集，垂直向上光束（本地 clip 截断）。 */
    private static boolean renderChestHosts(ClientLevel level, PoseStack poseStack,
                                            MultiBufferSource.BufferSource buffers, Vec3 cameraPos,
                                            float partialTick) {
        List<BeaconHostPacket.Entry> hosts =
            BeaconHostClientCache.get(level.dimension().location());
        boolean drew = false;
        for (BeaconHostPacket.Entry entry : hosts) {
            // 未加载区块不画 —— 否则会在"还没画出来的地形"上悬一条光束
            // （半径 256 覆盖到视距之外时必然出现，原版是方块没这问题）
            if (!level.hasChunkAt(entry.pos())) {
                continue;
            }
            // 起点 = 箱口顶部中心（方块顶面；0.875 的盖面差在视觉上不可见）
            Vec3 start = Vec3.atBottomCenterOf(entry.pos()).add(0.0, 1.0, 0.0);
            // 颜色：箱口上方逐格扫描（照原版算法，见 BeamColorSections）
            List<BeamColorSections.Section> sections = BeamColorSections.build(
                scanAbove(level, entry.pos().above(), COLOR_SCAN_LIMIT), BeamColorSections.DEFAULT_COLOR);
            drew |= renderBeamAlong(poseStack, buffers, cameraPos, partialTick, level,
                start, new Vec3(0.0, 1.0, 0.0), sections);
        }
        return drew;
    }

    /**
     * 求「被<b>遮光</b>方块截断」的终点（设计稿 §4 规则 5；判据逐字对齐原版）。
     *
     * <p>⚠️ <b>不能直接用 {@code level.clip} 的结果</b>：玻璃 / 玻璃板 / 铁栏 / 树叶 / 台阶
     * 都有完整碰撞形状 ⇒ 会被当成"挡住了"（用户实测 2026-10-16「光束不能穿透玻璃」）。
     * 判据走 {@link #blocksBeam}（{@code getLightBlock ≥ 15 && !bedrock}，与原版
     * {@code BeaconBlockEntity.tick} 逐字一致）：石头 / 土 / 木板 / 羊毛等实心方块截断；
     * 玻璃 / 染色玻璃 / 树叶 / 铁栏 / 台阶 / <b>基岩</b>一律穿过。</p>
     *
     * <p>实现：命中非遮光方块 ⇒ 从命中点往前挪一小步继续打；<b>同一方块只判一次</b>
     * （{@code skipped} 守卫，避免从方块内部反复命中它而死循环），直到命中遮光方块或到长度上限。
     * 常见场景（天空 / 实心地形）仍是单次 clip —— 只有真的遇到透明方块才会多打几发。</p>
     */
    private static Vec3 opaqueHitEnd(ClientLevel level, Vec3 start, Vec3 dir, int maxLength) {
        Vec3 end = start.add(dir.scale(maxLength));
        Vec3 from = start;
        BlockPos skipped = null;
        for (int guard = 0; guard < MAX_CLIP_STEPS; guard++) {
            BlockHitResult hit = level.clip(new ClipContext(
                from, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, CollisionContext.empty()));
            if (hit.getType() != HitResult.Type.BLOCK) {
                return end;                                  // 一路无遮挡
            }
            BlockPos pos = hit.getBlockPos();
            if (!pos.equals(skipped) && blocksBeam(level, pos)) {
                return hit.getLocation();                    // 遮光 ⇒ 就在这里截断
            }
            skipped = pos;
            from = hit.getLocation().add(dir.scale(CLIP_STEP));   // 透明 ⇒ 跳过继续
        }
        return end;
    }

    /**
     * 通用光束：起点 + 方向（单位向量）+ 颜色分段 ⇒ 本地求截断高度，<b>逐段堆叠</b>调原版函数
     * （照原版渲染器：最后一段一直画到终点）。
     */
    private static boolean renderBeamAlong(PoseStack poseStack, MultiBufferSource.BufferSource buffers,
                                           Vec3 cameraPos, float partialTick, ClientLevel level,
                                           Vec3 start, Vec3 look,
                                           List<BeamColorSections.Section> sections) {
        Vec3 end = opaqueHitEnd(level, start, look, MAX_BEAM_LENGTH);
        int height = (int) Math.min(Math.round(end.distanceTo(start)), MAX_BEAM_LENGTH);
        if (height <= 0) {
            return false;
        }

        // yaw / pitch：把局部 +Y 对齐到方向（LivingToolModelRenderer.drawModel 同款）
        float yaw = (float) Mth.atan2(look.x, look.z);
        double horizontal = Math.sqrt(look.x * look.x + look.z * look.z);
        float pitch = (float) Math.atan2(horizontal, look.y);

        poseStack.pushPose();
        // 事件的 PoseStack 已是相机相对坐标
        poseStack.translate(start.x - cameraPos.x, start.y - cameraPos.y, start.z - cameraPos.z);
        poseStack.mulPose(Axis.YP.rotation(yaw));
        poseStack.mulPose(Axis.XP.rotation(pitch));
        // ⚠️ 预补偿 renderBeaconBeam 内部的 translate(0.5, 0, 0.5)（设计稿 §2.1 已知坑）
        poseStack.translate(-0.5F, 0.0F, -0.5F);

        int yOffset = 0;
        for (int i = 0; i < sections.size(); i++) {
            BeamColorSections.Section section = sections.get(i);
            int remain = height - yOffset;
            if (remain <= 0) {
                break;                       // 高度用尽 ⇒ 后面段无意义
            }
            int segHeight = (i == sections.size() - 1)
                ? remain                                           // 最后一段 ⇒ 一直画到终点
                : Math.min(section.height(), remain);
            if (segHeight <= 0) {
                continue;                    // 0 高段跳过（信标自身格等），不是终止条件
            }
            BeaconRenderer.renderBeaconBeam(
                poseStack, buffers, BeaconRenderer.BEAM_LOCATION, partialTick, 1.0F,
                level.getGameTime(), yOffset, segHeight, section.color(), BEAM_RADIUS, GLOW_RADIUS);
            yOffset += segHeight;
        }

        poseStack.popPose();
        return true;
    }

    /**
     * 本地（客户端）读下方 9 列柱高 —— 与 {@code BeaconFunction.readColumnHeights} 同口径
     * （9 列网格；背包 hotbar 第 0 行、主区 1~3 行、offhand 第 4 行）。
     */
    static long[] localColumnHeights(Inventory inventory, int slot) {
        long[] heights = new long[BeaconPyramid.COLUMNS];
        int size = inventory.getContainerSize();
        for (int offset = -BeaconPyramid.CENTER; offset <= BeaconPyramid.CENTER; offset++) {
            int target = SlotResolver.resolve(slot, new Pos2D(offset, 1), size, 9);
            if (target >= 0) {
                heights[offset + BeaconPyramid.CENTER] =
                    BeaconBaseBlocks.columnHeight(inventory.getItem(target));
            }
        }
        return heights;
    }
}
