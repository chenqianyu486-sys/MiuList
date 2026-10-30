#!/usr/bin/env bash
# MiuList 断点截图回归矩阵
#
# ## 它解决什么
#
# 这个 App 的自适应改动（窄屏两段、宽屏两栏、字号随宽度上调）全是**看截图**
# 才能确认的：数字算对了，不代表排出来好看。而手工改 `wm size` /
# `wm density` / `font_scale` 再一张张抓，既慢又一定会漏掉某个组合。
#
# 所以把「断点 × 字号」这个矩阵固化成脚本：一次跑完，每个断点四张截图
# （总览 / 课表 / 清单 / 抽屉）+ 对应的 uiautomator dump，外加一份 manifest。
#
# ## 用法
#
#   tools/capture-matrix.sh                 # 跑默认矩阵
#   tools/capture-matrix.sh B332 C411       # 只跑指定断点
#   tools/capture-matrix.sh --all           # 跑全部（含耗时最长的宽屏横屏）
#   tools/capture-matrix.sh --baseline      # 抓完顺便与 .tmp/adapt/baseline 比对
#
# ## 断点表
#
# 标签 | wm size | density | font_scale | 窗口宽 dp | 为什么有它
# ------|----------|---------|------------|-----------|--------------------------------------------
# A320  | 640x1366 | 320     | 1.0        | 320       | 最窄的手机。窄屏两段排法就在这一档验
# B332  | 664x1480 | 320     | 1.0        | 332       | 332dp 宽、标准字号
# R332f116|1080x2414|520    | 1.16       | 332       | **用户真机**：1080×2414 @520dpi + 系统字号 1.16
#                                           |           | —— 高度只有 743dp，13 节放不下，浮岛避让就靠这一档验
# C411  | 1080x2400| 420     | 1.0        | 411       | 设计基准。基准屏必须**一像素不变**
# D411f15|1080x2400| 420     | 1.5        | 411       | 基准屏 + 大字号
# E411f20|1080x2400| 420     | 2.0        | 411       | 基准屏 + 极端字号
# A320f15|640x1366 | 320     | 1.5        | 320       | 最窄 + 大字号。**P2 的正脸**：
#                                           |           | 用户放大字号时，App 不该再拿「内容缩放」压回去
# F600  | 1200x2000| 320     | 1.0        | 600       | MEDIUM 档起点
# G800  | 1600x1200| 320     | 1.0        | 800       | 宽屏横屏。两栏排法 + 宽屏字阶
# H320f20|640x1366 | 320     | 2.0        | 320       | 最窄 + 极端字号（最容易被顶破的一档）
# L914  | 2400x1080| 420     | 1.0        | 914×411   | **横屏**：C411 那一台转过来。宽 914dp、高只有 411dp
#                                           |           | —— 竖排布局在横屏里会被 chrome 吃光
# L640  | 1280x720 | 320     | 1.0        | 640×360   | **更矮的横屏**：桌面小窗 / 分屏窄条
#
# ## 收尾
#
# 脚本**一定会把设备设置还原**（size / density / font_scale 全部还原成跑之前的样子）。
# 这一步不是洁癖：设备是日常开发用的，留一个 1600x1200 的分辨率在里面，
# 下次跑别的脚本会以为屏幕就是那个大小，排查起来非常费时间。
set -u

export PATH="$HOME/.workbuddy/binaries/android-sdk/platform-tools:$PATH"
export MSYS_NO_PATHCONV=1

PKG=com.aistudio.scheduletodo.unmq
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# **必须 cd 到工程根**：`adb pull` 是 Windows 程序，不认 Git Bash 的 /d/... 路径。
# 而 MSYS_NO_PATHCONV=1 又挡住了 MSYS 的自动转换，于是只能给**相对路径**，
# 让 adb 按自己的 CWD 解析。（早先一版写绝对路径，pull 全部静默失败、
# dump 文件一个都没落盘。）
cd "$ROOT" || exit 1
OUT_ROOT=".tmp/adapt/matrix"
BASELINE_DIR=".tmp/adapt/baseline"

