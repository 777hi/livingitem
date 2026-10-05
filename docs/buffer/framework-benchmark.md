# 框架层对标：AnvilCraft + Cataclysm（未定案 · 参考输入）

*创建: 2026-10-06 · 状态: **未定案**，本文只是**对标记录**，不是执行排期*

| 对标对象 | 路径 | 类型 | 对标轮次 |
|---|---|---|---|
| **A · AnvilCraft 1.21-1.6** | `libs/src/AnvilCraft-dev-1.21-1.6`（NeoForge 21.1.238，Java 21） | 内容型大模组（2536 java） | 2026-10-06 第 1 轮 |
| **B · L_Ender's Cataclysm 3.33** | `libs/src/new1.20.1-1.21`（NeoForge 21.1.219，硬依赖 Curios + Lionfish API） | 内容型大模组（842 java，客户端占 48%） | 2026-10-06 第 2 轮 |
| **C · Create 6.0.10** | `libs/src/Create-mc1.21.1-6.0.10`（NeoForge，依赖 Registrate / Catnip / Ponder 库） | **机制型**大模组（2016 java，分层 `api`/`impl`/`foundation`/`infrastructure`/`content`） | 2026-10-06 第 3 轮 |

> ⚠️ **本文是「探讨层」，不是现状描述。** 下列所有"定稿值"指的是**本次讨论得出的结论**，
> **代码尚未改动**；动代码前必须先回到本文逐条确认。
>
> ⚠️ **并行会话冲突提示（2026-10-06）**：用户明确「另一个 AI 正在做架构方面的优化」。
> 本文定位是**那份架构优化的参考输入**，不是与之竞争的第二份计划。
> ⇒ **执行前必须先读一遍对方的改动**（`git status` / `AGENTS.md`「开发进展」），
> 逐条确认没有重叠（重叠项以对方为准，本文降级为"背景与理由"）。

---

## 0. 为什么会有这份文档

2026-10-06 用户先后要求对标两个第三方模组的**框架层**，看有什么可学的。

第 1 轮（AnvilCraft）结论：**体量差 8.7 倍，可学的只有 5 条，且没有一条"必须照做"**——
它是内容型大模组，很多抽象是"被 2536 个文件的体量逼出来的"，照抄对我们是过度设计。

第 2 轮（Cataclysm）结论：**框架层工程化不如我们（0 测试、硬依赖、巨型注册表），
但有一条具体发现戳中我们的真实缺口**（规则 JSON 放在 `assets/` 不可被数据包覆盖，见 §3.9）。

第 3 轮（Create）结论：**它是唯一的「机制型」对标对象，可比性远高于 A/B，而且修正了 A 轮的一条结论**——
Create（addon 生态最繁荣）用的是**静态注册表**而非注册期事件 ⇒ §3.2 由「做」降级为备选，
改为先做 §3.13 的统一注册表原语。另外它给出了**分层准则**（`api`/`impl`/`foundation`/`infrastructure`/`content`，见 §3.15）
与一个我们必须承认的差距：**mixin 里 accessor 占比我们只有 11%，Create 是 60%**。

⇒ 本文的价值不在于"学了多少"，而在于**把每条结论的定稿值、被否掉的选项、待决项写清楚**，
避免下次重新讨论一遍（AI 零记忆，这是项目文档系统的核心前提，见 [README.md](../README.md) 铁律 0）。

## 1. 规模对比（可复算）

```bash
# 我方
(Get-ChildItem -Recurse -Filter *.java src\main).Count   # 290
(Get-ChildItem -Recurse -Filter *.java src\test).Count   # 55 文件 / 473 用例
(Get-ChildItem -Recurse -Filter *.java src\main\java\com\qiqi\li\client).Count  # 46（占 16%）
# A · AnvilCraft（在 libs/src/AnvilCraft-dev-1.21-1.6 下）
(Get-ChildItem -Recurse -Filter *.java src\main).Count   # 2536
(Get-ChildItem -Recurse -Filter *.java src\test).Count   # 0 —— 无 src/test 目录
# B · Cataclysm（在 libs/src/new1.20.1-1.21 下）
(Get-ChildItem -Recurse -Filter *.java src\main).Count   # 842，其中 client/ 405（48%）
(Get-ChildItem -Recurse -Filter *.java src\test).Count   # 0 —— 无 src/test 目录
```

