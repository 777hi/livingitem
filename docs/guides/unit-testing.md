# 单元测试指南

*最后更新: 2026-08-22*

本项目的单元测试运行在 **FML 环境**中，可直接引用 Minecraft 类与本模组注册的 DataComponent。

```bash
./gradlew test                              # 全部测试（约 20s，增量 <1s）
./gradlew test --tests "*ContainerRedstoneDataTest"   # 单个测试类
```

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

> 全量清单（含每类用例数）以 [AGENTS.md](../../AGENTS.md) 的测试树为准，本节仅列可测性示范用的三类。

| 测试类 | 数量 | 覆盖内容 |
|--------|------|----------|
| [ContainerRedstoneDataTest](../../src/test/java/com/qiqi/li/living/domain/redstone/ContainerRedstoneDataTest.java) | 29 | 信号上限规则、红石块/拉杆信号源、链式衰减、红石灯点亮、堆叠数影响、信号归零、网格重建 |
| [ContainerCompatibilityConfigTest](../../src/test/java/com/qiqi/li/living/transfer/ContainerCompatibilityConfigTest.java) | 14 | 列数推断、宿主槽位、方向偏移、Builder 校验 |
| [MapCoordHelperTest](../../src/test/java/com/qiqi/li/living/domain/map/MapCoordHelperTest.java) | 29 | UV 换算、展示框朝向×旋转组合、地图中心网格对齐、边界射线距离 |

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

## 可测性边界

**可以直接测**：不依赖 `Level` / `MapItemSavedData` / 注册表查询的纯逻辑。
`ContainerRedstoneData.calculate`、`ContainerCompatibilityConfig`、
`MapCoordHelper` 的算术方法、`ContainerContext.getNeighbors` 都属于这一类。

**难以直接测**：`MapCoordHelper` 中接收 `MapItemSavedData` 的方法
（`isExplored` / `findBannerHit` / `uvToWorldPos` 等）需要真实存档数据。
`ContainerLivingItemHandler` 的静态方法配合静态缓存，也不便于隔离。

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

## 新增测试时

1. 优先测**纯逻辑**：多分支状态机、坐标/网格换算、边界推断。这类代码 bug 密度最高、手测最难覆盖。
2. 对反直觉的现有行为，**先跑一遍确认实际值再写断言**，不要凭公式推算。
3. ⚠️ **新增"守卫用例"后必须做负向验证**：把实现**临时改回旧行为**，确认新用例**真的会 FAIL**，
   再还原（还原后 `grep` 核对关键行 + 重跑确认变绿，临时备份删掉）。
   **只报"新用例 PASSED"没有任何信息量** —— 它可能只是恒真（断言写成了实现本身的复述）。
   2026-09-22 的 `affects_coversEveryBlockInsideSphere` 就是这么验的：旧判据下 `Task :test FAILED`。
4. ⚠️ **别把"既有行为"当规格钉住** —— 钉之前先确认既有行为**是对的**。
   否则错误行为会被测试保护起来，后来者不敢改、bug 长期潜伏。
   判断依据：**这条行为有没有物理/语义上的理由？** 只是"旧代码就这么写的"不算理由。
   （2026-09-22 实例：`ExplosionParams.affects` 的「区块**中心**在半径内」判据被用例
   `affects_matchesLegacyCriterion` 钉住、注释还写着"保持不动以免改变既有行为"，
   结果**坑不圆、边缘残留整块区块**长期没被发现。已改为「区块 AABB ∩ 球体」。
   见 `living-tnt-tech.md` §4.3。）
   与上一条「被锁定的反直觉行为」的区别：**那节锁的是"反直觉但正确"的，这节防的是"锁错"**。

---

## 全量测试与复核口径

```bash
./gradlew test --rerun 2>&1 | grep -aE "FAILED|error:|BUILD"
```

- ⚠️ **`./gradlew test` 若显示 `FROM-CACHE` / `UP-TO-DATE`，就是没有真跑**（命中构建缓存），
  报 `BUILD SUCCESSFUL` 也是**假绿**。**复核必须加 `--rerun`。**
- **数用例只认 `build/test-results/test/*.xml` 的 `tests=` 求和**：

  ```bash
  grep -ho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' \
    build/test-results/test/*.xml | awk -F'"' '{t+=$2;f+=$6;e+=$8} END {print t, f, e}'
  ```

- ⚠️ **别用 `grep -c "@Test"` 数方法数**：带 `@ParameterizedTest` 的类**方法数 ≠ 用例数**
  （本项目已有 3 个类差 5~13 项；按方法数加总会少 23）。参数化展开后的调用次数才是用例数。
- 结果文件数 ≠ 源文件数（`SimpleContainerContextTest` 有 `@Nested` 内部类 → 1 个源文件出多个结果文件）。
- 跑完**核对文档里的测试条数**与实测一致（文档即规范，条数漂移会被当成陈旧信息）。

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
   本项目的列数推断和信号上限都有特例。
3. 断言失败信息里带上实际值（`"实际=" + actual`），否则中文测试名在 Windows 控制台会乱码，
   只能靠失败信息定位。
