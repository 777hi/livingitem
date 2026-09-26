# 项目长期记忆（Living Item Mod）

> **工具侧速记**：只留「红线一句话 + 指针」。**完整正文一律在仓库 `docs/`**（多 AI 工具共用）；
> **冲突时以仓库文档为准**。文档规约见 [`docs/README.md`](../../docs/README.md)。

## 🔴 G 盘有坏块 = 「文件/目录莫名消失」的真因（2026-09-22 查明）

- 证据：系统日志 `disk` **事件 ID 7 bad block**；G 卷 `HealthStatus=Warning`；
  **Disk1 = E:/F:/G: 同一物理盘** ⇒ 备份到 E/F **无效**，只有 **C 盘**是另一块物理盘。
  诊断三招：日志 ID7 / 各卷 `HealthStatus` / 分区→物理盘映射。
- 应对：① 文件消失先 `git status`——整目录 deleted ⇒ **别 `git add -A`**，先 `git checkout HEAD -- <路径>`；
  ② 改完**立即 commit + push**（GitHub 是唯一脱离该盘的副本）；
  ③ 无备份时**禁止** `chkdsk /f` / `git gc` / `git prune`。
- 现状：`gc.auto=0`；跨盘 bundle 备份 `C:\Users\AI-777hi\livingitem-backup-*`。
- 已排除（别再查）：Defender / 受控文件夹 / OneDrive / 清理软件 / 并行 AI 工具。

## 协作：多 AI 工具并行 ⇒ 只 add 自己改的路径

- `git add -A` 会夹带别的会话未提交改动（实例 `064256f`）。**动手前先 `git status`**。

## 文档系统（全文在 `docs/README.md`）

- 改代码必须同步三处：技术文档小节 + AGENTS.md 进展一行 + 子系统索引概述；改完跑 `tools/doc_check.py`。
- 「开发进展」= AGENTS 只放**最近 10 条**「一行结论 + 指针」，正文写 `changelog.md`，超 10 删最底行。
  ⚠️ 别再提「批次制 / 保留 3 批次 / 连续迁出 / 边界单调」（已作废，`D-doc-04` 取代 `D-doc-02`）。
- 入口超 20,000 字符先查冗余、**不要上调预算**；搬文档**搬走不删掉**；守恒校验逐段判定。

## 运行时红线（正文见 docs）

- **单测**：Gradle 同一 JVM 跑全部测试 ⇒ 静态注册表/缓存**必须显式 reset**；诊断先写探针验证假设，用完即删。
- **`ChunkEvent.Load` 禁止任何世界交互**（会 `getChunk(join)` 自锁冻结服务端）：只登记坐标，扫描推迟到
  `ServerTickEvent.Pre`。**发现**= 所有已加载区块，**处理**= 仅 ticking 区。→ `infrastructure` §3.2
- **爆炸**：唯一入口 `ExplosionComponent.applyToChunk`；未加载区块**丢弃**（等 Load 重登记）、
  预算用尽 **carryOver**、队列**只 remove 本 tick 处理过的**（写反即丢爆炸）；
  `affects()` = **区块 AABB ∩ 球体**（非中心判据，三处生效）。→ `living-tnt-tech.md` §4.3
- **批量改 Section 必须自己补光照**：`initializeLightSources` → 逐 section `updateSectionStatus` →
  `propagateLightSources` → 逐个 `checkBlock`；①必须早于③、③只增光④才减光；
  `NORMAL` 走 `level.setBlock()` 不受影响。→ `living-tnt-tech.md` §4.3
- **能量接口 `ContainerEnergyStorage`**：`receive()` 只扫一遍槽位、保持无状态；
  ⚠️ **`a*b/c` 先转 double**（mFE 定点 × 1e9 ⇒ long 溢出 ⇒ 零头回收退化成 10 亿轮冻结）。
  → `power-invariants.md` §2.G
- **电力层 v3**：`tickContainerData` 每组件仅锚点跑一次 `runBfs`，其余 `copyFrom`（深拷贝）；
  跨 tick 持久化只能用 `SimpleContainerContext`。
- **`PerfMetrics` 只插桩 `processContext`** ⇒ 外部 mod 直调能力接口不在计时内；
  与 spark 数字不矛盾，交叉验证看 **spark 绝对毫秒**。→ `infrastructure` §10.3
- **物品↔世界入口是 Mixin**（别挂传输层）；**优先调原版入口**（`ItemStack.useOn`）别自己 `setBlock`；
  读物品栈必须锚在 **consume 之前**；软逻辑 try/catch；`isClientSide` 是 final ⇒ 单测用方法式。
  → `infrastructure` §9.8 / `farmland-tech` §3.5
- **跨容器传输**：`pushToNeighbor` / `pullFromNeighbor` 是**两份独立实现**；
  `SlotAccessorFactory.create` 对非箱类活物品返 null ⇒ **拉取方向**必须显式前置分支；
  货物准入唯一定义点 = `SlotInteractions.isEligibleCargo`。→ `living-hopper-tech.md` §6.2
- **渲染/纹理**：槽位叠加优先 `IItemDecorator`（HUD 不走 `AbstractContainerScreen`）；
  配方书须同时注入 `initVisuals` + `updateStackedContents`，**保持无状态**；
  相位圆盘 = **标量和**（画辐条不画箭头）；删纹理前 grep **Java 字符串 + 模型 JSON** 两类引用。
  → `icon-system.md` / `guides/recipe-book-style.md` / `tooltip-system.md`
- 图片处理用隔离 venv：`C:/Users/AI-777hi/.workbuddy-ai/binaries/python/envs/default/Scripts/python.exe`（Pillow）。