| 维度 | A · AnvilCraft | B · Cataclysm | C · Create | Living Item | 判定 |
|---|---|---|---|---|---|
| 主源码 | 2536 | 842 | **2016** | 290 | 体量差 3~7×；**C 与我们同为「机制型」**，可比性最高 |
| 分层 | `api` 350 + 业务混杂 | 无分层（`entity`/`client`/`init`） | **`api` 88 / `impl` 19 / `foundation` 324 / `infrastructure` 131 / `content` 1304** | `living/api` 7 + `container` + `domain/*` | ⚠️ **C 的分层准则值得抄，见 §3.15** |
| 测试 | **0** | **0** | **0 JUnit，但有 gametest**（`tests/` 6 类） | 475 JUnit / **0 gametest** | 各缺一半，见 §3.17 |
| Mixin | 189 | 23（9 个 accessor，39%） | **65（39 个 accessor，60%）** | 19（**2 个 accessor，11%**） | ⚠️ **我们 accessor 比例最低，见 §3.1** |
| 扩展点机制 | 注册期**事件** | 无 | **`SimpleRegistry` 统一原语 + 静态注册** | 4 处各造一遍 | ⚠️ **见 §3.13 / §3.14** |
| 网络包 | 114（分组聚合） | 15（手工登记） | 114（**单 enum**，`AllPackets`） | 15（手工登记） | 规模都不痛/都是反模式 |
| 第三方依赖 | 自研库 AnvilLib（jarJar 内嵌） | **curios + lionfishapi 硬依赖**，无软依赖机制 | Registrate / Catnip / Ponder 库 + **StackWalker 禁止第三方拿 Registrate** | Create / Sable **软依赖 + mixin plugin** | **我们更优** |
| 数据驱动 JSON | 有（datapack registry） | `data/cataclysm/` 500+，**可被数据包覆盖** | 有（`src/generated/` 6532 JSON） | `assets/living_item/` 4 个，**不可** | ⚠️ **我们是缺口，见 §3.9** |
| 文档 | 1 份 `AGENTS.md` | 0（README 还是 MDK 模板原文） | 0 仓库文档，但有 **Ponder 游戏内教程** | 入口 + 13 子系统 + `doc_check.py` | **我们显著领先**（Ponder 见 §3.19） |

> 📌 **定稿口径**：对标时**不许**用"人家大模组都这么干"作为理由。
> 判据只有一条：**这条抽象解决的是我们真实存在的问题吗？** 存在 ⇒ 学；不存在 ⇒ 记下来但不做。

## 2. 对象 A · AnvilCraft —— 逐条结论（体例：定稿值 / 被否掉的选项及理由 / 待决项 / 测试守卫）

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

> 🔄 **2026-10-06 第 3 轮修正（对标 Create 后）**：本条**降级为备选方案**。
> Create（2016 文件、机制型模组里 addon 生态最繁荣的一个）**选的是静态注册表 + 明确的注册时机约定，
> 不用注册期事件**；用事件的是 AnvilCraft。
> ⇒ **先做 §3.13 的 `SimpleRegistry` 静态原语**；**只有当出现"需要拒绝/校验第三方注册"的真实需求时**
> 才升级为注册期事件。完整判据见 **§3.14**。

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
⚠️ 与 §3.9 联动：若规则表迁到 `data/`，本项的收益才完整（数据包 + `/reload` 一条龙）。

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

**定稿值：不做**，进「未来场景预演」（§6）等触发条件。

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

## 3. 对象 B · Cataclysm —— 逐条结论

