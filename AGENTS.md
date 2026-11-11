# Living Item (活物品)

**Minecraft 1.21.1 + NeoForge 21.1.x**
*状态: Alpha 测试阶段；v8.1 接口化重构 / 红电 v19.1 等更早变更见 [changelog](docs/archive/changelog.md)*

---

## 项目概述

将世界中的方块功能（熔炉、漏斗等）**活化到物品层面**。活物品在容器（箱子、背包等）内自动运行，状态通过 DataComponent 持久化，跟随物品跨容器迁移。

### 核心特性

- **活按钮 UI**：在容器界面点击按钮，将手持物品转化为活物品
- **容器内自动执行**：含活物品的被加载容器会自动 tick
- **DataComponent 直接管理**：每个功能类直接管理其类型化的 DataComponent，替代旧的 `ComponentState` + `LivingFunctionData` 中转层
- **功能内聚**：每个功能类自行实现 tick 逻辑和数据管理，不再依赖编排器调度
- **不可变数据模型**：使用 Java Record 实现不可变数据结构，通过 `withXxx()` 方法创建新实例
- **增量同步**：功能数据拆分为独立的 DataComponent，只在数据变化时更新并同步到客户端
- **活物品隔离**：活物品不会被其他活物品当作普通物品处理（不传输、不熔炼、不作为燃料）
- **GUI交互系统**：声明式规则 + 统一拦截 + 服务端处理
- **活物品图标系统**：组件化声明式配置，新增活物品图标无需编写 Java 类
- **活箱子**：27 槽虚拟箱子（原版 `CONTAINER` 组件存储），16KB 字节上限（插入点精确把关），count>1 锁定存取
- **活末影箱**：无线传输路由器，路由模式（共享黑板）+ 直连模式（绑定玩家末影箱）
- **活水车**：应力产生类活物品，软依赖 Create，3D 旋转渲染
- **活地图传送**：活末影珍珠 + 活地图，三种场景 + UV 精确传送 + 跨维度 + 载具 + Sable 飞艇兼容
- **活耕地**：GUI 交互获取/种植/骨粉催熟 + 世界轴节拍生长 + round-robin 逐项产出 + 双槽渲染
- **接口化扩展**：`HasDirection`（WASD 朝向配置）+ `HasContainerData`（容器级数据计算），新增活物品无需修改核心文件
- **活红石系统**：活红石粉（信号传播）+ 活红石火把（反相器）+ 活按钮/活拉杆/活红石灯 + 活中继器/活比较器/活红石块 + 活侦测器（正面相邻槽公开状态变化 → 红石脉冲），支持与世界红石双向互通
- **活工具 / 活武器**：记忆玩家的操作（挖掘 / 交互 / 攻击）→ 借 FakePlayer 回放；无记忆的在背后环上辅助玩家（挖掘 / 攻击），有记忆的自主沿记忆射线干活；两类状态**联机可见**（主动模式的记忆射线需 F3+B）

---

## 架构概览

```
服务端 tick (LivingItem.onServerTick)
    ↓
ContainerChunkCache (容器位置缓存，拉取模型)
    ↓
ContainerLivingItemHandler (扫描容器、按功能分组)
    ↓
LivingItemFunction.tick() (各功能类自行实现 tick 逻辑)
    ↓
功能类直接管理 DataComponent，无中间层
    ├── LivingTntFunction        → LivingTntData
    ├── LivingFurnaceFunction    → LivingFurnaceData
    ├── LivingHopperFunction     → TransferPipeline (统一传输入口)
    ├── LivingEnderChestFunction → LivingEnderChestData
    ├── LivingWaterWheelFunction → LivingWaterWheelData
    ├── LivingChestFunction      → 原版 CONTAINER 组件（27 槽 + 16KB 字节上限）
    ├── LivingEnderPearlFunction → (纯工具类，无 DataComponent)
    ├── LivingRedstoneFunction   → LivingRedstoneData
    ├── LivingRedstoneTorchFunction → LivingRedstoneTorchData
    ├── LivingButtonFunction / LivingLeverFunction / LivingRedstoneLampFunction
    │   LivingRepeaterFunction / LivingComparatorFunction / LivingRedstoneBlockFunction
    └── LivingToolFunction      → LivingToolMemory / LivingToolProgress
                                  LivingToolAction / LivingToolOwner / LivingToolDigTicks
                                  （活工具 + 活武器：记忆玩家操作 → FakePlayer 回放；
                                   渲染见 client/render/LivingToolModelRenderer + RayRenderer）
    ↓
容器级数据计算（HasContainerData 接口，按优先级排序；**全库仅 4 个实现者**）
    ├── LivingFluidFunction        (prio 0) — 容器级流体 BFS（**自维持**，与桶解耦）
    ├── LivingWaterWheelFunction   (prio 1) — 应力计算 + postTickSync
    ├── LivingRedstoneFunction     (prio 2) — 红石信号传播（**自维持，唯一驱动点**）
    └── LivingWaxedCopperFunction  (prio 3) — 电力记账（依赖红石已算完）
    ↓
ContainerContext (组合接口) → TickContext (tick 级临时状态 + 脏槽位批量同步)
    ↓
SlotAccessor (模拟优先传输 + FilteredSlotAccessor 过滤)
```

