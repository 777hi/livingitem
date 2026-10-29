#!/bin/sh
# 启用仓库内的 Git hooks（一次即可）。
# 撤销：git config --unset core.hooksPath
set -e
cd "$(dirname "$0")/.."
git config core.hooksPath .githooks
echo "已设置 core.hooksPath = .githooks（四个门禁随 git commit 自动运行）"
