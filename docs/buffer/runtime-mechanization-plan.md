# runtime 机制化方案（runtime mechanization）

*创建: 2026-10-08 · 状态: **调研完成，待拍板** —— 本文只做调研与方案，**未动任何代码***

> ⚠️ **本文件是设计稿（未实现）**。文中「改后」段落描述**目标状态**，不是现状。现状以 `src/` 为准。

---

## 0. 一句话结论

`runtime` **不是一个「放错层的领域」，而是 4 个不同性质的东西塞进了同一个包**。
⇒ 所以「机制化」的实际动作是 **拆包 + 泛化聚合**，**不是搬层**。

⚠️ **单纯搬层（L3 → L2）净收益 ≈ 0** —— 见 §2 的论证。

---

## 1. 现状：4 个文件、4 种性质

| 文件 | 行数 | 真实性质 | 问题 |
|---|---|---|---|
| `ContainerRuntimeCache` | 140 | 服务端容器级运行时缓存 + **主动发包** | 依赖 `network.LivingItemSyncPacket`（**L4**）⇒ R1 违规 |
| `LivingItemClientCache` | 91 | **客户端**缓存 | 零项目依赖（很干净），但住在服务端域 ⇒ 应属 L5 |
| `LivingItemRuntimeData` | 59 | **三领域聚合 record** | 硬编码 `generatorTelemetry` / `hopper` / `furnace` ⇒ 出边 R3 |
| `RuntimeRegistration` | 21 | 注册入口（只登记客户端缓存清理） | ✓ 干净 |

### 出边（runtime → 其它）
只有 `LivingItemRuntimeData` 有出边：`hopper.ResolvedSlotData` · `furnace.TransformData` · `power.LivingWaxedGeneratorData`

### 入边（谁依赖 runtime）—— 横跨 **5 个模块**
`container`(L1) · `furnace` / `hopper` / `power`(L3) · `LivingItem` / `network`(L4) · `LivingItemClient` / `client/render`(L5)

⇒ **它是「被所有层依赖」的共享基础设施** —— 这正是「它不该是并列领域」的证据 ✓

---

## 2. ⚠️ 为什么「只搬层」无意义

| 依赖方向 | 现在 | 搬 L3 → L2 之后 |
|---|---|---|
| `furnace / hopper / power → runtime` | **R3 违规**（领域互依赖）3 条 | ✅ 合法（L3 → L2） |
| `runtime → furnace / hopper / power` | R3 违规 3 条 | ❌ **变成 R1 违规**（L2 → L3）3 条 |

⇒ **净收益 ≈ 0**：只是把违规换了个名字。

⇒ **必须先解决 `LivingItemRuntimeData` 的领域依赖**，搬层才有意义。

---

## 3. 方案（分两档）

### 档 1：纯搬家（低风险，收益小）

- `LivingItemClientCache` → `client/`（L5）—— 它零项目依赖，纯移动
- `RuntimeRegistration` 里那条客户端缓存登记随之调整（按 A1「登记点归属」原则，
  客户端缓存应归客户端侧而非领域 Registration）

⇒ 收益：R1 / R3 各减 1~2 条。

### 档 2：完整机制化（**推荐**，中等风险）

**① `ContainerRuntimeCache` 反转发包职责**

- 现状：cache 自己 `new LivingItemSyncPacket(...)` 并 send ⇒ 依赖 L4
- 改后：cache 只暴露 `drainDirty()`；由 L4（`LivingItem` 的 tick 或 `network` 层）消费并发包
- ⇒ 切断 `runtime → network` ✓

**② `LivingItemRuntimeData` 泛化成「按 key 索引」**

- 现状：硬编码 3 个领域字段 + `forGenerator` / `forHopper` / `forFurnace` 工厂 + `isXxx()` 判定
- 改后：`Map<String, Object>`（或带 key 的片段列表），领域侧用
  `RuntimeSegments.put(stack, "furnace", ...)` 自行登记
- ⇒ **runtime 不再认识任何领域** ✓
- ⭐ **附带收益**：新增需要遥测的活物品**不用再改这个聚合类**（与「消除样板」同一思路）

**③ 包整体上移 L2**

- `living/domain/runtime/` → `living/runtime/`，并在 `tools/gen_code_map.py` 的 `LAYERS` 表登记为 L2

⇒ 收益：**R3 −3 · R1 −1** · 消除一个「假领域」· 聚合类不再为每个新活物品改一次。

---

## 4. 风险与验证

| 项 | 评估 |
|---|---|
| **触及面** | ⚠️ **网络同步**（`LivingItemSyncPacket` 的三组字段）+ **tooltip 渲染**（`LivingItemTooltip` 的 `isGenerator()` / `isHopper()` / `isFurnace()`） |
| **序列化** | 泛化后「任意片段」需要能编解码 ⇒ **需设计**（可选：片段自带 CODEC / 用 `DataComponentType` 式注册） |
| **档 1 的风险** | 几乎为零（纯搬家 + 编译验证） |
| **验证** | 538 全绿 · 分层校验（预期 R3 22 → 19 / R1 93 → 92）· **手测 tooltip**：发电机遥测 / 漏斗冷却 / 熔炉进度 **三种都要看** |

---

## 5. 待拍板

1. 做 **档 1**（低风险小收益）还是 **档 2**（完整）？
2. 档 2 的「片段泛化」具体形式：`Map<String, Object>` vs 带 CODEC 的接口 —— 待设计确认
3. 包名：`living/domain/runtime/` → `living/runtime/`（L2）？

> 📌 顺带记录：`redstone` 域在 2026-10-08 反向边下移后**已零跨领域依赖** ——
> 可作「一个域能干净到什么程度」的参照。
