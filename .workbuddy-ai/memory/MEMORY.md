# 项目长期记忆（Living Item Mod）

> 本文件是**工具侧**记忆（只对当前 AI 工具生效）。用户用**多个 AI 工具**开发 ⇒
> **项目级知识必须写在仓库 `docs/` 里**，本文件只保留「红线速记 + 指针」，避免超限被注入截断。
> 📌 文档系统规约见 [`docs/README.md`](../../docs/README.md)；改规约改那份文档。

## 🔴 环境红线：G 盘有坏块 ⇒ 「文件/目录莫名消失」的真因（2026-09-22 查明）

- ⚠️ **症状**：工作区文件甚至**整个目录**（`docs/`、`.git`）无故消失，且**找不到任何软件在删**。
- **真因（已确认的事实，非推测）**：**G 盘所在物理盘有坏块** ——
  - 系统日志 `disk` 事件 **ID 7**：`The device, \Device\Harddisk1\DR1, has a bad block.`
    （2026-09-22 22:35:07 连续 10+ 条）
  - 卷健康：**G 卷 `HealthStatus = Warning`**（E / F / C 均 Healthy）
  - 盘符映射：**Disk 1（致钛 TiPlus5000 1TB）= E: / F: / G:**
    ⇒ ⚠️ **E/F/G 是同一块物理盘，备份到 E/F 完全无效**；只有 **C 盘（Disk 0，CT1000P3PSSD8）**
    是另一块物理盘。
- **诊断方法**（三条命令，输出写文件再读即可；本环境 PowerShell 工具的 stdout 不返回）：
  1. 系统日志筛 `disk` / `Ntfs` / `volmgr` 提供者的事件（Level 1~3，近 60 条）⇒ 看 ID 7 坏块
  2. 列出所有卷的 `HealthStatus` 与剩余空间 ⇒ 看哪个卷 Warning
  3. 列出分区→物理盘映射（`DiskNumber` + `DriveLetter`）⇒ 确认哪些盘符同盘
- ⚠️ **已排除**（别再往这些方向查）：
  - Defender：威胁历史里只有无关项（PCL2.exe / Sunshine.exe / 游戏 exe）
  - 受控文件夹访问：关闭（=0）
  - OneDrive：`FileSyncFSCache.db` 里本项目 **0 匹配**、项目文件无重解析点
  - 清理类软件：进程里无 360 / CCleaner / 火绒等
  - 并行 AI 工具：memory 无记录、reflog 只有本会话提交
