# 项目长期记忆（Living Item Mod）

## 纹理约定
- 涂蜡铜灯图标 = 未涂蜡对应图标 + 外圈黄色边框。边框 60 像素，颜色 (232,160,62,255)，四种锈蚀等级（copper/exposed/weathered/oxidized）边框掩码完全一致。发光版（lit）同理：内部取未涂蜡发光图、外圈填黄框。
- item 纹理均为 16×16 PNG（含 P 调色板与 RGBA 两种，均有效）。

## 环境
- Python 隔离 venv：`C:/Users/AI-777hi/.workbuddy-ai/binaries/python/envs/default`（已装 Pillow 12.3.0），处理图片用其 `Scripts/python.exe`。

## 配方书材料表（stackedContents）注入约定
- `RecipeBookComponent` 重建 `stackedContents` 只有两条路径：`initVisuals()`（开界面/切可见性）和
  `updateStackedContents()`（背包变动/点槽位）。**改这个 Mixin 时两条都要注入**，漏 `initVisuals`
  就会出现"刚打开界面活箱子材料不识别，做点什么才恢复"。
- 其余 `updateCollections` 调用点（切标签/搜索/筛选/recipesUpdated）只复用、不重建，无需注入。
- **内容变化后的刷新靠原版链路，不要再加轮询/指纹/缓存机制**（2026-09-05 加过又被用户要求移除）：
  服务端改 CONTAINER → `AbstractContainerMenu.triggerSlotListeners` 用 `ItemStack.matches` 判定
  → 发包 → 客户端 `Inventory.setItem` → `timesChanged++` → 客户端 tick 走路径 B。1–3 tick，比任何
  轮询都快。前提已验证：`ItemStackMixin` 只改了 `isSameItemSameComponents` 和 `getTooltipImage`，
  **没动 `matches`**。
- **保持无状态是这个 Mixin 正确的原因** —— 需要引入状态字段的优化方案，先质疑它是否真有必要。

## 相位圆盘（v19.2）不变量
- 合因子是**标量求和** `Σ√|Δᵢ|`，相位只通过 n（去重计数）与调谐效率参与，**不做矢量相加** → 相位图画辐条（spoke），不画箭头（arrow），否则暗示不存在的物理。
- **相位分布不影响收益**：n 路均匀分布 vs 挤成一坨，`u = eff×n/pref` 与每周期总跳变数完全相同。圆盘的价值是**诊断**（我搭了 4 路为什么 n 只有 3），不是优化目标——别让玩家以为该把相位摆均匀。
- 相位顺序一律走 `PhaseDomain.phasesSorted()`；`deltaByOffset()` 是 HashMap 值视图（哈希序）且**不含 offset**。
- 最佳域按 `period == detectedPeriod` 定位即可（period 在域集合内唯一，不需要 best 标记字段）。

## 电力层架构（v3 铜块网络）
- `tickContainerData` 按 (TopoKey, rep, channelIdx) 组件遍历：同氧化级连通块内多台发电机共享同一张边集，每组件仅锚点（最小铜块槽位 rep）跑一次 `runBfs`，其余 `ChannelState.copyFrom` 锚点通道，`accountEnergy` 仍逐机调用（各自 pref）。
- **关键不变量**：`ChannelState.onPhaseEvent(event, pref)` 中 pref 完全不被使用——域只按 `event.period()` 分桶，pref 仅在读时 `bestFactor/bestDomain(pref)` 选域。故同网络内多机共享同一 ChannelState 实例安全。
- `ChannelState.copyFrom` 为深拷贝（domains 逐 PhaseDomain 复制 offsets/lastEventTick），须保持深拷贝——改 ChannelState 字段时同步改 copyFrom。
- 跨 tick 持久化：`SimpleContainerContext` 把 powerData/redstoneData 存进 `ContainerLivingItemHandler` 静态缓存（按 containerKey）；`FakeContainerContext` 不持久化，驱动多 tick 逻辑须用 SimpleContainerContext。
- 测试 seam：`ContainerRedstoneData.setEdgeForTest/setPrevEdgeForTest` 供电力层单测注入边信号驱动 `tickContainerData`（绕过完整传播）。

## 相位圆盘渲染（LivingWaxedCopperTooltipRenderer）像素对齐规约（2026-09-10 已修复）

