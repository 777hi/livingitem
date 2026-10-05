# 框架层对标：AnvilCraft（未定案 · 参考输入）

*创建: 2026-10-06 · 状态: **未定案**，本文只是**对标记录**，不是执行排期*
*对标对象: `libs/src/AnvilCraft-dev-1.21-1.6`（AnvilCraft 1.21-1.6，NeoForge 21.1.238，Java 21）*

> ⚠️ **本文是「探讨层」，不是现状描述。** 下列所有"定稿值"指的是**本次讨论得出的结论**，
> **代码尚未改动**；动代码前必须先回到本文逐条确认。
>
> ⚠️ **并行会话冲突提示（2026-10-06）**：用户明确「另一个 AI 正在做架构方面的优化」。
> 本文定位是**那份架构优化的参考输入**，不是与之竞争的第二份计划。
> ⇒ **执行前必须先读一遍对方的改动**（`git status` / `AGENTS.md`「开发进展」），
> 逐条确认没有重叠（重叠项以对方为准，本文降级为"背景与理由"）。

---

## 0. 为什么会有这份文档

2026-10-06 用户要求：「看看 AnvilCraft 这个项目，和当前项目比较一下，比较框架层面有哪些可以学习的地方」。

结论先说：**体量差 8.7 倍，但框架层可学的东西只有 5 条，且没有一条是"必须照做"**。
AnvilCraft 是**内容型大模组**（516 block / 146 item），我们的是**机制型模组**——
它的很多抽象是"被 2536 个文件的体量逼出来的"，照抄对我们是过度设计。

⇒ 本文的价值不在于"学了多少"，而在于**把每条结论的定稿值、被否掉的选项、待决项写清楚**，
避免下次重新讨论一遍（AI 零记忆，这是项目文档系统的核心前提，见 [README.md](../README.md) 铁律 0）。

## 1. 规模对比（可复算）

```bash
# 我方
(Get-ChildItem -Recurse -Filter *.java src\main).Count   # 290
(Get-ChildItem -Recurse -Filter *.java src\test).Count   # 55 文件 / 473 用例
# AnvilCraft（在 libs/src/AnvilCraft-dev-1.21-1.6 下）
(Get-ChildItem -Recurse -Filter *.java src\main).Count   # 2536
(Get-ChildItem -Recurse -Filter *.java src\test).Count   # 0 —— 无 src/test 目录
```

| 维度 | AnvilCraft | Living Item | 判定 |
|---|---|---|---|
| 主源码 | 2536 | 290 | 体量差 8.7×，**抽象需求不同量级** |
| 测试 | **0**（无 `src/test`、无 JUnit 依赖、无 gametest） | 473 用例 | **我们显著领先，不许倒退** |
| Mixin | 189 | 19 | **我们是优势**，不要为对齐而增加 mixin |
| 自定义 Registry | 13 个（含 3 个 datapack registry） | 0（JSON 自研加载器 2 套） | 可学，但**等场景驱动**（见 §3.5） |
| 文档 | 1 份 `AGENTS.md`（给 AI 的操作守则） | 入口 + 13 子系统 + `doc_check.py` | **我们显著领先** |

> 📌 **定稿口径**：对标时**不许**用"人家大模组都这么干"作为理由。
> 判据只有一条：**这条抽象解决的是我们真实存在的问题吗？** 存在 ⇒ 学；不存在 ⇒ 记下来但不做。

## 2. 逐条结论（体例：定稿值 / 被否掉的选项及理由 / 待决项 / 测试守卫）

### 3.1 Mixin 业务逻辑外移为 Duck-interface（**定稿：做**，P0）

**现状**：19 个 mixin 全是裸 `@Inject`，业务逻辑写在 mixin 类内部。
两个巨型文件是上游升级的唯一高风险面：
`src/main/java/com/qiqi/li/client/mixin/RecipeBookComponentMixin.java`（约 50 KB）、
`src/main/java/com/qiqi/li/client/mixin/AbstractContainerScreenMixin.java`（约 34 KB）。