# 标签|wm size|density|font_scale
MATRIX=(
  "A320|640x1366|320|1.0"
  "B332|664x1480|320|1.0"
  "R332f116|1080x2414|520|1.16"
  "C411|1080x2400|420|1.0"
  "D411f15|1080x2400|420|1.5"
  "E411f20|1080x2400|420|2.0"
  "A320f15|640x1366|320|1.5"
  "H320f20|640x1366|320|2.0"
  "F600|1200x2000|320|1.0"
  "G800|1600x1200|320|1.0"
  "L914|2400x1080|420|1.0"
  "L640|1280x720|320|1.0"
)

DO_ALL=0
DO_BASELINE=0
SELECTED=()

for arg in "$@"; do
  case "$arg" in
    --all) DO_ALL=1 ;;
    --baseline) DO_BASELINE=1 ;;
    -h|--help) sed -n '2,40p' "$0"; exit 0 ;;
    *) SELECTED+=("$arg") ;;
  esac
done

if ! command -v adb >/dev/null 2>&1; then
  echo "!! 找不到 adb。先确认 \$HOME/.workbuddy/binaries/android-sdk/platform-tools 在 PATH 上。" >&2
  exit 1
fi
if ! adb devices | tail -n +2 | grep -q "device"; then
  echo "!! 没有连上的设备。先起模拟器再跑这个脚本。" >&2
  exit 1
fi

# ---- 记住设备当前设置，跑完还原 ----
ORIG_FILE=".tmp/adapt/orig_device.txt"
CUR_SIZE="$(adb shell wm size 2>/dev/null | tr -d '\r' | sed -n 's/^Override size: //p')"
CUR_DENSITY="$(adb shell wm density 2>/dev/null | tr -d '\r' | sed -n 's/^Override density: //p')"
CUR_FONT="$(adb shell settings get system font_scale 2>/dev/null | tr -d '\r')"

# 当前设备是不是**矩阵里的某一档**（即上一轮跑剩下的中间状态）。
is_matrix_state() {
  local size=$1 density=$2
  [ -z "$size" ] && return 1
  for entry in "${MATRIX[@]}"; do
    local rest="${entry#*|}"
    local s="${rest%%|*}"
    local d="${rest#*|}"
    d="${d%%|*}"
    if [ "$s" = "$size" ] && [ "$d" = "$density" ]; then
      return 0
    fi
  done
  return 1
}

# 原始设置怎么定（这个坑踩过）：
#
# 矩阵跑一半被打断时，退出钩子来不及还原设备 → 设备停在某一档上；
# 下一轮开跑时把它当成「原始设置」，还原时又把它写回去 —— 越跑越偏，
# 最后设备被留在最极端的那一档上（320dp + 2.0 倍字号），
# 打开模拟器看到的就是一张完全走形的界面。
#
# 所以：**只有当前状态不是矩阵里的任何一档时**，才把它当作原始设置记下来；
# 否则从文件里读上一次记下的原始设置。
if is_matrix_state "$CUR_SIZE" "$CUR_DENSITY"; then
  if [ -f "$ORIG_FILE" ]; then
    ORIG_SIZE="$(sed -n 1p "$ORIG_FILE")"
    ORIG_DENSITY="$(sed -n 2p "$ORIG_FILE")"
    ORIG_FONT="$(sed -n 3p "$ORIG_FILE")"
  else
    # 没有记录：还原成 AVD 的原生尺寸（wm size reset）
    ORIG_SIZE=""
    ORIG_DENSITY=""
    ORIG_FONT="1.0"
  fi
  echo "（当前设备停在矩阵的某一档上，按上一次记下的原始设置还原）"
else
  mkdir -p "$(dirname "$ORIG_FILE")"
  printf '%s\n%s\n%s\n' "$CUR_SIZE" "$CUR_DENSITY" "$CUR_FONT" > "$ORIG_FILE"
  ORIG_SIZE="$CUR_SIZE"
  ORIG_DENSITY="$CUR_DENSITY"
  ORIG_FONT="$CUR_FONT"
fi