改这个渲染器（或任何自己走点的圆形图元）时，**三条规约必须同时成立**，否则整圈会「偏心」：

1. **点块以 (px, py) 为几何中心** —— `fill(px-dotRadius, py-dotRadius, px+dotRadius+1, py+dotRadius+1)`。
   写成 `-dotRadius+1` 会让 dotRadius=1 退化成 2×2、块心偏移 (0.5, 0.5)，顶部变 r−0.5、底部变 r+0.5。
2. **步数取偶数** —— `2*ceil(π*r*turns)`，让 i=0 / steps⁄4 / steps⁄2 / 3·steps⁄4 精确命中 12/3/6/9 点。
   `ceil(2πr)` 在 r=36 时是 227（奇数），6 点落在 i=113.5 永远采样不到 —— **这条对视觉的影响比第 1 条更大**。
3. **用 `symRound` 而非 `Math.round`** —— `Math.round` 对 .5 朝 +∞（round(3.5)=4 / round(-3.5)=-3），
   圆上镜像点（sin 反号）会各偏同侧。`symRound(v) = v>=0 ? round(v) : -round(-v)`。
   辐条端点同样要用。

**展开条柱位用整数槽宽**：`slotW=max(1, STEM_W/period)`、`barW=slotW-1`、
`originX = left + (STEM_W - min(slotW*period, STEM_W))/2` 居中。
浮点 `round(k*slotW)` 会在 .5 处累积 +1，柱间裂出 2px 双缝。

**φ=0 标记必须在 `renderWheel` 末尾画**：它压在周期环 (r=31) 上，而满相时
`SPOKE_RING_FULL_COLOR` 回填 r=29..30，先画的话底行会被亮青环吃掉。

**环的厚度只能取奇数（1/3/5…）**：`drawArc` 的点块以 (px, py) 为中心向两侧各扩 `dotRadius`，
要关于整数半径 r 对称就必须是奇数宽 —— **2 px 的对称环在整数像素网格上不存在**。
想让某条环变细，直接把 dotRadius 降一档（1→0）；守卫条件是 `dotRadius < 0` 所以 0 合法。
当前：外黄弧 dotRadius=1（3 px，主指标）、中心毂 dotRadius=0（1 px，次指标）。

**布局留白**（改尺寸常数时一并复核）：`LEVEL_BASE_X=167` 与 `STEM_X+STEM_W=167` 必须相等，
否则锈级条比展开条内缩（`fill` 右端排他，最右像素 = 基线 − 1）。
`PANEL_H=115`、`labelY = PANEL_H - 12`（字形高 8，占 103..110，下边框 114，留 3）。
当前四边留白 上2/下3/左3/右4。

**验证工具**：`tools/_sim_tooltip.py` —— 离线复现全部像素图元，`--old` 复现修复前行为、
`--oldpalette` 复现旧配色。改渲染前先跑它量化 before/after，比反复启动游戏截图快得多。
判对称要**对比四个基点方向的径向范围**，不要用象限像素计数（会把 x==cx 中心列误判到右侧）。
检查顺序别漏：**圆盘 → 展开条 → 锈级条 → 底部文字行**，后两块最容易漏。

## 性能判读：自埋 PerfMetrics 的覆盖盲区（2026-09-10 血的教训）
- PerfMetrics 只插桩 `processContext` 内部。**外部 mod 在自己 ServerTickEvent 里直接调我们能力接口**
  的路径（Flux Networks → `ContainerEnergyStorage.receiveEnergy`）**完全不在计时区间内**。
- 因此「PerfMetrics 说 living_item 只占 3%」与「spark 说某方法占 98.79%」**不矛盾**，是覆盖盲区。
  曾据此误判"卡顿与本模组无关"。**交叉验证必须看 spark 节点的绝对毫秒数，不能只看百分比。**
- 用户的场景描述（"只有传输电力才卡"）比任何采样百分比都值钱 —— **先问场景，再读火焰图**。

## 容器对外能量接口（ContainerEnergyStorage）热路径规约
- `receive()` 在外部电力 mod 热路径上：**只扫一遍 `getStackInSlot`**，
  比例分配与零头回收两遍走 `stacks[]` 数组（非铜灯槽位留 null）。别改回"每遍重新取物品"。
