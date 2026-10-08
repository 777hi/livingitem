# components 去领域耦合方案（`LivingComponents` 拆解）

*创建: 2026-10-08 · 状态: 📋 **调研完成，待拍板**（尚未改任何代码）*

> ### 📌 当前状态：只出方案，未动代码
>
> | 项 | 状态 |
> |---|---|
> | 现状核算 | ✅ 35 个组件 / 123 个调用点 / 53 个文件 / **27 条 R1** |
> | 药方设计 | ✅ 见 §3（组件定义**进各 Data 类**，靠同 `<clinit>` 保证安全） |
> | 代码改动 | ❌ **未开始**（等拍板） |

---

## 0. 一句话结论

`LivingComponents` 是**当前 R1 最大的一块**（27 条，占剩余 61 条的 44%）。
它是 A1 迁移（2026-09-28）**有意集中**的结果 —— 而**集中的理由（消灭静态初始化时序问题）今天依然成立**
⇒ 所以拆解方案**必须保留那个保证**，不能只是「把常量搬走」。

---

## 1. 现状

**`LivingComponents`**（`living/components/`，321 行）：35 个注册项。

| 分组 | 个数 | 组件 |
|---|---|---|
| **框架级**（无领域类型） | **3** | `is_living` · `living_tool_owner` · `living_tool_owner_name` |
| tnt | 1 | `living_tnt_data` |
| furnace | 2 | `living_furnace_data` · `living_furnace_burning` |
| hopper | 2 | `living_hopper_data` · `living_hopper_filter` |
| water | 4 | `container_fluid_data` · `container_fluid_data_player` · `container_stress_data` · `living_water_wheel_data` |
| power | 4 | `container_phase_snapshot` · `living_generator_data` · `living_waxed_bulb_data` · `living_waxed_chiseled_data` |
| ender | 1 | `living_ender_chest_data` |
| redstone | **11** | `living_button_data` · `living_comparator_data` · `living_copper_bulb_data` · `living_copper_signal` · `living_cut_copper_data` · `living_grate_data` · `living_lever_data` · `living_redstone_data` · `living_redstone_lamp_data` · `living_redstone_torch_data` · `living_repeater_data` |
| farmland | 2 | `farmland_plant` · `living_farmland_moist` |
| tools | 5 | `living_tool_dig_ticks` · `living_tool_last_action` · `living_tool_memory` · `living_tool_progress` · `living_tool_ray_tuning` |

**影响面**：`LivingComponents.X` 共 **123 个调用点**，分布在 **53 个文件**。

**产生的边**：`components → living/domain/*` **27 条**（8 个领域、27 个不同的领域类）。

---

## 2. ⚠️ 动之前必须理解：A1 为什么集中

`LivingComponents` 自己的 javadoc 写着（原文）：

> ⚠️ **为什么常量集中在一个类：静态初始化顺序由 JVM 决定、无法声明 ——
> 一个类一个 `<clinit>` 原子完成，从结构上消灭注册时序问题。**

⇒ 这句话**今天依然成立**。而且 **2026-10-08 的 ⑤ 刚刚用血教训验证了它**：

> key 从「集中定义的懒加载类」挪到「领域静态字段」后，求值提前到类加载 ⇒
> 触发 `DeferredHolder.value()` 在注册完成前被读 ⇒
> `NullPointerException: Trying to access unbound value` ⇒ **测试 JVM 启动失败**。
> （见 [container-domain-decoupling-plan.md](container-domain-decoupling-plan.md) §10.1）

⇒ **所以本方案的成败判据是：拆完之后，注册时序是否仍然「一个 `<clinit>` 原子完成」。**

---

## 3. 药方：组件定义**放进各自的 `XxxData` 类**

### 核心洞察

现状里每个组件都被**它自己的 Data 类**使用：

```java
// LivingButtonData.of(stack) —— 同一个类里用同一个组件
return LivingItemManager.getData(stack, LivingComponents.LIVING_BUTTON_DATA.value(), DEFAULT);
```

⇒ 如果把组件定义**搬进 `LivingButtonData` 自己**，那么：
- 「定义组件」与「使用组件」落在**同一个 `<clinit>`** ⇒ 原子完成 ✓
- `of()` 里读的是**同类**的静态字段 ⇒ **不可能产生循环初始化** ✓
- 这正是 A1 那句话想保证的性质，只是粒度从「全库一个类」缩到「每个 Data 类」

### 具体形态

