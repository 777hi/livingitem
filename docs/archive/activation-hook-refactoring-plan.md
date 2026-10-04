# 活化钩子收口方案（activation hook refactoring plan）

*创建: 2026-09-30（前身是一份三行待办池，同名文件已删除）· 2026-10-04 改名扩写 · **2026-10-04 已实施并归档***
*状态: ✅ **已实施**（467 测试全绿）—— 契约落在 `docs/system-design/api-contract.md` §1.5；本文转为**实施记录 + 未来场景预演***

> 📄 **本文现在是历史记录**：方案已全部落地（§3.2 的 H1~H7 逐条实施）。
> 仍然有效的是 **§3.4 降级表**、**§3.5 的触发条件**、**§6 未来场景预演** ——
> 它们是 `docs/TODO.md`「未来活物品」（活经验瓶 / 活凋零玫瑰 / 活纸）的决策依据。
> 当时的 `docs/buffer/` 同类文档见 [`../buffer/open-plan.md`](../buffer/open-plan.md)。

> ⚠️ **中间层文档（`docs/buffer/`）**：本文描述**计划的未来，不是现状**。
> 实施前不得当现状引用（`docs/README.md` §1 铁律：未实现的设计稿写成现状口吻 = 典型误导）。
> 完成后按 `power-refactoring-plan.md` / `infrastructure-refactoring-plan.md` 的先例搬进 `docs/archive/`。

> 📄 **本文的前身**是一份三行待办池（记 A1/A2/A3 三条「活化流程」遗留）。
> 复核后（2026-10-04）发现**其中两条的前提不成立**，而第三条要动的是**对外公开接口**
> —— 规模远超「待办」能承载，故扩写成本记录文档。

---

## 0. 缘起

2026-09-30 审查 `living_tool_owner` 这个 DataComponent 的 NBT 占用时，
发现「活化那一刻要挂什么」这件事**散落在网络包代码里**，且以硬编码类型判断的形式存在
（`src/main/java/com/qiqi/li/network/LivingTagPacket.java:84-111`，共 5 段）。

这与本项目已经定过的两次方向相反：

| 已有先例 | 原做法 | 改法 | 位置 |
|---|---|---|---|
| **A2（2026-09-27）** | `clearLivingData` 是一份 31 行硬编码 `stack.remove` 清单 | 各功能自声明 `getOwnedComponentTypes()` | `api-contract.md` §1.1 |
| **A1（2026-09-27）** | 22 处 `registerFunction(` 塞在主类一个方法里 | 按域下放成 11 个 Registration | `open-plan.md` §4 A1 |

⇒ 本次要做的是同一件事的第三块拼图：**活化 / 取消活化的「附加动作」也归各功能自声明。**

---

## 1. 现状盘点（2026-10-04 实查）

### 1.1 要收编的 5 段内联判断

全在 `LivingTagPacket.handle` 一处，横跨 3 个域：

| # | 时机 | 现状代码 | 归属功能 | 需要什么上下文 |
|---|---|---|---|---|
| 1 | 取消活化 | `LivingChestFunction.isLivingChest(stack)` → `dropAllItems(stack, player)` | 活箱子 | `Player`（掉落位置） |
| 2 | 取消活化 | `stack.is(Items.ENDER_CHEST)` → `clearBoundPlayer(stack)` | 活末影箱 | — |
| 3 | 活化 | `setLiving(stack, living, player.getUUID())` → 写 `LIVING_TOOL_OWNER` | 活工具 / 活武器 | `Player`（UUID） |
| 4 | 活化 | 末影箱 GUI 中 → `setBoundPlayer(stack, uuid, name)` | 活末影箱 | `Player`（UUID + 是否在末影箱 GUI） |
| 5 | 活化 | `isLivingToolOrWeapon(stack)` → `LivingToolOwnerName.set(...)` | 活工具 / 活武器 | `Player`（名字） |

### 1.2 全部活化入口只有 3 个（`setLiving` 在 main 的全部调用点）

