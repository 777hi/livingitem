# 开发问题记录

## Edit 工具因 Read 工具访问限制而失败

**日期**：2026-07-13

**现象**：
使用 Edit 工具修改 Java 文件时报错 `String to replace not found in file`，即使字符串完全匹配也无法替换。

**根因**：
Edit 工具要求必须先通过 Read 工具读取文件才能编辑。当 Read 工具因访问限制返回 `Access denied` 时，Edit 工具的内部状态认为文件未被读取，导致后续编辑操作失败。

**关键发现**：
- 文件的换行符格式（CRLF/LF）不是导致 Edit 失败的原因
- 字符串匹配本身没有问题（通过 PowerShell 验证字节完全一致）
- 问题出在 Read 工具的访问权限——只要 Read 能成功读取，Edit 就能正常使用

**解决方案**：
如果遇到 Edit 工具失败，先确认 Read 工具能否正常读取目标文件。如果 Read 返回 Access denied，可以尝试重新执行 Read 操作，通常后续就能成功。

**历史背景**：
之前曾误判为 CRLF 换行符问题，执行了批量 CRLF->LF 转换。实际上 CRLF 和 LF 的区别仅在于换行符编码：
- LF（`\n`，0x0A）：Linux/macOS/Unix 标准
- CRLF（`\r\n`，0x0D 0x0A）：Windows 标准

CRLF 问题确实会导致 PowerShell 字符串替换失败（`\r\n` vs `\n` 不匹配），但不是 Edit 工具失败的原因。

---

## IronChests 模组箱子中活按钮不显示

**日期**：2026-07-13

**现象**：
活按钮（LivingButton）在原版箱子和潜影盒界面中正常显示，但在 IronChests 模组的箱子界面中不显示。

**根因**：
`AbstractContainerScreenMixin.living_item()` 方法中存在白名单检查：
`java
if (!((Screen) this instanceof ContainerScreen || (Screen) this instanceof ShulkerBoxScreen)) {
    return;
}
`
IronChests 使用自定义的 `IronChestScreen` 类，不在白名单内，导致方法提前返回。

**解决方案**：
移除白名单检查，让按钮在所有 `AbstractContainerScreen` 子类界面中显示。因为 Mixin 的目标是 `AbstractContainerScreen`，所有容器界面都会被注入，无需额外限制。

**副作用**：
此修改使活按钮兼容所有使用自定义 Screen 的模组容器，不仅限于 IronChests。

**注意**：
此白名单检查是在之前 CRLF 问题期间，通过 PowerShell 命令反复尝试修改文件时意外引入的。原始代码中可能没有此检查。