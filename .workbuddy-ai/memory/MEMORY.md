# 项目长期记忆（Living Item Mod）

> 本文件只放**没有别的归宿**的不变量与红线；细节在 `docs/` 与用户级技能里，已就地标注指针。

## 文档系统的定位（用户 2026-09-16 明确）

- **人 ↔ AI ↔ 代码 三者靠文档联系**：文档 = DNA（存信息），代码 = 蛋白质（实现功能），AI = RNA（转录）。
  **目标**：新对话只需把 `AGENTS.md` 加进上下文 + 说明需求，AI 就能通过这个入口理解整个项目。
  **极端目标**：代码丢了，靠文档系统能还原。
- ⇒ 因此：**改代码必须同步文档**（项目铁律「文档即规范」的由来）；**新能力必须有文档入口**；
  入口文档（AGENTS.md）的体量直接决定这个目标成不成立。
- ⚠️ 已诊断的结构性问题（2026-09-16）：AGENTS.md 52,961 字符中
  **「开发进展」47% + 「核心文件索引」32% = 79% 是 append-only 日志与文件清单**
  —— 前者会无限膨胀、后者可从代码反推。**入口层必须瘦身 + 分层**。
- **判断一篇内容该不该进文档**：**只写「代码里推不出来的」**（为什么这样决策、踩过什么坑、
  口径/语义约定、不变量）。能从代码反推的（文件清单/签名/调用链）写进文档是**负债**——会漂移且挤占预算。
- **文档要写成约束（不可违反的断言），不要写成描述（可自由解读的叙述）**——AI 会重新诠释描述，
  但绕不过约束。

## 纹理 / 环境
- 涂蜡铜灯图标 = 未涂蜡图标 + 外圈黄框：**60 px、(232,160,62,255)**，四锈蚀级（copper/exposed/weathered/oxidized）边框掩码**完全一致**；lit 版同理（内部取未涂蜡发光图）。item 纹理 16×16 PNG（P 调色板与 RGBA 均有效）。
- 图片处理用隔离 venv：`C:/Users/AI-777hi/.workbuddy-ai/binaries/python/envs/default/Scripts/python.exe`（Pillow 12.3.0）。

## 配方书材料表（stackedContents）Mixin
- `RecipeBookComponent` 重建 `stackedContents` 只有两条路径：`initVisuals()`（开界面/切可见性）、`updateStackedContents()`（背包变动/点槽位）。**两条都要注入**；漏 `initVisuals` ⇒「刚开界面活箱子材料不识别，动一下才恢复」。其余 `updateCollections` 调用点（切标签/搜索/筛选/recipesUpdated）只复用不重建。
- **内容变化后的刷新靠原版链路，别加轮询/指纹/缓存**（2026-09-05 加过又被移除）：服务端改 CONTAINER → `triggerSlotListeners` 用 `ItemStack.matches` 判定 → 发包 → 客户端 `Inventory.setItem` → `timesChanged++` → 客户端 tick，1–3 tick。前提：`ItemStackMixin` 只改 `isSameItemSameComponents`/`getTooltipImage`，**没动 `matches`**。
- **无状态是这个 Mixin 正确的原因**——要引状态字段的优化，先质疑是否必要。

## 相位圆盘（v19.2）不变量
- 合因子 = **标量和** `Σ√|Δᵢ|`；相位只经 n（去重计数）与调谐效率参与，**不做矢量相加** ⇒ 画**辐条**不画箭头，否则暗示不存在的物理。
- **相位分布不影响收益**（均匀 vs 挤一坨，`u = eff×n/pref` 与每周期总跳变数完全相同）。圆盘价值是**诊断**（「我搭了 4 路为什么 n 只有 3」），不是优化目标。
- 相位顺序一律 `PhaseDomain.phasesSorted()`；`deltaByOffset()` 是 HashMap 值视图（哈希序）且**不含 offset**。最佳域按 `period == detectedPeriod` 定位即可（period 在域集合内唯一，无需 best 标记字段）。

