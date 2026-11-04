# 最小 `moveStack` 能力 —— 框架侧与活塞侧的收口结论

> **状态**: **框架侧已实现（2026-11-03）**，等活塞接入验证后转稳定层。
> 实现：`ContainerContext#moveStack` / `#undo` / `#canAcceptAll` + `MoveResult` / `UndoToken`；
> 守卫测试 `ContainerMoveStackTest`（10 项）。
> **范围**: 只收口「一步整栈移动 + 安全撤销」。**不含**链解析、状态机、渲染、压送。
> **契约依据**: [container-boundary.md](../system-design/container-boundary.md) `I-C12` / `I-C13` / `I-C14`
> **消费者**: [活活塞-design.md](../archive/活活塞-design.md) §7.4（精确本体移动）
> **分工**: 共享 API 由框架侧实现；活塞侧并行做链解析 / 状态机 / 渲染，**不自行实现移动与回滚**。

---

## 0. 一句话

**框架保证「一步」的正确与可撤销；活塞组织「整条链」的逆序撤销。**
框架不理解链，活塞不碰底层恢复。

---

## 1. 最终 API

```java
/** 一步整栈移动：把 from 槽的全部物品移到 to 槽。不拆数量、不合并。 */
MoveResult moveStack(int from, int to);

/** 预检查，不改任何状态。moveStack 执行时仍会重新验证。 */
boolean canAcceptAll(int to, ItemStack stack);

/** 撤销一步。凭据由 Success 携带，只在本次 tick 内有效。 */
boolean undo(UndoToken token);
```

### 结果四态（与活塞 §7.4 对齐）

```java
sealed interface MoveResult {
    record Success(int moved, UndoToken undo)      implements MoveResult {}
    record Rejected(Reason reason)                 implements MoveResult {}
    record Unsupported(Reason reason)              implements MoveResult {}
    record BackendViolation(String detail)         implements MoveResult {}
}
```

⚠️ **不设「部分成功」**：`Success.moved` 恒等于操作开始时的源栈数量。
部分交付只属于漏斗压送（另一个入口），不往这个接口加参数。

### 拒绝原因（6 档，稳定不细分）

| Reason | 含义 |
|---|---|
| `SOURCE_EMPTY` | 源为空 |
| `TARGET_OCCUPIED` | 目标被占（纯位移要求空槽） |
| `SOURCE_NOT_EXTRACTABLE` | 容器拒绝完整取出 |
| `TARGET_ADMISSION_DENIED` | 物品不允许进入目标槽 |
| `TARGET_INSUFFICIENT` | 不能完整接收整栈（**无法区分准入与容量时用这档，不猜**） |
| `INVALID_ADDRESS` | 越界 / 源目标相同 |

不支持精确操作 → `Unsupported`；写入与承诺不符 → `BackendViolation`。
这些原因先服务测试与调试；**是否全部上 tooltip 后定，不让 UI 反过来扩大 API**。

---

## 2. 撤销：凭据，不是裸 `restore`

**为什么裸 `restore(slot, stack)` 不安全**（活塞侧指出，2026-11-03）：

```
移动后：源空，目标有 A
只 restore(源, A) → 源和目标各有 A ⇒ 复制
```

它只表达了「恢复源」，没有表达「撤销目标新增」。⇒ **单槽恢复降为框架内部原语**，
对外只提供「一步移动的撤销」。

### 凭据形状

```java
/** 一步移动的撤销凭据。只是本次调用内的槽号/数量/状态，不是物品 UUID、不是持久事务。 */
record UndoToken(int from, int to, ItemStack sourceBefore, ItemStack targetBefore, int moved) {}
```

### `undo` 的执行顺序

1. **校验**当前两槽是否仍是本步的**预期结果**：
   `getItem(from)` == `sourceBefore - moved`，`getItem(to)` == `targetBefore + moved`
2. 不匹配 ⇒ 返回 `false`，**不覆盖**（第三方已改动，覆盖等于第二次破坏）
3. 匹配 ⇒ 两槽一起恢复到 `sourceBefore` / `targetBefore`
4. 恢复后校验一次（`ItemStack.matches`），失败则留痕并返回 `false`

