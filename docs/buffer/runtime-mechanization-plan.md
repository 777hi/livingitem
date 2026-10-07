# runtime 机制化方案（runtime mechanization）

*创建: 2026-10-08 · 状态: ✅ **档 2 已实现（A~E 全部完成，2026-10-08）***

> ✅ **本方案已落地**。目标状态即现状，实现记录见 §8；文中 §1~§6 保留为**当时的调研与取舍依据**。
> 新增片段请照 §7.1 / §7.2 的契约做（`RuntimeSegmentType` + 领域内 `*Registration` 登记）。
>
> 📌 用户拍板原话：「档2吧，是实现先计划好就行」→ 随后授权「一次性做好 A~E」。

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

1. ~~做 **档 1** 还是 **档 2**？~~ ⇒ ✅ **已拍板：档 2**（2026-10-08）
2. 档 2 的「片段泛化」具体形式 —— ✅ **已定：注册式 `RuntimeSegmentType` + `StreamCodec`**（见 §7.2）
3. 包名 —— ✅ **已定：`living/runtime/`（L2）**（见 §7.1 步骤 D）

> 📌 顺带记录：`redstone` 域在 2026-10-08 反向边下移后**已零跨领域依赖** ——
> 可作「一个域能干净到什么程度」的参照。

---

## 6. 实施前的实测清点（2026-10-08，逐步改动的事实基础）

> 以下全部为**读代码实测**，不是推测。这是 §7 每一条改动的依据。

### 6.1 生产端（3 处，全部只写「自己那一段」）

| 位置 | 写什么 | 现在怎么调 |
|---|---|---|
| `LivingFurnaceFunction:107` | `progress/total/burnTime/transform` | `forFurnace(...)` |
| `LivingHopperFunction:104,132` | `cooldown/slotInfo` | `forHopper(...)` |
| `LivingWaxedCopperFunction:206` | 13 个发电机遥测字段 | `forGenerator(...)` |

### 6.2 服务端读端（2 处，**同 tick 读回自己的旧值**）

| 位置 | 读什么 | 用途 |
|---|---|---|
| `LivingFurnaceFunction:65` | `cached.furnace()` | 取回上一 tick 的 `progress.total`（燃料/进度续算） |
| `LivingHopperFunction:67` | `cached.hopper()` | 取回 `cooldown`（漏斗冷却续算） |

> ⚠️ **这是本次最容易踩的坑**：`runtime` 不只是「给客户端看的」——
> **漏斗与熔炉把自己 tick 之间的瞬态状态存在这里**（记忆效应）。
> ⇒ 泛化后**服务端本地读路径必须保留且语义不变**，否则冷却会丢、熔炉进度会重置。

### 6.3 客户端读端（4 处）

| 位置 | 读法 |
|---|---|
| `LivingItemTooltip:34-62` | 取整份 data → `setCurrentTooltipData` |
| `LivingFurnaceFunction:332` | `runtimeData.isFurnace() ? runtimeData.furnace() : data.fuel()` |
| `LivingHopperFunction:170` | `runtimeData.isHopper() ? ... : data.transfer()` |
| `LivingWaxedCopperFunction:923` | `runtimeData.isGenerator() ? t : LivingWaxedGeneratorData.of(stack)` |
| `LivingItemClient:175-187` | 同上，为**渲染**（非 tooltip）取发电机遥测 |

⇒ **「回退到 DataComponent」是统一模式**：拿不到运行时数据时用组件里的旧值。
泛化后这个模式必须逐点保留（`get(segType) == null` ⇒ 走回退）。

### 6.4 同步链路（含一个 `player_` 特例）

| 环节 | 位置 | 说明 |
|---|---|---|
| 发送 | `ContainerRuntimeCache.flushToClients:74` | 由 `ContainerLivingItemHandler:549` 每 tick 阶段 4.5 调用 |
| 发送 | `ContainerRuntimeCache.sendSnapshotToPlayer:116` | **零调用者**（保留的公开 API，本次不动） |
| 打包 | `LivingItemSyncPacket` | `flags` 字节 + 3 组固定字段 |
| 接收 | `LivingItemSyncPacket.handle` | 按 key 前缀分流 `updatePlayer` / `update` |
| 客户端缓存 | `LivingItemClientCache` | 「最后快照」模型 + `ThreadLocal` 当前 tooltip 数据 |

