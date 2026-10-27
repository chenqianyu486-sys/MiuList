#!/usr/bin/env bash
# MiuList「一键生成待办」开关矩阵：抓开关开 / 关两种状态下的各个界面
#
# ## 它解决什么
#
# 这个开关改的是**界面上有没有东西**：顶栏那颗 ✨、「更多」抽屉里那两行规则、
# 以及新手引导走几步。所以它必须在**不同的屏幕尺寸**下看：
# 少一行 / 多一行之后，排版有没有被挤坏，窄屏 + 大字号时面板还装不装得下。
#
# ## 每断点抓什么
#
# | 文件 | 内容 |
# |------|------|
# | 01_topbar_off / 09_topbar_on | 总览页顶栏（关：没有 ✨；开：有 ✨） |
# | 02_drawer_off / 10_drawer_on | 「更多」抽屉（开关行 + 规则行的显隐） |
# | 03~08_guide_off_* | 引导的**轻量三步**（开关关着时走的那套） |
# | 05_guide_after_enable | 在引导里按下「开启」之后落到「课程分类」 |
# | 11~15_guide_on_* | 引导的**完整五步**（开关开着时走的那套） |
#
# ## 用法
#
#   tools/capture-quickgenerate.sh                # 跑默认断点
#   tools/capture-quickgenerate.sh C411           # 只跑指定断点
#   tools/capture-quickgenerate.sh --guide-only    # 只抓引导（少一半图，跑得快）
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
OUT_ROOT=".tmp/adapt/quickgen"

MATRIX=(
  "R332f116|1080x2414|520|1.16"
  "C411|1080x2400|420|1.0"
  "A320|640x1366|320|1.0"
  "E411f20|1080x2400|420|2.0"
)

GUIDE_ONLY=0
SELECTED=()
for arg in "$@"; do
  case "$arg" in
    -h|--help) sed -n '2,28p' "$0"; exit 0 ;;
    --guide-only) GUIDE_ONLY=1 ;;
    *) SELECTED+=("$arg") ;;
  esac
done

command -v adb >/dev/null 2>&1 || { echo "!! 找不到 adb。" >&2; exit 1; }
adb devices | tail -n +2 | grep -q "device" || { echo "!! 没有连上的设备。" >&2; exit 1; }

ORIG_FILE=".tmp/adapt/orig_device_quickgen.txt"
CUR_SIZE="$(adb shell wm size 2>/dev/null | tr -d '\r' | grep -E 'Override size|Physical size' | tail -1 | sed -n 's/.*size: //p')"
CUR_DENSITY="$(adb shell wm density 2>/dev/null | tr -d '\r' | grep -E 'Override density|Physical density' | tail -1 | sed -n 's/.*density: //p')"
CUR_FONT="$(adb shell settings get system font_scale 2>/dev/null | tr -d '\r')"

mkdir -p "$(dirname "$ORIG_FILE")"
printf '%s\n%s\n%s\n' "$CUR_SIZE" "$CUR_DENSITY" "$CUR_FONT" > "$ORIG_FILE"

restore() {
  if [ -n "$CUR_SIZE" ]; then adb shell wm size "$CUR_SIZE" >/dev/null 2>&1; else adb shell wm size reset >/dev/null 2>&1; fi
  if [ -n "$CUR_DENSITY" ]; then adb shell wm density "$CUR_DENSITY" >/dev/null 2>&1; else adb shell wm density reset >/dev/null 2>&1; fi
  if [ -n "$CUR_FONT" ] && [ "$CUR_FONT" != "null" ]; then adb shell settings put system font_scale "$CUR_FONT" >/dev/null 2>&1; fi
  echo ""
  echo "设备设置已还原：size=${CUR_SIZE:-reset} density=${CUR_DENSITY:-reset} font_scale=${CUR_FONT:-?}"
}
trap restore EXIT

OUT=""
FAILED=""

get_size() {
  adb shell wm size 2>/dev/null | tr -d '\r' | grep -E 'Override size|Physical size' | tail -1 |
    sed -n 's/.*size: \([0-9]*\)x\([0-9]*\).*/\1 \2/p'
}

shot() {
  adb exec-out screencap -p > "$OUT/$1.png"
  echo "    · $1.png"
}

dump_to() {
  local out=$1
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

# 按可见文字（或无障碍描述）找节点并点它的中心。
# **每点一次都重新 dump**：面板内容一变高度就变，旧的坐标会落到别的东西上。
tap_label() {
  local text=$1
  local tmp="$OUT_ROOT/.tmp_dump.xml"
  dump_to "$tmp" || return 1
  local bounds
  bounds="$(grep -o "text=\"$text\"[^>]*bounds=\"[^\"]*\"" "$tmp" 2>/dev/null | head -1 |
    sed 's/.*bounds="\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\]".*/\1 \2 \3 \4/')"
  if [ -z "$bounds" ] && [ ${#text} -ge 3 ]; then
    bounds="$(grep -o "text=\"[^\"]*$text[^\"]*\"[^>]*bounds=\"[^\"]*\"" "$tmp" 2>/dev/null | head -1 |
      sed 's/.*bounds="\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\]".*/\1 \2 \3 \4/')"
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
  adb shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
  sleep 2
  return 0
}

# 那个开关节点：Compose 把它暴露成一个 checkable 的 View（class 不是 Switch，
# 但 checkable="true" + checked="true|false" 是它的特征）。
# 抽屉里只有它一个 checkable 节点，所以取第一个就行。
switch_node() {
  local tmp="$OUT_ROOT/.tmp_dump.xml"
  dump_to "$tmp" || return 1
  grep -o 'checkable="true"[^>]*bounds="[^"]*"' "$tmp" | head -1
}

switch_checked() {
  local tmp="$OUT_ROOT/.tmp_dump.xml"
  dump_to "$tmp" || return 1
  local v
  v="$(grep -o 'checkable="true"[^>]*checked="[^"]*"' "$tmp" | head -1 | sed -n 's/.*checked="\([^"]*\)".*/\1/p')"
  echo "$v"
}