| 入口 | 途径 | 现状 |
|---|---|---|
| `LivingTagPacket`（玩家点活按钮） | `Via.PLAYER` | 5 段判断全在这一条路上 |
| `TillToFarmlandHandler:41`（活锄头耕地 ⇒ 活耕地） | `Via.INTERNAL` | 只调 `setLiving(stack, true)` |
| `LivingMapEventHandler:227`（活地图匣 ⇒ 活地图） | `Via.INTERNAL` | 只调 `setLiving(stack, true)` |

⚠️ 两条 INTERNAL 路径**都有 `Player` 在手**，只是没传。

### 1.3 owner 写入点只有 1 个

`setLiving(stack, living, owner)` 是唯一会写 `LIVING_TOOL_OWNER` 的地方
（`src/main/java/com/qiqi/li/living/api/LivingItemManager.java:298`），
而**全项目只有 `LivingTagPacket` 传了非 null 的 owner**。

---

## 2. 勘误：原 A1 / A2 的前提不成立 ⭐

> 这一节是本文最重要的产出：**照原 todo 的字面去修 A1 会修一个不存在的问题。**

### A1「活化指令路径缺 owner 写入」—— ❌ 病灶不存在

原文写「`LivingItemActivationCommand` 活化时不写 `LIVING_TOOL_OWNER`」。实查：
`src/main/java/com/qiqi/li/living/command/LivingItemActivationCommand.java` 是
**`/livingitem activation` 规则管理命令**（`reload` / `list` / `test` / `deny` / `allow` / `remove`），
**全程不调用 `setLiving`，它根本不活化物品**（见 §1.2 的调用点清单）。

⇒ 玩家用指令拿到活武器的路径**今天不存在**，「领地判定失效 / 赋灵者 tooltip 不显示」观察不到。
**A1 不作为独立缺陷修**；但 §1.2 的三个入口一旦收口到同一扇门，这类漏写就**结构性不可能再发生**
（这才是 A1 真正想要的东西）。

### A2「owner 写入未按类型过滤」—— ✅ 现象为真，但不值得单修

现象为真：`LivingTagPacket` 对**任何**被活化物品都写 16B UUID，
而消费者只有工具/武器链（`LivingToolOwnerName` / `LivingToolReplay`）⇒ 非工具类活物品带冗余 UUID（无害）。

**它会在本次收编里自动消失**：owner 写进 `LivingToolFunction` 自己的钩子后，
非工具功能不覆盖钩子 ⇒ 天然不写。**不需要单独做「加 `isLivingToolOrWeapon` 判断」这一步。**

### 结论

原 3 条待办 ⇒ **1 个架构改动**（本文 §3 的方案）+ 2 条勘误（本文 §1.2 / §2）。
待办池本身**不复存在**，本文取而代之。

---

## 3. 方案：派发点与钩子形态

### 3.1 命名撞车提醒 ⚠️

原 todo 的「A3」与 `open-plan.md` §4 A 组里的 A3（**注册时序契约**）是**两件事**。
本文档内的编号一律改用 **`H1/H2/H3…`**，避免交叉引用时串台。

### 3.2 方案定稿（2026-10-04 讨论拍板）

> 原为「待拍板项」，讨论后逐条定值。**未采纳的选项连同理由一并留下** ——
> 将来若要改回来，这里就是「为什么当初没选它」的答案。