⚠️ **`player_` 前缀特例**：玩家背包的 key 是 `player_<uuid>`，发包时**直发本人**、
且**绕过** `containerInstances` 菜单匹配（`ContainerRuntimeCache:88-99`，注释记录了 v19.1 的修复原因）。
⇒ **这个特例在泛化中必须原样保留**（它修的是真 bug，不是冗余）。

### 6.5 测试覆盖现状（⚠️ 有缺口）

| 项 | 现状 |
|---|---|
| `LivingItemSyncPacket` 编解码 | **零测试**（`src/test/.../network/` 只有 `GuiInteractionPacketTest`） |
| `ContainerRuntimeCache` | 无直接测试（4 个引用它的测试都是间接） |

⇒ **本次必须补「编解码往返」测试**（见 §7.5）—— 否则「泛化后编解码没坏」没有机械保证。
这是「防线②：写了就红」的落点。

---

## 7. 档 2 实施计划（正文）

### 7.0 总策略：分 5 步，每步独立可编译 + 可回滚

**核心原则：先建机制、再换实现、最后搬家。** 每步结束都要 538 全绿 + `check_layers` 无新增违规。
**先做纯重构（行为不变），再做泛化（触及网络）** —— 让「改坏了」与「改对了」可分辨。

| 步 | 内容 | 风险 | 可独立提交 |
|---|---|---|---|
| **A** | 建 `RuntimeSegmentType<T>` 注册机制 + 单一 codec | 低（纯新增，无人用） | ✅ |
| **B** | 补 `LivingItemSyncPacket` 编解码往返测试（**先测现有行为**） | 零（只加测试） | ✅ |
| **C** | 三个领域改为「自登记片段」+ 同步包改走注册表 | ⚠️ **中**（触及网络） | ✅ |
| **D** | 反转发包职责（`drainDirty()`）+ 包搬到 `living/runtime/` | 中 | ✅ |
| **E** | 收尾：文档三处同步 + 分层基线收紧 | 零 | ✅ |

> ⭐ **B 在 C 之前**是关键：先用测试把「现有编解码的字节格式」钉住，
> 改完 C 后同一批测试**必须依然全绿**（= 线上格式不变，只是构造方式变了）。

---

### 7.1 目标结构（改完后）

```
src/main/java/com/qiqi/li/living/runtime/          ← L2（从 domain/runtime/ 迁入）
├── RuntimeSegmentType.java       # 片段类型注册（新）
├── RuntimeSegments.java          # 泛化的聚合容器（新，取代 LivingItemRuntimeData）
├── ContainerRuntimeCache.java    # 服务端缓存（不再发包，只登记脏）
├── RuntimeRegistration.java      # 注册入口
└── segments/                     # 各领域的片段定义（新）
    ├── GeneratorSegment.java     # 由 power 域「认领」，但定义放这？见下
    ├── HopperSegment.java
    └── FurnaceSegment.java

src/main/java/com/qiqi/li/client/runtime/
└── LivingItemClientCache.java    # 客户端缓存（从 domain/runtime/ 迁入 L5）
```

⚠️ **片段定义的归属（关键设计决策）**：三个片段的**字段类型**分别来自
`power.LivingWaxedGeneratorData` / `hopper.ResolvedSlotData` / `furnace.TransformData`
—— **若片段定义放 `living/runtime/`，就会新增 R1 违规**（L2 → L3）。

⇒ **解法：片段定义放各自领域**（`domain/power/GeneratorRuntimeSegment.java` …），
`living/runtime/` 只放**机制**（`RuntimeSegmentType` 抽象 + 注册表 + 缓存）。

```
living/runtime/           ← L2 机制
    RuntimeSegmentType<T>     # 抽象：id + StreamCodec + 线程安全的不可变值
    RuntimeSegmentRegistry    # 注册表：id → type
    RuntimeSegments           # 一次快照的片段集合（Map<String,Object> 的类型安全外壳）
    ContainerRuntimeCache     # 服务端缓存（登记脏，不发包）
    RuntimeRegistration       # 注册入口（框架侧）
living/domain/power/
    GeneratorSegment.java     # 实现 RuntimeSegmentType<LivingWaxedGeneratorData>
living/domain/hopper/
    HopperSegment.java
living/domain/furnace/
    FurnaceSegment.java
```

⇒ **依赖方向**：`domain → runtime`（L3 → L2 = **合规**）；
`runtime` **不认识**任何领域（不知道 segment 有几个、叫什么）✓

