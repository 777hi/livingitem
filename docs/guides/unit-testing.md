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

| 测试类 | 数量 | 覆盖内容 |
|--------|------|----------|
| [ContainerRedstoneDataTest](../../src/test/java/com/qiqi/li/living/domain/redstone/ContainerRedstoneDataTest.java) | 19 | 信号上限规则、红石块/拉杆信号源、链式衰减、红石灯点亮、堆叠数影响、信号归零、网格重建 |
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
   本项目的列数推断和信号上限都有特例。
3. 断言失败信息里带上实际值（`"实际=" + actual`），否则中文测试名在 Windows 控制台会乱码，
   只能靠失败信息定位。