> B 轮（2026-10-06 第 2 轮）的整体判断：**工程化层面它不如我们**（0 测试、硬依赖、巨型注册表、
> 手写 Proxy 而非 `DistExecutor`），**但有一条具体发现戳中我们的真实缺口**（§3.9）。

### 3.9 规则 JSON 从 `assets/` 迁到 `data/`（**定稿：待拍板**，B 轮最重要发现）

**现状（可复算）**：4 个规则表全部在 `src/main/resources/assets/living_item/`——
`activation_rules.json`、`container_rules.json`、`fluid_transforms.json`、`interaction_rules.json`。
项目**没有** `src/main/resources/data/` 目录。

```java
66:66:src/main/java/com/qiqi/li/living/domain/water/FluidTransformTable.java
private static final String BUNDLED_RESOURCE = "/assets/living_item/" + CONFIG_FILE;
```

**Cataclysm 的做法**：`resources/data/cataclysm/` 下 12 个子目录、500+ JSON——
`recipe` / `loot_table` / `advancement` / `structure` / `worldgen` / `damage_type` / `tags` /
`curios` / `weapon_attributes` / `jukebox_song` …，其中 `weapon_attributes` 是**自制的 datapack 可扩展点**。
结构、战利品、伤害类型完全数据驱动，代码只写行为。

**判据（这是本节的核心）**：
> **`assets/<namespace>/` 不是数据包命名空间 —— 整合包作者无法用数据包覆盖它；`data/` 才是。**

我们目前唯一的覆盖途径是 `config/living_item/*.json`：需要**文件系统权限**（不能用数据包随世界分发），
且不支持 `/reload`。⇒ 对**整合包作者 / 服主**这一群体，我们实际上是**关闭**的。

**定稿值**：**待拍板（Q6）**。倾向「新建 `data/living_item/`，与 assets 内置默认并存」，
优先级 **数据包 > 玩家 config > 内置默认**（与 §3.4 的三级解析同构）。

**被否掉的选项及理由**：
| 选项 | 结论 | 理由 |
|---|---|---|
| 保持现状（assets + config 双轨） | ⚠️ **可辩护** | 作者自己调参的需求已被 config 覆盖。**要承认**：这条的收益只在"别人要改我们的规则"时才兑现 ⇒ 所以它该由**真实需求**触发（见 §6），不该因为"大模组都放 data"就做 |
| 只迁路径、不动优先级语义 | ❌ | 若不分优先级，内置默认与数据包同名文件冲突时行为未定义 ⇒ 又一处"凭心情决定" |
| 直接做成 datapack registry | ❌ | 同 §3.5：是重写不是迁移 |

**待决项**：
- ⚠️ **安全边界（必须先确认）**：mod 内置 `data/living_item/fluid_transforms.json` 会进入数据包目录结构。
  该文件放在**非标准子目录**（不在 `recipe`/`loot_table` 等原版会解析的路径下）⇒ 原版**不会**尝试解析它。
  但这一点必须用实机验证（`data/<modid>/` 下放一个未知 JSON，确认启动无警告/无报错）。
- 4 个表是否一起迁？倾向**先迁 `fluid_transforms` 一个**（它有指令与测试，回滚成本低）。

**测试守卫**：现有 `FluidTransformTableTest` 的加载语义断言必须**全部保持通过**（路径换了，语义不变）；
新增一条「同名 `data/` 文件覆盖内置默认」的断言。

### 3.10 PROXY 模式做客户端解耦（**定稿：不抄**）

**Cataclysm**：`ServerProxy.java` 是**服务端可编译的空实现**，`ClientProxy extends ServerProxy` 覆写；
逻辑层调 `Cataclysm.PROXY.playWorldSound(...)` / `PROXY.getPartialTicks()`，服务端跑时是空操作。

**定稿值：不抄。** 理由：我们已有 `src/main/java/com/qiqi/li/client/`（46 java）与 `living/` 的
**物理分包** + `LivingItemClient` 独立入口 ⇒ **物理隔离强于逻辑分派**（PROXY 只是"运行时分派"，
物理分包还能保证服务端根本不加载客户端类）。