tap_switch() {
  local node bounds
  node="$(switch_node)" || return 1
  bounds="$(echo "$node" | sed 's/.*bounds="\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\]".*/\1 \2 \3 \4/')"
  if [ -z "$bounds" ]; then echo "    !! 找不到开关节点"; FAILED="$FAILED switch"; return 1; fi
  set -- $bounds
  adb shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
  sleep 2
}

open_drawer() {
  adb shell input tap $(( W / 5 )) $(( H / 12 ))
  sleep 3
}

close_drawer() {
  adb shell input keyevent 4
  sleep 2
}

scroll_drawer_down() {
  adb shell input swipe $(( W / 2 )) $(( H * 3 / 4 )) $(( W / 2 )) $(( H / 4 )) 300
  sleep 2
}

scroll_page_down() {
  adb shell input swipe $(( W / 2 )) $(( H * 3 / 4 )) $(( W / 2 )) $(( H / 3 )) 300
  sleep 2
}

tap_with_scroll() {
  local text=$1
  local i
  for i in 1 2 3 4; do
    if tap_label "$text" >/dev/null 2>&1; then return 0; fi
    scroll_drawer_down
  done
  echo "    !! 找不到「$text」（滚到底也没有）"
  FAILED="$FAILED $text"
  return 1
}

# 设置页 → 使用引导 → 打开
open_guide() {
  open_drawer
  tap_with_scroll "设置" >/dev/null 2>&1
  sleep 1
  if ! tap_label "打开" >/dev/null 2>&1; then
    scroll_page_down
    tap_label "打开" >/dev/null 2>&1
  fi
  sleep 2
}

# 把引导的进度**清掉**，好让它从第 1 步重新开始。
#
# 引导是**续看**的：上次停在哪一步，这次打开就是哪一步（进度记在设备上）。
# 不清的话，这一轮拍到的「第 1 步」其实是上轮留下的第 3 步 ——
# 图名和内容对不上，而且按「下一步」会按到意料之外的地方。
reset_guide() {
  open_guide
  tap_label "跳过" >/dev/null 2>&1
  sleep 1
}

# 把开关拨到指定状态。抽屉里只有一个开关，状态读它的 checked 属性。
set_switch() {
  local want=$1
  open_drawer
  local cur
  cur="$(switch_checked)"
  if [ "$cur" != "$want" ]; then
    tap_switch
    cur="$(switch_checked)"
    if [ "$cur" != "$want" ]; then
      echo "    !! 开关没拨到 $want（现在 $cur）"
      FAILED="$FAILED switch=$want"
    fi
  fi
  close_drawer
}

relaunch() {
  adb shell am force-stop $PKG >/dev/null 2>&1
  adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
  sleep 9
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

  echo "▸ $label  size=$size density=$density font=$font"
  read W H <<< "$(get_size)"

  relaunch

  # ---------------- 开关：关 ----------------
  set_switch false
  shot 01_topbar_off

  if [ "$GUIDE_ONLY" -eq 0 ]; then
    open_drawer
    shot 02_drawer_off
    close_drawer
  fi

  # 引导的轻量三步
  reset_guide
  open_guide
  shot 03_guide_off_1_sync
  tap_label "下一步" >/dev/null 2>&1; shot 04_guide_off_2_toggle
  # 在引导里按下「开启」→ 引导落到「课程分类」（轻量三步变完整五步）
  tap_label "开启" >/dev/null 2>&1; shot 05_guide_after_enable
  tap_label "跳过" >/dev/null 2>&1

  # 拨回关，再走一遍轻量三步（这次按「下一步」而不是「开启」）
  set_switch false
  reset_guide
  open_guide
  shot 06_guide_off_1_sync
  tap_label "下一步" >/dev/null 2>&1; shot 07_guide_off_2_toggle
  tap_label "下一步" >/dev/null 2>&1; shot 08_guide_off_3_wallpaper
  tap_label "跳过" >/dev/null 2>&1

  # ---------------- 开关：开 ----------------
  set_switch true
  shot 09_topbar_on

  if [ "$GUIDE_ONLY" -eq 0 ]; then
    open_drawer
    shot 10_drawer_on
    close_drawer
  fi

  # 引导的完整五步
  reset_guide
  open_guide
  shot 11_guide_on_1_sync
  tap_label "下一步" >/dev/null 2>&1; shot 12_guide_on_2_category
  tap_label "下一步" >/dev/null 2>&1; shot 13_guide_on_3_rules
  tap_label "下一步" >/dev/null 2>&1; shot 14_guide_on_4_generate
  tap_label "下一步" >/dev/null 2>&1; shot 15_guide_on_5_wallpaper
  tap_label "跳过" >/dev/null 2>&1

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
