# 文档手术备份（2026-09-16）

> ⚠️ **这是临时安全网，不是项目文档。**
> - 故意**不放进 `docs/`** —— 否则会被 `tools/doc_check.py` 的路径检查扫到（快照里含早已失效的旧路径）
> - 故意**不提交进 git**（`.workbuddy-ai/` 未被忽略，所以它们会出现在 `git status` 的 untracked 里，属预期）
>
> **用户提交那批文档改动后，本目录可直接删除** —— git 才是长期安全网，这层只管「提交前」。

## 起因

2026-09-16 对文档系统做了一轮大改（入口拆层 / 详单降级 / 归档 / 去重 / 补契约），
每一步都动了 `AGENTS.md` 或 `docs/archive/changelog.md`。为了能回滚，逐阶段留了快照。

## 快照清单

| 文件 | 是什么 | 何时有用 |
|---|---|---|
| `AGENTS.md.bak` | **P0-1 之前**（54,045 字符，含完整历史与逐文件详单） | 想整体回到手术前 → 用它 + `changelog.md.bak` |
| `AGENTS.md.pre-map` | P0-1 之后、**P0-2（详单降级）之前** | 只回滚 P0-2 |
| `AGENTS.md.pre-nav-merge` | P0-2 之后、**导航去重之前** | 只回滚导航合并 |
| `changelog.md.bak` | **补录断档之前**（24,612 字符） | 想撤销 `2026-09-01 ~ 09-04` 的补录 |

## 怎么恢复

```bash
cp .workbuddy-ai/backup/doc-surgery-2026-09-16/AGENTS.md.bak AGENTS.md
cp .workbuddy-ai/backup/doc-surgery-2026-09-16/changelog.md.bak docs/archive/changelog.md
```

⚠️ `AGENTS.md.bak` 是**手术前**状态，恢复它等于同时撤销 P0-1 / P0-2 与之后所有入口改动。

## 为什么不直接靠 git

因为那批改动**当时尚未提交** —— `git checkout` 会把它们一起丢掉，所以需要这一层。

## 为什么留 3 份 AGENTS.md 快照

最终状态已通过 `python tools/doc_check.py` 六项检查（路径 / 测试条数与测试树 / 归档连续性 /
入口体量 / 决策取代关系）。三份快照对应三个手术阶段，用于**定点回滚**而不是整体回滚；
若只想保留一个，留 `AGENTS.md.bak`（最完整）即可。
