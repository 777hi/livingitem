<!-- markdownlint-disable -->

# 对外开放计划（fork / addon 双路线）

*创建: 2026-09-27 · 状态: **部分落地** —— Q1/Q2 已拍板、A1/A2/D2 已完成；**A3 / D1 与 Q3~Q6 待办***

> ⚠️ **中间层文档（`docs/buffer/`）**：本文描述**计划的未来，不是现状**。
> 实施前不得当现状引用 —— 这正是 `docs/README.md` §1 那条 ⚠️ 要防的误导
> （"未实现的设计稿写成现状口吻"）。
>
> 📄 **现状以代码为准**：今天已可用的注册式扩展点见 §1.1，
> 接口化背景见 [`docs/archive/framework-refactoring.md`](../archive/framework-refactoring.md)。
> 本文只记「**还要改什么**」。
>
> **用法**：§3 每个问题拍板后回填「结论」列并标日期；**Q1/Q2 收敛后才动§4 的代码**。

---

## 0. 为什么会有这份文档

仓库已开放（`github.com/777hi/livingitem`），目标是让第三方开发自己的活物品。

2026-09-27 盘了一遍入口面，结论与直觉相反：**扩展点已经够了，缺的是承诺与验证手段**。
因此本计划的重点不是"加多少 API"，而是"怎么让第三方敢用、且用得对"。

---

## 1. 现状量化

> 铁律 4（可复算）：下列全部数字在 §6 有对应命令。**改代码后重跑，数字变了就更新本表。**

### 1.1 已经是注册式的 —— ❌ 不要重做

| 扩展点 | 位置 | 今天的状态 |
|---|---|---|
| `LivingItemManager.registerFunction` | `LivingItemManager.java:355` | ✅ public static，第三方可调用 |
| `InteractionRegistry.register` / `registerHandler` | `LivingItem.java:236–300` | ✅ 声明式规则 + 谓词收窄（活耕地案例已证明无需枚举物品类） |
| `ContainerSnapshot.registerProvider` | `LivingItem.java:226–228` | ✅ 注册驱动，解除了 container 包对 domain 类的依赖 |
| 可选接口 `HasDirection` / `HasContainerData` | `living/api/` | ✅ 新增活物品无需改核心文件（2026-08-17 重构的成果） |
| `SlotInteractions.register` | `SlotInteractions.java:71` | ⚠️ public，但**全仓仅 1 处调用**（内置静态块）——扩展点从未被外部验证过 |
| `LivingIconRegistry.register` | `LivingIconRegistry.java:318` | ⚠️ public static，但内置调用时机在 **mod 构造函数**（`LivingItemClient.java:58`），第三方无时序契约可依 |

### 1.2 真正的阻塞 —— 都要求第三方改我的源码

| # | 问题 | 位置 | 为什么阻塞 |
|---|---|---|---|
| ~~P0-1~~ | ✅ **已解决（A2 · 2026-09-27）**：`clearLivingData` 原是 33 行硬编码清单，已改为遍历各功能自声明的 `getOwnedComponentTypes()` | 契约见 [`api-contract.md`](../system-design/api-contract.md) §1.1 | 第三方不再需要改这个文件；不变量 I-C1~I-C3 由 `ComponentOwnershipTest` 守卫 |
| P0-2 | `getFunctionId()` 返回**裸 String** | `LivingItemFunction.java:47` | 无命名空间，两个第三方同名即撞车 |
| P1-1 | `getAllFunctions()` 返回 ArrayList 视图 | `LivingItemManager.java:361` | tick 期间注册会 `ConcurrentModificationException`（今天无人触发，只因全部注册集中在 `commonSetup` 一次） |
| P1-2 | `getApplicableFunctions` 收 `ItemStack`，缓存 key 却是 `Item` | `LivingItemManager.java:373–389` | 第三方写「按组件内容判定」会**静默失效**——契约与实际不一致 |
| P1-3 | `LivingButton.onPress` **无条件**活化任何手持物品 | `LivingButton.java:68–77` | 活化后触发活物品隔离（不传输 / 不熔炼 / 不作燃料）⇒ **静默破坏第三方物品**，第三方会误以为是自己的 bug |
| P2-1 | 全仓 `@ApiStatus` = 0、`@since` = 1 | — | 第三方无法区分「稳定」与「明天就改」 |
| P2-2 | API 面 public 成员 Javadoc 覆盖率 **128/329 (38%)**，最差 `client/icon` 10/67 (15%) | — | `docs/README.md` §7.2 已实证「一处 javadoc 与代码完全相反」⇒ 补写必须先配校验 |