| # | 定稿 | 讨论中否掉的选项（及理由） |
|---|---|---|
| **H1** | **新增门面** `LivingItemActivation.apply(stack, level, player, via, activate)`：切换 + 派发合一 | ❌ 塞进 `LivingItemManager.setLiving` —— 它只有 UUID 没有 `Level`/`Player`/`Via`，且框架类不该知道「活化」这个时机概念 |
| **H2** | **两个钩子** `onActivated(stack, level, player, via)` / `onDeactivated(...)`，都默认空实现 | ❌ 单方法带 `boolean activated` —— 取消活化侧有 2 件真事（箱子掉物、末影箱解绑），单方法会让每个实现都长 `if` |
| **H3** | 派发给 `getApplicableFunctions(stack)`（**认领该物品的功能**） | ❌ 用 `hasAnyFunctionFor` 的 copy 探针 —— 它专为此写了「不入 `APPLICABLE_CACHE`」的注释，是为**判定**设计的 |
| **H4** | **`evaluate()` 不收进门面**（判定留在 `LivingTagPacket`）；`via` 仍作参数传进钩子，作为将来的接缝 | ❌ 收进来 —— `LivingItemActivation` 类 javadoc 的表明写 INTERNAL/EXTERNAL **不受约束**、「禁了功能就坏了」；而内部产出被规则拦时**没有玩家能收到提示** ⇒ 静默失败，正是 `open-plan.md` §5 原则 4 要防的 |
| **H5** | **删** `setLiving(stack, living, owner)` 的 `owner` 参数（owner 改由活工具的钩子写） | ❌ 保留兼容 —— 那个参数本身是「框架不知道该写谁、就让调用方传」的 workaround，收口后它没有存在理由 |
| **H6** | 钩子**不得依赖**上下文（`player` 可空时必须有定义好的降级）；**触及邻接的通道是 `tick` / `InteractionHandler`** | ❌ 钩子一律禁止触及外部 —— 太强：F3（活纸）证明「一次性 + 槽位上下文」是真实需求（见 §6） |
| **H7** | 签名里 **`Level` 必填、`Player` 可空**；配「无法安全降级 ⇒ **拒绝操作并提示**」统一口径（见 §3.4） | ❌ 现在就上 Context/能力对象 —— 新框架机制、无终点、零数据点（见 §3.5） |

### 3.3 硬顺序不变量（H2 无论怎么选都要成立）

**「钩子执行时，物品必定处于『活』状态」** —— 因为三个相关判据都含 `isLivingItem`：

- `LivingChestFunction.canApply` / `isLivingChest`：`stack.is(Items.CHEST) && isLivingItem(stack)`
- `LivingEnderChestFunction.canApply`：同上
- `LivingToolRecorder.isLivingToolOrWeapon` → `isLivingTool` / `isLivingWeapon`：开头即 `!isLivingItem` 则 false

由此得两条**硬顺序约束**：

| 方向 | 顺序 | 不遵守的后果 |
|---|---|---|
| 取消活化 | **`onDeactivated` → `clearLivingData()`** | 箱子不掉物、末影箱不清绑定（判据在 `IS_LIVING` 被清之后恒 false） |
| 活化 | **`setLiving(true)` → `onActivated`** | 反之，功能认不出该物品 |

好处：两个方向都能直接用 `getApplicableFunctions` / `canApply`，
**不需要 copy 探针**，也不会把探针结果写进按 `Item` 缓存的表。
⇒ 这条不变量应写进接口 javadoc 当契约（`api-contract.md` §1 口径：契约层只记不变量）。

### 3.4 「玩家缺席」的降级约定（H7 的核心）

> 📌 **口径修正（2026-10-04 实施后）**：本节原标题为「降级表」，实际写成了
> **逐功能的降级清单 —— 那属于下游**（每个功能自己的数据语义）。
> 上游只承诺两件事：**参数原样送达** + **否决 ⇒ 零改动**；
> 「缺席时各功能做什么」写在该功能的实现里，不在本方案、也不在 `api-contract.md`。
> 下面保留这张表作为**当时的实施记录**，但请按下游视角读。

**为什么现在就要定 `Player` 可空**：活化入口今天全都持有 `Player`（§1.2）⇒ 缺席场景**尚不存在**，
但**未来三台机器会制造它**（F2 批量转化 / F3 还原，详见 `docs/TODO.md`「未来活物品」节）。
现在定的只是**上游签名允许缺席**这一件事，成本极低。

**「依赖玩家」的三类**（只有后两类可解）：