**AnvilCraft 的做法**：`api/injection/` 专门放**注入契约接口**（`IExplosionExtension.java` 等，
含 `block`/`entity`/`tooltip` 三个子包），方法统一 `anvilcraft$` 前缀；
mixin 类只 `implements` 它，业务代码全程 `instanceof` 调用，**mixin 内不写逻辑**。

**定稿值**：新写/改动的 mixin，一律「契约接口 + 薄 `@Inject` 转发」；存量按域逐步搬，**先动 `RecipeBookComponentMixin`**。

**被否掉的选项及理由**：
| 选项 | 结论 | 理由 |
|---|---|---|
| 一次性全部搬完 | ❌ | 19 个文件横跨服务端/客户端/Create 条件加载，一次性改完无法逐域验证，且**与并行会话的架构改动撞车风险高** |
| 保持现状（裸 `@Inject`） | ❌ | 逻辑住在 mixin 里 = **不可单测**。我们最在乎 473 个测试，这条直接把它挡在门外 |
| 引入 MixinExtras/DIC 之类的新依赖 | ❌ | 19 个 mixin 的量不值得引入新依赖；普通接口 + `implements` 已经够 |

**待决项**：
- 契约接口放哪？候选 ① 新建 `living/api/injection/`（对齐 AnvilCraft）② 就近放各域包。
  倾向 ①（**待拍板**），理由是「注入契约」是跨域共享的、且与 `api/` 现有公开接口同层。
- `client/mixin/` 的 11 个文件是否同批处理？倾向**不同批**（客户端升级风险低于服务端）。

**测试守卫**：搬出的逻辑必须补至少 1 个不依赖 FML 的纯单测（证明"不启动游戏也能测"）。
搬完后自检：`git diff --stat` 里 mixin 文件的**净减行数 > 0** 才算成功（否则只是挪了个位置）。

### 3.2 注册期事件取代主类手写注册链（**定稿：做**，P1）

**现状**：`src/main/java/com/qiqi/li/LivingItem.java` 在 `commonSetup` 里手写 11 个域注册器调用
（`Furnace/Hopper/Chest/Ender/Water/Tnt/Redstone/Power/Farmland/Tool/MapRegistration.register()`），
随后 `sortFunctionsByPriority()`；`src/main/java/com/qiqi/li/living/transfer/SlotInteractions.java`
用 `static{}` 块硬编码内置交互。

**AnvilCraft 的做法**（`api/anvil/IAnvilBehavior.java:63-69`）：

```java
static void register() {
    AnvilBehaviorRegisterEvent event = new AnvilBehaviorRegisterEvent(
        IAnvilBehavior::registerBehavior, IAnvilBehavior::registerBehavior);
    NeoForge.EVENT_BUS.post(event);
}
```

关键：**不自己造总线**，只是发一个携带"注册函数"的 NeoForge 事件；模组自身也在
`init/ModAnvilBehaviors.java` 用 `@SubscribeEvent` 注册 20 个行为——**自身和外部走同一条路**。

**定稿值**：新增一个「活物品功能注册事件」（名字待定），把主类那 11 行换成"发一个事件"；
各域注册器改为 `@SubscribeEvent`。**注册顺序仍由 `sortFunctionsByPriority()` 兜底**（事件顺序不可依赖）。

**被否掉的选项及理由**：
| 选项 | 结论 | 理由 |
|---|---|---|
| 上 NeoForge `DeferredRegister` 管活物品功能 | ❌ | 活物品功能不是 MC 注册表对象，`DeferredRegister` 管不了；硬套要造一个假 registry，得不偿失 |
| 反射扫描包路径自动发现 | ❌ | 失去显式顺序控制，且启动期反射扫描在 FML 环境下有类加载时机坑 |
| 保持手写链 | ❌ | 主类必须"认识"所有功能 ⇒ 新增域模块/第三方都要改核心文件，与 `getOwnedComponentTypes()` 已有的自声明方向相反 |