### 1.3 fork 模式专属阻塞：上帝函数

`LivingItem.java:151–302`（**152 行**）一个方法体内塞了：

| 内容 | 处数 |
|---|---|
| `registerFunction(` | **22** |
| `new InteractionEntry` | **21** |
| `InteractionRegistry.registerHandler` | **9** |
| `ContainerSnapshot.registerProvider` | **3** |

⇒ **fork 者加一个活物品，diff 必然落入同一函数的同一区域；你每次改也落在同一区域
⇒ 每次同步上游都在同一位置撞车**（且是"无关 diff 相邻"型冲突，最难解）。

这是今天 fork 体验最大的技术债，**且它同时拖累自己**——那 152 行本来就是你反复编辑的热点。

### 1.4 fork 模式专属陷阱：modid

`LivingItem.MOD_ID` 全仓被引用 **23 处**；**31 个** DataComponent 全部注册在 `living_item` 命名空间下。

| fork 时怎么选 | 后果 |
|---|---|
| **保持** `modid = living_item` | ❌ 不能与官方版共存（MC 生态一个实例一个 modid） |
| **改名** | ❌ DataComponent ID 从 `living_item:xxx` 变 `mymod:xxx` ⇒ **旧存档活物品组件键不匹配，数据静默丢失**（不崩，日志是 unknown component） |

⇒ 必须在文档里写死，否则迟早有人踩。

---

## 2. 三条路线 —— ❌ 不互斥

| | fork | addon | **数据化（JSON）** |
|---|---|---|---|
| 受益人群 | 想改内部逻辑的人 | 想基于它做新东西的人 | **服务器主 / 整合包作者 / 玩家** |
| 人群规模 | 极少 | 极少 | **最多** |
| 他需要会什么 | 写 Java + rebase | 写 Java + 配 maven | **改 JSON / 用指令** |
| 上手成本 | 极低（clone 就跑） | 高（配 maven + dev 环境） | **极低（改一行 JSON）** |
| 长期成本 | **惨**（每次上游更新要 rebase） | 低（改版本号） | 低 |
| 能否公开发布 | ❌ modid 冲突 | ✅ 独立 modid | ✅（随整合包分发） |
| 上游能收到什么 | ✅ PR | ❌ 收不到代码 | ✅ **校准后的 JSON** |
| LGPL 在这里的作用是 | **武器**（强制回馈社区） | **摩擦**（第三方不想开源） | 无关（不改代码） |

> ⭐ **第 3 列是原先漏掉的那条**（2026-09-27 由用户指出）：
> 之前只算了「addon 作者」这一种外部开发者，而 `ContainerRuleConfig` 已经证明
> **「玩家校准 → 导出 → 发作者 → 作者合并 → 随包发布」这条闭环在本项目里真的跑通过**
> ——这是实证，不是设想（`ContainerRuleConfigTest` 就在 `src/test/` 里）。

**关键判断**：

- fork 者是 addon 作者的**漏斗顶层** —— 今天 fork 你仓库改东西的人，
  很可能就是明天的第一个 addon 作者或第一个 PR 贡献者。
- **但三者里成本最低、验证最快的是数据化**：如果连配置者（服务器主 / 整合包作者）都不来配 JSON，
  那 fork / addon 两条路大概率也不值得投入
  ⇒ **这是回答「生态要不要做」最便宜的一次实验**（见 Q2）。

---

## 3. 待决问题池 ⭐ 拍板前不写代码

