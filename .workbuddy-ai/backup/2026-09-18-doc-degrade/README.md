# 备份快照 —— 2026-09-18 AGENTS.md 入口瘦身

## 这批快照对应哪个阶段

把 AGENTS.md 的「已完成功能」整章降级搬到 `docs/reference/completed-features.md`
**之前**的原样备份（用户拍板「清冗余，预算不动」，见 `docs/README.md` §4/§5）。

| 文件 | 当时状态 |
|---|---|
| `AGENTS.md` | **19,756 字符**（余量仅 244）；含「已完成功能」章节（77 项勾选、4,005 字符） |
| `docs-README.md` | 即当时的 `docs/README.md`：§4 只有 2026-09-16 一条冗余案例；§5 只有「文件索引 / 大段历史」两条降级范式 |

## 怎么恢复

```bash
cp .workbuddy-ai/backup/2026-09-18-doc-degrade/AGENTS.md AGENTS.md
cp .workbuddy-ai/backup/2026-09-18-doc-degrade/docs-README.md docs/README.md
python tools/doc_check.py   # 恢复后必须重跑
```

> ⚠️ 恢复 AGENTS.md 后 `docs/reference/completed-features.md` 会变成**内容重复的孤儿**，
> 需一并删除 —— **别两份都留**，那正是这次要清掉的负债。

## 何时可删

**这批改动提交进 git 之后即可删** —— git 才是长期安全网。