> 容器级流体/红石数据由 `ContainerLivingItemHandler` 以「维度+containerKey」为键跨 tick 持久化，
> 并维护「位置 → 缓存键」反向索引供 mixin 热路径 O(1) 查询。

> 📄 基础设施详见 [living-item-infrastructure.md](docs/system-design/living-item-infrastructure.md)
> 📄 数据模型与设计决策详见 [data-model.md](docs/system-design/data-model.md)
> 📄 框架重构总结详见 [framework-refactoring.md](docs/archive/framework-refactoring.md)

---

## 子系统索引

> **下方全部子系统均已实现**（没有列在这里的就是没做）。**按族列出，族内每个子系统直链其文档**；
> 每个子系统「做什么」的完整概述见 [subsystem-index.md](docs/reference/subsystem-index.md)，
> 逐项能力清单见各 tech 文档 §0（**能力口径与不变量以各子系统文档为准**，本文只作路由）。
>
> ⭐ **读子系统先读它 tech 文档最前面的「§0 玩法定义」**（是什么 / 怎么玩 / 规则 / 能力清单）
> —— 它是**功能需求的来源**与**行为对错的判据**（规约见 [docs/README.md](docs/README.md)）。

| 族 | 子系统（点击直达文档） |
|----|----------------------|
| **活物品功能（20）** | [活TNT](docs/tech/living-tnt-tech.md) · [活流体](docs/tech/living-fluid-tech.md) · [活熔炉](docs/tech/living-furnace-tech.md) · [活酿造台](docs/tech/living-brewing-stand-tech.md) · [活漏斗](docs/tech/living-hopper-tech.md) · [活箱子](docs/tech/living-chest-tech.md) · [活末影箱](docs/tech/living-ender-chest-tech.md) · [活水车](docs/tech/living-water-wheel-tech.md) · [活地图传送](docs/tech/living-map-ender-pearl-tech.md) · [活耕地](docs/tech/living-farmland-tech.md) · [活工具](docs/tech/living-tool-tech.md) · [活武器](docs/tech/living-weapon-tech.md) · [活红石](docs/tech/living-redstone-tech.md)（信号层含 [活铜](docs/tech/living-copper-tech.md)） · [活涂蜡铜块（红电发电）](docs/tech/living-power-tech.md) · [活打火石](docs/tech/living-flint-and-steel-tech.md) · [活信标](docs/tech/living-beacon-tech.md) · [活背包](docs/tech/living-backpack-tech.md) · [活海绵](docs/tech/living-sponge-tech.md) · [活活塞](docs/tech/living-piston-tech.md) · [活魂符 / 女仆背包联动](docs/tech/living-maid-tech.md) |
| **容器基础设施（6）** | [容器七问总览](docs/system-design/container-overview.md) · [基础设施](docs/system-design/living-item-infrastructure.md) · [多方块容器身份解析](docs/system-design/container-identity.md) · [容器边界口径](docs/system-design/container-boundary.md) · [数据模型](docs/system-design/data-model.md) · [超大堆叠审计](docs/system-design/oversized-stack-audit.md) |
| **红电 · 电力（2）** | [红电架构演进](docs/buffer/redstone-evolution-roadmap.md) · [红电不变量测试](docs/system-design/power-invariants.md) |
| **跨域机制（3）** | [GUI交互](docs/system-design/gui-interaction-system.md) · [图标系统](docs/system-design/icon-system.md) · [Tooltip 系统](docs/system-design/tooltip-system.md) |
| **工程实践（4）** | [单元测试](docs/guides/unit-testing.md) · [活物品设计模板](docs/guides/living-item-design-template.md) · [槽位操作选型](docs/guides/slot-ops.md) · [分层现状与检查工具](docs/archive/architecture-layering-plan.md) |