| # | 问题 | 选项 | 影响面 | 结论 |
|---|---|---|---|---|
| **Q1** | LGPL 是否加 classpath exception | 保持 / 加例外 / 换宽松 | **若走 fork 为主 ⇒ 自动取消** | ✅ **已定：加例外**（2026-09-28 用户拍板「别人可以随意使用本模组作为前置库」）。落地：LICENSE.txt 末尾附加 **GNU LGPL v3 §7 additional permission**（LGPL-3.0 语境下 classpath exception 的规范写法，FSF 认可，非魔改）；README 两处许可说明 + 第三方开发者须知已同步。注意 SPDX 无此例外的标准标识符，自动化工具仍用 `LGPL-3.0-or-later`（LICENSE.txt 已注明）。效果：**依赖本库的 addon 可任意授权（含闭源）；修改库本体仍须 LGPL** —— copyleft 对库保留、对使用者解除 |
| **Q2** | fork / addon / 数据化 谁优先 | fork / addon / **数据化（配置者优先）** / 交集优先 | 决定 §4 走 B / C / **D** 哪一组 | **建议「D 组优先」**（2026-09-27 修订）——成本最低、验证最快，且会给后两条路提供真实信号。**D 组已完成（2026-09-28）** |
| **Q3** | `example-addon` 落本 repo 子项目还是独立 repo | 子项目 / 独立 / **不做** | 子项目可兼职当**兼容性哨兵** | 未拍板 |
| **Q4** | 是否独立 SemVer + 承诺「次版本不破坏 `living/api/` 里的公开类」 | 是 / 否 | 现在 `mod_version=1.5.1` 表达不了 API 稳定性 | 未拍板 |
| **Q5** | 能否让第三方用自己的命名空间注册 Function / DataComponent | 见 §1.4 | 与 modid 强耦合，**改动面大，先别动** | 暂缓 |
| **Q6** | **核心框架是否抽成独立前置库**（2026-09-28 用户愿景：把「tick 容器里的物品」框架拆出，本体降级为框架之上的内容模组，第三方基于框架衍生玩法） | ① 现在就拆 gradle 多模块 ② **先做 C 组（逻辑拆分），物理拆分等真实 addon 出现** ③ 不拆 | 决定仓库形态与发布物数量 | **倾向 ②** —— 「前置库」可以先是一个逻辑概念：C 组做完（maven 发布 + `living/api/` 边界 + ApiStatus 分层），addon 就只依赖 api 面，**物理上没拆、效果上已是前置库**；现在做物理拆分 = 在零 addon 数据点的情况下切 core/内容边界，大概率切错，等错了再改付双倍。见 §4.1 拆解顺序 |

---

## 4. 落地顺序草案

### A 组 · 交集（两种模式都受益 —— 先做这组）

| # | 做什么 | 为什么两边都要 | 状态 |
|---|---|---|---|
| A1 | **拆 `commonSetup()`**：注册按领域下放，主类只做一行汇总调用 | fork ⇒ diff 落进第三方自己的新文件，不再抢同一行；addon ⇒ 才能给出确定的注册时序契约 | ✅ **2026-09-27 完成**（11 个 `XxxRegistration`，提交 `0bbf3cd`；活打火石并入 `TntRegistration`）；配套的 tick 顺序显式化 ✅ **2026-09-28**（`getTickPriority()` + 冻结，见 api-contract §1.3，含 `TickOrderTest` 4 项） |
| A2 | `clearLivingData` → 遍历各功能自声明的 `getOwnedComponentTypes()` | 消灭 ~~P0-1~~「必须改我源码」，**零 break** | ✅ **2026-09-27 完成**（含 `ComponentOwnershipTest` 3 项） |
| A3 | 写清「**在哪注册**」的时序契约 | 今天三类扩展分别在 mod 构造函数 / 静态块 / `FMLCommonSetupEvent`，第三方在猜 | 未开工（建议排 A1 之后） |

> A 组不依赖 Q1~Q5 任何一项 ⇒ **随时可开工**。
> A1 的细粒度施工图（12 个注册入口 / `living/function/` 不搬 / tick 优先级）见
> [`api-contract.md`](../system-design/api-contract.md) §2.1~§2.4（tick 优先级已实施，并入其 §1.3）。

### D 组 · 数据化（2026-09-27 新增 —— 服务配置者：服务器主 / 整合包作者）

**为什么单独成组**：受益人群与前两组完全不同 —— 他们不写 Java，
只需改 JSON 或执行指令即可调整模组行为（见 §2 第 3 列）。

| # | 做什么 | 前置 | 为什么现在做 |
|---|---|---|---|
| **D1** | **活化黑名单 JSON**：哪些物品不允许被活化 | **无 —— 可立刻做** | 顺手解决 **P1-3**（`LivingButton.onPress` 无条件活化 ⇒ 静默破坏第三方物品）；配置者改一行 JSON 即可做平衡性调整。设计稿见 [`activation-rule-design.md`](../guides/activation-rules.md) |
| **D2** | `InteractionEntry` 数据化（`target × trigger → handlerId` 本质是四元组） | **A1 完成后 ✅** | ✅ **2026-09-28 完成**：13 条内置 JSON（源码 18 处 → tag 压缩后 13 条，`#minecraft:buttons` 一条顶 13 按钮）+ 玩家差异 + `InteractionPredicates`（2 个谓词 ID 化）+ `reload`/`list` 指令。设计与论证见 [`interaction-rule-design.md`](../guides/interaction-rules.md) |

