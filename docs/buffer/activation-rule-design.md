<!-- markdownlint-disable -->

# 活化规则 JSON（D1）设计稿

*创建: 2026-09-27 · 状态: **未实现**（设计评审中，未开工）*

> ⚠️ **中间层（`docs/buffer/`）**：本文是**设计稿，不是现状**。
> 实施前不得当现状引用。
>
> 📄 归属：`open-plan.md` §4 **D1**。契约层（已生效的部分）见
> [`api-contract.md`](../system-design/api-contract.md)。

---

## 0. 需求与人群

允许整合包作者 / 服务器主 **不写一行 Java**，通过 JSON 控制「哪些物品可以被活化」。

| 场景 | 谁 | 他做什么 |
|---|---|---|
| 平衡性：禁止 OP 物品被活化 | 整合包作者 | 加一条 `deny` |
| 只开放一小批物品 | 硬核整合包 | `default: deny` + 几条 `allow` |
| 修掉「误点按钮把物品变活」 | **默认行为**（见 §2） | 什么都不用做 |

---

## 1. ⚠️ 先定边界：活化有 3 个入口，只能拦 1 个

这是本设计**最容易被做错**的地方 —— 清单必须精确：

| # | 入口 | 位置 | 途径标识 | 该不该被禁 |
|---|---|---|---|---|
| 1 | **玩家点活按钮** | `LivingTagPacket.java:79` | `player` | ✅ **要禁** |
| 2 | 活锄头耕地产出活耕地 | `TillToFarmlandHandler.java:41` | `internal` | ❌ 不该禁（模组内部产出，禁了功能就坏） |
| 3 | 活地图事件创建活地图 | `LivingMapEventHandler.java:227` | `internal` | ❌ 不该禁（同上） |
| 4 | 任务奖励 / 命令给予 / 掉落 | *外部，尚未实现* | `external` | ❌ **不该禁 —— 这正是整合包作者的发放渠道** |

> ⭐ **本功能的真实目的**（2026-09-27 用户澄清）：
> **只封「玩家自己动手活化」这一条非作弊途径**，让整合包作者能
> 「黑名单某个物品 + 通过任务奖励发放该活物品」。
> ⇒ 若规则把途径 4 也禁掉，这个功能就自相矛盾了。

**⇒ 途径是一等公民。** 判定点不能散在各个入口（未来每加一个途径都得记得补判定，迟早漏），
应收口到一个 **`LivingItemActivation` 门面**：每个途径调用时自报身份，门面统一判定。

> ⚠️ **已否决的早期草案**：曾写「判定点放在 `LivingTagPacket` 的处理链路里」——
> 那样无法区分途径，也无法支持未来的新活化机制。

**未来途径**（现在只预留，不实现）：
「活化其它物品的活物品」/「取消活化活物品的活物品」⇒ 只需往 `via` 枚举加值。

---

## 2. 建议顺手修的默认行为：拒绝「无功能认领」的活化

这是当前 **P1-3 的本体**：`LivingButton.onPress` 无条件活化任何手持物品，
活化后物品进入**活物品隔离**（不传输 / 不熔炼 / 不作燃料），
但没有任何 `LivingItemFunction` 认领它 ⇒ **纯副作用，零收益**。
第三方会以为是自己模组的 bug。

### 2.1 一个技术陷阱（决定了实现方式）

直观写法是 `getApplicableFunctions(stack).isEmpty()`，但**行不通**：

```java
// LivingItemManager.java:373
public static List<LivingItemFunction> getApplicableFunctions(ItemStack stack) {
    if (!isLivingItem(stack)) return List.of();     // ← 未活化的物品在这里就返回空了
```

而 21/22 个 `canApply` 内部**都含有** `isLivingItem(stack)`
（如 `LivingFurnaceFunction.java:45`：`stack.is(Items.FURNACE) && LivingItemManager.isLivingItem(stack)`）
⇒ 对未活化的物品，任何 function 都不会认领，判定恒为「无功能」。

### 2.2 方案：`copy()` 探针

```java
/** 若这个物品被活化，会不会有任何功能认领它？【活化前的预判】 */
public static boolean hasAnyFunctionFor(ItemStack stack) {
    if (isLivingItem(stack)) return !getApplicableFunctions(stack).isEmpty();
    ItemStack probe = stack.copy();          // 试探：临时标记 IS_LIVING 再查
    probe.set(IS_LIVING.value(), true);
    return !getApplicableFunctions(probe).isEmpty();
}
```

| | 评价 |
|---|---|
| 优点 | **零改动**那 22 个 `canApply`；语义完全正确（走真实的 `canApply`） |
| 成本 | 一次 `copy()` —— 只在玩家点按钮时发生，非热路径，可接受 |
| 备选（否决） | 给 `LivingItemFunction` 加 `wouldApplyIfActivated()` ⇒ 要改 22 个类，且多一个与 `canApply` 语义重叠的契约，容易漂移 |