---

## 模块地图

> 只列**目录级职责**（根级文件与关键子目录随附）。**完整文件树用 `find src/main -name "*.java"`**
> （人工文件树快照已于 2026-10-10 删除 —— 清单型内容必然漂移，不再维护）。

```
src/main/java/com/qiqi/li/
├── LivingItem.java                          # Mod 主类：tick 入口、网络包注册
├── LivingItemClient.java                    # 客户端入口
├── living/
│   ├── api/                                 # 公开接口 + 管理器
│   ├── container/                           # 容器抽象层（跨活物品共享基础设施）
│   ├── domain/                              # 领域模块（每个活物品内聚到此）
│   │   ├── hopper/                           #   活漏斗领域
│   │   ├── chest/                            #   活箱子领域
│   │   ├── ender/                            #   活末影箱领域
│   │   ├── furnace/                          #   活熔炉领域
│   │   ├── water/                            #   活水领域
│   │   ├── tnt/                              #   活TNT领域
│   │   ├── farmland/                         #   活耕地领域
│   │   ├── tools/                            #   活工具 / 活武器领域（记忆 + 回放 + 环渲染）
│   │   └── map/                              #   活地图传送领域
│   ├── domain/redstone/                     #   活红石领域
│   ├── domain/piston/                       #   活活塞领域（红石触发的位移执行件）
│   ├── domain/power/                        #   红电发电领域（活涂蜡铜块）
│   ├── function/                             # 简单活物品功能（无需领域模块）
│   ├── compat/                              # 第三方模组兼容层
│   │   ├── create/                          #   Create 兼容（软依赖）
│   │   └── sable/                           #   Sable 飞艇兼容（软依赖）
│   ├── components/                          # 无状态工具组件
│   ├── transfer/                            # 传输基础设施
│   ├── interaction/                         # GUI交互
│   ├── model/                               # 配置/方向模型
│   ├── mixin/                               # 服务端 Mixin
│   │   └── create/                          #   Create Mixin（条件加载）
│   ├── debug/                               # 调试工具（默认关闭，命令启用）
│   └── perf/                                # 性能监控
├── client/
│   ├── gui/LivingButton.java                # 活按钮
│   ├── icon/                                # 图标系统（声明式配置）
│   ├── input/                               # 输入处理
│   ├── render/                              # 渲染
│   ├── util/                                # 客户端工具
│   └── mixin/                               # 客户端 Mixin
└── network/                                 # 网络包
```

**测试全绿 = `doc_check` 第 2 项**：依据 `tools/gen_test_summary.py` 记录的最近一次**全量**
测试快照（failures/errors 恒 0）。条数不进文档（随每次加测试必漂，可从脚本输出或
`build/verification/full-test-summary.json` 读取）。局部 `--tests` 不覆盖快照 ⇒
改文档无需跑全量；**当前代码是否全绿由 CI 的 `./gradlew build` 兜底**。
已知 @Disabled 为环境性（FML 单测不加载 item tags，已游戏内验证兜住），
说明与回归入口住 [test-map.md](docs/reference/test-map.md)。

> 📄 测试环境配置与编写约定见 [unit-testing.md](docs/guides/unit-testing.md)；
> 测试文件树（每个测试类守什么）见 [test-map.md](docs/reference/test-map.md)。

## 提交前必跑（2026-10-30 更新）

```bash
python tools/check_layers.py          # 层纪律：R1 依赖方向 / R2 Mixin 位置 / R3 领域互依赖 / R4 注册入口
python tools/check_mixins.py          # Mixin：R1 living_item$ 前缀 / R2 显式成员 / R3 配置 ↔ 源文件
python tools/check_container_keys.py  # 容器键解析收敛（ContainerKeys 唯一入口）
python tools/check_stack_ops.py       # 槽位裸写棘轮：domain/function 的 setItem( 只减不增（原语是唯一数量操作入口）
python tools/check_icon_resources.py  # 图标声明入口与本地模型/parent/texture（不替代实际烘焙）
python tools/doc_check.py             # 文档一致性：路径 / 测试全绿快照 / 入口体量 / 生成名单区 …
```

