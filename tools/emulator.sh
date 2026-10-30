#!/usr/bin/env bash
# tools/emulator.sh — 模拟器启停与资源回收
#
# ## 为什么要有这个脚本
#
# 这台机器 31.5 GB 内存、提交上限 34 GB。无窗口模拟器常年占 2 GB，
# 而构建期 Gradle 还要吃掉 1.6~3 GB —— 两者叠加时提交占用一度到 71%，
# Windows 开始压缩内存页，桌面就跟着发飘（用户说的「电脑越来越卡」）。
#
# 所以模拟器要**按需起、按需停**，而不是开一整天。
#
# ## 用法
#
#   tools/emulator.sh start            # 起（默认 swiftshader，可与基线逐像素比）
#   tools/emulator.sh start --gpu host # 起（宿主 GPU，快，但像素与基线不可比）
#   tools/emulator.sh stop             # 关（立刻回收 ~2 GB）
#   tools/emulator.sh status           # 看跑没跑、占多少
#
# ## 两种 GPU 模式的用途边界（别搞混）
#
# | 模式 | 怎么起 | 快慢 | 能不能和基线逐像素比 |
# |------|--------|------|---------------------|
# | swiftshader（默认） | `-gpu swiftshader_indirect` | 慢（CPU 软画每一帧） | **能** |
# | host | `-gpu host` | 快（走真显卡） | **不能** |
#
# 两者抗锯齿不一样，截图差几个灰阶。所以：
#
# - **调试轮**（反复改、反复看）：用 `--gpu host`，省时间；
# - **验收轮**（要 `compare_baseline.py` 逐像素比）：必须回到 swiftshader，
#   否则基线比对全是噪声差异，报告里的「差异率」就废了。
#
# 基线目录 `.tmp/adapt/baseline` 是 **swiftshader** 抓的。
#
# ## 别的坑
#
# - **别用 bash 后台任务起模拟器**：任务有 30 分钟上限，超时会把模拟器一起杀掉
#   （2026-10-22 就这么丢了一轮弹层抓取）。这里用 PowerShell 的 Start-Process
#   起，进程独立于本脚本，不会跟着任务一起死。
# - 模拟器起来后要等 `sys.boot_completed=1`，别急着重启 App。

set -u

SDK="${ANDROID_HOME:-$HOME/.workbuddy/binaries/android-sdk}"
EMULATOR="$SDK/emulator/emulator.exe"
AVD="${MIULIST_AVD:-MiuList_Preview}"
PKG="com.aistudio.scheduletodo.unmq"

# 用户真机那一档：1080×2414 @520dpi = 332×743 dp，系统字号 1.16。
# 抓完图记得还原（capture-matrix.sh 自己会还原，这里只作提示）。
DEVICE_SIZE="1080x2414"
DEVICE_DENSITY="520"
DEVICE_FONT="1.16"