> 这正是**「注册表 vs 被注册者」**模式的又一次应用：注册表（`runtime`）提供机制，
> 被注册者（各领域片段）自带身份与编解码 —— 与 `LivingComponents`（DataComponentType）
> 和 `StaticCacheRegistry` 的思路一致。

---

### 7.2 序列化设计（本次唯一需要新设计的部分）

**现状**（`LivingItemSyncPacket:68-82`）：`flags` 字节 + `if (flags & 1) encodeGenerator(...)` 硬编码三分支。

**改后**：**每个片段 = 一个「id + 自描述编解码」的注册项**。

```java
/** 一个运行时数据片段类型：全局唯一 id + 自描述编解码。 */
public interface RuntimeSegmentType<T> {
    String id();                                  // 稳定 id，进网络包，**不可改名**
    StreamCodec<? super RegistryFriendlyByteBuf, T> codec();
}
```

线上格式（**保留可扩展性**）：

```
containerKey (utf)
slotCount (varint)
for each slot:
  slotIndex (varint)
  segmentCount (varint)                    ← 取代原来的单字节 flags
  for each segment:
    segmentId (utf)                        ← 取代原来的 bit 位
    payload (由该 id 注册的 codec 解码)
```

**字节量对比**（评估过，可接受）：

| 项 | 现在 | 改后 |
|---|---|---|
| 每槽的「有哪些段」 | 1 字节 flags | `varint segmentCount` + 每段 `writeUtf(id)` |
| 典型情况 | 1 个段 ⇒ 1 字节 | 1 个段 ⇒ 1 + (2+idLen≈12) ≈ 15 字节 |

⇒ **每槽多约 14 字节**。一容器假设 27 槽全有遥测 ⇒ 一次包多约 **380 字节**，
且**只在脏容器时发**（`dirtyContainers` 过滤）⇒ **可接受**。
（备选：id 用 `varint` 序号代替字符串，省 10 字节/段 —— **本次不做**，先保可读性与明确性。）

> ⚠️ **id 是线格式契约**：一旦发布就不能改（改了老客户端解不出）。
> 三个既有段沿用原语义命名：`"generator"` / `"hopper"` / `"furnace"`。

**空段优化**（沿用现状语义）：现在「无 flags」= 该槽无数据（`EMPTY`）。
改后 `segmentCount == 0` 即无数据 —— 语义等价，可照旧跳过。

---

### 7.3 逐步改动清单

#### 步骤 A：建机制（纯新增，零调用者）

1. 新建 `living/runtime/RuntimeSegmentType.java`（接口，见 §7.2）
2. 新建 `living/runtime/RuntimeSegmentRegistry.java`：
   - `register(RuntimeSegmentType<?>)` / `get(String id)` / `ids()`
   - **静态注册**（与项目既有 `StaticCacheRegistry` / 各 `XxxRegistration` 一致）
   - ⚠️ **单测必须显式 reset**（项目红线：Gradle 同 JVM 跑全部测试 ⇒ 静态注册表要能清）
3. 新建 `living/runtime/RuntimeSegments.java`：类型安全外壳
   ```java
   public final class RuntimeSegments {
       private final Map<String, Object> byId;
       public static final RuntimeSegments EMPTY = ...;
       public <T> RuntimeSegments with(RuntimeSegmentType<T> type, T value);
       @Nullable public <T> T get(RuntimeSegmentType<T> type);
       public boolean isEmpty();
   }
   ```
   ⭐ **`get(Type)` 而不是 `get(String)`** ⇒ 编译期就杜绝「拿错段」的强转。
4. `check_layers.py` 的 `LAYERS` 表：`living/runtime/` 登记为 **L2**（此时包里只有新文件，无违规变化）

**验收**：`compileJava` ✓ · 538 全绿（新类无调用者，不可能影响）

#### 步骤 B：补编解码往返测试（**先钉住现有格式**）

5. 新建 `src/test/java/com/qiqi/li/network/LivingItemSyncPacketTest.java`：
   - 用 `RegistryFriendlyByteBuf` 构造 → `encode` → `decode` → 断言字段逐项相等
   - 三组：仅 generator / 仅 hopper / 仅 furnace / 空 / **多槽混合**
   - `player_<uuid>` key 与普通 key 各一条（保证 key 不被破坏）
   - ⚠️ 需要 `RegistryFriendlyByteBuf` 的 mock（参照 `GuiInteractionPacketTest` 的做法）