restore() {
  if [ -n "$ORIG_SIZE" ]; then
    adb shell wm size "$ORIG_SIZE" >/dev/null 2>&1
  else
    adb shell wm size reset >/dev/null 2>&1
  fi
  if [ -n "$ORIG_DENSITY" ]; then
    adb shell wm density "$ORIG_DENSITY" >/dev/null 2>&1
  else
    adb shell wm density reset >/dev/null 2>&1
  fi
  if [ -n "$ORIG_FONT" ] && [ "$ORIG_FONT" != "null" ]; then
    adb shell settings put system font_scale "$ORIG_FONT" >/dev/null 2>&1
  fi
  echo ""
  echo "设备设置已还原：size=${ORIG_SIZE:-reset} density=${ORIG_DENSITY:-reset} font_scale=${ORIG_FONT:-?}"
}
trap restore EXIT

mkdir -p "$OUT_ROOT"

dump() {
  # **先删旧文件**：`uiautomator dump` 失败时不会覆盖目标文件，
  # 于是 pull 回来的是上一次的内容 —— 看起来像「App 崩了」，
  # 其实是上一次 dump 的残留。删掉再 dump，失败就是失败。
  # 顺带重试三次：设备偶尔会报 "UiAutomationService already registered"。
  adb shell rm -f /sdcard/ui.xml >/dev/null 2>&1
  local i
  for i in 1 2 3; do
    if adb shell uiautomator dump /sdcard/ui.xml 2>&1 | grep -q "UI hierchary dumped"; then
      adb pull /sdcard/ui.xml "$1" >/dev/null 2>&1 && return 0
    fi
    sleep 2
  done
  echo "  !! dump 失败（重试 3 次）"
  return 1
}

tap_label() {
  local text=$1
  local tmp="$OUT_ROOT/.tmp_dump.xml"
  dump "$tmp"
  local bounds
  # 先找可见文字，找不到再找无障碍标签（content-desc）。
  # 矮屏（横屏）时浮岛只留图标，文字标签不画，只剩 content-desc。
  bounds="$(grep -o "text=\"$text\"[^>]*bounds=\"[^\"]*\"" "$tmp" | head -1 |
    sed 's/.*bounds="\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\]".*/\1 \2 \3 \4/')"
  if [ -z "$bounds" ]; then
    bounds="$(grep -o "content-desc=\"$text\"[^>]*bounds=\"[^\"]*\"" "$tmp" | head -1 |
      sed 's/.*bounds="\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\]".*/\1 \2 \3 \4/')"
  fi
  rm -f "$tmp"
  if [ -z "$bounds" ]; then
    echo "  !! 找不到可点的「$text」，跳过这一步"
    return 1
  fi
  set -- $bounds
  local x=$(( ($1 + $3) / 2 )) y=$(( ($2 + $4) / 2 ))
  adb shell input tap "$x" "$y"
  sleep 2
  return 0
}

# 按**正则**找一段文字并点它。
#
# 为什么要单独一个：主卡正面那行日期是「2026年10月22日 星期四」，
# 每天都不一样，写死文字就点了空气 —— 用「年/月/日」的模式去抓才行。
tap_pattern() {
  local pattern=$1
  local tmp="$OUT_ROOT/.tmp_dump.xml"
  dump "$tmp"
  local bounds
  bounds="$(grep -o "$pattern[^>]*bounds=\"[^\"]*\"" "$tmp" | head -1 |
    sed 's/.*bounds="\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\]".*/\1 \2 \3 \4/')"
  rm -f "$tmp"
  if [ -z "$bounds" ]; then
    echo "  !! 找不到匹配「$pattern」的文字，跳过这一步"
    return 1
  fi
  set -- $bounds
  local x=$(( ($1 + $3) / 2 )) y=$(( ($2 + $4) / 2 ))
  adb shell input tap "$x" "$y"
  sleep 2
  return 0
}