> ⚠️ **并行边界（重要）**：
> **D1 与 A1 零重叠 ⇒ 可完全并行**；
> **D2 必须排在 A1 之后** —— A1 要搬运 `InteractionEntry`，同时改成 JSON 加载 = 两处改同一份代码。
> 换个角度看：**A1 本身就是 D2 的准备工作**（把 21 条规则按域聚拢后，数据化才容易做）。

**D 组的硬要求** —— 照抄 `ContainerRuleConfig` 这套**已验证**的模式：

| 必须照抄 | 出处 |
|---|---|
| 三层来源优先级：玩家移除 > 玩家规则 > 内置规则 | `ContainerRuleConfig` javadoc「三层来源」 |
| **增量语义**：配置只存玩家差异，不回写内置副本（否则升级会丢失新的内置内容） | 同上 |
| `load()` 幂等 ⇒ reload 能反映磁盘现状 | 同上 |
| `version` 字段 + 显式 UTF-8 + malformed 跳过不崩 | 同上 |

**且必须额外补两层**（`ContainerRuleConfig` 目前也缺）：见 §5 原则 4。

> **建议执行顺序**：`A2 ✅ → D1 → A1 → D2`（✅ 全部完成，2026-09-28）

### 4.1 前置库路线图（Q6 的执行拆解，2026-09-28 新增）

用户的愿景：核心框架（tick 容器里的物品机制）最终成为**独立前置库**，
本体降级为框架之上的内容模组，第三方基于框架衍生玩法。愿景成立，
但执行分三步走，**每步都有独立的完成判据**：

| 步 | 内容 | 性质 | 前置条件 |
|---|---|---|---|
| **第一步：逻辑拆分** | C 组全做：maven 发布（含 sources/javadocJar）、`living/api/` 边界收敛、ApiStatus 分层、api-contract 稳定 | **零风险** —— 只是把"什么是库、什么是内容"在代码与文档里写清，仓库不动 | 无 |
| **第二步：签名验证** | 出现 1~2 个真实 addon（哪怕是自己做的玩具）走 maven 依赖并跑通 | **验证第一步切对了** | 第一步 + Q3/Q4 拍板 |
| **第三步：物理拆分** | gradle 多模块 / 独立仓库，`living-core` 独立 artifact 与版本线 | **高风险高成本** —— core/内容边界被两个下游同时消费后改动极贵 | 第二步有真实数据点 |

> 判据一句话：**前置库先是一个契约（api 面），再是一个发布物（maven），最后才是一个仓库（多模块）。**
> 跳级执行 = 在没有数据点的时候替未来的自己切边界（open-plan §5 原则 1 的反例形态）。

### B 组 · fork 专有（Q2 选 fork 后做）

| # | 做什么 |
|---|---|
| B1 | `CONTRIBUTING.md`（fork → dev → test → PR 流程，含 `./gradlew test --rerun` 那条——`FROM-CACHE` 是假绿） |
| B2 | §1.4 的 modid 陷阱写进文档 |
| B3 | **mixin 使用规约**：3 个 mixin config，fork 冲突是**字节码层面静默失效**，不是编译错误 ⇒ 写「优先用 event / API，别加 mixin」 |

### C 组 · addon 专有（**大部分挂起：价值在第一个真实第三方出现时才兑现**，2026-09-28 用户定）

| # | 做什么 | 状态 |
|---|---|---|
| C1 | maven 发布 + `javadocJar` + `sourcesJar`（现状 `build.gradle:272-283` 只发到 `file://…/repo`，第三方根本拿不到） | ⏸ 挂起 —— 发布了没人拉等于白发 |
| C2 | `@ApiStatus` 分层标注 | ✅ **2026-09-28 完成（形态修正）**：曾逐类标注 211 个后撤销（用户判"太笨"，Create/AnvilCraft 先例支持），改为**包级口径**「默认内部，例外对外」—— 对外包仅 5 个（api/container/transfer/model/interaction），各带 package-info 契约；`@ApiStatus.Internal` 逐类标注只在精准点名的场合用（现 0 处）。**Javadoc 覆盖率棘轮**（基线 48%）拆到第 9/10 项待建 |
| C3 | `example-addon/` 子项目（兼容哨兵） | ⏸ 挂起 —— 依赖 Q3 拍板 |
| C4 | `docs/addon-guide.md`（**待建**，≤2 页，**只写 Javadoc 说不了的**：注册时序、`canApply` 缓存语义限制、大箱子 27 格错位、33 圈 ticking 边界、活物品隔离会波及第三方物品） | ⏸ 挂起 —— 写了没人读会腐烂（§5 原则）；等第一个真实 addon 作者的问题清单来喂内容 |