- `isBulb()` 短路顺序固定 `!isEmpty → isWaxedBulb(Item) → isLivingItem(stack)`：
  Item 引用比较是纳秒级且无内存访问；DataComponent 查询是 `Reference2ObjectArrayMap` 线性扫描 + cache miss。
  顺序反了会对每个非灯槽位白跑一次组件查询。
- 本方法**保持无状态**（只比原实现多分配 `stacks[]` 一个数组）。考虑过 ThreadLocal 暂存池消除分配，
  因引入跨调用状态 + 重入风险而否决 —— 与配方书 Mixin 同一条教训。
- 语义红线（改性能时不许破坏，RoundTripConservationIT 守着）：整 FE 量化 `accept -= accept % 1000`、
  完整步进保护（`count > leftover` 跳过）、「宁损勿造」（声明 `accept`、实充 ≤ 记账）。
- **long 溢出红线（2026-09-11 真凶，别再踩）**：mFE 定点制（1 FE = 1000 mFE）把量级抬了 1000 倍，
  每盏容量 `BULB_UNIT_CAPACITY_MFE = 1e9`。`receive()` 里 `accept * remaining[i]` 可到 1e23 ≫ Long.MAX。
  **凡是 `a * b / c` 的份额/比例计算，都要重算溢出边界**，先转 double 算比例再夹 `remaining[i]`。
  溢出后 `distributed≈0` → `leftover=accept` → 零头回收 `while` 退化成 ~10 亿轮（服务端冻结）。
  **27 槽时铜灯总数超过约 16 盏就会触发**。
- **外部 mod 会用 `Integer.MAX_VALUE` 调用**：Flux Networks「绕过限制」模式下
  `getLimit()` 返回 `Long.MAX_VALUE`，`onCycleStart` 每 tick 按满额跑一遍**模拟**调用。
  所以 `receiveEnergy` 必须能安全吃下 `Integer.MAX_VALUE`，不能假设请求量很小。
- `MAX_LEFTOVER_PASSES = 256` 是零头回收循环的防御性上限：合法 leftover ≤ Σ(count−1)，1~2 轮就完；
  超限说明份额算错，宁可少充也别卡死（少充仍满足实充 ≤ 记账）。**别把这个上限删掉。**
- `extract()` 无同类溢出：`Math.min(q * count, remaining)` 中 `remaining` 被 `wantMilliFe ≤ 2.1e12` 封顶。
- **同款第二处已修：`LivingWaxedCopperFunction.distributeToBulbs`**（发电直存）。
  那里 `mfe` 是全部发电机按锈级累加，`× remaining` 同样会溢出。后果**不会卡死但更危险**：
  share 为负 → 发电静默丢弃；share 变巨大正数 → `newQ` 被拉到 CAP → **凭空造电**。
- **新增/修改 mFE 算术时的自检**：该包里凡出现 `a * b / c`（份额、比例、分配），
  先估算极值再决定要不要转 double。用
  `grep -n "[a-zA-Z0-9_)] \* [a-zA-Z0-9_(].* / [a-zA-Z0-9_(]" src/.../domain/power/*.java` 扫。
- 常量现状：`BULB_UNIT_CAPACITY_FE = 1_000_000`、`BULB_UNIT_CAPACITY_MFE = 1e9`（每盏）。
  **注意 `WaxedCopperStorageTest` 里「容量 16 × 100_000」那句注释是旧值，已过时。**
- ~~已知遗留失败：`WaxedGeneratorFeedChainTest.chiseled_configMatchesSamplingSide`~~
  **2026-09-15 复核已消失**：该用例（雕文配置一致性：配置输入=右后，右侧注入派生、左侧注入忽略）
  在 `--rerun` 全量跑中 PASSED，`WaxedGeneratorFeedChainTest` 12/12 绿，全量 **288 passed / 0 failed / 0 skipped**。
  别再引用「已知遗留失败」这个说法（旧的 09-15 日志条目里还留着，以本节为准）。
- **全量基线（2026-09-15 实测）**：288 用例。核数**只认 `build/test-results/test/*.xml` 的
  `tests=` 求和**，不要用 `grep -c "@Test"` 数方法——3 个带 `@ParameterizedTest` 的类
  （ContainerRedstoneDataTest 24→29、MapCoordHelperTest 16→29、ContainerCompatibilityConfigTest 9→14）
  方法数 ≠ 用例数，按方法数加总会少 23。