## 电力层（v3 铜块网络）
- `tickContainerData` 按 `(TopoKey, rep, channelIdx)` 组件遍历：同氧化级连通块多机共享同一张边集，**每组件仅锚点（最小铜块槽位 rep）跑一次 `runBfs`**，其余 `ChannelState.copyFrom` 锚点通道；`accountEnergy` 仍逐机调用（各自 pref）。
- **不变量**：`ChannelState.onPhaseEvent(event, pref)` 中 **pref 完全不被使用**（域只按 `event.period()` 分桶，pref 仅在读时 `bestFactor/bestDomain(pref)` 选域）⇒ 同网络多机共享同一 ChannelState 实例安全。
- `ChannelState.copyFrom` 是**深拷贝**（domains 逐 PhaseDomain 复制 offsets/lastEventTick），**必须保持深拷贝**——改 ChannelState 字段时同步改 copyFrom。
- 跨 tick 持久化：`SimpleContainerContext` 把 powerData/redstoneData 存进 `ContainerLivingItemHandler` 静态缓存（按 containerKey）；`FakeContainerContext` **不持久化** ⇒ 驱动多 tick 逻辑必须用 SimpleContainerContext。
- 测试 seam：`ContainerRedstoneData.setEdgeForTest/setPrevEdgeForTest` 注入边信号驱动 `tickContainerData`（绕过完整传播）。

## 相位圆盘渲染（LivingWaxedCopperTooltipRenderer）像素对齐（2026-09-10 已修）
> 本节在 `docs/` 里**没有归宿**，是唯一记录处，别删。改这个渲染器（或任何自己走点的圆形图元）时**三条规约必须同时成立**，否则整圈「偏心」：
1. **点块以 (px,py) 为几何中心**：`fill(px-r, py-r, px+r+1, py+r+1)`。写成 `-r+1` 会让 r=1 退化成 2×2、块心偏 (0.5,0.5)。
2. **步数取偶数**：`2*ceil(π*r*turns)`，让 i=0 / steps/4 / steps/2 / 3·steps/4 精确命中 12/3/6/9 点。`ceil(2πr)` 在 r=36 是 227（奇数）⇒ 6 点落 i=113.5 永远采样不到——**这条影响比第 1 条更大**。
3. **用 `symRound` 不用 `Math.round`**：后者对 .5 朝 +∞（round(3.5)=4 / round(-3.5)=-3），圆上镜像点（sin 反号）会各偏同侧。`symRound(v) = v>=0 ? round(v) : -round(-v)`；辐条端点同样用。

- **展开条柱位用整数槽宽**：`slotW=max(1, STEM_W/period)`、`barW=slotW-1`、`originX = left + (STEM_W - min(slotW*period, STEM_W))/2` 居中。浮点 `round(k*slotW)` 会在 .5 累积 +1，柱间裂出 2px 双缝。
- **φ=0 标记必须在 `renderWheel` 末尾画**：它压在周期环 (r=31)，满相时 `SPOKE_RING_FULL_COLOR` 回填 r=29..30，先画会被亮青环吃掉底行。
- **环厚只能取奇数（1/3/5…）**：`drawArc` 点块以 (px,py) 为中心向两侧各扩 `dotRadius`，要关于整数半径对称就必须奇数宽——**2 px 对称环在整数像素网格上不存在**。想变细就把 dotRadius 降一档（1→0），守卫是 `dotRadius < 0` 所以 0 合法。当前：外黄弧 dotRadius=1（3px，主指标）、中心毂 dotRadius=0（1px，次指标）。
- **布局留白**（改尺寸常数时一并复核）：`LEVEL_BASE_X=167` 必须等于 `STEM_X+STEM_W=167`（否则锈级条内缩——`fill` 右端排他，最右像素 = 基线−1）；`PANEL_H=115`、`labelY=PANEL_H-12`（字形高 8 占 103..110，下边框 114，留 3）。当前四边留白 上2/下3/左3/右4。
- **验证工具** `tools/_sim_tooltip.py`：离线复现全部像素图元，`--old` 复现修复前行为、`--oldpalette` 复现旧配色。改渲染前先跑它量化 before/after，比反复启动游戏截图快得多。判对称要**对比四个基点方向的径向范围**，别用象限像素计数（会把 x==cx 中心列误判到右侧）。检查顺序别漏：**圆盘 → 展开条 → 锈级条 → 底部文字行**（后两块最易漏）。

## 性能判读：PerfMetrics 的覆盖盲区（2026-09-10 血的教训）
- PerfMetrics 只插桩 `processContext` 内部。**外部 mod 在自己 ServerTickEvent 里直调我们能力接口**（Flux Networks → `ContainerEnergyStorage.receiveEnergy`）**完全不在计时区间内**。
- 故「PerfMetrics 说 living_item 只占 3%」与「spark 说某方法占 98.79%」**不矛盾**，是覆盖盲区——曾据此误判「卡顿与本模组无关」。**交叉验证必须看 spark 节点的绝对毫秒数，不能只看百分比。**
- 用户的场景描述（「只有传输电力才卡」）比任何采样百分比都值钱——**先问场景，再读火焰图**。