> ⚠️ **不假设「同 tick 内原槽不会被别人动」**（活塞侧指出）：
> setter 会同步触发监听器，第三方逻辑可能响应并改变内容。
> 所以第 1 步的校验不能省，也不能简化成"反正没人动"。

---

## 3. 实现要点（框架侧）

### 3.1 超大栈：一次只抽 64 不算后端违约

某些容器的 `extractItem` 单次有上限（返回 64 而非整栈）。处理：

1. **先模拟**：`simulateExtract` / `extractItem(slot, amount, true)` 确认能完整取出
   —— 不足则 `SOURCE_NOT_EXTRACTABLE`，**不尝试部分抽取**
2. **循环抽取**直到取够或取空
3. 中途取不够（模拟说够、实际不够）⇒ **把已抽的放回 + `BackendViolation`**

⚠️ 第 3 步的"放回"本身可能失败 —— 那就必须 WARN 留痕，不能让物品静默消失（`I-C6`）。

### 3.2 精确落槽

写入走 `setItem` → `writeBackDirectly`（`setStackInSlot`），不是 `insertItem`
⇒ 不会被第三方容器的「整库重定向」挪到别的槽（`I-C3`）。

### 3.3 数量内核共享

`moveStack` 与 `SlotAccessor.transfer` **共享同一个数量内核**（容量预算 + 饱和算术，
见 `I-C14`），但**不共享入口**：`SlotAccessor` 保留给传输引擎的跨容器/过滤器场景，
`moveStack` 服务同容器精确位移。共享内核、不共享外观。

---

## 4. 批量同步的保证范围（收窄）

**已有机制**（不需要新增）：`syncSlotToClients` 登记 `dirtySlots`，`flushDirtySlots`
在 tick 末读取**最终值**发送（`SimpleContainerContext:515-532`、
`ContainerLivingItemHandler:730-734`）。

⚠️ **但保证范围仅限「现有槽位同步通道」**（活塞侧指出）：

- 不覆盖第三方通知（setter 触发的监听器会立即看到中间态）
- 不覆盖 runtime 机械态的首包与删除通知 —— 那是独立接缝

⇒ 结论不变（活塞单 tick 内完成动作即可），但**不要把这条当成"任何中间态都不会外泄"的通用保证**。

---

## 5. 链级撤销的分工

```
C 移动成功
B 移动成功
A 被拒绝
→ 活塞逆序调用 undo(B 的凭据)
→ 活塞逆序调用 undo(C 的凭据)
→ 整条链恢复原位
```

- **框架**：保证一次 `moveStack` 的成功、失败与安全撤销
- **活塞**：记录本次成功步骤的凭据，失败时逆序调用 `undo`
- **双方都不需要**：框架理解链、活塞写底层恢复

---

## 6. 明确不做

| 不做 | 理由 |
|---|---|
| 链解析 / N+1 接收端判定 | 活塞玩法 |
| 状态机 / 伸出态 / 朝向有效性 | 同上 |
| 跨容器移动 | 活塞 §0 已定不做 |
| 部分交付（拆栈压送） | 另一个入口，等活塞做压送时单独定 |
| 新的同步机制 | §4 已由 `flushDirtySlots` 覆盖（范围内） |
| 链级事务 | 活塞逆序 undo 即可，框架不需要懂链 |

---

## 7. 活塞侧现在就能并行做的事

1. 纯链解析与动作计划：空推、超长失败、N+1 接收端判定（**只读**，不写回）
2. 状态机与生命周期逻辑测试
3. 压送数量 / 成功判据 / 后方不动的测试
4. 三维本体 + 头杆渲染原型

⚠️ **不要为了先跑通而写临时的移动/回滚** —— 哪怕 20 行，也会在 API 落地后变成第二套逻辑。

---

## 8. 下一步

框架侧按本稿实现 `moveStack` / `undo` / `canAcceptAll` + 数量内核，
然后活塞接入，**先跑通「普通推一条链 + 粘性拉一栈」**，再做压送。

熔炉、酿造作为第二批验证者，反过来检验接口是否通用（尤其是 `Rejected` 的粒度够不够）。