| 类别 | 实例 | 可否解耦 |
|---|---|---|
| **语义必然** | 活工具 `LIVING_TOOL_OWNER`（FakePlayer 要冒充真实玩家过领地/保护判定）；活末影箱绑定 | ❌ 不可解 —— 没有玩家就没有 owner/绑定 |
| **伪依赖**（把 player 当工具箱） | 箱子掉物只需要**位置**；末影箱绑定只需要 **UUID + 名字** | ✅ 可解 ⇒ 签名的 `Level` 必填、`Player` 可空即解掉 |
| **触发场景依赖** | `isInEnderChestGui(player)` 问的是「玩家正在干什么」，与物品数据无关 | ✅ 可解（缺席 ⇒ 视为否） |

**当时的实施记录**（下游视角，非上游契约）：

| 功能 | `player == null` 时 |
|---|---|
| 活工具（活化） | 不写 owner（**缺席即无主**，与 2 参 `setLiving` 路径一致；`FALLBACK_UUID` 兜底） |
| 活末影箱（活化） | 不绑定 ⇒ 落回**路由模式（公共黑板）** —— 已存在的合法模式 |
| 活末影箱（取消） | 无需 player，照常清绑定 |
| 活箱子（取消） | 返回 `false`（本次不做）—— 掉落需要位置，而门面没有位置参数 |

> 🔴 **一处曾被写错、已纠正的说法**：本节曾写「箱子无玩家时拒绝取消活化是为了避免
> **27 格内容凭空消失**」——**这是错的**。箱子内容存在**原版
> `DataComponents.CONTAINER`**，而活箱子**没有**声明任何 owned component
> ⇒ `clearLivingData` 不碰它，取消活化后**内容完好**，箱子只是变回普通箱子。
> 拒绝的真正理由是「**掉不出来**」，不是「**会丢**」。
> （教训：这个错误能出现，是因为把「下游的数据语义」当成了「上游的框架保证」来推理。）

### 3.5 为什么**现在不做**「上下文对象」

「解耦玩家」的彻底形态是把 `Player` 拆成能力袋（UUID / 名字 / 位置 / 菜单状态）——
**那是新框架机制，且没有终点**（拆完又会想加 level / dimension / qui…）。
按 `open-plan.md` §4「等真实第三方出现才有数据点」与 §5 原则 2，本次只做 H7 的浅层：

| 现在做（本次） | 推到有数据点之后 |
|---|---|
| `Level` 必填（三个入口天然都有，**零成本**消掉半数 player 用途） | Context / 能力对象 |
| `Player` 标 `@Nullable` + 上面那张降级表 | 槽位 / 容器上下文参数（`BlockPos` 之类） |
| 「无法降级则拒绝」的口径 + 守卫测试 | 批量无玩家转化的新机制 |

⭐ **可复算的理由**：三个入口**没有一个能自然给出容器位置**（carried 更是不在任何槽位）
⇒ 现在加位置参数就是「为想象中的需求预留」。

> 📌 **触发条件（写在这里，将来别忘）**：**F1 / F2 落地时重新评估上下文模型** ——
> 它们是第一个「一批物品、没有单一玩家」的真实数据点。

---

## 4. 与既有约束的冲突（必须先说清）

### 4.1 ⚠️ 与 `open-plan.md`「❌ 明确不做：新 hook」直接冲突

`open-plan.md` §4 末尾列着**不做**：「事件总线 / 新 hook —— 等真实第三方出现才有数据点；
今天能设计出来的接口大概率是错的」；§5 原则 2：「**删除要求 > 增加 API**」。

⇒ **要让本次改动立得住，动机必须定成「消除散落的内联类型判断（内部收编）」，
不能写成「给第三方的扩展点」**，且：

- javadoc **不写稳定性承诺**（open-plan Q4「独立 SemVer / 不破坏 `living/api/`」**未拍板**；
  P2-1 记录全仓 `@since` 仅 1 处）
- 若将来要对外承诺，条件是 Q3/Q4 拍板 + 出现真实 addon（§4.1 前置库路线图第二步）

### 4.2 文档跟进清单（实施当天必须同步，否则文档漂移）