**保留的一点约束**：将来若出现「服务端逻辑想触发客户端效果」的需求，**优先用网络包**而非 PROXY——
**理由**：PROXY 只在单机语义下成立，联机时必须走网络包；直接用 PROXY 会在联机下静默失效。

### 3.11 配置 bake 到静态字段（**定稿：已有等价物，不抄**；但补一条守卫）

**Cataclysm**：`config/ConfigHolder.java` 持 `ModConfigSpec`，`config/CMCommonConfig.java`（42 KB）
在 `onModConfigLoading/Reloading` 时把 spec 值刷到静态字段，避免运行时读 spec。

**定稿值**：不抄——`FluidTransformTable` 已建 O(1) 索引、`InteractionRuleConfig` 同构，语义等价。

**新增守卫（写进约束层）**：任何**每 tick 被调用 N 次**的"读配置"必须走缓存索引，
**不允许**在热路径里直接查表 / 读 `ModConfigSpec` / 读 JSON。

### 3.12 渲染数据与逻辑分离（**定稿：暂不做**，P3）

**Cataclysm**：`client/animation/` 34 个 `*_Animation.java`（Lionfish 动画数据类，单个 200 KB+），
`client/` 严格分 9 个子包（`animation` / `model` / `render` / `particle` / `sound` / `gui` / `event` / `tool`），405 文件占全项目 48%。

**定稿值：暂不做。** 我们 client 仅 46 文件（16%），规模不痛。等渲染量上来再说（触发条件见 §6）。

## 4. 对象 C · Create —— 逐条结论

> C 轮（2026-10-06 第 3 轮）的整体判断：**Create 是与我们同类型的机制型模组，可比性远高于 A/B**，
> 且它**修正了 A 轮的一条结论**（§3.2 事件 → §3.14 静态注册表）。本节是本轮价值最高的一部分。

### 3.13 统一注册表原语 `SimpleRegistry`（**定稿：做**，P1，**优先于 §3.2**）

**Create**：`api/registry/SimpleRegistry.java` + `impl/registry/SimpleRegistryImpl.java`——
`IdentityHashMap` + Provider 列表 + 缓存 + **`synchronized`（明确为"并行 mod 加载"设计）**。
**所有行为扩展点都是它的实例**：`MovementBehaviour.REGISTRY`、`BlockSpoutingBehaviour.BY_BLOCK`…
接口本身就是注册点（`api/behaviour/movement/MovementBehaviour.java:31`）：

```java
SimpleRegistry<Block, MovementBehaviour> REGISTRY = SimpleRegistry.create();
static <B extends Block> NonNullConsumer<? super B> movementBehaviour(MovementBehaviour b) {
    return b2 -> REGISTRY.register(b2, b);
}
```

**我们的现状（这是缺口）**：4 个扩展点**各造了一遍注册机制**——
`LivingItemManager`（static List + 排序）、`SlotInteractions`（static List，无排序）、
`FluidTransformTable` / `InteractionRuleConfig`（自研 JSON 索引，同构两份）。
且**四处都没有同步**（我们是 alpha 单线程假设，但注册期是并发的）。

**定稿值**：抽一个 `SimpleRegistry<K,V>` 原语——**确定性排序 + 缓存 + 兜底 + 并行安全一次做对**，
4 处复用。§3.4 的"排序 + 兜底"因此只实现一次，而不是四遍各漂移一次。

**被否掉的选项及理由**：
| 选项 | 结论 | 理由 |
|---|---|---|
| 按 §3.4 逐个给 4 个列表各加排序 | ❌ | 同一份语义写 4 遍 ⇒ 必然漂移（项目铁律 3：只写推不出来的；重复实现是可推出来的重复） |
| 用 NeoForge 自定义 Registry 代替 | ❌ | 同 §3.5：只解决 ID 唯一性，且引入启动顺序依赖 |
| 用 `ConcurrentHashMap` 自己糊一个 | ❌ | 与"抽原语"等价但没有类型化的 K/V 语义；**要么用现成约定要么抽原语，不要半套** |