## 容器对外能量接口（ContainerEnergyStorage）热路径
> 详细不变量见 `docs/system-design/power-invariants.md`；以下是必须记住的红线。
- `receive()`：**只扫一遍 `getStackInSlot`**，比例分配与零头回收走 `stacks[]` 数组（非铜灯槽留 null）。别改回「每遍重新取物品」。`isBulb()` 短路顺序固定 `!isEmpty → isWaxedBulb(Item) → isLivingItem(stack)`（Item 引用比较纳秒级；DataComponent 查询是 `Reference2ObjectArrayMap` 线性扫描 + cache miss）——顺序反了每个非灯槽白跑一次组件查询。**保持无状态**（ThreadLocal 暂存池因跨调用状态 + 重入风险否决）。
- **语义红线**（`RoundTripConservationIT` 守着）：整 FE 量化 `accept -= accept % 1000`；完整步进保护（`count > leftover` 跳过）；「宁损勿造」（声明 `accept`、实充 ≤ 记账）。
- **long 溢出红线（2026-09-11 真凶）**：mFE 定点制（1 FE = 1000 mFE）+ 每盏 `BULB_UNIT_CAPACITY_MFE = 1e9` ⇒ `accept * remaining[i]` 可到 1e23 ≫ Long.MAX。**凡 `a * b / c` 都要先转 double 算比例再夹 `remaining[i]`**。溢出后零头回收 `while` 退化成 ~10 亿轮（服务端冻结）；**27 槽时铜灯超过约 16 盏就会触发**。自检：`grep -n "[a-zA-Z0-9_)] \* [a-zA-Z0-9_(].* / [a-zA-Z0-9_(]" src/.../domain/power/*.java`。
- **同款第二处已修：`LivingWaxedCopperFunction.distributeToBulbs`**（发电直存）。后果**不卡死但更危险**：share 为负 → 发电静默丢弃；share 变巨大正数 → `newQ` 被拉到 CAP → **凭空造电**。
- 外部 mod 会用 `Integer.MAX_VALUE` 调用（Flux Networks「绕过限制」模式 `getLimit()` 返 `Long.MAX_VALUE`，每 tick 跑一遍**模拟**调用）⇒ `receiveEnergy` 必须安全吃下。`extract()` 无同类溢出（`remaining` 被 `wantMilliFe ≤ 2.1e12` 封顶）。
- `MAX_LEFTOVER_PASSES = 256` 是零头回收的防御上限（合法 leftover ≤ Σ(count−1)，1~2 轮就完；超限说明份额算错，**宁可少充也别卡死**）——**别删**。
- 常量：`BULB_UNIT_CAPACITY_FE = 1_000_000`、`BULB_UNIT_CAPACITY_MFE = 1e9`（每盏）。**`WaxedCopperStorageTest` 里「容量 16 × 100_000」那句注释是旧值，已过时。**
- **全量基线（2026-09-16 实测）297 用例**。核数**只认 `build/test-results/test/*.xml` 的 `tests=` 求和**，别用 `grep -c "@Test"`——3 个 `@ParameterizedTest` 类（ContainerRedstoneDataTest 24→29、MapCoordHelperTest 16→29、ContainerCompatibilityConfigTest 9→14）方法数 ≠ 用例数，按方法数加总会少 23。
- `./gradlew test` 报 `FROM-CACHE`/`UP-TO-DATE` 时**没真跑**，加 `--rerun` 才算复核。`WaxedGeneratorFeedChainTest.chiseled_configMatchesSamplingSide` 2026-09-15 复核已 PASSED（12/12 绿）——**别再引用「已知遗留失败」**。

## 物品 ↔ 世界：独立于传输层的第三条链路（2026-09-16）
> 📌 细节已固化为用户级技能 `mc-livingitem-mixin-guardrails`（改 Mixin / 加「物品作用于世界」能力时先加载）。
- `SlotAccessor`/`SlotInteractions` 只管**容器内**；**物品 ↔ 世界**（放置/交互世界方块）完全独立，入口是 Mixin，别往传输层挂。
- **优先调原版入口方法，别复刻它的结果**：模拟玩家右键就调 `ItemStack.useOn`，**别自己 `setBlock`**——后者静默绕过模组在 `canSurvive`/覆写 `useOn` 里的校验（如「水稻只能在水下种」）。
- **`BlockItem.place` 里读物品栈必须锚在 `consume` 之前**（`@At("RETURN")` 时单块放置已 count=0、`getComponents()` 返 `EMPTY` ⇒ 静默失效，堆叠放置却正常）。
- **`Level.isClientSide` 是 `public final` 字段**（方法只是读它）⇒ 单测里写字段式会让客户端守卫**永远不触发**，统一用方法式。
- **单测 JVM 里 Mixin 是被应用的**（FML 环境）⇒ 可写端到端用例证明接线；`defaultRequire: 1` 是有意的 fail-fast；**注入在别人方法内部 ⇒ 必须 try/catch**（否则「方块已放、物品未扣」+ 炸 tick）。