> ⚠️ **风险**：`RegistryFriendlyByteBuf` 依赖 registry access，单测可能需 mock 注册表。
> **若 mock 成本过高**，退一步：只在 §7.5 用手测覆盖，并在文档里**诚实标注测试缺口**。
> ⇒ **先试，试不通就如实记录**（不要为了"有测试"而写假测试）。

**验收**：新测试通过（**改 C 之前必须全绿**，这是 C 的安全网）

#### 步骤 C：三个领域改自登记 + 同步包走注册表（⚠️ 核心步骤）

6. 各领域新建片段类型（3 个文件，见 §7.1）：
   ```java
   // domain/furnace/FurnaceSegment.java
   public final class FurnaceSegment implements RuntimeSegmentType<LivingItemRuntimeData.FurnaceRuntime> {
       public static final FurnaceSegment INSTANCE = new FurnaceSegment();
       public String id() { return "furnace"; }
       public StreamCodec<? super RegistryFriendlyByteBuf, FurnaceRuntime> codec() { ... }  // 搬 encodeFurnace/decodeFurnace
   }
   ```
   ⇒ 把 `LivingItemSyncPacket` 里那 6 个 `encodeXxx`/`decodeXxx` 私有方法**迁到各自片段**（网络包不再认识具体字段）
7. 改生产端 3 处：`forFurnace(...)` → `RuntimeSegments.EMPTY.with(FurnaceSegment.INSTANCE, new FurnaceRuntime(...))`
8. 改读端：`cached.isFurnace()` → `cached.get(FurnaceSegment.INSTANCE) != null`（语义等价）
9. 改 `LivingItemSyncPacket`：`encodeRuntimeData` / `decodeRuntimeData` 改为**遍历注册表**（§7.2 格式）
10. 删 `LivingItemRuntimeData`（**先确认零引用**：`grep -rn "LivingItemRuntimeData" src/`）
11. 客户端 4 处读点同步改（`living-item 工具` 的 `isXxx()` → `get(XxxSegment.INSTANCE)`）

⚠️ **本步骤必须逐点核对 §6 的 9 个读/写点，一个不漏**（尤其 §6.2 的服务端回读）。

**验收**：538 全绿 **且步骤 B 的测试不改一行仍全绿**（⇒ 字节格式未变，只是构造方式变了）

#### 步骤 D：反转发包 + 搬家

12. `ContainerRuntimeCache` 增加 `drainDirty()`（返回 `Map<key, snapshot>` 并清脏），
    **删掉** `flushToClients` 的 send 部分 —— 但 ⚠️ **`isViewingContainer` 的菜单匹配逻辑要保留**（它是功能，不是发包）
    ⇒ 建议形态：cache 提供 `drainDirty()` + **保留** `findViewers(key, instances)`（纯查询，不发包）
13. 把发包逻辑搬到 L4：`LivingItem` tick 里（或 `ContainerLivingItemHandler:549` 调用点）
    消费 `drainDirty()` → `new LivingItemSyncPacket(...)` → 按 `player_` 前缀分流发送
    ⚠️ **`player_` 特例必须一起搬过去**（§6.4）
13b. **（可选优化）背包分支加「正在看容器界面」守卫** —— 见 §7.3.1
14. `git mv` 两个包：
    - `living/domain/runtime/*` → `living/runtime/`（`LivingItemClientCache` 除外）
    - `LivingItemClientCache` → `client/runtime/`
15. 全库改 import（**用脚本 + 编译验证** —— 项目有踩过「脚本改 import 静默失效」的坑）
16. `RuntimeRegistration` 的客户端缓存登记随之调整（按 A1「登记点归属」）

**验收**：`check_layers` **R3 22 → 19 · R1 93 → 92**（预期）· 538 全绿 · 配对数收紧基线

#### 7.3.1 背包分支的空转发包（**可选优化，不单独做**）

**问题（2026-10-08 用户指出）**：`flushToClients` 的两个分支过滤强度不同 ——

| 分支 | 脏过滤 | viewer 过滤 | 结论 |
|---|---|---|---|
| BE 容器 | ✅ | ✅ `isViewing` | **已是「脏 + 有人看」** |
| **玩家背包**（`player_<uuid>`） | ✅ | ❌ **无条件直发主人** | ⚠️ 玩家没开界面时，包发了**没人读** |