**待决项**：
- 事件名与包位置（`living/api/` 还是 `living/event/`）？**待拍板**。
- `SlotInteractions` 是否一起改成事件？倾向**一起改**（它现在是 `static{}`，第三方注册时机比功能注册更晚，风险更高）。
- ⚠️ **必须与并行会话确认**：若对方正在动 `LivingItem.java` 的注册段，本文此项**让位**。

**测试守卫**：已有 `ActivationHookTest` 等守着活化链路；新增断言「发事件后 11 个功能全部在位」
（数量断言要写成 `>= 11` 而非 `== 11`，避免第三方加入后误挂——**理由：数字断言写死值 = 把扩展点焊死**）。

### 3.3 JSON 表挂原版 reload 钩子（**定稿：做**，P1）

**现状**：`src/main/java/com/qiqi/li/living/domain/water/FluidTransformTable.java` 与
`src/main/java/com/qiqi/li/living/interaction/InteractionRuleConfig.java` 都已是三层来源
（内置 assets + 玩家 config 差异 + removed），**但只由指令 `/livingitem transforms reload` 触发**——
玩家执行 `/reload`（数据包重载）时不会生效。

**AnvilCraft 的做法**：主类 `addReloadListeners()` 挂自定义 reload listener；
`BLOCK_PLACEMENT_RULES` 等直接做成 **datapack registry**，天然随 `/reload` 走。

**定稿值**：两个 JSON 表注册成 reload listener，**保留手动指令**（调试用），二者走同一份 `load()`。

**被否掉的选项及理由**：
| 选项 | 结论 | 理由 |
|---|---|---|
| 现在就改成 datapack registry | ❌（暂缓） | 见 §3.5：需要 dispatch codec 重写多态序列化，成本高，且**目前没有"玩家想用数据包加活物品行为"的真实需求** |
| 只留手动指令不动 | ❌ | 玩家直觉里 `/reload` 就该重载配置，不生效属于**静默失效**（我们吃过这类亏：桶的渲染残留） |

**待决项**：reload 是**服务端**行为，但 `InteractionRuleConfig` 有客户端用途（GUI 拦截）⇒ 需要确认同步时机。**待查源码后定**。

**测试守卫**：现有 `FluidTransformTableTest` 已守加载语义；新增一条「`load()` 幂等」断言（连调两次状态一致），
因为 `/reload` 会让它被高频调用。

### 3.4 扩展点列表加确定性排序 + 兜底档（**定稿：做**，P1）

**AnvilCraft 的做法**（`api/block/BlockPlacementRules.java:59-86`）：三级解析
① 查 datapack registry ② 代码 fallback（comparator = 优先级 → 类名 → 包名，**保证重启后顺序完全一致**）
③ 动态生成默认规则（**永远返回一个可解释的结果，而不是 null**）。

**定稿值**：`SlotInteractions` 加确定性排序（对齐上述 comparator 口径）+ 未命中时的**显式兜底**。

**被否掉的选项及理由**：
| 选项 | 结论 | 理由 |
|---|---|---|
| 只加 `priority()` 字段排序 | ❌ | 同优先级时顺序由 `HashMap` 迭代序决定 ⇒ **重启后行为可能变化**，这是最难复现的一类 bug |
| 未命中静默 no-op（现状） | ❌ | 违反项目既有口径「永远给一个可解释的结果」；静默 no-op 排查成本极高 |

**待决项**：兜底档做什么？(a) 返回 `Optional.empty()` 但在 debug 日志打一行 (b) 返回一个 no-op 实例。
倾向 (a)——**理由**：no-op 实例会让"没注册"和"注册了但什么都不做"无法区分。

**测试守卫**：一条「打乱注册顺序 → 查询结果不变」的断言（这是确定性排序唯一可靠的证明方式）。

### 3.5 自定义 Registry / datapack registry（**定稿：暂不做**，P2）