- ✅ **已做的防护**：
  - `git config gc.auto 0`（防对象被自动 prune）
  - `.workbuddy-ai/backup/` 与 `tmp/` 已进 `.gitignore`
  - **跨盘备份到 C 盘**：`C:\Users\AI-777hi\livingitem-backup-<时间>\`
    （`livingitem-all.bundle` = 完整历史 231 提交；`worktree-core.tar.gz` = 工作区核心）
- **应对原则**：
  1. **发现文件消失先 `git status`**：若是「整目录 deleted」⇒ 大概率坏块，
     **别急着 `git add -A`**（会把删除一起提交）；先 `git checkout HEAD -- <路径>` 恢复。
  2. **改完立即提交 + 定期 push**（远程 GitHub 是唯一脱离这块盘的副本）。
  3. **备份必须跨物理盘**（E/F 与 G 同盘，不算备份）。
  4. **不要**在没有备份时跑 `chkdsk /f` / `git gc` / `git prune`。

## 协作：多 AI 工具并行开发 ⇒ 提交别用 `git add -A`（2026-09-22）

- 用户同时用**多个 AI 工具**改同一仓库 ⇒ `git add -A` 会**夹带别的会话未提交的改动**。
  实例：2026-09-22 提交 `064256f`（图标光照）夹带了并行会话的活漏斗 `CrossContainerTransfer`
  方向选择 + 新测试 + 3 个文档（15 文件里 6 个不是本次改动）。
- 后果：提交信息与实际内容不符，两个不相关变更混在一个提交里（**代码没丢**，但历史难读）。
- ⇒ **提交只 add 自己改的路径**（`git add <路径1> <路径2>`）；若已夹带，在提交信息里点名说明。
- 同理：**动手前先 `git status`** —— 工作区有别人未提交的改动时，不要以为那是自己的。

## 文档系统（定位与红线，2026-09-16）

- ⭐ **文档的主要读者是 AI**（用户原话）—— 写**约束**不写叙述、**只写代码里推不出来的**、
  断言必须**可复算**、**索引与正文分离**、**翻转必须留痕**。
- **人 ↔ AI ↔ 代码 靠文档联系**；目标「新对话只加载 `AGENTS.md` 就能理解项目」。
- **改代码必须同步文档**：技术文档小节 + AGENTS.md 进展条目 + 子系统索引概述（三者齐）。
- 入口：`glossary.md`（术语）· `decisions.md`（决策 + supersedes 留痕）·
  `tools/doc_check.py`（**改完必跑**，6 项）。
- ⚠️ 搬文档**搬走不删掉**，**守恒校验必须逐段判定**（只验第一块曾误删整段）。
- ⚠️ 入口超 20,000 字符时**先查冗余再动历史**；**不要靠上调预算解决**（两次超标都是只清冗余、历史没动）。
- ⭐ **「开发进展」= 一行式滚动**（2026-09-22 改，`D-doc-04` 取代 `D-doc-02`）：
  AGENTS 只放**最近 10 条**「一行结论 + 指针」，**完整正文直接写 `changelog.md`**；
  超 10 条就删最底部一行（正文早已在 changelog）⇒ **没有归档环节、无守恒校验**。
  ⚠️ **别再提议恢复「批次制 / 保留 3 批次 / 连续迁出 / 边界单调」**（已作废：实测条目均 705 字符
  占入口 55%，且归档是高风险手工操作、漏做即永久丢历史）。
  **断档教训仍有效**：历史必须落仓库（AGENTS/changelog）—— 只写 `.workbuddy-ai/memory/` 日志会丢。
  `doc_check.py` 第 4 项：① 条数 ≤10 ② 最新条目日期 == changelog 顶部 ③ 每行指针含 `.md`。

## 单测：静态注册表必须显式重置（2026-09-20）

- ⚠️ Gradle 在**同一 JVM** 跑完所有测试类 ⇒ **静态注册表/静态缓存测试间必须显式 `reset`**。
  症状隐蔽：日志「加载 0 条」看着像资源读不到，实为 `findRule(id).isPresent() → continue`（已存在被跳过）。
- **诊断纪律：先写探针验证假设**。当时误判为「classpath 读不到资源」，探针实测
  `getResourceAsStream` 三种全可读 ⇒ 立即排除，否则会去改正确的 `loadFromResource`。
  **探针用完即删，不留仓库**。
- 落点：`ContainerRuleConfig.load()` 内部先调 `resetState()`（清 `RULES` + 三个静态集）保证幂等。

## 区块加载事件红线（2026-09-18 · D-core-04 / D-core-05）

- ⚠️ **`ChunkEvent.Load` 回调里禁止任何世界交互**（`getCapability`/`getBlockEntity`/读方块状态）。
  它在区块 FULL 任务**内部**触发；内部查询会跑第三方能力提供者（Create 传送带 → 查别的区块的 BE）
  → `getChunk(requireChunk=true)` → `managedBlock`+`join` ⇒ 主线程自等自，**服务端冻结**。
- 口诀：**同区块不卡、指向别的区块必卡**（原版 `currentlyLoading` 旁路只覆盖自身）。
  `level.isLoaded()` 不充分（`hasChunk` 只查 ticket level）。
- 修法：事件只登记坐标，扫描推迟到 `ServerTickEvent.Pre`；用 `getChunkNow` 不用 `getChunk`；每 tick 限量。
- ⚠️ **发现 ≠ 处理（D-core-05）**：**发现**覆盖**所有已加载区块**；**处理**只针对 **ticking 区**
  （`isPositionTicking`，ticket ≤32）。危险带 = 已加载但不 tick 的**最外一圈（33 圈）**，
  邻居全在未加载区，读邻居触发强制加载（一次凑 289 区块足迹 + 票据续期永久钉住）。
  **扫描侧不能过滤**（区块提升到 ticking 无事件 ⇒ 永久漏发现）。
- 入口：`ContainerChunkCache.getProcessableChunks(ServerLevel)`；观测 `/living_monitor cache`。
- 全文 §3.2 + 守卫测试 `ContainerChunkCacheChunkLoadTest`；工具侧方法论技能 `mc-event-callback-safety`
  （**项目事实以仓库文档为准**）。

## 爆炸：逐区块分帧 + 待炸账本（2026-09-18 · D-tnt-01）

- **破坏唯一入口 `ExplosionComponent.applyToChunk(level, params, chunk)`** —— 三种模式
  （NORMAL/HIGH_YIELD/SUPER）都按区块执行 ⇒ 同一场爆炸无论区块何时加载，结果一致。
- `ExplosionLedger`（世界级 `SavedData`）：参数 + `long[]` 完成位图；每 tick ≤ `MAX_CHUNKS_PER_TICK = 32`。
- ⚠️ **未加载区块 → 丢弃**（等 `ChunkEvent.Load` 重新登记，**不能轮询**）；
  **预算用尽 → carryOver**（已加载的不会再触发 Load，丢了**永远不炸**）。写反即丢爆炸。
- ⚠️ **队列只 `remove` 本 tick 处理过的区块，永不整体替换** ⇒ 否则同 tick 新登记的区块被覆盖，
  而它们已加载、不会再触发 Load ⇒ 永远不炸。
- ⚠️ `schedule` 返回 false（账本满）时调用方**必须降级**（只炸已加载 + WARN），不能忽略
  —— 否则声光已播、方块没坏。
- 多条目重叠：同一区块对每条各处理一次，**破坏幂等**；掉落物归属取决于处理顺序（不可观测）。
- ⚠️ **`ExplosionParams.affects()` = 「区块 AABB ∩ 球体」**（取最近点算距离），
  **不是**「区块中心在半径内」（2026-09-22 修）。区块是 16×16，中心判据会把
  "中心在外、边缘在球内"的区块**建档时就标记完成、永不处理** ⇒ **坑不圆、残留整块地形**。
  该判据在**三处**生效（建档标记 / `flush` 跳过 / `remainingChunks`）⇒ 单点根因。
  ⚠️ 它曾被测试**钉住**（用例名"与旧口径一致"）—— **把既有行为当规格钉住前，先确认它是对的**。
- 全文 `living-tnt-tech.md` §4.3 + `living-item-infrastructure.md` §3.2.2；
  给测试者的操作说明 `docs/guides/living-tnt-testing.md`。

## 批量改 Section ⇒ 必须自己补光照（2026-09-22）

- ⚠️ **`LevelChunkSection.setBlockState()` 绕过 `LevelChunk.setBlockState()`** ⇒ 光照引擎
  **完全不知道方块变了**（原版把 `updateSectionStatus` / `checkBlock` 写在后者的 258~270 行）。
  症状：大当量/超级爆炸后**坑里一片漆黑**（天光柱高图仍认为地下被堵死）。
  `NORMAL` 走 `level.setBlock()`，**不受影响** —— 别把它也"修"一遍。
- 修法 `ExplosionComponent.refreshLightAfterBulkEdit(level, chunk, removedLightSources)`：
  ① `chunk.initializeLightSources()` → ② 逐 section `updateSectionStatus(SectionPos.of(cp, minSection+i), hasOnlyAir)`
  → ③ `propagateLightSources(cp)` → ④ 被炸掉的发光方块逐个 `checkBlock`。
- **两条红线**：**①必须早于③**（③ 按柱高图算天光，反了 = 没修）；
  **③只管增光、④才管减光**（`propagateLightSources` 只重新登记**现存**光源）。
- ⚠️ ②③④ 是**入队**非同步 ⇒ 下一 tick 由原版 `ClientboundLightUpdatePacket`
  覆盖成正确值（**别**为此改成同步或自己造包）。`SUPER` 传空表即可（整区块清空）。
- 守卫 `ExplosionComponentLightTest`（钉接线与顺序）；**真实光照计算只能游戏内验**。
  全文 `living-tnt-tech.md` §4.3「⚠️ 光照刷新」。

## 容器对外能量接口（ContainerEnergyStorage）热路径

> 详见 [`power-invariants.md` §2.G](../../docs/system-design/power-invariants.md)；以下是红线。

- `receive()`：**只扫一遍 `getStackInSlot`**，比例分配与零头回收走 `stacks[]` 数组。
  `isBulb()` 短路顺序固定 `!isEmpty → isWaxedBulb(Item) → isLivingItem(stack)`（反了白跑组件查询）。
  **保持无状态**（ThreadLocal 暂存池因跨调用状态 + 重入风险否决）。
- **语义红线**（`RoundTripConservationIT` 守着）：整 FE 量化 `accept -= accept % 1000`；
  完整步进保护（`count > leftover` 跳过）；「宁损勿造」。
- **long 溢出红线（真凶）**：mFE 定点（1 FE = 1000 mFE）+ 每盏 `BULB_UNIT_CAPACITY_MFE = 1e9`
  ⇒ `accept * remaining[i]` 可到 1e23 ≫ Long.MAX。**凡 `a * b / c` 先转 double 再夹 `remaining[i]`**。
  溢出后零头回收 `while` 退化成 ~10 亿轮（服务端冻结）；**27 槽铜灯 > 约 16 盏即触发**。
  同款第二处已修：`LivingWaxedCopperFunction.distributeToBulbs`（后果**凭空造电**）。
- 外部 mod 会传 `Integer.MAX_VALUE`（Flux「绕过限制」）⇒ 必须安全吃下；
  `MAX_LEFTOVER_PASSES = 256` 是防御上限**别删**。`extract()` 无同类溢出。

## 物品 ↔ 世界：独立于传输层的第三条链路

- `SlotAccessor`/`SlotInteractions` 只管**容器内**；**物品 ↔ 世界**入口是 Mixin，别往传输层挂。
- **优先调原版入口方法**：模拟玩家右键就调 `ItemStack.useOn`，**别自己 `setBlock`**
  （会静默绕过模组在 `canSurvive`/覆写 `useOn` 里的校验）。
- **读物品栈必须锚在 `consume` 之前**（`@At("RETURN")` 时单块放置已 count=0、`getComponents()` 返 `EMPTY`
  ⇒ 单块静默失效、堆叠却正常）。**软逻辑必须 try/catch**（否则「方块已放、物品未扣」+ 炸 tick）。
- `Level.isClientSide` 是 **`public final` 字段** ⇒ Mockito 不可 mock，单测统一用**方法式**。
- **单测 JVM 里 Mixin 是被应用的** ⇒ 可写端到端用例证明接线（不必等游戏启动）。
- 详见 [`infrastructure` §9.8](../../docs/system-design/living-item-infrastructure.md) /
  [`farmland-tech` §3.5 / §11.16](../../docs/tech/living-farmland-tech.md)。

## 跨容器传输：方向对称性必须逐条点名验证

- `pushToNeighbor` 与 `pullFromNeighbor` 是**两份独立实现**；「一处内嵌两路径共用」**不覆盖拉取方向**。
- **`SlotAccessorFactory.create` 把非箱类活物品挡掉（null）**，`createForNeighbor` 不查
  ⇒ **拉取方向**通用传输对活物品目标槽**必然失败**，特殊能力必须显式前置分支。
- **货物准入唯一定义点 = `SlotInteractions.isEligibleCargo`**（`!isLivingItem || 活箱子 || 活末影箱`）；
  规则收在共享入口，**调用点顺序变更绕不过它**。**活骨粉不给漏斗施肥**（属**传输语义**）。
- **判定「某能力是否覆盖」不能靠结构推断**：要么 grep 三处分支，要么写方向化单测。
- 详见 [`living-hopper-tech.md` §6.2.1 / §6.2.2](../../docs/tech/living-hopper-tech.md)。

## 槽位叠加层 / 配方书 / 相位圆盘 / 纹理（渲染与 Mixin 速记）

- **能进 `IItemDecorator` 就别写 `AbstractContainerScreenMixin`** —— **HUD 快捷栏走
  `Gui.renderHotbar` → `renderItemDecorations`，从不经过 `AbstractContainerScreen`**。
  三契约：坐标 =「当前 pose 内 `translate(xOffset, yOffset, z)`」（容器传 `slot.x`，**未加** `leftPos`）；
  容器内 z 有 `+100` 基准；`ItemDecoratorHandler` 会 `resetRenderState()` ⇒ **别写 `RenderSystem`**。
  详见 [`icon-system.md`](../../docs/system-design/icon-system.md)。
- **配方书 `stackedContents` 重建只有两条路径**：`initVisuals()`、`updateStackedContents()`
  —— **两条都要注入**（漏 `initVisuals` ⇒「刚开界面材料不识别，动一下才恢复」）。
  **内容变化后的刷新靠原版链路，别加轮询/指纹/缓存**（2026-09-05 加过又被移除）；
  **无状态是这个 Mixin 正确的原因**。详见 [`guides/recipe-book-style.md`](../../docs/guides/recipe-book-style.md)。
- **相位圆盘**：合因子 = **标量和** `Σ√|Δᵢ|`（`eff_δ_sum`）再 `^(1+u)`；相位只经 n 与调谐效率参与，
  **不做矢量相加** ⇒ 画**辐条**不画箭头。**相位分布不影响收益**（圆盘是诊断不是优化目标）。
  相位顺序一律 `PhaseDomain.phasesSorted()`；`deltaByOffset()` 是 HashMap 值视图且**不含 offset**。
  渲染速记：**点块以 (px,py) 为几何中心** · **步数取偶数** · **用 `symRound` 不用 `Math.round`**；
  改渲染前先跑 `tools/_sim_tooltip.py` 量化 before/after。
  详见 [`tooltip-system.md`](../../docs/system-design/tooltip-system.md)。
- **纹理**：涂蜡铜灯 = 未涂蜡 + **外圈 60px 黄框 `(232,160,62,255)`**，四锈蚀级掩码**完全一致**；
  改图标**只改内部**。图片处理用隔离 venv：
  `C:/Users/AI-777hi/.workbuddy-ai/binaries/python/envs/default/Scripts/python.exe`（Pillow 12.3.0）。
- **去原版化（删冗余纹理副本）时的引用扫描红线（2026-09-22 回归教训）**：
  ⚠️ 删纹理前必须同时扫两类引用，**Java 字符串里**：
  ① `living_item:textures/...`（带命名空间）+ `"textures/..."`（命名空间来自 `fromNamespaceAndPath(MOD_ID, ...)` 的第二个参数，**不带前缀**）。
  ② 模型 JSON 里 `living_item:item/xxx` 或 `minecraft:item/xxx` —— 别忘了原版引用链（minecraft 也会指向被「去原版化」删除的副本）。
  漏任一项 = **运行时缺失纹理（黑紫占位色）**，如 `LivingRedstoneDecorator.DOT_TEXTURE`
  指向已删 `living_item:textures/item/redstone_dust_dot.png` ⇒ 整个活红石粉装饰渲染失效。
  **修法**：删纹理后 grep 全项目 `fromNamespaceAndPath(MOD_ID, "textures/...")`，每个命中要么保留纹理，要么改指原版。

## 电力层（v3 铜块网络）

- `tickContainerData` 按 `(TopoKey, rep, channelIdx)` 组件遍历：**每组件仅锚点跑一次 `runBfs`**，
  其余 `ChannelState.copyFrom`；`accountEnergy` 仍逐机调用。
- **不变量**：`ChannelState.onPhaseEvent(event, pref)` 中 **pref 完全不被使用**（域只按 `event.period()` 分桶）
  ⇒ 同网络多机共享同一 ChannelState 实例安全。`copyFrom` 是**深拷贝**（改字段同步改它）。
- 跨 tick 持久化：`SimpleContainerContext` 存进 `ContainerLivingItemHandler` 静态缓存（按 containerKey）；
  `FakeContainerContext` **不持久化** ⇒ 驱动多 tick 逻辑必须用 SimpleContainerContext。
- 测试 seam：`ContainerRedstoneData.setEdgeForTest/setPrevEdgeForTest`。

## 性能判读：`PerfMetrics` 的覆盖盲区

- `PerfMetrics` 只插桩 `processContext` 内部；**外部 mod 直调我们能力接口**的路径
  （Flux Networks → `ContainerEnergyStorage.receiveEnergy`）**完全不在计时区间内**。
- ⇒ 「PerfMetrics 说只占 3%」与「spark 说 98.79%」**不矛盾**；
  **交叉验证必须看 spark 的绝对毫秒数**。**先问场景，再读火焰图**。
- 全文见 [`living-item-infrastructure.md` §10.3](../../docs/system-design/living-item-infrastructure.md)。
