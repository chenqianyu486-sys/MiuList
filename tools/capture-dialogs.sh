#!/usr/bin/env bash
# MiuList 弹层矩阵：每个断点下逐个打开各弹层并截图
#
# ## 它解决什么
#
# `capture-matrix.sh` 只抓四个**页面**（总览 / 课表 / 清单 / 抽屉）。
# 但自适应的问题有一大半在**弹层**上：弹层有自己的一套缩放
# （`AdaptiveDialogContent`）、自己的安全区、自己的高度上限，
# 而且弹层里的固定高度最容易在大字号下顶破。
#
# 所以这个脚本把弹层也做成矩阵：每个断点逐个打开各弹层，各截一张。
#
# ## 用法
#
#   tools/capture-dialogs.sh                 # 跑默认断点
#   tools/capture-dialogs.sh C411 G800       # 只跑指定断点
#
# ## 断点表
#
# 标签 | wm size | density | font_scale | 窗口宽 dp | 为什么有它
# ------|----------|---------|------------|-----------|------------
# A320  | 640x1366 | 320     | 1.0        | 320       | 最窄的手机
# R332f116|1080x2414|520   | 1.16       | 332       | **用户真机**（高度只有 743dp，弹层最挤）
# C411  | 1080x2400| 420     | 1.0        | 411       | 设计基准
# G800  | 1600x1200| 320     | 1.0        | 800       | 宽屏
# E411f20|1080x2400| 420     | 2.0        | 411       | 基准屏 + 极端字号
#
# ## 收尾
#
# 脚本**一定会把设备设置还原**（size / density / font_scale）。
set -u

export PATH="$HOME/.workbuddy/binaries/android-sdk/platform-tools:$PATH"
export MSYS_NO_PATHCONV=1

PKG=com.aistudio.scheduletodo.unmq
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT" || exit 1
OUT_ROOT=".tmp/adapt/dialogs"

MATRIX=(
  "A320|640x1366|320|1.0"
  "R332f116|1080x2414|520|1.16"
  "C411|1080x2400|420|1.0"
  "G800|1600x1200|320|1.0"
  "E411f20|1080x2400|420|2.0"
)

DO_ALL=0
SELECTED=()
for arg in "$@"; do
  case "$arg" in
    -h|--help) sed -n '2,32p' "$0"; exit 0 ;;
    *) SELECTED+=("$arg") ;;
  esac
done

if ! command -v adb >/dev/null 2>&1; then
  echo "!! 找不到 adb。" >&2
  exit 1
fi
if ! adb devices | tail -n +2 | grep -q "device"; then
  echo "!! 没有连上的设备。" >&2
  exit 1
fi

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

# 原始设置的取法见 capture-matrix.sh 里的同一段注释：
# 只有当前状态**不是**矩阵里的任何一档时才算原始设置，否则从文件里读。
if is_matrix_state "$CUR_SIZE" "$CUR_DENSITY"; then
  if [ -f "$ORIG_FILE" ]; then
    ORIG_SIZE="$(sed -n 1p "$ORIG_FILE")"
    ORIG_DENSITY="$(sed -n 2p "$ORIG_FILE")"
    ORIG_FONT="$(sed -n 3p "$ORIG_FILE")"
  else
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

OUT=""
FAILED=""

shot() {
  adb exec-out screencap -p > "$OUT/$1.png"
  echo "    · $1.png"
}

dump_to() {
  local out=$1
  # **先删旧文件**：`uiautomator dump` 失败时不会覆盖目标文件，
  # 于是 pull 回来的是上一次的内容 —— 看起来像「App 崩了」，
  # 其实是上一次 dump 的残留。删掉再 dump，失败就是失败。
  # 顺带重试三次：设备偶尔会报 "UiAutomationService already registered"。
  adb shell rm -f /sdcard/ui.xml >/dev/null 2>&1
  local i
  for i in 1 2 3; do
    if timeout 25 adb shell uiautomator dump /sdcard/ui.xml 2>&1 | grep -q "UI hierchary dumped"; then
      adb pull /sdcard/ui.xml "$out" >/dev/null 2>&1 && return 0
    fi
    sleep 2
  done
  echo "    !! dump 失败（重试 3 次）"
  return 1
}