**任一为红就不要提交。** `check_layers` 与 `check_stack_ops` 是**棘轮**（基线只允许收紧），
其余检查是零容忍门禁，所以「新引入的违规」一定会被看见。

> ⚠️ **全量复核必须加 `--rerun`**（`FROM-CACHE` / `UP-TO-DATE` 是假绿）；
> 口径见 [unit-testing.md](docs/guides/unit-testing.md)。
> 改文档不必先跑全量：局部 `--tests` 不覆盖全量快照，`gen_test_summary.py` 由 hook 自动调用。

> 💡 **自动防护**：仓库内 `.githooks/pre-commit` 跑上面各项（红的直接拦住）。
> 首次检出 / 换机器后执行一次 `sh tools/setup-dev.sh`
> （等价于 `git config core.hooksPath .githooks`）。
> ⚠️ 设置 hooksPath 后，本地 `.git/hooks/pre-commit` 不再执行 —— 以仓库内版本为准，
> 两套不要同时维护。

> 🤖 **CI 跑同样检查 + `gen_test_summary.py` + 图标门禁的临时夹具回归**（`.github/workflows/build.yml` 的 `Quality gates`），
> 且 `doc_check` 用 `DOC_CHECK_STRICT=1`：CI 里没产出全量测试快照直接红，不再 SKIP。

## 排查铁律：原版机制挡路时

遇到「原版 / NeoForge 的某个机制限制了我们」时，**按顺序**做，别跳步：

1. **先找官方配置入口。** 该机制通常**留了口子** ——
   看到 getter / container / 事件名，就**顺着追**它的 setter 与调用方。

   > 🔴 **反面案例（2026-09-24）**：需要"无视实体无敌帧打出多段伤害"时，
   > 我 grep 到 `LivingEntity#hurt` 里的 `getPostAttackInvulnerabilityTicks()`，
   > **却没深追**，直接去改 `public int invulnerableTime` 字段。
   > 而正解是 NeoForge 官方接口：
   > `LivingIncomingDamageEvent#getContainer().setPostAttackInvulnerabilityTicks(0)`
   > （铁魔法 `DamageSources#preHitEffects` 正是这么做的）。

2. **确认没有，才考虑改字段 / 反射 / Access Transformer** ——
   并在代码注释里写明**代价**（如"生产环境会因混淆失效"）。

> ⚠️ **跳步的代价**：野路子会在 MC / NeoForge 改内部实现时**静默失效**，
> 且往往比官方接口**影响面更大**（波及本不该波及的场景）。
> 📌 教训：**看到 getter 就该想到有 setter，看到 container 就该想到有事件入口。**

## Mixin 铁律：@Accessor / @Invoker 方法名必须带 `living_item$` 前缀

Mixin 的 `@Accessor` / `@Invoker` 会在**目标类上生成合成方法，名字直接取自接口里的方法名**。
如果名字是从原版字段推导的通用名（如 `getTarget`、`getSkipNextRelease`），**任何其它模组**
只要也访问同一个原版字段、起了同名方法，就会在加载该目标类时触发
`Incompatible @Accessor ... previously written by ...` ⇒ `MixinApplyError` ⇒ **客户端崩溃**
（本模组是「打开创造模式物品栏并点标签页、加载 `Slot` 时」触发）。

📌 **硬性规则**：所有 `@Accessor` / `@Invoker` 方法名一律加 `living_item$` 前缀，
**且必须同时显式指定目标成员** —— `@Accessor("字段名")` / `@Invoker("方法名")`。
接口名可带 `LivingItem` 前缀，但**方法名前缀才是关键** —— 冲突判定以「在目标类上生成的
方法名」为键，与接口名 / 包名无关。新增访问器 / 调用器前先确认方法名全局唯一。