**待决项**：
- 原语放哪？候选 `living/api/`（对外，第三方也要用）vs `living/util/`。倾向 `living/api/`（**待拍板 Q9**）。
- 是否要支持"按 tag 注册"（Create 的 `AllInteractionBehaviours.java:16-18` 支持 tag provider）？倾向**先不做**。

**测试守卫**：原语自带 4 条断言（注册后查询命中 / 打乱注册顺序结果不变 / 重复注册同一 key 的语义明确 / 并发注册不丢）。

### 3.14 静态注册表 vs 注册期事件（**定稿：优先静态注册表**，本条修正 §3.2）

**证据对比**：
| | 机制 | 体量 / addon 生态 |
|---|---|---|
| **Create** | **静态注册表（public static）+ 文档写明"必须在 common setup 注册"** | 2016 文件，addon 生态最繁荣 |
| AnvilCraft | 注册期**事件**（`NeoForge.EVENT_BUS.post`） | 2536 文件，无公开 addon 生态 |

**判据**：两者能力差只有一条——**事件能在注册前否决/校验**；除此之外静态注册表更简单、
注册后可缓存不可变、无事件时序依赖、可静态追踪谁注册了什么。

**定稿值**：**先做静态注册表。** 只有当出现"需要拒绝第三方注册"或"注册前需跨模组校验"的真实需求时，
才升级为事件（届时 §3.2 复活）。**不许两个都上**（双入口 = 两套口径，违反项目铁律 6）。

**被否掉的选项及理由**：
| 选项 | 结论 | 理由 |
|---|---|---|
| 保留 §3.2 的事件方案 | ❌（本轮修正） | Create 用最朴素的机制就撑起了最大的 addon 生态——**这是对我们的强反证**：我们 11 个功能，用事件是过度设计 |
| 静态注册表 + 事件双入口 | ❌ | 两套注册路径 ⇒ 顺序语义、时序、文档都要维护两份 |
| 什么都不做（保持 4 处手写） | ❌ | 见 §3.13：缺口是"重复实现"本身，不是"用哪种机制" |

### 3.15 `api/` + `impl/` 分层与 `@ApiStatus.Internal`（**定稿：做**，P2）

**Create 的四层准则**（这是 C 轮最值得抄的一条）：

| 层 | 文件数 | 放什么 | 不放什么 |
|---|---|---|---|
| `api/` | 88 | **对外契约**：接口、静态注册点、事件、给 addon 的 datagen 工具 | **不放实现**（实现委托 `impl`） |
| `impl/` | 19 | api 的**内部实现** + 默认处理器，整包 `@ApiStatus.Internal` | 不给第三方用 |
| `foundation/` | 324 | **引擎**：与游戏内容无关的可复用机制（blockEntity / gui / render / codec / virtualWorld / mixin） | 不放具体玩法 |
| `infrastructure/` | 131 | **装配层**：把引擎挂进 mod 生命周期（config / command / gametest / worldgen / ponder） | 不放引擎逻辑 |
| `content/` | 1304 | 游戏内容，按玩法域分包 | 不放机制 |

**我们的现状**：`living/api/` 已有对外契约雏形（7 文件），但**没有 `impl/` 层**，
也没有任何"内部/外部"的显式标注 ⇒ 第三方（和未来的我们）无法区分"能依赖"与"随时会改"。

**定稿值**：给 `living/api/` 补对偶的 `living/impl/`（或至少对内部实现类加 `@ApiStatus.Internal`）。

⚠️ **必须同时记住的反例**：Create 自己就漏了——
`api/behaviour/movement/MovementBehaviour.java:7` **反向依赖 `content`**；
它只能靠 `Create.java:213-217` 用 `StackWalker` 在运行时挡第三方（治标）。
⇒ **分层纪律没有机械检查就会漏**，哪怕是最成熟的模组。

