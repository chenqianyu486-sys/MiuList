#!/usr/bin/env bash
# MiuList 上手引导矩阵：每个断点下逐张抓引导的每一步 + 翻面后的总览页
#
# ## 它解决什么
#
# 引导是**同窗口 overlay**，而 `capture-matrix.sh` 只抓四个**页面**、
# `capture-dialogs.sh` 只抓那十几个弹层 —— 引导两边都没覆盖。
#
# 而引导恰恰是最需要看截图的地方：它的面板高度是**跟着内容走**的，
# 每一步加一句说明就长一截，窄屏 + 大字号时最容易把按钮顶出面板。
#
# ## 用法
#
#   tools/capture-onboarding.sh                  # 跑默认断点
#   tools/capture-onboarding.sh R332f116 C411    # 只跑指定断点
#
# ## 每张抓什么
#
# | 文件 | 内容 |
# |------|------|
# | 01_step1_sync … 07_step6_flip | 引导的六步，每步一张 |
# | 05_step4_preview | 第 4 步点「看看它会排什么」弹出的预览弹层 |
# | 08_flip_result | 最后一步点「翻给我看」之后总览页那张卡翻到背面 |
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
OUT_ROOT=".tmp/adapt/onboarding"

MATRIX=(
  "R332f116|1080x2414|520|1.16"
  "C411|1080x2400|420|1.0"
  "A320|640x1366|320|1.0"
  "E411f20|1080x2400|420|2.0"
  "G800|1600x1200|320|1.0"
)

DO_ALL=0
SELECTED=()
for arg in "$@"; do
  case "$arg" in
    -h|--help) sed -n '2,30p' "$0"; exit 0 ;;
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

ORIG_FILE=".tmp/adapt/orig_device_onboarding.txt"
CUR_SIZE="$(adb shell wm size 2>/dev/null | tr -d '\r' | sed -n 's/^Override size: //p')"
CUR_DENSITY="$(adb shell wm density 2>/dev/null | tr -d '\r' | sed -n 's/^Override density: //p')"
CUR_FONT="$(adb shell settings get system font_scale 2>/dev/null | tr -d '\r')"

mkdir -p "$(dirname "$ORIG_FILE")"
printf '%s\n%s\n%s\n' "$CUR_SIZE" "$CUR_DENSITY" "$CUR_FONT" > "$ORIG_FILE"

restore() {
  if [ -n "$CUR_SIZE" ]; then
    adb shell wm size "$CUR_SIZE" >/dev/null 2>&1
  else
    adb shell wm size reset >/dev/null 2>&1
  fi
  if [ -n "$CUR_DENSITY" ]; then
    adb shell wm density "$CUR_DENSITY" >/dev/null 2>&1
  else
    adb shell wm density reset >/dev/null 2>&1
  fi
  if [ -n "$CUR_FONT" ] && [ "$CUR_FONT" != "null" ]; then
    adb shell settings put system font_scale "$CUR_FONT" >/dev/null 2>&1
  fi
  echo ""
  echo "设备设置已还原：size=${CUR_SIZE:-reset} density=${CUR_DENSITY:-reset} font_scale=${CUR_FONT:-?}"
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

relaunch() {
  adb shell am force-stop $PKG >/dev/null
  adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
  # 等够久：App 启动要读数据库 + 拉天气，太早截图只会拍到加载态的转圈。
  sleep 9
}

# 按可见文字（或无障碍描述）找节点并点它的中心。
# 三级匹配：完全相等 → 包含（仅 3 个字以上）→ 无障碍描述。
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
  local x=$(( ($1 + $3) / 2 )) y=$(( ($2 + $4) / 2 ))
  adb shell input tap "$x" "$y"
  sleep 2
  return 0
}

# 顶栏左侧的学期名 = 抽屉入口
open_drawer() {
  adb shell input tap $(( W / 5 )) $(( H / 12 ))
  sleep 2
}

# 抽屉是**可滚动**的：「设置」那一行在底部，初始状态在屏外。
scroll_drawer_down() {
  adb shell input swipe $(( W / 2 )) $(( H * 3 / 4 )) $(( W / 2 )) $(( H / 4 )) 300
  sleep 2
}

scroll_page_down() {
  adb shell input swipe $(( W / 2 )) $(( H * 3 / 4 )) $(( W / 2 )) $(( H / 3 )) 300
  sleep 2
}

# 找不到就往下滚一屏再找（最多 4 轮）。
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

# 设置页 → 使用引导 → 打开
open_guide() {
  open_drawer
  tap_with_scroll "设置" >/dev/null 2>&1
  sleep 1
  # 「使用引导」那张卡在设置页的第四张卡，屏幕够高时直接可见；
  # 够不到就往下滚一屏（滚过头会滚到「关于」那些行，所以只滚一次）。
  if ! tap_label "打开" >/dev/null 2>&1; then
    scroll_page_down
    tap_label "打开" >/dev/null 2>&1
  fi
  sleep 2
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
  W="${size%x*}"
  H="${size#*x}"

  # 引导的进度是**记在设备上**的（停在哪一步）。
  # 上一轮要是停在中途，这一轮打开的就不是第 1 步 —— 所以先打开一次再「跳过」
  # （跳过会把步数归零），然后重新打开。
  relaunch
  open_guide
  tap_label "跳过" >/dev/null 2>&1
  sleep 1
  open_guide

  # 六步
  shot 01_step1_sync
  tap_label "下一步" >/dev/null 2>&1; shot 02_step2_category
  tap_label "下一步" >/dev/null 2>&1; shot 03_step3_rules
  tap_label "下一步" >/dev/null 2>&1; shot 04_step4_generate

  # 第 4 步的「看看它会排什么」→ 预览弹层（关掉它引导会自己接回来）
  tap_label "看看它会排什么" >/dev/null 2>&1
  sleep 1
  shot 05_step4_preview
  tap_label "取消" >/dev/null 2>&1
  sleep 1

  tap_label "下一步" >/dev/null 2>&1; shot 06_step5_wallpaper
  tap_label "下一步" >/dev/null 2>&1; shot 07_step6_flip

  # 最后一步的「翻给我看」→ 总览页那张卡翻到背面
  tap_label "翻给我看" >/dev/null 2>&1
  sleep 3
  shot 08_flip_result

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
