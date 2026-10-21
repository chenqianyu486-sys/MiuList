# tools/ — 断点截图回归矩阵

这个目录里的三个脚本是一套：**把「自适应改动对不对」从肉眼判断变成可重复的检查**。

## 为什么需要它

这个 App 的排版改动（窄屏两段、宽屏两栏、字号随宽度上调）**看截图才能确认**，
数字算对了不代表排出来好看。而手工改 `wm size` / `wm density` / `font_scale`
再一张张抓，既慢又一定会漏掉某个组合。

这套脚本把「断点 × 字号」固化成矩阵，一次跑完。

## 三个脚本

| 脚本 | 干什么 |
|------|--------|
| `capture-matrix.sh` | 按矩阵逐断点抓图 + dump，产出 `manifest.md`，**跑完自动还原设备设置** |
| `check_bounds.py` | 解析 dump，找出被挤出屏幕 / 被挤成 0 尺寸的节点 |
| `check_proportions.py` | 量各断点**渲染出来**的尺寸，核对「整页比例」与设计稿是否一致 |
| `compare_baseline.py` | 与基线目录逐张做像素比对，报告差异率并写出差异图 |

## 快速上手

```bash
# 1. 抓一遍（默认断点；要连宽屏那两档加 --all）
tools/capture-matrix.sh

# 2. 看有没有节点越界（capture-matrix 已经自动跑过一次）
python tools/check_bounds.py .tmp/adapt/matrix

# 3. 核对比例：各断点渲染出来的尺寸与设计稿是否成比例
python tools/check_proportions.py .tmp/adapt/matrix

# 4. 存成基线（改动之前做这一步）
python tools/compare_baseline.py --save .tmp/adapt/baseline .tmp/adapt/matrix

# 5. 改完代码再抓一遍，和基线比
tools/capture-matrix.sh --baseline
```

## 矩阵覆盖的断点

| 标签 | 窗口宽 dp | 系统字号 | 这一档验什么 |
|------|-----------|---------|-------------|
| A320 | 320 | 1.0 | 最窄的手机。窄屏上下两段排法 |
| B332 | 332 | 1.0 | 用户真机 |
| C411 | 411 | 1.0 | **设计基准。必须一分不变** |
| D411f15 | 411 | 1.5 | 基准屏 + 大字号 |
| E411f20 | 411 | 2.0 | 基准屏 + 极端字号 |
| A320f15 | 320 | 1.5 | 最窄 + 大字号。**P2 的正脸**：用户放大字号时 App 不该再拿「内容缩放」压回去 |
| H320f20 | 320 | 2.0 | 最窄 + 极端字号（最容易被顶破的一档） |
| F600 | 600 | 1.0 | MEDIUM 档起点 |
| G800 | 800 | 1.0 | 宽屏横屏。两栏排法 + 宽屏字阶 |
| **L914** | **914×411** | 1.0 | **手机横屏**：宽 914dp、高只有 411dp —— 竖排布局在横屏里会被 chrome 吃光 |
| **L640** | **640×360** | 1.0 | **更矮的横屏**：桌面小窗 / 分屏窄条 |

横屏这两档最慢，但也是**唯一能暴露「高度不够」问题**的两档，别把它们从矩阵里删掉。

## 前提

- 模拟器在跑（`adb devices` 能看到）。
- APK 装好了（`adb install -r app/build/outputs/apk/debug/app-debug.apk`）。
- Windows 上用 Git Bash 跑 `capture-matrix.sh`；`python` 需要带 Pillow
  （`compare_baseline.py` 要用）。

## 两条硬规矩

1. **`capture-matrix.sh` 一定会还原设备设置**（size / density / font_scale）。
   设备是日常开发用的，留一个 1600x1200 在里面，下次跑别的脚本会以为
   屏幕本来就是那个大小，排查起来非常费时间。

2. **`check_bounds.py` 不报「文字被 Ellipsis 截断」**。
   uiautomator 拿到的是 Compose 的语义**原文**，截断只发生在渲染层，dump 里看不出来。
   要判断截断得看截图——那是 `compare_baseline.py` 的活。
   这里只报**结构上确凿**的问题（越界、挤没），误报一多，脚本就没人跑了。