**新增守卫**：我们有 `tools/gen_code_map.py`（2026-10-05，含**分层违规报告**），Create 没有 ⇒
**这是我们的潜在领先项，但前提是真的用起来**。见 Q12（是否接进 `doc_check.py` / CI）。

### 3.16 DataComponent 成对声明 persistent + networkSynchronized（**定稿：已达标**，不抄）

**Create**（`AllDataComponents.java:57-66`）：统一 `builder.persistent(codec).networkSynchronized(streamCodec)`。
**我们**（`src/main/java/com/qiqi/li/living/transfer/LivingComponents.java:62-67`）：已是同款：

```java
DataComponentType.<Boolean>builder().persistent(Codec.BOOL)
    .networkSynchronized(ByteBufCodecs.BOOL).build();
```

⇒ 打平。**新增自检项（写进约束层）**：新增组件**必须**成对声明；
若缺 `networkSynchronized`，必须在注释里写明理由（如"仅服务端，客户端不需要"）——
否则会重现活工具 owner 那个坑（2026-09-29：不同步则客户端 tooltip 显示不出主人）。

### 3.17 gametest（**定稿：待决**，P2）

**Create**：`infrastructure/gametest/CreateGameTests.java`（`@EventBusSubscriber` + `RegisterGameTestsEvent`）
+ `tests/` 下 6 个类：`Contraptions` / `Fluids` / `Items` / `Misc` / `Processing` / **`Regressions`**。
**但 Create 没有 JUnit 单测。**

**我们**：475 个 JUnit 单测（FML 环境内），**0 个 gametest**。⇒ **各缺一半**。

**判据**：gametest 能覆盖 JUnit 覆盖不到的——**真实 tick 序列、跨容器迁移、跨维度、真实方块交互**；
代价是要起 gametest server、跑得慢、CI 难集成。

**定稿值：待决（Q11），不为对齐而做。** 触发条件见 §6。
值得抄的是它的**组织方式**：按域分包 + **单独一个 `Regressions` 类放回归用例**（回归与功能测试分开）。

### 3.18 配置值暴露为 `Supplier` 以支持热重载（**定稿：做**，P2）

**Create**（`infrastructure/config/CStress.java:53-65`）：`getImpact()` 返回 **`DoubleSupplier`**
（实时取值而非缓存快照），配合 `ConfigBase.onLoad/onReload` 重烘；另有 `VERSION` 字段用于版本变更时重置配置。

**与 §3.11 的关系（必须写清，否则会被当成矛盾）**：
- §3.11 说的是**热路径不许直接查表/读 spec**（性能）；
- 本条说的是**配置值本身用 `Supplier` 传递**（正确性：热重载后自动生效，不会持有 stale 快照）。
⇒ 两者是同一件事的两面：**缓存索引照建，但对外暴露 `Supplier` 而不是拷贝值**。

### 3.19 Ponder 游戏内教程（**定稿：不抄**）

**Create**：教程引擎是**外部独立库**（`net.createmod.ponder`）；Create 侧只实现 `PonderPlugin` 接口
（`foundation/ponder/CreatePonderPlugin.java`）+ 按内容域分包场景（`infrastructure/ponder/scenes/`，30+ 场景类）+ tag 索引。

**定稿值：不抄。** 理由：alpha 阶段我们有 tooltip + 13 份文档，自研教程引擎成本远大于收益。

**但记下路线**：若将来真要做新手引导，**走"外部库 + Plugin 接口"**，不要自研引擎
（Create 自己是这么做的——引擎与内容分离，内容侧只是一个插件）。

## 5. 明确不抄清单（带理由）

### A · AnvilCraft