> 🔴 **前缀的代价（2026-10-11 实测踩坑，启动即 FATAL）**：Mixin 靠方法名的
> `get` / `is` / `set`（Invoker：`call`）前缀**推断目标成员名**，裸 `@Accessor` + 带前缀方法名
> ⇒ 推断失效 ⇒ `InvalidAccessorException: Failed to inflect target name ... supported
> prefixes: [get, is]`。**「加前缀」与「显式指定成员名」必须成对出现，缺一即崩。**

> 🔴 **反面案例（2026-10-11）**：
> 1. `SlotWrapperAccessor.getTarget()` 与 inventoryessentials（背包必备）的同名 `@Accessor`
>    命中同一 `CreativeModeInventoryScreen$SlotWrapper` ⇒ 崩溃。已改名
>    `LivingItemSlotWrapperAccessor` + 方法 `living_item$getWrappedTarget()`。
> 2. `AbstractContainerScreenAccessor.getSkipNextRelease / setSkipNextRelease` 是**原版私有字段
>    `skipNextRelease` 推导出的通用名**，与 `getTarget` **同一类隐患**（只是暂未撞上其它模组）。
>    已全部加 `living_item$` 前缀。
>
> ⚠️ `@Inject` / `@Mixin` 注入器的 handler 方法名任意（惯例 `living_item$xxx`），不在目标类生成
> 同名合成方法 ⇒ 无此碰撞。**`@Shadow` 恰恰相反**：它是「别名目标已有成员」，必须与目标成员
> **同名**、**不能**加前缀（加了 = 解析不到目标而失效）；且 shadow 成员被剥离、不生成新成员
> ⇒ 无碰撞风险。（「建议 @Shadow 也加前缀」是 2026-10-11 更正掉的错误说法。）
>
> **「以小见大」原则**：通用名（原版字段派生 / 短方法名）一律默认有全局碰撞风险，
> 先加前缀再写，别等撞了才改。
>
> **判据可复算**：`python tools/check_mixins.py` —— R1 前缀 / R2 显式成员 /
> R3 mixins.json 条目·插件 ↔ 源文件 / R4 @Mixin 源文件 ↔ 配置双向一致；
> 违例即退出码 1（先例同 `check_layers.py`，可挂 CI / 提交前检查）。

## 设计铁律：新增机制前先找对应物

**顺序不可倒置：先定作用对象和行为，再找对应抽象；现有接口不能反向定义玩法。**
设计前填 [设计模板](docs/guides/living-item-design-template.md)「玩法原点」五问与正反例。

方案里每出现一层**新机制**（扫描器 / 生命周期 / 新挂点 / 新键形），必须能回答
「**现有抽象为什么不够**」。答不上来就退回上一步 —— 症状驱动 + 增量补丁会系统性地
把人带离核心抽象（2026-10-22 实证：活背包本该是「一个活物品功能」，却绕了三轮
viewer 驱动 / 框架扫描 / 框架判定才回到 `LivingItemFunction`）。

**点醒句**（看到征兆就打一句）：冒出新机制 ⇒「现有架构里有对应物吗？」· 连续补丁 ⇒
「回到原点，本来应该是什么？」· 在治症状 ⇒「这是症状还是病？」· 层数在涨 ⇒
「能不能用现有抽象表达？」· 说不清 ⇒「不建这个机制会怎样？」。安全词：**原点**。

## 注释铁律：只写「是什么 + 约束」

注释只写两件事：**这是什么**、**改动它有什么约束**。不写推导过程与背景论证。

**点名其它模组的唯一条件**：这段代码**直接对接**它（联动模块、我们调它的 API、替身类与守卫测试）。
拿它**举例 / 对照 / 论证** ⇒ 泛化成「第三方模组」「某模组」「其它模组的同类实现」。

📌 正例：`// ⚠️ 必须带容量：同一 BE 类型可有多档容量且列数不同`
　反例：`// ⚠️ 必须带容量：某模组 27/45 是 9 列`（点名只为举例）

## 文档导航

**冷启动默认路径（可按任务偏离）**

默认从本文件 → `docs/glossary.md` → 目标子系统 `docs/tech/living-*-tech.md` §0 建立心智模型；
改代码前在 `docs/decisions.md` 按领域确认口径未被取代（要改文档，先读 `docs/README.md` §1）。