## 槽位叠加层：优先走 IItemDecorator（2026-09-16）
> 📌 坐标/z/渲染状态三契约详见 `docs/system-design/icon-system.md`「种子图标改走装饰器路径」。
- **能进 `IItemDecorator` 就别写 `AbstractContainerScreenMixin`**：后者依赖 `leftPos`/`topPos`，而 **HUD 快捷栏走 `Gui.renderHotbar` → `renderItemDecorations`，从不经过 `AbstractContainerScreen`** ⇒ 快捷栏永远不渲染。装饰器则快捷栏/容器 GUI/创造物品栏/副手槽共用一份代码。
- 三契约速记：① 坐标 =「当前 pose 内 `translate(xOffset, yOffset, z)`」（容器传 `slot.x`，**未加** `leftPos`）⇒ **别自己再加**；② 容器内 z 有 `+100` 基准 ⇒ z=200 → 净 300；③ `ItemDecoratorHandler` 会 `resetRenderState()`（开深度 + 开 blend）⇒ **别写 `RenderSystem`**，TAIL 版的 `disableDepthTest()` 不能照搬。
- **拿不到容器槽表的能力留在 Mixin**（如「上方一格生长槽大图」）——装饰器只有 x/y。

## 跨容器传输：方向对称性必须逐条点名验证（2026-09-15 血的教训）
> 📌 细节已固化为用户级技能 `mc-livingitem-transfer-guardrails`（改 `domain/hopper/**`/`transfer/**`、新增 `SlotInteraction`、或遇「某方向生效另一方向不生效」时先加载）。
- `pushToNeighbor` 与 `pullFromNeighbor` 是**两份独立实现**，只有前者 + `transferBetweenNeighbors` 共用 `tryPushToNeighbor`。「一处内嵌两路径共用」**不覆盖拉取方向**。
- **`SlotAccessorFactory.create` 把非箱类活物品挡掉（null）**，而 `createForNeighbor` 不查 ⇒ **拉取方向**目标槽走 `create` → null，通用拉取对任何活物品目标槽**必然失败**，特殊能力必须显式前置分支。新增「活物品目标槽」能力**三个方向都要点名**（容器内 / 推送 / 拉取）。
- **「货物 × 目标槽」型交互已有注册式扩展点，别再三处硬编码**：实现 `transfer/SlotInteraction`（`matches` 纯谓词 + `interact` 只改 target + `consumeAmount` 默认 1；协议 = 模拟优先 + equals 零空转 + 不生效不扣货）→ `SlotInteractions.register(...)`。三处调用点已统一调 `tryInteract`/`tryInteractFromNeighbor`，**新增交互零传输代码改动**；自检 `grep -c "SlotInteractions.tryInteract" domain/hopper/*.java` 应为 3。
- **货物准入唯一定义点 = `SlotInteractions.isEligibleCargo`**（`!isLivingItem || 活箱子 || 活末影箱`）；`TransferPipeline.isTransferableSource` 直接委托 ⇒ **规则收在共享入口，调用点顺序变更绕不过**（曾因交互分发排在隔离检查之前而意外放行）。`tryPushToNeighbor` 另留循环自守 `interactionOnly = !isEligibleCargo(cargo)`。
- **活骨粉不给漏斗施肥**（2026-09-15 用户定案，别当 bug 改掉）：施肥属**传输语义** ⇒ 受漏斗自身货物规则约束（活物品不作货物）。口径 = **手动要活化（GUI 右键要活骨粉）、自动要普通（漏斗只认普通骨粉）**。曾把活骨粉放行到四个方向 + 加 `SlotAccessorFactory.createForInteraction`，**方向错了，已全部回退并移除**。守卫 `SlotInteractionCargoGateTest`（真值表 + 活骨粉不施肥不扣货）。
- **判归属**：属「传输语义」还是「交互语义」决定受哪套规则约束；拿不准**问用户**——这是设计决策，不是实现细节。