start_emulator() {
  local gpu_flag="$1"
  if adb devices | tail -n +2 | grep -q "device"; then
    echo "模拟器已经在跑了：$(adb devices | tail -n +2 | grep device | awk '{print $1}')"
    return 0
  fi
  if [ ! -f "$EMULATOR" ]; then
    echo "!! 找不到 emulator：$EMULATOR" >&2
    return 1
  fi

  echo "启动 AVD=$AVD  渲染=$gpu_flag ..."
  # 用 PowerShell 的 Start-Process：进程独立于本脚本，不会被 bash 任务超时带走。
  #
  # **`-gpu` 必须单独成一个参数**（'-gpu','$gpu_flag'），不能只传渲染模式的名：
  # 只传 `swiftshader_indirect` 的话它会变成一个**位置参数**，模拟器看不懂就**启动即退**，
  # 而这里的输出被丢进了 /dev/null —— 表现是「脚本跑满 5 分钟然后说超时，模拟器没起来」，
  # 真正的原因（参数写错）一个字都看不到。
  powershell -NoProfile -Command \
    "Start-Process -FilePath '$EMULATOR' -ArgumentList '-avd','$AVD','-no-window','-no-audio','-no-boot-anim','-gpu','$gpu_flag' -WindowStyle Hidden" \
    >/dev/null 2>&1

  printf "等待开机"
  for _ in $(seq 1 60); do
    if [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; then
      echo "  ✔ 已启动"
      # 装上用户真机那一档（332dp + 字号 1.16）—— 自适应问题多半出在这里
      adb shell wm size "$DEVICE_SIZE"      >/dev/null 2>&1
      adb shell wm density "$DEVICE_DENSITY" >/dev/null 2>&1
      adb shell settings put system font_scale "$DEVICE_FONT" >/dev/null 2>&1
      echo "  已设成用户真机：${DEVICE_SIZE} @${DEVICE_DENSITY}dpi, font_scale=${DEVICE_FONT}"
      echo "  （用完请 tools/emulator.sh stop 还原资源）"
      return 0
    fi
    printf "."
    sleep 5
  done
  echo "  ✘ 超时，模拟器没起来"
  # 超时的时候**把真正的原因打出来**：模拟器把自己的日志写在**当前目录**下
  # （emulator-<时间戳>.err.log），那里才有「为什么没起来」。
  local errlog
  errlog="$(ls -t emulator-*.err.log 2>/dev/null | head -1)"
  if [ -n "$errlog" ]; then
    echo "     最新的模拟器日志：$errlog（末尾几行）"
    tail -5 "$errlog" | sed 's/^/       /'
  else
    echo "     当前目录下没有 emulator-*.err.log —— 模拟器可能根本没被拉起来。"
  fi
  return 1
}

stop_emulator() {
  if adb devices | tail -n +2 | grep -q "device"; then
    adb emu kill >/dev/null 2>&1
    echo "已关闭模拟器（回收约 2 GB）"
  else
    echo "模拟器本来就没跑"
  fi
}

status_emulator() {
  local line
  line="$(adb devices | tail -n +2 | grep device | head -n1)"
  if [ -z "$line" ]; then
    echo "模拟器：未运行"
    return 0
  fi
  echo "模拟器：运行中  ($line)"
  if command -v powershell >/dev/null 2>&1; then
    powershell -NoProfile -Command \
      "Get-Process | Where-Object { \$_.ProcessName -like 'qemu*' } | ForEach-Object { '  qemu 常驻: {0:N0} MB' -f (\$_.WorkingSet64/1MB) }" \
      2>/dev/null
  fi
  echo "  屏幕: $(adb shell wm size 2>/dev/null | tr -d '\r' | sed -n 's/^Override size: //p')"
  echo "  密度: $(adb shell wm density 2>/dev/null | tr -d '\r' | sed -n 's/^Override density: //p')"
  echo "  字号: $(adb shell settings get system font_scale 2>/dev/null | tr -d '\r')"
}

case "${1:-status}" in
  start)
    # `--gpu host` 与 "--gpu host" **两种写法都认**。
    #
    # 原来只认后者（拿 "$2" 整个字符串去比 "--gpu host"），于是 README 与
    # 脚本注释里写的 `start --gpu host` 会**静默**退回 swiftshader ——
    # "$2" 其实是 "--gpu"，不等于 "--gpu host"。
    # 命令看起来执行成功、也打印了「已启动」，渲染模式却不是要的那个。
    GPU="swiftshader_indirect"
    if [ "${2:-}" = "--gpu host" ]; then
      GPU="host"
    elif [ "${2:-}" = "--gpu" ] && [ "${3:-}" = "host" ]; then
      GPU="host"
    fi
    start_emulator "$GPU"
    ;;
  stop)    stop_emulator ;;
  status)  status_emulator ;;
  *)       sed -n '2,30p' "$0"; exit 1 ;;
esac