⚠️ **别沿路径硬推**：任务跨多个子系统、要改玩法规则、文档与代码看着不一致、
或需要知道「为什么否决过某方案」时，改走 `AGENTS.md` 子系统索引 / `docs/README.md` §3.1。
信息不足就停下来查证，不要继续推断。改完见本文件「提交前必跑」。

文档按**层**组织（契约 / 实现 / 指南 / 参考 / 归档）—— 分层定义、体量约束与维护规约见
[docs/README.md](docs/README.md)（**改文档前先读**）。

**该读哪一篇**：见上方「子系统索引」（每个子系统一行，直链到对应文档）。其余常用入口：

| 想做的事 | 读它 |
|---|---|
| **看不懂某个名词**（活物品 / 锈级 / 相位域 / 跳变 / 口径…） | [glossary.md](docs/glossary.md)（**术语表，先扫这一页**） |
| **查「为什么这么定」/ 某口径是否已被取代** | [decisions.md](docs/decisions.md)（决策索引 + 翻转留痕） |
| 改文档 / 归档 / 校验 | [docs/README.md](docs/README.md) + `python tools/doc_check.py` |
| **设计新活物品 / 写设计稿** | [living-item-design-template.md](docs/guides/living-item-design-template.md) —— **五张横切检查表**（生命周期 / 准入 / 反馈 / 容器能力 / 槽位操作选型）+ 原版对齐清单 + 规则→验证入口对应表 |
| **写槽位数量操作 / 更新槽内组件 / 对外报资源量** | [slot-ops.md](docs/guides/slot-ops.md)（数量原语与组件提交选型）—— 契约见 [api-contract §1.7/§1.8](docs/system-design/api-contract.md)；设计前过 [模板 §1.4/§2.2](docs/guides/living-item-design-template.md) 的报账与宿主写回检查 |
| 写测试 / 跑全量 / mock `Level` | [unit-testing.md](docs/guides/unit-testing.md) |
| **梳理上下游 / 估改动爆炸半径 / 判断模块是否真解耦 / 查新增子系统的扩展点** | [code-map.md](docs/guides/code-map.md) —— 人看 HTML，**AI 直接调 `python tools/gen_code_map.py --query <类名>` / `--extend`**（文本输出、直接解析源码、**不会读到过期图**）；**改完代码跑 `python tools/check_layers.py`**（分层违规即退出 1，棘轮式「只减不增」） |
| 找某个源文件 | `find src/main -name "*.java"` 或 `python tools/gen_code_map.py --query <类名>`（人工文件树快照已删，2026-10-10） |
| 查指令用法 / 加新指令 | [commands.md](docs/reference/commands.md)（指令清单，快照） |
| **查某子系统的待定计划 / 还欠什么** | [TODO.md](docs/TODO.md)（**全项目唯一需求池** —— 2026-10-10 起 tech §0 不再列未实现，D-doc-06） |
| **查原版 / NeoForge / 第三方模组源码** | 若本机存在 `libs/src/`（可选、不入库），可直接搜；该目录被 `.gitignore` 忽略，搜索需把 path 指进去。没有就按常规方式查证 |
| 查历史变更 | [changelog.md](docs/archive/changelog.md)（按日期倒序） |
| **改容器层 / 排查容器相关 bug** | [container-overview.md](docs/system-design/container-overview.md)（**七问** —— 先定位问题属于哪一问，再只改那一问的唯一入口） |
| 红电系统总体设计 | [红电系统.md](docs/system-design/红电系统.md) |
| 活潜影箱独立设计 | [活潜影箱实现细节.md](docs/buffer/活潜影箱实现细节.md) |
| **给第三方写扩展 / 查对外契约**（**违反即 bug**） | [api-contract.md](docs/system-design/api-contract.md)（对外开放计划与待决问题池见 [open-plan.md](docs/buffer/open-plan.md)） |
| GUI 点击拦截 / 单元测试 / 活TNT测试 | `docs/guides/`（**做法**） |
| **配方书 / 拼音搜索**（跨活物品的共享技术） | `docs/tech/common/` |
| **未定案的草稿**（设计稿 / 计划 / 路线图 / 探讨 / 待办） | `docs/buffer/`（**中间层 —— 不稳定，别当现状读**） |
| 红电波形分析 / 方块朝向 / Sable 投影 | `docs/reference/`（**查表：快照 / 外部资料**） |