> 这条默认行为**本身就能修掉 P1-3 的绝大部分**，且零配置。
> JSON 是叠加在它之上的**额外控制权**。

### 2.3 组件解耦现状 —— 为什么活箱子是唯一例外

**结论**：活化 NBT（`IS_LIVING`）与功能 NBT **已经是解耦的** ——
功能 NBT 是 **tick 时惰性创建**的，只带 `IS_LIVING` 的物品大多数能正常工作。
**但活箱子是唯一例外**，根因是它用的是**原版组件**而非模组自有组件：

| | 用的组件 | 缺失时的行为 | 需要显式初始化？ |
|---|---|---|---|
| 绝大多数活物品 | 模组自有 `LIVING_*_DATA` | `getData(…, DEFAULT)` 有兜底 ⇒ 惰性可用 | ❌ 不需要 |
| **活箱子** | **原版** `DataComponents.CONTAINER` | 没有「默认值」概念，缺失即 `null` | ✅ **必须** |

```java
// LivingChestFunction.java:195
public static boolean hasStorage(ItemStack stack) {
    return stack.get(DataComponents.CONTAINER) != null;   // 缺失 ⇒ false ⇒ 整条链静默失效
}
```

`hasStorage` 有 **8 处**调用点，全是关键路径：
`ServerPacketHandler` ×4（存 / 取）、`ServerPlaceRecipeMixin` ×2（配方书）、`ItemStackMixin` ×1（tooltip）。

⇒ 所以 `setLiving` 里那条显式初始化 `CONTAINER = EMPTY` **是必需的，不是冗余**。

⇒ **推论**：外部途径（任务奖励）发一个活箱子，同样必须补这一步，
   否则玩家拿到的是「看起来是活箱子、实际什么都放不进去」的物品 —— 而这正是本功能要支持的核心场景。

### 2.4 配套：`ensureInitialized()` 钩子建议

与 A2 的 `getOwnedComponentTypes()` 成对（一个声明「我拥有什么」，一个负责「缺了怎么补」）：

```java
/** 确保本功能所需组件的初值存在（缺失则补）。外部途径获得的活物品、tick 首帧调用。 */
default void ensureInitialized(ItemStack stack) {}
```

| 功能 | 实现 |
|---|---|
| `LivingChestFunction` | 补 `CONTAINER` |
| 其余 21 个 | 默认空实现（惰性已够，不动） |

⇒ 把「活箱子的隐式兜底」变成**显式契约**，并让「任务奖励发活箱子」真正可用。

---

## 3. JSON Schema（提案）

文件：`config/living_item/activation_rules.json`（玩家差异）
内置：`assets/living_item/activation_rules.json`（随 jar，与 `container_rules.json` 对称）

```json
{
  "version": 1,
  "default": "allow",
  "rules": [
    { "item": "minecraft:bedrock",  "activate": "deny",  "via": ["player"] },
    { "tag": "#minecraft:swords",   "activate": "deny",  "deactivate": "allow" },
    { "namespace": "cheatymod",     "activate": "deny" },
    { "item": "minecraft:chest",    "activate": "allow" }
  ],
  "options": {
    "denyUnclaimed": true
  }
}
```

| 字段 | 含义 |
|---|---|
| `default` | 无规则命中时的默认值：`allow`（黑名单模式）/ `deny`（白名单模式） |
| `rules[].item` | 单个物品 ID |
| `rules[].tag` | 物品 Tag（`#` 前缀，复用原版 tag 系统） |
| `rules[].namespace` | 整个 modid（「禁掉某某模组的全部物品」） |
| `rules[].activate` | 对**活化**动作的处理：`allow` / `deny`（省略 = 取 `default`） |
| `rules[].deactivate` | 对**取消活化**动作的处理 —— 与 `activate` **互相独立**（2026-09-27 用户要求） |
| `rules[].via` | 本规则作用于哪些**途径**（§1）：`player` / `external` / `internal`；省略语义见 Q-D1-5 |
| `options.denyUnclaimed` | §2 那条默认行为的开关，**默认 `true`** |

> ⚠️ **`via` 的缺省值是危险默认值**：若省略 = 全部途径，
> 一条 `{ "item": "x", "activate": "deny" }` 会把**任务奖励也禁掉**，
> 与 §1 的功能目的直接冲突 —— 而这恰恰是整合包作者最容易写出的形式。
> 见 Q-D1-5。

### 匹配优先级

**按 `rules` 数组顺序，先命中先赢**（与 `ContainerRuleConfig` 的「先到先得 + WARN 冲突」一致）。
都不命中 ⇒ 取 `default`。

> 顺序语义很重要：它让「默认拒绝，只放行某个 tag」这种白名单模式也能表达，
> 而不需要第二套 `allowlist` 字段。

---

## 4. 加载语义 —— 照抄 `ContainerRuleConfig`

按 `open-plan.md` §4 D 组已经列好的硬要求，逐条对齐：