| 做法 | 不抄的理由 |
|---|---|
| 依赖自研库 AnvilLib（`Registrum` / `ConfigManager` / `NetworkRegistrar`）+ jarJar 内嵌 | 单模组引入自研库不划算；我们的 `LivingComponents`（`DeferredRegister` 注册站）已覆盖同等需求 |
| 巨型集中注册表（`ModBlocks.java` 242 KB） | 反模式；我们按域分包（`living/domain/*/`）更好 |
| **0 测试** | 我们的 473 个测试是核心资产，任何改动都不得以"大模组也没测试"为借口砍测试 |
| 189 个 mixin | 我们的 19 个是优势，别对齐 |
| 硬编码主世界 `SavedData` 单例 | 引入不该有的假设（见 §3.7） |
| 注解式自研配置（`config/AnvilCraftServerConfig.java` 走 AnvilLib） | 我们已用 JSON 三层来源 + 指令，语义等价且更透明 |

### B · Cataclysm

| 做法 | 不抄的理由 |
|---|---|
| **0 测试** | 同上。它的 README 甚至还是 NeoForge MDK 模板原文未改 |
| 巨型集中注册表（`ModItems.java` 85 KB / `ModEntities.java` 48 KB / `CommonConfig.java` 74 KB） | 同 A：反模式 |
| **curios + lionfishapi 写死为 `required` 硬依赖**，全项目**无** `ModList.get().isLoaded` / `LoadingModList`，无 mixin 条件加载 | 我们的 Create / Sable 软依赖 + `src/main/java/com/qiqi/li/living/compat/create/CreateMixinPlugin.java` 更优。**这是我们的领先项，不许退化** |
| 手写 `ServerProxy` / `ClientProxy` 而非 `DistExecutor` | 见 §3.10：我们有更强的物理分包 |
| Attachment 全部不 `.sync()`，同步靠手写包（`init/ModDataAttachments.java` 仅 `HOOK_FALLING` 带 `.serialize(Codec.BOOL)`） | 我们的 `CONTAINER_FLUID_DATA` 带落盘 + 同步包，更规范 |
| 客户端 405 文件靠 PROXY 入口聚合 | 规模驱动的产物，我们不需要 |

### C · Create

| 做法 | 不抄的理由 |
|---|---|
| `AllBlocks.java` **2711 行 / 124 KB**（import 占 325 行）、`content/contraptions/Contraption.java` 58 KB、`AllBlockEntityTypes.java` 48.8 KB | God class 反模式。我们按域分包（`living/domain/*/`）+ `LivingComponents` 纯注册站更好 |
| `AllPackets.java` 单 enum 装 114 个包 | 一个文件的膨胀速度 = 包数量，我们自己 15 个包手工登记反而更可控 |
| **0 JUnit 单测**（只有 gametest） | 我们的 475 个 JUnit 是核心资产。gametest 是**补充**不是替代（见 §3.17） |
| 源码自认技术债（`Create.java:142` `TODO - Make these use Registry.register`、`:147/163` `FIXME: not thread-safe`） | 大模组也会留债 —— **不能反过来当成"我们也可以"的许可** |
| `api/` 反向依赖 `content`（`MovementBehaviour.java:7`）+ 用 `StackWalker` 运行时挡第三方 | 分层泄漏后只能治标。我们有 `gen_code_map.py` 可以**机械检查**，别浪费 |

## 6. 未来场景预演（什么时候该回头看本文）