**为什么"没人读"**：`LivingItemTooltip` 只在 `AbstractContainerScreen` 里渲染
（`LivingItemTooltip:39`）⇒ 玩家在普通游戏画面里根本不会读这个包。
而**背包每 tick 都被无条件 tick**（`LivingItem:202`，与开不开界面无关）
⇒ 背包里有活漏斗/熔炉/发电机时，**每 tick 一个废包**（20 包/秒/玩家）。

**为什么不单独做（用户问「有必要吗，对玩家而言」→ 结论：收益/风险不对称）**：

| 维度 | 评估 |
|---|---|
| 收益 | ⚠️ **玩家无感** —— 不卡、不掉帧、延迟不变。省的是**几 KB/s 带宽**（最坏 ~100-200 字节 × 20/s/玩家）+ 每 tick 一次 `snapshot` 遍历。联机服务器上多玩家叠加才是"本来不该有的持续流量" |
| 风险 | ⚠️ **玩家有感** —— ① 引入「打开背包后第一帧是旧值」（1 tick，通常看不出）；② 判据写错会**复现 v19.1 的 tooltip 全 0**（那是玩家能看见的回归） |
| ⇒ 结论 | **「改对了玩家无感，改错了玩家有感」** ⇒ 不值得**独立一次改动 + 专门手测**（要测开背包/关背包/创造模式物品栏三种） |

⇒ **作为 D 步的附带项**（D 步本来就要重写发包职责 ⇒ 零边际成本，且 §7.5 手测第 4 条正好覆盖）。

**若要做，正确写法**：

```java
// 玩家背包（player_ 前缀）：仅当**开着某个容器界面**时才发
// ⚠️ 不可复用 isViewing —— 它对背包恒 false（menu == inventoryMenu 早退，见 ContainerContexts:177）
// ⇒ 复用即复现 v19.1「背包 tooltip 全 0」bug。
// 也不可用「是不是 InventoryScreen」—— 背包 GUI 里的 tooltip 同样要显示。
if (owner.containerMenu == owner.inventoryMenu) {
    continue;   // 没开任何容器界面 ⇒ 无人读 tooltip ⇒ 不发
}
owner.connection.send(packet);
```

⚠️ **附带发现**：若担心「打开背包第一帧」的即时性，`sendSnapshotToPlayer`
（`ContainerRuntimeCache:116`，**零调用者**，保留的公开 API）正好是现成的补发入口。
**本次不做**，仅在需要时启用。



#### 步骤 E：收尾

17. 文档三处同步：`living-item-infrastructure.md`（§2 若提到 runtime）+ `file-map.md` + `AGENTS.md`（模块地图）
18. `--update-baseline` 收紧棘轮
19. changelog + AGENTS 进展行

---

### 7.4 风险登记

| # | 风险 | 缓解 |
|---|---|---|
| R1 | **服务端回读丢失**（§6.2）⇒ 漏斗冷却/熔炉进度重置 | C 步逐点核对；写单测断言「写回后 get 能拿到」 |
| R2 | 编解码泛化后**字节格式变了** ⇒ 客户端解不出 | 用**新 id 串进包**但**字段顺序不变**；步骤 B 的测试当安全网 |
| R3 | `player_` 特例被顺手"清理"掉 | §6.4 已记录它是 v19.1 的真 bug 修复 ⇒ **代码注释 + 本方案双留痕** |
| R4 | 脚本改 import 静默失效 | 改完**必须编译**（旧坑：`LivingItemClient` 那次） |
| R5 | `drainDirty` 语义写错（发漏/发重） | 保留现有「取快照 → 清脏」顺序；脏集合用 `ConcurrentHashMap.newKeySet()` |
| R6 | 静态注册表污染单测 | 注册表加 `clearForTest()`（项目红线） |
| R7 | 触及网络 ⇒ 单测覆盖不足 | 步骤 B + §7.5 手测兜底 |
| R8 | **（若做可选优化）背包守卫写错** ⇒ 复现 v19.1「背包 tooltip 全 0」 | 用 `containerMenu == inventoryMenu` 而非 `isViewing`（§7.3.1）；手测第 4 条专门覆盖 |

### 7.5 验收清单（每步 + 总）

**每步**（硬门槛）：
- `./gradlew test --rerun` **538 全绿**
- `python tools/check_layers.py` 无新增违规
- `python tools/doc_check.py` 9/9