| 要求 | 本设计的落地 |
|---|---|
| 三层来源：玩家移除 > 玩家规则 > 内置规则 | 同构：内置 + `rules` 覆盖 + `removed` |
| **增量语义**（只存玩家差异） | 玩家文件只写自己改过的条目 |
| `load()` 幂等 | 复用同一个 `resetState()` 模式 |
| `version` 字段 | `version: 1` |
| 显式 UTF-8 | 同 |
| malformed 跳过不崩 | 坏 ID / 未知 action → WARN 跳过 |
| **未知字段 → WARN** | ⚠️ `ContainerRuleConfig` 目前**没有**这层，本设计必须补 |

---

## 5. 命令（照抄 `/livingitem container` 的形态）

权限 2（与 container 一致）：

```
/livingitem activation reload    # 重新加载 JSON（改完不用重启）
/livingitem activation list      # 列出当前生效规则
/livingitem activation test      # ★ 判定手中物品能否被活化，并说明被哪条规则命中
```

> `test` 是这个设计里对整合包作者最有价值的一条：
> 他改完 JSON 能**立刻**验证，而不是靠猜。
> 输出应包含「被哪条规则命中」——否则仍然是在黑盒里调。

---

## 6. 玩家反馈

被拒绝时必须给出**明确原因**，否则玩家会以为 mod 坏了：

| 情形 | 提示 |
|---|---|
| 命中 deny 规则 | 「该物品已被整合包规则禁止活化」+ 命中条目 |
| `denyUnclaimed` | 「该物品没有对应的活物品功能，活化它没有意义」 |

---

## 7. 待决问题（拍板后再写代码）

| # | 问题 | 选项 | 倾向 |
|---|---|---|---|
| **Q-D1-1** | 已活化的物品被加入 deny 后，要不要清理？ | ① 只阻止未来活化 ② reload 时一并清除 | **①** —— ② 会摧毁玩家已有进度，风险过高 |
| **Q-D1-2** | `namespace` 通配要不要第一版就做？ | 做 / 不做 | **做** —— 实现仅 3 行，且是整合包作者高频需求；但需确认与 tag 的冲突语义 |
| **Q-D1-3** | 客户端按钮要不要变灰？ | 变灰 / 只出提示 | **只出提示**（第一版）—— 变灰要把规则同步到客户端，成本高 |
| **Q-D1-4** | 是否同时提供 Java 谓词 API？ | 提供 / 只做 JSON | **只做 JSON**（第一版）—— 服务对象是整合包作者；addon 作者将来若要，可再加，且不会与 JSON 冲突（二者 OR） |
| **Q-D1-5** | `via` 省略时的语义？ | ① 缺省 = 全部途径 ② 缺省 = 仅 `player` | **②** —— 与功能目的（只封玩家途径）一致；① 会让整合包作者**误禁掉自己的任务奖励**，且这正是最容易写出的形式 |
| **Q-D1-6** | `ensureInitialized()`（§2.4）是否并入 D1？ | 并入 / 单独做 | **单独做** —— 它是「任务奖励发活箱子」的**正确性前提**，与黑名单无关；改动面也很小（1 个默认方法 + 活箱子 1 处实现） |

---

## 8. 不做的事

| 不做 | 理由 |
|---|---|
| 把判定放进 `setLiving()` | 会破坏 §1 的入口 2/3（活耕地、活地图） |
| 给 `LivingItemFunction` 加 `wouldApplyIfActivated()` | 要改 22 个类，且与 `canApply` 语义重叠易漂移（§2.2） |
| 一开始就抽「通用配置框架」 | 现在只有 2 个数据点；按 `framework-evolution.md` 的教训，**等第 3 个**。先照抄模式，不抽象 |

---

## 9. 复算命令

```bash
rg -n "setLiving\(" src/main/java          # 应得 3 处调用（见 §1，第 4 条途径尚未实现）
rg -c "canApply" src/main/java/com/qiqi/li/living/domain
rg -c "hasStorage\(" src/main/java         # 应得 8 处（见 §2.3）
ls src/main/resources/assets/living_item/*.json
```

---

## 10. 已完成（本设计相关的清理）

| 日期 | 内容 |
|---|---|
| 2026-09-27 | ✅ **清掉熔炉的死代码初始化**：原为 `setData(…, LIVING_FURNACE_DATA.DEFAULT, LIVING_FURNACE_DATA.DEFAULT)`，因**实参 == 默认值**，`setData` 恒走 `remove` 分支 ⇒ **从未生效过**（活熔炉能工作全靠 tick 惰性兜底）。应是某次组件体系重构后的遗留。<br>顺带消除一个潜在数据丢失：对**已带数据的**活熔炉再调 `setLiving(true)` 会清掉它的数据。360 测试全绿。 |
| 2026-09-27 | ✅ 给活箱子的初始化补上「**为什么它特殊**」的注释 —— 原代码无任何解释，看起来像另一条冗余，容易被后人误删 |