```java
// living/domain/redstone/LivingButtonData.java（组件定义进 Data 类）
public record LivingButtonData(boolean pressed, int pulseTimer, boolean isWood) implements TooltipProvider {

    /** 组件注册（2026-10-08：从 LivingComponents 拆回本领域）。 */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<LivingButtonData>> COMPONENT =
        RedstoneComponents.REG.register("living_button_data", () ->
            DataComponentType.<LivingButtonData>builder()
                .persistent(CODEC)
                .networkSynchronized(STREAM_CODEC)
                .build());

    public static LivingButtonData of(ItemStack stack) {
        return LivingItemManager.getData(stack, COMPONENT.value(), DEFAULT);   // 同类字段，无循环
    }
}
```

```java
// living/domain/redstone/RedstoneComponents.java（领域内只放「总线」）
public final class RedstoneComponents {
    public static final DeferredRegister<DataComponentType<?>> REG =
        DeferredRegister.create(Registries.DATA_COMPONENT_TYPE, LivingMod.ID);
}
```

```java
// living/components/LivingComponentRegistries.java（框架侧只认「总线」，不认识领域）
public final class LivingComponentRegistries {
    private static final List<DeferredRegister<?>> ALL = new ArrayList<>();
    public static void add(DeferredRegister<?> r) { ALL.add(r); }
    public static void registerAll(IEventBus bus) { for (var r : ALL) r.register(bus); }
}
```

领域在自己的 `*Registration` 里登记总线：

```java
// RedstoneRegistration.register()
LivingComponentRegistries.add(RedstoneComponents.REG);
```

⇒ `LivingComponents` 只保留 **3 个框架级组件**，`import domain.*` **全部消失** ✓

---

## 4. 分步实施计划

> 每步独立提交；硬门槛 = `551 全绿` + `check_layers` 下降 + `doc_check 9/9`。

| 步 | 内容 | 消边 | 改动面 |
|---|---|---|---|
| **1** | 加 `LivingComponentRegistries`（框架侧总线表）+ 各领域 `XxxComponents`（只放 REG）+ 在 `*Registration` 登记；`LivingItem` 改走 `registerAll(bus)` | 0 | 10 个领域 + `LivingItem` |
| **2** | **逐领域**把组件定义搬进 `XxxData`，同步改该领域的调用点 | 每领域 1~11 条 | 按领域分批 |
| **3** | `LivingComponents` 收尾：只剩 3 个框架级组件；改名/改注释为「框架级组件」 | — | 1 个文件 |

**建议领域顺序**（由小到大，先易后难）：
`ender`(1) → `tnt`(1) → `farmland`(2) → `furnace`(2) → `hopper`(2) → `water`(4) → `power`(4) → `tools`(5) → **`redstone`(11)**

⇒ 完成后 R1 **61 → 34**（-27）。

---

## 5. 风险与回滚

| # | 风险 | 缓解 |
|---|---|---|
| R1 | **静态初始化时序**（A1 集中正是为了防它） | 组件定义**与使用它的 `of()`/`set()` 同类** ⇒ 同 `<clinit>`；**禁止**把组件定义放到与 Data 类分离的第三方类里 |
| R2 | `DeferredRegister` 挂总线的**时机** | 各领域在 `*Registration.register()` 里 `add(REG)`；`LivingItem.commonSetup` 在**所有领域注册之后**调 `registerAll(bus)` |
| R3 | 123 个调用点漏改 | 逐领域分批，每批 `551 全绿` 兜底；编译期即报错（无静默失败） |
| R4 | 「框架级组件」误判（某些组件其实被领域用） | 按**组件的泛型类型**判：类型是领域类 ⇒ 归领域；是 `Boolean`/`UUID`/`String` ⇒ 留框架侧 |
| R5 | 领域内 `XxxComponents` 被 Data 类的 `<clinit>` 触发（反向） | `XxxComponents` **只含 REG 常量**（不引用任何 Data 类）⇒ 无反向边 |

**回滚**：每步一个提交，`git revert` 即可。

---

## 6. 验收

| 项 | 期望 |
|---|---|
| `./gradlew test --rerun` | **551 / 0 failures**（每步） |
| `check_layers.py` | R1 **61 → 34**；`components → domain` **归零** |
| 注册时序 | 测试 JVM 正常启动（**这是本方案的核心验收点**） |
| `doc_check.py` | 9/9 |

---

## 7. 待拍板

| # | 问题 |
|---|---|
| **P1** | **是否开工**？若开工，按 §4 的领域顺序分批做，还是先只做 1~2 个领域试水？ |
| **P2** | 组件定义的落点：**进 `XxxData` 类**（推荐 —— 同 `<clinit>` 最安全），还是独立 `XxxComponents` 类？ |
| **P3** | `LivingComponents` 拆分后**改名**吗？（如 `FrameworkComponents`，语义更准） |