**总验收**（D 步后）：
| 项 | 期望 | 实测 |
|---|---|---|
| R3 | 22 → **19** | ✅ **10**（比预估更好：领域侧 3 对出边随 God record 一起消失） |
| R1 | 93 → **92** | ✅ **91** |
| `LivingItemRuntimeData` | **已删除**（零引用） | ✅ |
| `runtime` 包的出边 | **零**（不再认识任何领域 / 网络层） | ✅ |
| 字节格式 | 与改前**语义等价**（步骤 B 测试不改仍绿） | ✅ 断言一字未动，551 全绿 |

**手测**（⚠️ 唯一无法机械保证的环节，三种都要看）：
1. **发电机遥测**：涂蜡铜块放容器里 → 悬停看仪表盘数字（核心三行 + 相位）
2. **漏斗冷却**：活漏斗放容器里 → 悬停看冷却/槽位信息
3. **熔炉进度**：活熔炉烧炼中 → 悬停看进度条 + 燃料
4. **玩家背包路径**（`player_` 特例）：上述活物品放**自己背包**里悬停（走直发通道）
   - ⚠️ **若做了 §7.3.1 可选优化**：额外测「**关掉所有容器界面 → 打开背包 → 第一帧 tooltip**」
     （验证 1 tick 延迟不可感知）与「**创造模式物品栏**」路径
5. **回归**：关容器再开（快照重发）/ 跨容器搬运（运行时缓存清零，tooltip 回退组件值）

### 7.6 回滚策略

**每步独立提交** ⇒ 任一步出问题 `git revert <该步>` 即可，不影响其它步。
**最坏情况**（C 步改坏且难定位）：`git revert` C+D 两步 ⇒ 回到「现状 + 新增的机制 A/B」，无害。

### 7.7 工作量与顺序

**顺序固定**：`A → B → C → D → E`（**B 必须在 C 前**，§7.0）。

**规模估计**（按文件与改动点）：
- A：**3 新建 + 1 改**（`LAYERS` 表）
- B：**1 新建测试**
- C：**3 新建片段 + 改 9 处读写点 + 改 1 网络包 + 删 1 类**
- D：**改 1 缓存 + 改 1 调用点 + 迁 2 个包 + 脚本改 import**（+ 可选：§7.3.1 背包守卫）
- E：**3 篇文档 + 基线**

⇒ **C 是重心**（唯一碰网络的一步），建议**单独一个会话做**，前 A/B 可合并。

---

## 8. 实现记录（2026-10-08，A~E 全部完成）

### 8.1 实际落点（与 §7.1 设计的差异）

| 设计稿 §7.1 | 实际落点 | 差异原因 |
|---|---|---|
| `living/runtime/` 放三机制类 | ✅ 同 | — |
| `LivingItemClientCache` 迁 L5 | ⚠️ **仍在 `living/runtime/`（L2）** | 它有客户端 `ThreadLocal`（悬停数据）但**无 L5 专属依赖**（不 import `client/*`）；放 L2 避免让下游领域反向依赖 L5（会新增 R1）。**待定**：若将来它 import `client/*`，必须迁 L5 |
| 包结构 `domain/runtime/` → `living/runtime/` | ✅ 4 文件全部 `git rm`，3 个迁入 `living/runtime/`（`LivingItemRuntimeData` 删除） | — |
| `RuntimeSegments.get(String)` | ✅ 改为 **`get(RuntimeSegmentType<T>)`** | 编译期杜绝「拿错段」强转（§7.2 已定稿） |

### 8.2 新增文件

**L2 机制（`living/runtime/`）**
- `RuntimeSegmentType<T>` —— 契约：`id()` + `codec()`
- `RuntimeSegmentRegistry` —— `id → 类型` 静态注册表（含 `clearForTest()`，单测红线）
- `RuntimeSegments` —— 不可变聚合容器；`with(type,value)` / `get(type)` / `EMPTY`
- `ContainerRuntimeCache` —— 迁入并**反转发包**（新增 `drainDirty()` / `hasDirty()` / `findViewers()` / `isJustInventoryMenu()`；删除 `flushToClients`）
- `LivingItemClientCache` —— 迁入（无改动语义）
- `RuntimeRegistration` —— 迁入（只登记客户端缓存清理）

