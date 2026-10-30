#!/usr/bin/env bash
# MiuList「网页同步」弹层矩阵：逐个断点打开这个弹层并截图
#
# ## 为什么单独一个脚本
#
# `capture-dialogs.sh` 抓的是那十几个**业务弹层**（待办编辑、课程滚筒、周次选择…），
# 「网页同步」不在其中 —— 它是**设置里的一页**，入口在「更多」抽屉里，
# 而且它有一个别的弹层都没有的特性：**里面的内容会随开关状态变化**
# （关着只有一行开关；开着才有地址、二维码、手动同步、同步密码）。
#
# 文案验收要看的就是「开着」那一种 —— 地址、二维码说明、手动同步三个方向、
# 同步密码这几段话全在开着的时候才出现。所以这个脚本**每档抓两张**：
# 关着（首屏）与开着（滚到底）。
#
# ## 用法
#
#   tools/capture-websync.sh                # 跑默认断点
#   tools/capture-websync.sh C411 G800      # 只跑指定断点
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
OUT_ROOT=".tmp/adapt/websync"

# 标签 | wm size | density | font_scale | 窗口宽 dp | 为什么有它
MATRIX=(
  "A320|640x1366|320|1.0"
  "R332f116|1080x2414|520|1.16"
  "C411|1080x2400|420|1.0"
  "G800|1600x1200|320|1.0"
)

SELECTED=()
for arg in "$@"; do SELECTED+=("$arg"); done

if ! command -v adb >/dev/null 2>&1; then
  echo "!! 找不到 adb。" >&2
  exit 1
fi
if ! adb devices | tail -n +2 | grep -q "device"; then
  echo "!! 没有连上的设备。" >&2
  exit 1
fi

ORIG_FILE=".tmp/adapt/orig_device_websync.txt"
CUR_SIZE="$(adb shell wm size 2>/dev/null | tr -d '\r' | sed -n 's/^Override size: //p')"
CUR_DENSITY="$(adb shell wm density 2>/dev/null | tr -d '\r' | sed -n 's/^Override density: //p')"
CUR_FONT="$(adb shell settings get system font_scale 2>/dev/null | tr -d '\r')"
if [ -f "$ORIG_FILE" ]; then
  ORIG_SIZE="$(sed -n 1p "$ORIG_FILE")"
  ORIG_DENSITY="$(sed -n 2p "$ORIG_FILE")"
  ORIG_FONT="$(sed -n 3p "$ORIG_FILE")"
else
  mkdir -p "$(dirname "$ORIG_FILE")"
  printf '%s\n%s\n%s\n' "$CUR_SIZE" "$CUR_DENSITY" "$CUR_FONT" > "$ORIG_FILE"
  ORIG_SIZE="$CUR_SIZE"; ORIG_DENSITY="$CUR_DENSITY"; ORIG_FONT="$CUR_FONT"
fi

restore() {
  if [ -n "$ORIG_SIZE" ]; then adb shell wm size "$ORIG_SIZE" >/dev/null 2>&1; else adb shell wm size reset >/dev/null 2>&1; fi
  if [ -n "$ORIG_DENSITY" ]; then adb shell wm density "$ORIG_DENSITY" >/dev/null 2>&1; else adb shell wm density reset >/dev/null 2>&1; fi
  if [ -n "$ORIG_FONT" ]; then adb shell settings put system font_scale "$ORIG_FONT" >/dev/null 2>&1; fi
  echo "（设备设置已还原：size=$ORIG_SIZE density=$ORIG_DENSITY font=$ORIG_FONT）"
}
trap restore EXIT

mkdir -p "$OUT_ROOT"
FAILED=""

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
  echo "    !! dump 失败"
  return 1
}

relaunch() {
  adb shell am force-stop $PKG >/dev/null
  adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
  sleep 8
}

# 按可见文字找节点并点它的中心（精确 → 包含 → 无障碍描述）。
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

# 点开关：弹层里那个 Switch 在 uiautomator 里是 checkable="true" 的节点。
#
# ## 为什么不用 testTag
#
# Compose 的 testTag **不出现在 uiautomator dump 里** —— dump 只给语义树，
# 而 testTag 不是语义。所以这里按「可切换的节点」找，取第一个 checkable。
tap_switch() {
  local tmp="$OUT_ROOT/.tmp_dump.xml"
  dump_to "$tmp" || return 1
  local bounds
  bounds="$(grep -o 'class="android.widget.Switch"[^>]*bounds="[^"]*"' "$tmp" 2>/dev/null | head -1 |
    sed 's/.*bounds="\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\]".*/\1 \2 \3 \4/')"
  if [ -z "$bounds" ]; then
    bounds="$(grep -o 'checkable="true"[^>]*bounds="[^"]*"' "$tmp" 2>/dev/null | head -1 |
      sed 's/.*bounds="\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\]".*/\1 \2 \3 \4/')"
  fi
  rm -f "$tmp"
  if [ -z "$bounds" ]; then
    echo "    !! 找不到开关"
    FAILED="$FAILED switch"
    return 1
  fi
  set -- $bounds
  adb shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
  sleep 3
  return 0
}