# 重启 App：回到干净状态。
#
# ## 为什么每个弹层前都要重启
#
# 有些动作会让 App 进入**永不 idle** 的状态（例如触发了同步 → 整屏换成
# CircularProgressIndicator，那个动画转不停）。uiautomator dump 要等 UI 空闲，
# 于是它一直等 → 系统弹出「Checking info…」遮罩 → 后面所有 dump 与点击全废。
#
# 所以每次抓弹层前重启一次：慢一点，但每次都是从确定的干净状态开始。
relaunch() {
  adb shell am force-stop $PKG >/dev/null
  adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
  # 等够久：App 启动要读数据库 + 拉天气，太早截图只会拍到加载态的转圈。
  sleep 8
}

# 按可见文字（或无障碍描述）找节点并点它的中心。
#
# 三级匹配：完全相等 → 包含 → 无障碍描述。课程名带后缀（「大学物理（甲）II」）
# 时完全相等会找不到，所以必须有「包含」这一级。
tap_label() {
  local text=$1
  local tmp="$OUT_ROOT/.tmp_dump.xml"
  dump_to "$tmp"
  local bounds
  bounds="$(grep -o "text=\"$text\"[^>]*bounds=\"[^\"]*\"" "$tmp" 2>/dev/null | head -1 |
    sed 's/.*bounds="\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\]".*/\1 \2 \3 \4/')"
  if [ -z "$bounds" ]; then
    # 「包含」匹配只给 3 个字以上的文本用：2 个字的太容易误伤
    # （「设置」会命中「按类别批量设置开关」那一行，把抽屉点开成别的弹层）。
    if [ ${#text} -ge 3 ]; then
      bounds="$(grep -o "text=\"[^\"]*$text[^\"]*\"[^>]*bounds=\"[^\"]*\"" "$tmp" 2>/dev/null | head -1 |
        sed 's/.*bounds="\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\]".*/\1 \2 \3 \4/')"
    fi
  fi
  if [ -z "$bounds" ]; then
    bounds="$(grep -o "content-desc=\"$text\"[^>]*bounds=\"[^\"]*\"" "$tmp" 2>/dev/null | head -1 |
      sed 's/.*bounds="\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\]".*/\1 \2 \3 \4/')"
  fi
  rm -f "$tmp"
  if [ -z "$bounds" ]; then
    echo "    !! 找不到「$text」"
    FAILED="$FAILED $text"
    return 1
  fi
  set -- $bounds
  local x=$(( ($1 + $3) / 2 )) y=$(( ($2 + $4) / 2 ))
  adb shell input tap "$x" "$y"
  sleep 2
  return 0
}

back() {
  adb shell input keyevent KEYCODE_BACK >/dev/null
  sleep 1
}

# 回到总览页（浮岛的三个页签之一）
to_dashboard() { tap_label "总览" >/dev/null 2>&1; sleep 1; }

open_drawer() {
  # 顶栏左侧的学期名：点屏幕左上那一块（宽屏上位置按比例走）
  adb shell input tap $(( W / 5 )) $(( H / 12 ))
  sleep 2
}

# 抽屉是**可滚动**的：「设置」那一行在底部，初始状态在屏外，
# dump 里根本没有它 —— 必须先滚一下再点。
scroll_drawer_down() {
  adb shell input swipe $(( W / 2 )) $(( H * 3 / 4 )) $(( W / 2 )) $(( H / 4 )) 300
  sleep 2
}

# 设置页也是可滚动的：「自定义壁纸」那一卡在下面。
scroll_page_down() {
  adb shell input swipe $(( W / 2 )) $(( H * 3 / 4 )) $(( W / 2 )) $(( H / 3 )) 300
  sleep 2
}

# 找不到就往下滚一屏再找（最多 4 轮）。
#
# 抽屉与设置页都是**可滚动**的：「设置」那一行在抽屉底部，
# 「自定义壁纸」那一卡在设置页下面 —— 初始状态在屏外，dump 里根本没有。
tap_with_scroll() {
  local text=$1
  local i
  for i in 1 2 3 4; do
    if tap_label "$text" >/dev/null 2>&1; then
      return 0
    fi
    scroll_drawer_down
  done
  echo "    !! 找不到「$text」（滚到底也没有）"
  FAILED="$FAILED $text"
  return 1
}