capture_one() {
  local label=$1 size=$2 density=$3 font=$4
  local out="$OUT_ROOT/$label"
  mkdir -p "$out"

  adb shell wm size "$size" >/dev/null
  adb shell wm density "$density" >/dev/null
  adb shell settings put system font_scale "$font" >/dev/null
  sleep 1
  adb shell am force-stop $PKG >/dev/null
  adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
  # 等够久：App 启动要读数据库 + 拉天气，太早截图只会拍到加载态的转圈。
  sleep 10

  adb exec-out screencap -p > "$out/dashboard.png"
  dump "$out/dashboard.xml"

  # 主卡的**背面**（学在浙大待交作业）：点一下卡就翻过去。
  # 这一面以前会被卡的下沿裁掉（用户真机 332dp + 1.16 倍字号：
  # 背面要 141.5dp、正面只要 124.8dp，小卡下面那行只剩半个字），
  # 所以矩阵里必须有一张它的截图 —— 只看正面看不出这个 bug。
  tap_pattern 'text="[0-9]*年[0-9]*月[0-9]*日' && {
    adb exec-out screencap -p > "$out/dashboard_flip.png"
    dump "$out/dashboard_flip.xml"
    # 翻回正面，接着抓别的页面
    tap_pattern 'text="学在浙大"' && true
  }

  tap_label 课表 && { adb exec-out screencap -p > "$out/schedule.png"; dump "$out/schedule.xml"; }
  tap_label 清单 && { adb exec-out screencap -p > "$out/checklist.png"; dump "$out/checklist.xml"; }
  tap_label 总览

  # 抽屉：点顶栏的学期名（宽屏上位置按比例走，别写死像素）
  adb shell input tap $(( W / 5 )) $(( H / 12 ))
  sleep 1
  adb exec-out screencap -p > "$out/drawer.png"
  dump "$out/drawer.xml"
  adb shell input keyevent KEYCODE_BACK
  sleep 1

  echo "  抓到 $(ls "$out" | wc -l) 个文件"
}

echo "MiuList 断点矩阵"
echo ""

for entry in "${MATRIX[@]}"; do
  label="${entry%%|*}"
  rest="${entry#*|}"
  size="${rest%%|*}"
  rest="${rest#*|}"
  density="${rest%%|*}"
  font="${rest##*|}"

  # 默认跳过最慢的两个宽屏断点（要 --all 才跑）
  if [ "$DO_ALL" -eq 0 ]; then
    case "$label" in
      F600|G800) continue ;;
    esac
  fi

  if [ ${#SELECTED[@]} -gt 0 ]; then
    found=0
    for sel in "${SELECTED[@]}"; do
      [ "$sel" = "$label" ] && found=1
    done
    [ "$found" -eq 0 ] && continue
  fi

  echo "▸ $label  size=$size density=$density font=$font"
  W="${size%x*}"
  H="${size#*x}"
  capture_one "$label" "$size" "$density" "$font"

  # 越界检测：这一档里有没有节点被挤出屏幕
  if [ -f "tools/check_bounds.py" ]; then
    python "tools/check_bounds.py" "$OUT_ROOT/$label" 2>/dev/null || true
  fi
done

# ---- manifest ----
{
  echo "# MiuList 断点矩阵截图"
  echo ""
  echo "抓取时间：$(date '+%Y-%m-%d %H:%M:%S')"
  echo ""
  echo "| 断点 | 窗口宽 dp | 系统字号 | 截图 |"
  echo "|------|-----------|---------|------|"
} > "$OUT_ROOT/manifest.md"

for entry in "${MATRIX[@]}"; do
  label="${entry%%|*}"
  rest="${entry#*|}"
  size="${rest%%|*}"
  rest="${rest#*|}"
  density="${rest%%|*}"
  font="${rest##*|}"
  W="${size%x*}"
  H="${size#*x}"
  dpw=$(( W * 160 / density ))
  [ -d "$OUT_ROOT/$label" ] || continue
  shots="$(ls "$OUT_ROOT/$label"/*.png 2>/dev/null | wc -l)"
  echo "| $label | ${dpw}dp | $font | $shots 张 |" >> "$OUT_ROOT/manifest.md"
done

echo ""
echo "输出目录：$OUT_ROOT"

if [ "$DO_BASELINE" -eq 1 ] && [ -d "$BASELINE_DIR" ]; then
  echo ""
  echo "与基线比对："
  python "tools/compare_baseline.py" "$BASELINE_DIR" "$OUT_ROOT"
fi