| # | 改哪 | 改什么 |
|---|---|---|
| D1 | `api-contract.md` §1 | 落地后把钩子写进「已生效的契约」：三段内容 —— ① 派发给「认领该物品的功能」；② §3.3 两条**顺序不变量**；③ §3.4 **玩家缺席降级表** + 「无法降级则拒绝」口径。⚠️ 该文口径是**只描述现状**，所以必须**代码先落地**再改文档 |
| D2 | `living-tool-tech.md` | owner / 主人名字缓存的写入点从 `LivingTagPacket` 移到 `LivingToolFunction`；补「内部产出 / 无玩家 ⇒ 无主（FALLBACK_UUID 兜底）」 |
| D3 | `living-chest-tech.md` / `living-ender-chest-tech.md` | 取消活化掉落 / 解绑的触发点位置；**箱子新增「无玩家 ⇒ 拒绝取消活化」**这条新行为 |
| D4 | `docs/archive/changelog.md` + `AGENTS.md` 进展一行式 | 按 `AGENTS.md` §4 体例（结论写 changelog，表里只留一行 + 指针） |
| D5 | 本文 | 实施完成后搬进 `docs/archive/`（先例：两份 `*-refactoring-plan.md`） |
| D6 | `docs/TODO.md`「未来活物品」节 | F1·F2·F3 已于 2026-10-04 记入；实施完成后**回填一条**「F1/F2 落地 = 重新评估上下文模型」（§3.5 的触发条件） |
| ~~D7~~ | ~~`open-plan.md` §1.1~~ | 原「若 H4 选了收进门面则补口径」—— **H4 已定「不收」**，此项作废 |

---

## 5. 测试守卫（open-plan §5 原则 1：先建校验，再建文档）

A2 的教训是「**忘了声明 = 静默失败**」：漏列组件只在取消活化后表现为脏数据，没有报错。
本次同理 —— 功能忘了覆盖钩子 ⇒ **主人 / 绑定 / 掉物静默不发生**，玩家只会觉得「这功能坏了」。

| # | 守卫 | 形态 |
|---|---|---|
| T1 | 派发时机与参数 | 注册一个**记录型 mock `LivingItemFunction`**，断言活化/取消活化各派发一次、`player`/`via` 原样送达 |
| T2 | 顺序不变量 | 取消活化时 `onDeactivated` 早于组件清除（用「钩子内 `isLivingItem` 仍为 true」断言，见 §3.3） |
| T3 | 端到端 · 工具 | 活化活镐 ⇒ `LIVING_TOOL_OWNER` + `OWNER_NAME` 都写；**活化一个非工具 ⇒ 两者都不写**（A2 现象的回归守卫） |
| T4 | 端到端 · 末影箱 | 在末影箱 GUI 内活化 ⇒ 绑定当前玩家；GUI 外活化 ⇒ 不绑定 |
| T5 | 端到端 · 箱子 | 取消活化 ⇒ 内容全部掉落。⚠️ `dropAllItems` 会生成 `ItemEntity`，需用现成 level 替身（`docs/guides/unit-testing.md`） |
| T6 | **玩家缺席降级**（H7） | `player == null` 派发 ⇒ 不抛异常，且**逐条断言降级行为**：工具不写 owner / 末影箱不绑定 / **活箱子拒绝取消活化（数据仍在）** |

守卫位置：`src/test/java/com/qiqi/li/living/api/ActivationHookTest.java`（✅ 7 项已落地）
（命名须同步登记进 `docs/reference/file-map.md` 的测试文件树 —— `tools/doc_check.py` 第 3 项校验）。

> 手法照抄 `src/test/java/com/qiqi/li/living/api/ComponentOwnershipTest.java`：
> 在测试里 `registerFunction` 一个 mock 功能，避免依赖全局注册状态。

---

## 6. 未来场景预演（F1 / F2 / F3）

> **本节的作用**：把「将来要拍什么板」提前钉在纸上，而不是等第三次踩坑。
> 三台机器的原始描述见 [`docs/TODO.md`](../TODO.md)「未来活物品」节。
> ⚠️ 它们**都没开工**；本节只回答一个问题 —— **本次的钩子够不够它们用**。