# 打开一个弹层 → 截图 → 关掉；失败也要继续往下走
#
# 失败会**重试一次**：切页有动画，dump 可能抓到转场中途的界面，
# 那时那一行还没摆好，按文字就找不到。
capture_dialog() {
  local name=$1
  shift
  if "$@"; then
    shot "$name"
    back
    return 0
  fi
  sleep 2
  if "$@"; then
    shot "$name"
    back
    return 0
  fi
}

capture_one() {
  local label=$1 size=$2 density=$3 font=$4
  OUT="$OUT_ROOT/$label"
  rm -rf "$OUT"
  mkdir -p "$OUT"

  adb shell wm size "$size" >/dev/null
  adb shell wm density "$density" >/dev/null
  adb shell settings put system font_scale "$font" >/dev/null
  sleep 1
  adb shell am force-stop $PKG >/dev/null
  adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
  sleep 5

  echo "▸ $label  size=$size density=$density font=$font"
  W="${size%x*}"
  H="${size#*x}"

  # ---------- 页面 ----------
  shot 01_dashboard
  tap_label "课表" >/dev/null 2>&1; shot 02_schedule
  tap_label "清单" >/dev/null 2>&1; shot 03_checklist

  # ---------- 弹层 ----------
  # 每个弹层都从「重启后的干净状态」出发（见 relaunch 的说明）。

  # 周次选择：主卡上的「第 N 周」chip
  relaunch
  capture_dialog 04_week_picker tap_label "第 6 周"

  # 任务详情：清单页点一张待办
  relaunch
  tap_label "清单" >/dev/null 2>&1
  capture_dialog 05_task_detail tap_label "第6周作业"

  # 新建待办：右下角 FAB（**只在清单页**有）
  relaunch
  tap_label "清单" >/dev/null 2>&1
  capture_dialog 06_task_edit tap_label "添加待办"

  # 课程详情：总览页点一张课程卡
  relaunch
  capture_dialog 07_course_detail tap_label "大学物理"

  # 抽屉本身
  relaunch
  open_drawer
  shot 08_drawer

  # 抽屉里的五个弹层（关掉后回到抽屉）
  relaunch; open_drawer
  capture_dialog 09_cloud_account tap_label "ZJU"
  relaunch; open_drawer
  capture_dialog 10_academic_calendar tap_label "当前学期"
  relaunch; open_drawer
  capture_dialog 11_course_category tap_label "课程分类"
  relaunch; open_drawer
  capture_dialog 12_category_rules tap_label "课程联动规则"
  relaunch; open_drawer
  capture_dialog 13_task_rules tap_label "待办生成规则"

  # 添加课程：抽屉里「本学期课程」那节的「添加」
  relaunch; open_drawer
  capture_dialog 14_add_course tap_with_scroll "添加"

  # 设置页本身（「设置」在抽屉底部，要先滚出来）
  relaunch; open_drawer
  tap_with_scroll "设置" >/dev/null 2>&1
  sleep 1
  shot 15_settings

  # 设置页里的三个弹层
  relaunch; open_drawer; tap_with_scroll "设置" >/dev/null 2>&1
  capture_dialog 16_transfer tap_with_scroll "管理"
  relaunch; open_drawer; tap_with_scroll "设置" >/dev/null 2>&1
  capture_dialog 17_task_category_manage tap_with_scroll "管理"
  relaunch; open_drawer; tap_with_scroll "设置" >/dev/null 2>&1
  capture_dialog 18_wallpaper tap_with_scroll "更换"

  # 清单页的「+ 新建」→ 新建待办类型
  relaunch
  tap_label "清单" >/dev/null 2>&1
  capture_dialog 19_new_task_type tap_with_scroll "+ 新建"

  echo "  抓到 $(ls "$OUT"/*.png 2>/dev/null | wc -l) 张"
}

mkdir -p "$OUT_ROOT"

for entry in "${MATRIX[@]}"; do
  label="${entry%%|*}"
  rest="${entry#*|}"
  size="${rest%%|*}"
  rest="${rest#*|}"
  density="${rest%%|*}"
  font="${rest##*|}"

  if [ ${#SELECTED[@]} -gt 0 ]; then
    found=0
    for sel in "${SELECTED[@]}"; do
      [ "$sel" = "$label" ] && found=1
    done
    [ "$found" -eq 0 ] && continue
  fi

  capture_one "$label" "$size" "$density" "$font"
done

echo ""
echo "输出目录：$OUT_ROOT"
if [ -n "$FAILED" ]; then
  echo "没点到的入口：$FAILED"
fi