> **C 组收手的判据**：A1/A2 做完了「契约边界」这个最核心的东西（第三方现在**能用**）；
> C1/C3/C4 全部是「让第三方更方便」—— 它们的保质期从完成日就开始倒计时，
> 而需求侧为 0。等第一个真实的 addon 作者带着问题来，C1/C4 会带着明确需求回来。

### ❌ 明确不做（现在做就是投机设计）

| 不做 | 理由 |
|---|---|
| 手写 API 参考 md | 违反 `docs/README.md` 铁律 1/3（能从代码反推的不写）；**且该项目已有两次"同一内容两处维护"导致漂移的实证**（§1 ⚠️ 反面案例、§7.2） |
| 事件总线 / 新 hook | 等真实第三方出现才有数据点；今天能设计出来的接口大概率是错的 |
| 域原语提取（NeighborObserver / GridFlowSimulator 之类） | `docs/archive/framework-evolution.md` 已定：**等第 3 个数据点** |

---

## 5. 原则（本轮推导 —— 可据此否决后续提议）

> 三条都是从**本项目的实证**推出来的，不是通用最佳实践。

1. **先建校验，再建文档。** C2 必须早于 C4 —— `docs/README.md` §7.2 记着那处
   「javadoc 与代码完全相反」。一次性的补写会立刻开始腐烂；棘轮是唯一防退化的手段。
2. **删除要求 > 增加 API。** 优先让第三方「不用改我的源码」（A2、P0-1），
   而不是给他们加一堆 hook。前者零猜测，后者容易设计错。
3. **验收依赖第一公民。** 没有真实第三方前，`example-addon`（addon 路线）
   或 fork 试用（fork 路线）是唯一验证手段 —— **验证不通必须回头，不得继续往前铺文档。**
4. **数据化产物必须自带有效性告警 + 测试**（2026-09-27 新增）。
   JSON 的失败**天生是静默的** —— 字段名拼错 / 类型写错 / 物品 ID 不存在，
   全都表现为「**什么都不发生**」。这是原则 1 在配置层的直接推论：
   A2 正是吃了同一个亏（「忘了声明 = 静默失败」），才补了 `ComponentOwnershipTest`。
   新 JSON 除了照抄 `ContainerRuleConfig`，还必须额外补两层：

   | 补什么 | 为什么 |
   |---|---|
   | **未知字段 / 非法值 → WARN 并跳过** | 沉默即缺陷。打错一个 key 就应当被告知，而不是静默降级成默认值 |
   | **与 `ContainerRuleConfigTest` 同款的加载语义测试** | 覆盖：三层优先级 / 增量语义 / reload 幂等 / 坏值跳过 |

   > ⚠️ **已知待补**：`ContainerRuleConfig` 目前**没有未知字段检测** ——
   > `{"containersize": 27}`（少个下划线）会被 Gson 静默忽略，`containerSize` 变成 0。
   > 这条应该在 D 组动手时顺手补上。

---

## 6. 复算命令（§1 全部数字的来源）

```bash
rg -c "registerFunction\("          src/main/java/com/qiqi/li/LivingItem.java
rg -c "new InteractionEntry"        src/main/java/com/qiqi/li/LivingItem.java
rg -c "registerHandler"             src/main/java/com/qiqi/li/LivingItem.java
rg -c "stack.remove\("              src/main/java/com/qiqi/li/living/api/LivingItemManager.java
rg -c "DATA_COMPONENT_TYPES.register" src/main/java/com/qiqi/li/living/api/LivingItemManager.java
rg -c "LivingItem\.MOD_ID"          src/main/java
rg -c "@ApiStatus"                  src/main/java
python tools/doc_check.py
```

> §1.2 的 Javadoc 覆盖率用一次性探针统计，方法：以「上一非空行是否以 `*/` 结尾」
> 判定该 public 成员有无 javadoc。**探针已跑完删除**（`docs/README.md` §7.2「脚本踩坑」约定）。