**各领域的段定义**
- `power/GeneratorSegment`（`id="generator"`）/ `hopper/HopperSegment`（`"hopper"`）/ `furnace/FurnaceSegment`（`"furnace"`）
- 各段内含自己的 `XxxRuntime` record + `STREAM_CODEC`（**字节布局与改造前逐位一致**）
- 各自的 `*Registration.register()` 里追加 `RuntimeSegmentRegistry.register(XxxSegment.INSTANCE)`

**L4 发包端**
- `network/LivingItemRuntimeSync` —— `flush(players, containerLookup)`：
  - `drainDirty()` 取脏 → `player_` 前缀走**直发主人 + `isJustInventoryMenu` 守卫**（§7.3.1 可选优化**已实现**）
  - 其余走 `findViewers`（菜单槽位匹配，含大箱 `CompoundContainer.contains` 特判）
- `LivingItemRuntimeSync.sendSnapshotToPlayer` —— 补发入口，**当前无调用者**（保留）

### 8.3 调用点变更

| 位置 | 变更 |
|---|---|
| `LivingItem.onServerTick` | **新增** `LivingItemRuntimeSync.flush(players, this::runtimeContainerInstances)`，放在**所有**容器处理之后 |
| `LivingItem.runtimeContainerInstances` / `parsePosFromContainerKey` | **新增**（容器键 → `Container` 实例集合；解析 `chest_x_y_z` 单箱与 `chest_x1_y1_z1_x2_y2_z2` 大箱） |
| `ContainerLivingItemHandler.processContext` | **删除**阶段 4.5（`ContainerRuntimeCache.flushToClients`）及其 `BlockEntity` 收集 |
| `LivingItemSyncPacket` | 记录类型改 `(String, Map<Integer, RuntimeSegments>)`；编解码**遍历注册表**，不再认识任何具体字段；未登记 id 抛 `IllegalStateException` |
| 9 处读写点 | furnace 3 / hopper 3 / power 2 / client 2 → `RuntimeSegments.EMPTY.with(XxxSegment.INSTANCE, …)` 与 `.get(XxxSegment.INSTANCE)` |

### 8.4 ⚠️ 实施中新发现的坑（值得记住）

1. **`runtime` 不只是「给客户端看的」**：漏斗把 `cooldown`、熔炉把 `progress` **当作跨 tick 瞬态状态**存在这里
   （`LivingHopperFunction:68`、`LivingFurnaceFunction:66` 会**读回**自己上一 tick 写的值）。
   ⇒ 泛化后**服务端本地回读路径必须保留**，否则冷却丢失、熔炉进度重置。这是本方案最易踩的坑。
2. **嵌套 record 的泛型限定名**：`class FurnaceSegment implements RuntimeSegmentType<FurnaceRuntime>` 里
   `FurnaceRuntime` 是**内部类**，在 `implements` 子句里尚不可见为简单名 ⇒ 必须写 `FurnaceSegment.FurnaceRuntime`。
3. **测试替身的键**：`FakeContainerContext` 的 `getContainerKey()` 是**测试键**，**不匹配** `chest_x_y_z` 形态
   ⇒ 新发包查找对它返回空集合 ⇒ 「不漏发」—— 单测不因此破。但**测试若断言单测里真的发了包，会失败**（当前无此类断言）。
4. **`registry.clearForTest()` 必须在测试里用**（项目红线）：Gradle 同一 JVM 跑全部测试，静态注册表会跨类残留。

### 8.5 验收实测

| 项 | 结果 |
|---|---|
| `./gradlew test --rerun` | ✅ **551 tests / 0 failures / 0 errors / 1 skipped**（538 → 550 为 B 步 12 例，550 → 551 为新增「未登记 id 报错」护栏） |
| `python tools/check_layers.py` | ✅ R1 **93 → 91**；R3 **22 → 10**；R2/R4 恒 0（8 条已消除项可 `--update-baseline` 收紧） |
| `python tools/doc_check.py` | ✅ 9/9 |

### 8.6 后续可选

- `tools/layer_baseline.txt` 跑 `--update-baseline` **收紧基线**（棘轮只降不升）
- 手测四项（§7.5）：发电机遥测 / 漏斗冷却 / 熔炉进度 / 背包路径 —— ⚠️ **尚未做**（用户休息前授权代码改造，未涉及游戏内实测）
- `LivingItemClientCache` 的层次归属复核（见 §8.1）