**AnvilCraft**：`init/registry/ModRegistries.java` 定义了 13 个自定义 Registry，其中
`AMULET_DEF` / `CATEGORY` / `BLOCK_PLACEMENT_RULES` 是 **datapack registry**（Codec 驱动，纯数据包可扩展），
配合 dispatch codec 做多态序列化。

**定稿值：不做**，进「未来场景预演」（§4）等触发条件。

**被否掉的选项及理由**：
| 选项 | 结论 | 理由 |
|---|---|---|
| 现在就上 datapack registry | ❌ | ① 需要把 `FluidTransformTable` / `InteractionRuleConfig` 的 JSON 结构重写成 dispatch codec，是**重写不是迁移**；② 我们 alpha 阶段口径未定，早做等于把未定案的结构固化进存档/数据包；③ **没有真实需求** |
| 上普通自定义 Registry（非 datapack） | ❌ | 只解决"ID 唯一性"，我们用字符串 id + 自声明归属已经够，多一层 registry 只增加启动顺序依赖 |

### 3.6 Nullness 约定（**定稿：做**，P0，最便宜）

**AnvilCraft**：每个包一份 `package-info.java`，`@MethodsReturnNonnullByDefault` +
`@ParametersAreNonnullByDefault` + `@FieldsAreNonnullByDefault`，可空必须显式 `@Nullable`。

**定稿值**：全库加 `package-info.java`，**先加 `living/api/` 和 `living/container/`**（这两个是跨域共享、最易被误用）。

**被否掉的选项及理由**：
| 选项 | 结论 | 理由 |
|---|---|---|
| 不加（现状） | ❌ | 2026-10-04「末影箱 tick 崩溃」的根因就是传 null inventory 撞上被删的 hashCode 分支。**这类问题 IDE 能静态抓出来** |
| 只加在新增包 | ❌ | 半套约定 = 两套口径 ⇒ 正是项目铁律 6 要防的「屎山机制：没有统一标准，每次凭心情决定」 |

**待决项**：存量代码会有大量 `@Nullable` 需要补（可能几十处）。是**一次性补完**还是**只加 package-info、警告先放着**？**待拍板**。

**测试守卫**：`./gradlew compileJava` 不新增 error；**不许**为了过编译而加 `@SuppressWarnings`。

### 3.7 持久化：留数据版本字段（**定稿：做**，P2，1 行成本）

**AnvilCraft**（`saved/BetterSavedData.java:26-31`）：抽象基类**在构造器里调抽象方法**
`registerDataFixers()` ⇒ 子类不实现就编译不过，把"存档兼容以后再说"变成编译期必答题。
另有自研轻量 DFU（`saved/datafixers/DataFixers.java`，按 `ResourceLocation` id 注册、按 version 链式修复）。

**定稿值**：`ContainerFluidData` 等落盘结构**留一个 version 字段**（成本 1 行），**不做**迁移逻辑。

**被否掉的选项及理由**：
| 选项 | 结论 | 理由 |
|---|---|---|
| 完整抄 `BetterSavedData` + 自研 DFU | ❌ | alpha 阶段铁律 6 明确「不做旧存档兼容」；为迁就过去的自己造框架 = 纯负债 |
| 连 version 字段都不留 | ❌ | 等到真要改结构时**无处可挂**，那时再补等于强制所有旧数据迁移——这正是"留 1 行省一年"的典型 |
| 抄它 `getLevel(Level.OVERWORLD)` 的主世界单例假设 | ❌⚠️ | 它硬编码主世界并断言 `ConstantConditions`；我们不该引入这个假设 |

### 3.8 网络包与拆包（**定稿：暂不做**，P2）

**AnvilCraft**：114 个网络包，`network/multiple/` 把同一功能的多个 record 聚在一个类里；
`network/split/PacketSplitter.java` 超 1640 字节自动拆包，`BetterSavedData.sync2C()` 直接复用。