| 触发条件 | 该做哪项 |
|---|---|
| **有整合包作者 / 服主要改我们的规则表**（真实需求出现） | §3.9 从"待拍板"升 P0，并与 §3.3 同批做（数据包 + `/reload` 一条龙） |
| 有玩家/整合包作者提出「想用数据包自定义活物品**行为**」 | §3.5 datapack registry 从 P2 升 P0；并按被否选项的理由先确认结构已定案 |
| 客户端源码超过 ~100 文件（现 46） | §3.12 渲染分层立项 |
| 某个容器同步包导致客户端断开/截断 | §3.8 拆包器立项 |
| NeoForge 或原版大版本升级，mixin 大面积报错 | §3.1 立刻从 P0 升为**阻断项**（此时 mixin 内逻辑不可单测的代价集中爆发） |
| 要发布 1.0（结束 alpha） | §3.7 的 version 字段必须已在位，否则 1.0 后第一次结构变更无法平滑 |
| 有第三方想做 addon | §3.13 静态注册表原语（或 §3.2 事件）是前置条件 |
| **出现第 3 个"需要注册 + 排序 + 兜底"的扩展点** | **这就是重复实现的信号** ⇒ §3.13 立即立项（现在已有 4 处，已达标） |
| 出现"需要拒绝/校验第三方注册"的需求 | §3.14 升级为注册期事件（§3.2 复活），并**废弃**静态注册表那一条路径（不许双入口） |
| 出现「只能在真实游戏环境复现」的 bug（跨容器迁移 / 跨维度 / 真实 tick 序列） | §3.17 gametest 立项；先建 `Regressions` 类放回归用例 |
| 有玩家反馈"看不懂活物品怎么用" | §3.19 重新评估（但仍走"外部库 + Plugin"，不自研引擎） |

## 7. 待决问题池（本次未拍板）

| # | 问题 | 影响 | 备注 |
|---|---|---|---|
| Q1 | 注入契约接口放 `living/api/injection/` 还是就近各域包？ | §3.1 | 倾向前者 |
| Q2 | `package-info.java` 存量 `@Nullable` 一次性补完 vs 警告先放着？ | §3.6 | 需评估补完量级 |
| Q3 | 注册事件的命名与包位置？与并行会话的架构改动是否重叠？ | §3.2 | **执行前必须先确认** |
| Q4 | `InteractionRuleConfig` 的客户端同步时机（reload 是服务端行为）？ | §3.3 | 待查源码 |
| Q5 | 扩展点未命中时的兜底：`Optional.empty()` + debug 日志 vs no-op 实例？ | §3.4 | 倾向前者 |
| **Q6** | **4 个规则 JSON 是否迁 `data/living_item/`？** | §3.9 | 倾向"迁，但等真实需求触发"；先迁 `fluid_transforms` 一个 |
| **Q7** | 迁移后三级优先级（数据包 > 玩家 config > 内置默认）的合并语义？ | §3.9 | 与 §3.4 同构，`removed[]` 语义需一并定义 |
| **Q8** | `data/<modid>/` 下放非标准 JSON 是否真的不被原版解析？ | §3.9 | **必须实机验证后再动** |
| **Q9** | `SimpleRegistry` 原语放 `living/api/` 还是 `living/util/`？是否支持 tag provider？ | §3.13 | 倾向 `living/api/`，tag 先不做 |
| **Q10** | 要不要建 `living/impl/` + `@ApiStatus.Internal`？还是只标注不建包？ | §3.15 | 倾向先只标注（成本低），真有第二个 impl 类再建包 |
| **Q11** | 要不要上 gametest？（我们 475 JUnit / 0 gametest） | §3.17 | 倾向不为对齐而做；触发条件见 §6 |
| **Q12** | `tools/gen_code_map.py` 的**分层违规报告**要不要接进 `doc_check.py` / CI？ | §3.15 | Create 因没有机械检查而泄漏；我们有工具但没自动跑 ⇒ **潜在领先项** |

## 8. 文档跟进清单

- [ ] 本文落成后跑 `python tools/doc_check.py`（第 1 项校验路径真实性）
- [ ] 任一条落地时：**先更新本文的「定稿值」再动代码**（用户既定流程）
- [ ] §3.9 拍板后：同步改 `docs/tech/living-fluid-tech.md` §2.2 的内置路径描述
- [ ] 落地后按 [README.md](../README.md) §4 写 changelog 一行 + `AGENTS.md` 进展一行
- [ ] 全部完成或决定放弃某条 ⇒ 本文从 `docs/buffer/` 搬 `docs/archive/`，并在 `docs/decisions.md` 留 supersedes 链
- [ ] ⚠️ 若与并行会话的架构优化重叠 ⇒ **本文降级为背景与理由，正文迁对方文档**