- `./gradlew test` 报 `FROM-CACHE` / `UP-TO-DATE` 时**没有真跑**，加 `--rerun` 才算复核。

## 跨容器传输：方向对称性必须逐条点名验证（2026-09-15 血的教训）

> 📌 本节已固化为用户级技能 `mc-livingitem-transfer-guardrails`
> （`~/.workbuddy-ai/skills/`）——改 `domain/hopper/**` 或 `transfer/**`、
> 新增 `SlotInteraction`、或遇到「某方向生效另一方向不生效」时先加载它。

- `CrossContainerTransfer` 的 **`pushToNeighbor` 与 `pullFromNeighbor` 是两份独立实现**，
  只有 `pushToNeighbor` + `transferBetweenNeighbors` 共用 `tryPushToNeighbor` 循环。
  「一处内嵌两路径共用」**不覆盖拉取方向**。
- **`SlotAccessorFactory.create` 开头就把非箱类活物品挡掉（return null）**，而
  `createForNeighbor` 不查活物品。所以：**推送方向**邻居槽走 `createForNeighbor`，
  内嵌的特殊分支（如施肥）有执行机会；**拉取方向**目标槽走 `create` → null →
  通用拉取对任何活物品目标槽**必然失败**，特殊能力必须显式前置分支。
- 新增任何「活物品目标槽」类能力（施肥/浇水/喂养…）时，**三个方向都要点名**：
  容器内（`TransferPipeline.executeInContainer`）、跨容器推送（`tryPushToNeighbor`）、
  跨容器拉取（`pullFromNeighbor`）。
- **但「货物 × 目标槽」型交互已有注册式扩展点，不要再三处硬编码**（2026-09-15 收编）：
  实现 `transfer/SlotInteraction`（`matches` 纯谓词 + `interact` 只改 target +
  `consumeAmount` 默认 1；协议 = 模拟优先 + equals 零空转 + 不生效不扣货）
  → `SlotInteractions.register(...)`（内置条目放 `SlotInteractions` 静态块）。
  三处调用点已统一调分发器 `tryInteract`（货物已知）/ `tryInteractFromNeighbor`
  （拉取方向），**新增交互零传输代码改动**。自检：
  `grep -c "SlotInteractions.tryInteract" domain/hopper/*.java` 应为 3。
  只有「邻居侧特殊**存储**」（把物品推进邻居容器里的活箱子）这类才仍需三处点名。
- **货物准入（隔离规则）唯一定义点 = `SlotInteractions.isEligibleCargo`**
  （`!isLivingItem || 活箱子 || 活末影箱`）。`TransferPipeline.isTransferableSource` 直接
  委托它，交互层两个入口共用——**规则收在共享入口，任何调用点顺序变更都绕不过**
  （曾因容器内交互分发排在隔离检查之前、靠「谓词没写检查」意外放行，一次重排就变 bug）。
  `tryPushToNeighbor` 另留循环自守 `interactionOnly = !isEligibleCargo(cargo)`。
- **活骨粉不给漏斗施肥**（用户 2026-09-15 定案，别当 bug 改掉）：施肥的语义是「漏斗用
  **传输能力**把骨粉送进活耕地」→ 属**传输语义** → 必须受漏斗自身的货物规则约束
  （活物品不作货物）。口径 = **手动要活化（GUI 右键要活骨粉）、自动要普通（漏斗只认普通骨粉）**。
  曾以「交互是消耗不是搬运」为由把活骨粉放行到四个方向 + 加了
  `SlotAccessorFactory.createForInteraction`（不拦活物品），**方向错了，已全部回退并移除**。
  守卫：`SlotInteractionCargoGateTest`（真值表 + 活骨粉不施肥不扣货）。
- **判归属**：一件事属于「传输语义」还是「交互语义」决定它受哪套规则约束。
  拿不准时**问用户**——这是设计决策，不是实现细节。
- 判定「某能力是否覆盖」不能靠代码结构推断，要么 grep 到三处分支，要么写方向化的单测
  （辅助方法留包级可见即可直接驱动，见 `CrossContainerTransferFertilizeTest`）。

