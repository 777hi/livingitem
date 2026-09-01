# 项目长期记忆（Living Item Mod）

## 纹理约定
- 涂蜡铜灯图标 = 未涂蜡对应图标 + 外圈黄色边框。边框 60 像素，颜色 (232,160,62,255)，四种锈蚀等级（copper/exposed/weathered/oxidized）边框掩码完全一致。发光版（lit）同理：内部取未涂蜡发光图、外圈填黄框。
- item 纹理均为 16×16 PNG（含 P 调色板与 RGBA 两种，均有效）。

## 环境
- Python 隔离 venv：`C:/Users/AI-777hi/.workbuddy-ai/binaries/python/envs/default`（已装 Pillow 12.3.0），处理图片用其 `Scripts/python.exe`。