**定稿值：都不做**。

**被否掉的选项及理由**：
| 选项 | 结论 | 理由 |
|---|---|---|
| 把 15 个包改成注册器扫描 | ❌ | 我们 15 个包手工登记在 `LivingItem.java` 的 `onRegisterPayloadHandler` 里，约定已统一（`TYPE`/`STREAM_CODEC`/`static handle`）。**规模还不痛，抽象不划算** |
| 引入 `PacketSplitter` | ❌ | 没有症状。等真出现超 MTU 的容器同步再说（判据：容器槽位数 × 每槽数据量） |

## 3. 明确不抄清单（带理由）

| AnvilCraft 的做法 | 不抄的理由 |
|---|---|
| 依赖自研库 AnvilLib（`Registrum` / `ConfigManager` / `NetworkRegistrar`）+ jarJar 内嵌 | 单模组引入自研库不划算；我们的 `LivingComponents`（`DeferredRegister` 注册站）已覆盖同等需求 |
| 巨型集中注册表（`ModBlocks.java` 242 KB） | 反模式；我们按域分包（`living/domain/*/`）更好 |
| **0 测试** | 我们的 473 个测试是核心资产，任何改动都不得以"大模组也没测试"为借口砍测试 |
| 189 个 mixin | 我们的 19 个是优势，别对齐 |
| 硬编码主世界 `SavedData` 单例 | 引入不该有的假设（见 §3.7） |
| 注解式自研配置（`config/AnvilCraftServerConfig.java` 走 AnvilLib） | 我们已用 JSON 三层来源 + 指令，语义等价且更透明 |

## 4. 未来场景预演（什么时候该回头看本文）

| 触发条件 | 该做哪项 |
|---|---|
| 有玩家/整合包作者提出「想用数据包自定义活物品行为」 | §3.5 datapack registry 从 P2 升 P0，并按本文被否选项的理由先确认结构已定案 |
| 某个容器同步包导致客户端断开/截断 | §3.8 拆包器立项 |
| NeoForge 或原版大版本升级，mixin 大面积报错 | §3.1 立刻从 P0 升为**阻断项**（此时 mixin 内逻辑不可单测的代价集中爆发） |
| 要发布 1.0（结束 alpha） | §3.7 的 version 字段必须已在位，否则 1.0 后第一次结构变更无法平滑 |
| 有第三方想做 addon | §3.2 注册期事件是前置条件 |

## 5. 待决问题池（本次未拍板）

| # | 问题 | 影响 | 备注 |
|---|---|---|---|
| Q1 | 注入契约接口放 `living/api/injection/` 还是就近各域包？ | §3.1 | 倾向前者 |
| Q2 | `package-info.java` 存量 `@Nullable` 一次性补完 vs 警告先放着？ | §3.6 | 需评估补完量级 |
| Q3 | 注册事件的命名与包位置？与并行会话的架构改动是否重叠？ | §3.2 | **执行前必须先确认** |
| Q4 | `InteractionRuleConfig` 的客户端同步时机（reload 是服务端行为）？ | §3.3 | 待查源码 |
| Q5 | 扩展点未命中时的兜底：`Optional.empty()` + debug 日志 vs no-op 实例？ | §3.4 | 倾向前者 |

## 6. 文档跟进清单

- [ ] 本文落成后跑 `python tools/doc_check.py`（第 1 项校验路径真实性）
- [ ] §3.1~§3.4 第 1 项落地时：**先更新本文的「定稿值」再动代码**（用户既定流程）
- [ ] 落地后按 [README.md](../README.md) §4 写 changelog 一行 + `AGENTS.md` 进展一行
- [ ] 全部完成或决定放弃某条 ⇒ 本文从 `docs/buffer/` 搬 `docs/archive/`，并在 `docs/decisions.md` 留 supersedes 链
- [ ] ⚠️ 若与并行会话的架构优化重叠 ⇒ **本文降级为背景与理由，正文迁对方文档**
