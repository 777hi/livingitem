# 单元测试指南


本项目的单元测试运行在 **FML 环境**中，可直接引用 Minecraft 类与本模组注册的 DataComponent。

```bash
./gradlew test                              # 默认套件（约 14s，增量 <1s；排除 @Tag("perf")）
./gradlew test -Pperf                       # 含性能量测（1202 项）
./gradlew test -Pperf --tests "*ContainerFluidPerfTest"  # 只跑量测
./gradlew test --tests "*ContainerRedstoneDataTest"      # 单个测试类
```

---

## 目录

- [环境配置](#环境配置)
  - [1. 启用 MDG 的 unitTest 支持](#1-启用-mdg-的-unittest-支持)
  - [2. jvmArgs 必须用 `+=` 追加](#2-jvmargs-必须用--追加)
  - [3. 客户端 Mixin 必须声明在 `client` 数组](#3-客户端-mixin-必须声明在-client-数组)
- [测试替身](#测试替身)
- [跨维度同步测试](#跨维度同步测试)
- [驱动容器级 tick](#驱动容器级-tick)
- [现有测试](#现有测试)
  - [被锁定的反直觉行为](#被锁定的反直觉行为)
- [测试台账与分层](#测试台账与分层)
  - [台账](#台账)
  - [分层](#分层)
- [可测性边界](#可测性边界)
- [管道性能基准（Q3，2026-11-15；Q4a 后读数口径见下方 2026-11-18 节）](#管道性能基准q32026-11-15q4a-后读数口径见下方-2026-11-18-节)
- [新增测试时](#新增测试时)
- [全量测试与复核口径](#全量测试与复核口径)
- [兼容下限核验](#兼容下限核验)
- [Mixin 接线与 mock Level](#mixin-接线与-mock-level)
- [测试 seam 与「改传输层必加的用例」](#测试-seam-与改传输层必加的用例)
- [Function 注册夹具隔离（2026-11-11）](#function-注册夹具隔离2026-11-11)

---

## 环境配置

ModDevGradle 的测试环境需要三处配置，缺一不可。

### 1. 启用 MDG 的 unitTest 支持

```groovy
neoForge {
    mods {
        "${mod_id}" { sourceSet(sourceSets.main) }
    }

    unitTest {
        enable()
        testedMod = mods."${mod_id}"
    }
}
```

> ⚠️ **不要用 `configurations { testImplementation.extendsFrom implementation }`**
> MDG 通过独立机制注入 Minecraft 依赖，不走 `implementation` 配置。
> 这样写会让测试 classpath 里完全没有 Minecraft，报
> `package net.minecraft.core does not exist`。

### 2. jvmArgs 必须用 `+=` 追加

```groovy
test {
    useJUnitPlatform()
    jvmArgs += ['-Xmx1G', '--add-opens=java.base/java.lang.invoke=ALL-UNNAMED']
}
```

用 `=` 赋值会覆盖 MDG 为 FML 引导注入的参数，导致测试 JVM 启动失败：

```
java.lang.reflect.InaccessibleObjectException: Unable to make field
static final java.lang.invoke.MethodHandles$Lookup.IMPL_LOOKUP accessible
```

### 3. 客户端 Mixin 必须声明在 `client` 数组

测试环境的 dist 是 `DEDICATED_SERVER`。若客户端 Mixin 放在双端加载的 `mixins` 数组里，
FML 会尝试在服务端加载它并直接崩溃：

```
Attempted to load class com/qiqi/li/client/mixin/RecipeBookComponentMixin
for invalid dist DEDICATED_SERVER
```

正确写法（`living_item.client.mixins.json`）：

```json
{
  "package": "com.qiqi.li.client.mixin",
  "client": [
    "AbstractContainerScreenMixin",
    "RecipeBookComponentMixin"
  ]
}
```

> 📌 这不只是测试问题 —— 专用服务器启动时会以同样方式崩溃。

---

## 测试替身

[`FakeContainerContext`](../../src/test/java/com/qiqi/li/testutil/FakeContainerContext.java)
用 `ItemStack[]` 实现 `ContainerContext`，不依赖 `Level` / `BlockEntity` / `IItemHandler`。

```java
var ctx = new FakeContainerContext(27, 9);   // 27 格，9 列
ctx.set(10, living(Items.REDSTONE_BLOCK, 1));
ctx.set(11, living(Items.REDSTONE, 1));

// syncSlotToClients 的调用会记录到 ctx.syncedSlots，可断言同步行为
```

活物品需要 `IS_LIVING` 标记，否则 `isLivingItem()` 返回 false：

```java
private static ItemStack living(Item item, int count) {
    ItemStack stack = new ItemStack(item, count);
    LivingItemManager.setLiving(stack, true);
    return stack;
}
```

---

## 跨维度同步测试

任何由 `LivingItem.onServerTick` 的维度循环逐维度调用 `flush(ServerLevel)` 的同步器，
都必须有一条「两个维度同时存在」的用例。只用一个维度测不出跨维串台（K2、信标玩家 / 实体、
信标箱子三次事故都是这样漏过的）。

两条硬要求：

1. **两个 `ServerLevel` 替身**（`overworld` / `nether`），分别用各自的 `getGameTime()`；
2. **两个维度用同一个坐标** —— 跨维误伤只在同坐标时才看得见。

最小驱动序列：

```java
sync.beginTick();          // 有 beginTick 的同步器；必须在两次 flush 之前
sync.flush(overworld);
sync.flush(nether);
```

至少断言三件事：

- **登记不被抽走**：两维都 `report` 后先 flush overworld，nether 的条目仍应在本维 flush 时发出；
- **节流不被重置**：overworld 在 t=1000 首次开盖，插一次 `flush(nether)`，t=1010 再 flush
  overworld 不应重发，t=1020 才重发；
- **动作不落错维度**：`verify(nether, never()).blockEvent(eq(SHARED_POS), any(), anyInt(), anyInt())`
  （按实际动作替换 `blockEvent`）。

现成样板：`LivingToolHostSyncDimensionTest`、`BeaconPlayerHostSyncTest`、
`BeaconEntityHostSyncTest`、`BeaconChestHostDimensionTest`（都用 Mockito 替身 + 共享坐标）。

---

## 驱动容器级 tick

`ContainerRedstoneData.calculate()` 依赖 `TickContext.getFunctionSlots()` 得知各功能占用的槽位，
这一步在生产代码里由 `ContainerLivingItemHandler.processContext` 填充。测试中需手动模拟：

```java
private static void tickOnce(ContainerRedstoneData data,
        FakeContainerContext ctx, Map<String, Set<Integer>> functionSlots) {
    TickContext tick = new TickContext(ctx);
    tick.setFunctionSlots(functionSlots);
    data.resetProcessedFlag();   // 否则第二次 calculate 会被 processedThisTick 挡掉
    data.calculate(ctx, tick);
}
```

调用时用各 Function 的 `ID` 常量作为 key：

```java
tickOnce(data, ctx, Map.of(
    LivingRedstoneBlockFunction.ID, Set.of(10),
    LivingRedstoneFunction.ID, Set.of(11)));
```

> ⚠️ 跨 tick 场景需注意 `calculate()` 内部会交换 `edgeGrid` / `prevEdgeGrid` 双缓冲。
> 如果只想验证单次传播结果，直接构造目标状态（如拉杆一开始就是 powered）比
> "先 tick 一次再改状态再 tick" 更可靠。

---

## 现有测试

> 用例数会随开发变化，**不在此维护**。全量数据由 `python tools/test_inventory.py` 生成
> （`build/test-inventory.md`：类 / 用例数 / 断言 / Tag / Disabled / known-gap / 是否走 `processContext`）。
> 本节只列可测性示范，不回答“有多少条”。

| 测试类 | 类型 | 覆盖内容 |
|--------|------|----------|
| [ContainerRedstoneDataTest](../../src/test/java/com/qiqi/li/living/domain/redstone/ContainerRedstoneDataTest.java) | 领域状态机 | 信号上限规则、红石块/拉杆信号源、链式衰减、红石灯点亮、堆叠数影响、信号归零、网格重建 |
| [ContainerCompatibilityConfigTest](../../src/test/java/com/qiqi/li/living/transfer/ContainerCompatibilityConfigTest.java) | 契约 / 配置 | 列数推断、宿主槽位、方向偏移、Builder 校验 |
| [MapCoordHelperTest](../../src/test/java/com/qiqi/li/living/domain/map/MapCoordHelperTest.java) | 纯函数 / 数学 | UV 换算、展示框朝向×旋转组合、地图中心网格对齐、边界射线距离 |

### 被锁定的反直觉行为

这几条容易在重构时被"顺手改对"，实际会破坏现有功能：

- **`findOrGenerateRule(40)` → 10 列**，不是 8 列。
  `resolveColumns` 从 `min(size,13)` 向下找第一个整除的宽度，40 依次试 13/12/11 都不整除，10 命中。
- **`findOrGenerateRule(17)` → 1 列**。质数且 >13，只有 1 能整除，退化为单列。
- **`calculateMapCenterCoord(-1, 0)` → -64**，不是 64。
  用的是 `Math.floor` 而非整数除法，负坐标向下取整。
- **`getSignalCap(1)` → 15，`getSignalCap(2)` → 4**。
  1 个是特例，其余是堆叠数的平方，所以 2 个红石粉的上限反而低于 1 个。

---

## 测试台账与分层

### 台账

```bash
python tools/test_inventory.py                    # 生成 build/test-inventory.md
python tools/test_inventory.py --unreferenced     # 附带主类未引用清单（静态近似）
```

台账是只读的：用例数取 `build/test-results/test/*.xml`（含参数化展开与 `@Nested`），
类型是按包名/类名的启发式归类；**不要手改生成的文件**。

### 分层

| 层 | 名称 | 放置 / 运行 |
|---|---|---|
| L1 | 纯函数 / 数学 | 现有 `*Test`，每次 `test` |
| L2 | 领域状态机 | 现有 `*Test`，每次 `test` |
| L3 | 接缝 / 契约（能力面、访问器、回滚） | 现有或新增 `*Test`，每次 `test` |
| L4 | 集成 / 端到端（`processContext`、真实菜单） | `*IT` / `*IntegrationTest`，每次 `test` |
| L5 | 游戏内验证（tag 依赖、渲染、手势、落盘） | 文档清单，发版前 |

上表 L1~L5 分层已定案；当时的梳理过程见 [测试域梳理](../archive/2026-10-29-测试域梳理.md)（已归档）。
known-gap 必须带 ID 且不得绿色通过（原口径同该文档 §5）。

---

## 可测性边界

**可以直接测**：不依赖 `Level` / `MapItemSavedData` / 注册表查询的纯逻辑。
`ContainerRedstoneData.calculate`、`ContainerCompatibilityConfig`、
`MapCoordHelper` 的算术方法、`ContainerContext.getNeighbors` 都属于这一类。

**难以直接测**：`MapCoordHelper` 中接收 `MapItemSavedData` 的方法
（`isExplored` / `findBannerHit` / `uvToWorldPos` 等）需要真实存档数据。
`ContainerLivingItemHandler` 的静态方法配合静态缓存，也不便于隔离。

**联动测试边界（2026-11-17 修订）**：不为第三方模组（Create 等）的真实行为造替身；实际网络传播、
Mixin 应用与重载时序仍靠实机验收。若先以项目自有端口隔离第三方 API，则可以用记录型 fake 测
**我方策略**（状态迁移、命令顺序、失败隔离），但 fake 不得模拟或证明第三方内部语义。
例如 `StressStateMachineTest` 只守向 `KineticOps` 发出的命令，不能代答 Create 动力网。

若确实需要在服务端上下文中测试，MDG 提供了 testframework：

```groovy
testImplementation "net.neoforged:testframework:<neoforge version>"
```

```java
@ExtendWith(EphemeralTestServerProvider.class)
class SomeTest {
    @Test
    void test(MinecraftServer server) { /* ... */ }
}
```

---

## 管道性能基准（Q3，2026-11-15；Q4a 后读数口径见下方 2026-11-18 节）

`ContainerPipelinePerfTest` 量的是完整 `ContainerLivingItemHandler.processContext` 管道
（不是 `ContainerFluidPerfTest` 的流体引擎）。`@Tag("perf")` 默认排除，只在 `-Pperf` 时跑：

```bash
./gradlew test -Pperf --tests "*ContainerPipelinePerfTest" --rerun
```

结果落盘在 **`build/minecraft-junit/build/_perf_pipeline.txt`**（测试工作目录是
`build/minecraft-junit`，所以代码里的相对路径 `build/...` 会落到那一层；2026-11-18 起每轮量测
**先截断再追加**，同文件不再混入历史轮次）；分阶段明细看同轮日志的 `[Phases]` 行
（scan / func_tick / container_data / stress / flush_slots）。

| 项 | 口径 |
|---|---|
| 场景 | 空容器 / 普通容器（3 件普通物品）/ 复杂容器（6 枚活红石粉 + 2 件普通物品） |
| 规模 | 1k / 5k / 10k 个 `SimpleContainerContext`，每个 54 槽（大箱子最坏形态） |
| 采样 | 200 容器 × 30 轮 JIT 预热；每组清缓存、冷启动 1 轮 + 稳定 2 轮（均不计入）；正式轮数 `max(8, 100000/N)`，取每轮耗时中位数与 P90 |
| 断言 | 只防病态回归：每容器每拍 < 1000 µs；**不卡 CI 耗时** |

**2026-11-15 基线（Q2 前历史读数，仅留痕不作为现行对比基准）**
（本机 FML 测试环境，JDK 21.0.11 / Windows 11；「每拍」= 处理该规模全部容器一轮）：

| 场景 | 1k | 5k | 10k | P90 @10k | 每容器每拍 @10k |
|---|---:|---:|---:|---:|---:|
| 空容器 | 1.63 ms | 5.19 ms | **9.31 ms** | 9.67 ms | 0.93 µs |
| 普通容器 | 1.09 ms | 6.47 ms | **11.07 ms** | 11.89 ms | 1.11 µs |
| 复杂容器 | 3.94 ms | 19.08 ms | **39.63 ms** | 48.20 ms | 3.96 µs |

**2026-11-17 Q2 观测（历史读数）**（签名含全组件+数量、扫描合一后；两轮独立运行）：

| 场景 | 10k 每拍中位 | `[Snapshot]` 计数器观测 |
|---|---:|---|
| 空容器 | 18.84 / 20.31 ms | 一次稳态窗口：rebuilds=0, hits=100000 |
| 普通容器 | 24.66 ms | 一次稳态窗口：rebuilds=0, hits=100000 |
| 复杂容器 | 82.56 / 85.46 ms | 同轮不同窗口出现 0/100000、10000/5000、20000/10000 等读数 |

**读数边界**：这些轮次与 2026-11-15 基线的绝对耗时差异很大，且同日独立运行也明显波动，
没有受控 A/B（相同代码、相同进程、交替采样）就**不能**把差值归因于 Q2、环境噪声或某个阶段；
因此不据此宣称性能升降。`[Snapshot]` 只计「重建/复用结果」，**不记录失效原因**，所以不能仅凭
它断言重建由哪种组件写入驱动。它能直接回答的只有：该测量窗口内发生了多少次重建与复用；
如需归因，须另加按 revision 来源分类的计数器后再测。

**2026-11-18 Q4a 观测**（静息快路径落地后；与 2026-11-17 同环境、同口径，结果文件已改截断式）：

| 场景 | 10k 每拍中位 | 相对 2026-11-17 读数 | 读法 |
|---|---:|---|---|
| 空容器 | 6.03 ms | 14.72 → 6.03 ms | 稳态轮全部走静息快路径（首拍完整、之后只跑扫描前缀 + 静息判定） |
| 普通容器 | 8.62 ms | 15.37 → 8.62 ms | 无活物品 ⇒ 同空容器，快路径生效 |
| 复杂容器 | 47.76 ms | 58.58 → 47.76 ms | 有活物品 ⇒ 永不静息，走完整管道；**该差值属环境波动，不可归因于 Q4a**（前后都是完整管道） |

⚠️ Q4a 改变了「空/普通容器每拍做什么」：这两类场景的稳态轮量到的**不再是完整管道**，而是
「扫描 + 签名 + 生命周期追踪 + 静息判定」的前缀；与历史读数**不可直接相减**。跨轮归因仍受
环境噪声限制（同上边界），`[IdleSuffix] full/skipped` 计数器报告每窗口实际走了多少完整拍。

读法与结论：

- 每容器单价在三个规模上基本稳定，线性度好 ⇒ 可按单价外推，但**外推值不等于真机**。
- Q4a 前：三个自维持功能每容器每拍各跑一次，空容器也不例外；Q4a 后只有**静息判定**仍每拍执行，
  完整管道只在「有活物品 / 有持续流体工作 / 闩锁未结算」时运行。
- 有活物品的复杂场景仍走完整管道，是 Q4a 环境噪声的最佳对照场景。
- 参考刻度（**不是验收阈值**）：一个 tick 为 50 ms。性能基准只作仪表盘读数，不设 tick 预算、不需要约定数值。

⚠️ **边界**：单测没有真实 `Level` / 方块实体 / 玩家列表，量不到 `onServerTick` 的区块缓存遍历、
BE 能力查询、去重 Set 与玩家背包循环；依赖 `level.isClientSide` 的领域功能（活漏斗等）也进不了基准。
数字只用于**同一环境下比较优化前后**，不能换算成真机 MSPT；真机开销仍要专用服务端实测。

---

## 新增测试时

1. 优先测**纯逻辑**：多分支状态机、坐标/网格换算、边界推断。这类代码 bug 密度最高、手测最难覆盖。
2. 对反直觉的现有行为，**先跑一遍确认实际值再写断言**，不要凭公式推算。
3. ⚠️ **新增"守卫用例"后必须做负向验证**：把实现**临时改成错误实现**（旧行为可回退就回退；
   新机制没有旧行为，直接改坏实现），确认新用例**真的会 FAIL**，
   再还原（还原后 `grep` 核对关键行 + 重跑确认变绿，临时备份删掉）。
   **只报"新用例 PASSED"没有任何信息量** —— 它可能只是恒真（断言写成了实现本身的复述）。
   2026-09-22 的 `affects_coversEveryBlockInsideSphere` 就是这么验的：旧判据下 `Task :test FAILED`。
   2026-11-18 的鞍槽偏移变异是「新机制改坏」的实例：拆掉 `EntityContainerContext#setItem`
   的偏移，`moveWholeStack_conserves` 仅 ENTITY 实例红。
4. ⚠️ **别把"既有行为"当规格钉住** —— 钉之前先确认既有行为**是对的**。
   否则错误行为会被测试保护起来，后来者不敢改、bug 长期潜伏。
   判断依据：**这条行为有没有物理/语义上的理由？** 只是"旧代码就这么写的"不算理由。
   （2026-09-22 实例：`ExplosionParams.affects` 的「区块**中心**在半径内」判据被用例
   `affects_matchesLegacyCriterion` 钉住、注释还写着"保持不动以免改变既有行为"，
   结果**坑不圆、边缘残留整块区块**长期没被发现。已改为「区块 AABB ∩ 球体」。
   见 `living-tnt-tech.md` §4.3。）
   与上一条「被锁定的反直觉行为」的区别：**那节锁的是"反直觉但正确"的，这节防的是"锁错"**。
5. **断言物理结果，不断言内部计数器**：断「世界里发生了什么 / 物品去哪了」，
   不断「内部字段走了几次」——内部计数是实现细节，重构即碎（2026-10-09 定案）。
6. **断言失败信息带上实际值**（`"实际=" + actual`）：中文测试名在 Windows 控制台会乱码，
   只能靠失败信息定位。

---

## 全量测试与复核口径

```bash
./gradlew test --rerun 2>&1 | grep -aE "FAILED|error:|BUILD"
```

- ⚠️ **`./gradlew test` 若显示 `FROM-CACHE` / `UP-TO-DATE`，就是没有真跑**（命中构建缓存），
  报 `BUILD SUCCESSFUL` 也是**假绿**。**复核必须加 `--rerun`。**
- **数用例只认 `build/test-results/test/*.xml` 的 `tests=` 求和**（脚本口径；也可看
  `python tools/doc_check.py` 第 2 项输出或 `build/verification/full-test-summary.json`）：

  ```bash
  grep -ho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' \
    build/test-results/test/*.xml | awk -F'"' '{t+=$2;f+=$6;e+=$8} END {print t, f, e}'
  ```

- **条数不写进任何文档**（2026-11-09 起，D-doc-08）：`gen_test_summary.py` 在提交前由
  hook / CI 自动把**全量**结果写成独立快照；`--tests` 局部运行不覆盖它 ⇒ 改文档不必先跑全量。
- ⚠️ **本地门禁只证明「最近一次全量测试全绿」，不证明「当前代码已测」** ——
  改了代码要自己跑 `./gradlew test --rerun`；CI 的 `./gradlew build` 是当前代码的最终防线。
- ⚠️ **别用 `grep -c "@Test"` 数方法数**：带 `@ParameterizedTest` 的类**方法数 ≠ 用例数**
  （本项目已有 3 个类差 5~13 项；按方法数加总会少 23）。参数化展开后的调用次数才是用例数。
- 结果文件数 ≠ 源文件数（`SimpleContainerContextTest` 有 `@Nested` 内部类 → 1 个源文件出多个结果文件）；
  全量判据用的是「结果文件数 ≥ 测试类数」，不是相等。
- **三个判据互相独立**（2026-10-27 审查定案）：① 测试类**存在** ② 跑过且**全绿**
  ③ 这条规则**被某个用例覆盖**——有 ① 不等于有 ②③；判「守住了」必须三查。
- **改前取基线**：重构 / 大改前先跑全量并留存失败清单；改后失败集合必须与基线**一致**
  （新增失败 = 改坏了；基线内既有失败另行处置，不许混进「本次改坏」里）。

## 兼容下限核验

`gradle.properties` 的 `neo_version` **同时**是编译版本与写进 `neoforge.mods.toml` 的对外下限
（`versionRange="[${neo_version},)"`）—— 刻意只用这一个值。

- **为什么不合两个属性**：拆成「编译版本 / 承诺下限」后，两套版本共用同一份产物缓存，
  缓存串了不会立刻炸，而是悄悄产出错产物 —— 本地假绿、CI 假红（2026-10-21 实测：
  219 的 NeoForge jar 配上了缓存里 249 的 patched MC jar ⇒ 编译器报「找不到
  `TooltipFlagExtension`」，而 219 根本没有这个类）。同源 ⇒ 结构上不可能「承诺 A、编在 B」。
- **核验方式**：CI 的 `build` 作业编译的就是下限 ⇒ 每次 push 都在核验承诺，无需第二个作业。
- **改下限的流程**：改 `neo_version` → 本地跑 `./gradlew build`（含全量测试）→ 提交。
  ⚠️ 换版本后本地/CI 的产物缓存里还留着旧版本的 patched MC jar ⇒ CI 作业会
  「版本变了就清一次产物缓存」自愈；本地若报莫名其妙的「找不到某个类」，先清
  `~/.gradle/caches/neoformruntime/intermediate_results` 再重跑。
- ⚠️ **能编译 ≠ 能跑**：下限能不能用，靠 `build` 作业真跑一遍来判，别靠推断
  （`TooltipFlagExtension` 就是 21.1.249 才引入的，249 之前 `TooltipFlag` 是原版接口）。

## Mixin 接线与 mock Level

**单元测试 JVM 里 Mixin 是被应用的**（FML 测试环境；证据：栈里出现
`BlockItem.handler$zzk000$create$fixDeployerPlacement`）⇒ **可以写端到端用例证明 Mixin 接线**，
不必等游戏启动：

```java
((BlockItem) Items.FARMLAND).place(context);        // 触发注入的逻辑
Mockito.verify(level).setBlock(expectedPos, expectedState, 11);
```

**mock `Level` 做「放置」的要点**（踩过才通）：

- `BlockPlaceContext.canPlace()` 要求**落点可替换** ⇒ 落点在放置前必须是空气、放置后才是方块
  ⇒ 用 `thenAnswer` + `AtomicBoolean` 模拟状态迁移；**固定 stub 过不了**。
- `CropBlock.canSurvive` 还要查光照 ⇒ 必须 stub `getRawBrightness(...) >= 8`。
- 还需 stub `enabledFeatures()` / `isUnobstructed(...)` → true / `setBlock(...)` → true。
- `player` 传 `null` 是安全的（`CollisionContext.empty()`；`ItemStack.consume` 对 null 安全）。
- `popResource` 依赖 `level.random` / gamerules，mock 下会 NPE ⇒ 这类分支交游戏实测。

**`Level.isClientSide` 是 `public final` 字段**（方法 `isClientSide()` 只是读它）。Mockito 只能
mock 方法 ⇒ 单测里写字段式会让客户端守卫**永远不触发**（表现为「测试红但代码看着完全对」）。
统一用方法式。

## 测试 seam 与「改传输层必加的用例」

- **测试 seam 约定**：需要被单测直接驱动的内部方法，**留包级可见即可**，不必为测试加 public API
  （先例：`CrossContainerTransfer.tryPushToNeighbor`、`LivingFarmlandFunction.tryOutputOnce`）。
- **改传输 / 交互层时必加**：
  - `isEligibleCargo` 真值表；活物品货物**不入空槽、不与同种普通堆合并**、无交互匹配的活物品全拒
  - 活物品货物在**每个入口**都被拒（`SlotInteractionCargoGateTest` 形态）
  - **普通货物与活物品货物各测一遍** —— 只测普通货物会漏掉「准入与访问器工厂同口径」这类 bug
    （活物品在 `SlotAccessorFactory.create` 处返回 null，与交互分发的拦截点不同）
  - **方向化单测**：两个分发入口 + 各方向各覆盖一遍（`CrossContainerTransferFertilizeTest` 形态）


## Function 注册夹具隔离（2026-11-11）

FML 单测启动已注册正式功能；测试不得再次追加相同 ID，`registerFunction` 会在变更之前拒绝。只需查询正式功能的测试直接使用原基线；需要自定义夹具时使用**测试源** `LivingItemRegistrationScope`，不调用领域 Registration 重装 hooks/handlers。

```java
try (var scope = LivingItemRegistrationScope.isolated(List.of(testFunction))) {
    // 真实注册与派发；结束（含异常）恢复原表
}
```

集成测试要保留正式驱动时用 `withAdditional(uniqueProbe)`；不能只装 probe 而丢掉红石/流体自维持功能。类级夹具在 `@BeforeAll` 安装、`@AfterAll` 关闭，修改全局注册表的类加 `@Isolated`，避免外部开启 Jupiter 并行后撞到其它读取者。

作用域保存原 Function 实例与顺序、适用缓存列表与自维持原视图；恢复回填原集合，不重注册、不排序、不重跑声明回调。夹具安装失败也恢复。它不清世界/路由/内容缓存，原测试的其他清理责任保持不变；不把持久注册表加入服务器退出清理。

包内 snapshot/restore 接缝住 Manager，跨包访问仅由测试源桥接；无生产 public reset、无反射私有字段。异常恢复、重复注册前零变更与活视图保持由 `LivingItemRegistrationTest` 守卫。