### 6.1 F1 活经验瓶 / F2 活凋零玫瑰 —— 转化型（严格对偶）

| | 输入 | 目标 | 目标态 |
|---|---|---|---|
| **F1** | 消耗自身 | 槽位物品 | 非活 → **活** |
| **F2** | 消耗自身 | 槽位物品 | 活 → **非活**（凋零 = 终结，与原版语义咬合） |

⇒ 实现时必须写成**一套机制 + 一个方向参数**（`isLivingToolOrWeapon` 事故的口径教训）。

| 需要的能力 | 复用现成的 |
|---|---|
| 朝向（决定消费方向） | `HasDirection` + WASD 方向槽位 |
| 圈定哪些槽位 | `ContainerRuleConfig` 容器黑白名单 + `TransferPipeline` 槽位过滤 |
| 消耗自身 | 普通减堆叠 |
| 改写目标物品 | ✅ **本次提供**：门面 `apply` 批量派发 |

⚠️ **F2 是潜在的数据销毁器**：去活化 = `clearLivingData`，会清掉活箱子内容 / 末影箱绑定 / 活工具记忆
（而掉物逻辑只挂在人工取消路径上）⇒ 批量去活化必须**逐类表态**。规则见 §3.4。

### 6.2 F3 活纸（蓝图）—— 快照型，**唯一真正要新造的东西**

| # | 问题 | 结论 / 待拍板 |
|---|---|---|
| 1 | **点活按钮时物品在鼠标上（`carried`），不在任何槽位** ⇒「当前容器布局」无定义 | 三个选项：**(a) 快照玩家背包**（carried 下玩家唯一能碰到的槽位集合，语义自洽，**零交互改动**）/ (b) 要求物品在槽位内才可激活（要改按钮交互模型）/ (c) 快照当前打开的容器（❌ 混乱，排除）。**倾向 (a)** |
| 2 | 快照存哪 | 必须存**物品自身**（组件）—— 否则物品离开容器就丢快照，违背「状态跟随物品迁移」的既定原则。⚠️ 27 槽 × 物品 NBT 的**体积是硬约束**，要定上限与截断策略 |
| 3 | 还原时背包材料不足 | 「尽量还原 + 明确报告缺料」，避免部分还原被误读成 bug |
| 4 | 还原时布局已变 | 以快照覆盖，还是只填空位 |

**F3 对本次改造的两个反推**：

1. **它证明「一次性 + 槽位上下文」是真实需求** ⇒ H6 不能写成「钩子永远不管邻接」（已按此修正）。
   但**接缝留在门面**（`apply` 将来加可选参数），钩子签名**不必**现在就带上下文。
2. **它的「顺便把还原出来的物品活化」是「批量 + 无玩家玩家上下文」的第一个真实用例**
   ⇒ 正好用来验 §3.4 的降级表：无主工具 ✅ / 未绑定末影箱 ✅ / 遇到活箱子 ⇒ 拒绝。

### 6.3 结论：本次改造对三台机器 sufficiency

| 机器 | 够不够 | 缺口落在哪 |
|---|---|---|
| F1 / F2 | ✅ 够 | 「批量 `apply`」与「去活化逐类口径」是使用方责任，不是框架缺口 |
| F3 | ✅ 够（走 (a) 方案） | 快照组件是**它自己的**设计题；槽位上下文若真需要，走**门面加参数**，钩子不动 |

---

## 7. 复算命令（本文数字与结论的来源）

```bash
# §1.2 三个活化入口（应恰好 3 处：network 1 / interaction 1 / domain-map 1）
rg -n "setLiving\(" src/main

# §1.1 五段内联判断（收编后应只在 LivingTagPacket 剩「无类型判断」的调用）
rg -n "isLivingChest|isLivingToolOrWeapon|Items.ENDER_CHEST" src/main/java/com/qiqi/li/network

# §1.3 owner 写入点唯一
rg -n "setToolOwner|LIVING_TOOL_OWNER" src/main

# 测试
./gradlew test --rerun --tests "com.qiqi.li.living.api.ActivationHookTest"
python tools/doc_check.py
```