# 截图一律走 `screencap` + `adb pull`：
# `adb exec-out screencap -p > x.png` 在 PowerShell 里会被**文本**重定向写坏（拿到 UTF-16）。
shot() {
  local file=$1
  adb shell screencap -p /sdcard/_shot.png >/dev/null 2>&1
  adb pull /sdcard/_shot.png "$file" >/dev/null 2>&1
  if [ -f "$file" ]; then
    echo "    shot $(basename "$file")  $(du -k "$file" | cut -f1) KB"
  else
    echo "    !! 截图失败：$file"
    FAILED="$FAILED shot"
  fi
}

for entry in "${MATRIX[@]}"; do
  label="${entry%%|*}"
  rest="${entry#*|}"
  size="${rest%%|*}"
  rest="${rest#*|}"
  density="${rest%%|*}"
  font="${rest#*|}"

  if [ ${#SELECTED[@]} -gt 0 ]; then
    want=0
    for s in "${SELECTED[@]}"; do [ "$s" = "$label" ] && want=1; done
    [ "$want" = "1" ] || continue
  fi

  echo "== $label  $size @${density}dpi  font=$font =="
  adb shell wm size "$size" >/dev/null 2>&1
  adb shell wm density "$density" >/dev/null 2>&1
  adb shell settings put system font_scale "$font" >/dev/null 2>&1
  sleep 3

  # 屏幕尺寸：**优先取 Override size**。
  #
  # `wm size` 在有覆盖时输出两行（Physical size / Override size），
  # 取第一行会拿到物理分辨率 —— 那跟屏幕上真正显示的尺寸不一样，
  # 于是按它算出来的点击坐标全偏，点到的不是抽屉。
  SIZE_OUT="$(adb shell wm size 2>/dev/null | tr -d '\r')"
  W="$(printf '%s\n' "$SIZE_OUT" | sed -n 's/^Override size: \([0-9]*\)x\([0-9]*\).*/\1/p' | head -1)"
  H="$(printf '%s\n' "$SIZE_OUT" | sed -n 's/^Override size: \([0-9]*\)x\([0-9]*\).*/\2/p' | head -1)"
  if [ -z "$W" ] || [ -z "$H" ]; then
    W="$(printf '%s\n' "$SIZE_OUT" | sed -n 's/^Physical size: \([0-9]*\)x\([0-9]*\).*/\1/p' | head -1)"
    H="$(printf '%s\n' "$SIZE_OUT" | sed -n 's/^Physical size: \([0-9]*\)x\([0-9]*\).*/\2/p' | head -1)"
  fi
  echo "    屏幕 ${W}x${H}"

  relaunch

  # 「更多」抽屉：点顶栏左侧那一块
  adb shell input tap $(( W / 5 )) $(( H / 12 ))
  sleep 2
  # 抽屉是**可滚动**的：「网页同步」在「待办生成规则」下面，
  # 宽屏（1600x1200）那一档它整个在首屏之外 —— 不滚就点不到，
  # 拍出来的是**抽屉本身**而不是弹层。
  adb shell input swipe $(( W / 2 )) $(( H * 3 / 4 )) $(( W / 2 )) $(( H / 4 )) 300
  sleep 2
  tap_label "网页同步"
  sleep 2

  shot "$OUT_ROOT/$label-01-off.png"

  # 打开开关：地址、二维码、手动同步、密码这些文案才出现
  tap_switch

  shot "$OUT_ROOT/$label-02-on.png"

  # 弹层内容可滚动：往下滚，看手动同步与说明那几段
  adb shell input swipe $(( W / 2 )) $(( H * 3 / 4 )) $(( W / 2 )) $(( H / 5 )) 300
  sleep 2
  shot "$OUT_ROOT/$label-03-scrolled.png"

  adb shell input keyevent KEYCODE_BACK >/dev/null 2>&1
  sleep 1
  adb shell input keyevent KEYCODE_BACK >/dev/null 2>&1
done

if [ -n "$FAILED" ]; then
  echo "有失败项：$FAILED"
  exit 1
fi
echo "全部完成。"
