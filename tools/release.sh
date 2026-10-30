#!/usr/bin/env bash
# MiuList 发版脚本
#
# ## 它做什么
#
# 一条命令走完整个发版流程，顺序是「先验证、后发布」：
#
#   1. 提版本号（versionCode +1 / versionName；桌面端 package.json 跟着走）
#   2. 同步 README 的下载入口
#   3. 用上传密钥构建 release APK，并复制成 MiuList-vX.Y.Z.apk
#   4. 校验：版本号对不对、签名有效吗、证书和上一版**同一把**吗（决定能不能覆盖升级）
#   5. 构建桌面单文件网页版，并检查产物里的版本号就是这一版
#   6. 生成发布说明
#   7. 提交 → 打 tag → 推送
#   8. 建 GitHub Release（测试版 = pre-release）并上传 APK 与桌面网页版
#      —— 这一半在 `tools/release_publish.sh` 里，**中断了可以单独补跑**。
#
# ## 用法
#
#   tools/release.sh v1.0.2                 # 发测试版（pre-release）
#   tools/release.sh v1.0.2 --stable        # 发正式版
#   tools/release.sh v1.0.2 --dry-run       # 只构建与校验，不提交不推送不发布（版本号会还原）
#   tools/release.sh v1.0.2 --notes my.md   # 用自己写的发布说明
#   tools/release.sh v1.0.2 --summary "一句话概述"
#
#   # 中途断了（代码推了、Release 还没建）时，把剩下的补上：
#   tools/release_publish.sh v1.0.2 --stable --notes .tmp/release_notes_1.0.2.md
#
# ## 为什么用「先校验后发布」
#
# 发版是不可逆的：APK 一旦传上去，别人就能下到。这一版把**所有能在本地查的问题**
# 都放到发布之前查：版本号对不对、签名有没有、能不能覆盖上一版。
# 其中「证书是不是同一把」最要紧 —— 不是同一把的话，用户装 v1.0.2 就得先卸载 v1.0.1，
# 数据全丢。脚本会比对已发布的那一版，证书不同会**大声警告**。
#
# ## 几个踩过的坑（都写在代码里了）
#
# - 签名密码在 `keystore.properties`，但构建脚本读的是**环境变量** → 这里负责把它们导进去。
# - `git credential fill` **不能自己写 username**：库里存的是 `chenqianyu486-sys`，
#   写错会让凭据管理器转去走交互式认证，整个命令挂住 → 只给 protocol/host，并加
#   `GCM_INTERACTIVE=never` 兜底。
# - JSON 载荷用 `tools/release_helper.py` 拼（PowerShell 的 ConvertTo-Json 会把字符串
#   序列化成对象，GitHub 判非法）→ 这里一律用 curl + 文件。
# - 找「release: vX.Y.Z」那个提交时**不能要求版本号后面有空格**：正式版的标题就是
#   「release: v1.1.0」，提交时 git 会把行尾空格去掉 —— 带着空格的模式一条都匹配不上，
#   脚本会在「代码已经推上去了、Release 还没建」的位置直接退出。
# - 发布那一半（推 tag → 建 Release → 传资产）单独成 `tools/release_publish.sh`：
#   发版中断在中间时，从头再跑一遍会被前置检查挡住（tag 已存在、工作区不干净）。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

PKG="com.aistudio.scheduletodo.unmq"
REPO_OWNER="chenqianyu486-sys"
REPO_NAME="MiuList"
OUT_APK_DIR="app/build/outputs/apk/release"
SDK_ROOT="${ANDROID_SDK_ROOT:-$HOME/.workbuddy/binaries/android-sdk}"
JDK_HOME="${JAVA_HOME_17:-$HOME/.workbuddy/binaries/jdk/jdk-17.0.20.1+1}"
PY="$(command -v python3 || command -v python)"

VERSION=""
DRY_RUN=0
STABLE=0
NOTES_FILE=""
SUMMARY=""

usage() {
  sed -n '2,47p' "$0"
  exit 1
}

while [ $# -gt 0 ]; do
  case "$1" in
    --dry-run) DRY_RUN=1 ;;
    --stable) STABLE=1 ;;
    --notes) NOTES_FILE="$2"; shift ;;
    --summary) SUMMARY="$2"; shift ;;
    -h|--help) usage ;;
    -*) echo "未知参数：$1" >&2; usage ;;
    *) VERSION="$1" ;;
  esac
  shift
done

[ -n "$VERSION" ] || usage
case "$VERSION" in
  v[0-9]*.[0-9]*.[0-9]*) ;;
  *) echo "版本号要写成 vX.Y.Z 的样子，比如 v1.0.2（收到的是：$VERSION）" >&2; exit 1 ;;
esac

VERSION_NO_V="${VERSION#v}"
APK_NAME="MiuList-${VERSION}.apk"
RELEASE_NAME="MiuList ${VERSION}"
if [ "$STABLE" -eq 0 ]; then
  RELEASE_NAME="MiuList ${VERSION}（测试版）"
fi

# ---------------------------------------------------------------- 前置检查
echo "== 前置检查 =="
if [ -n "$(git status --porcelain)" ]; then
  echo "!! 工作区不干净。先把改动提交或暂存，再发版。" >&2
  git status --short >&2
  exit 1
fi
BRANCH="$(git branch --show-current)"
if [ "$BRANCH" != "main" ]; then
  echo "!! 当前分支是 $BRANCH，发版要在 main 上做。" >&2
  exit 1
fi
if git rev-parse -q --verify "refs/tags/$VERSION" >/dev/null; then
  echo "!! tag $VERSION 已经存在了。" >&2
  exit 1
fi
[ -f keystore.properties ] || {
  echo "!! 找不到 keystore.properties（签名密码与密钥路径都在里面）。" >&2
  exit 1
}
[ -n "$PY" ] || { echo "!! 找不到 python。" >&2; exit 1; }
echo "分支 $BRANCH · 目标版本 $VERSION · $([ "$STABLE" -eq 1 ] && echo '正式版' || echo '测试版')"

# ---------------------------------------------------------------- 代理兜底
#
# 这台机器的 HTTP_PROXY / HTTPS_PROXY 指向一个本地代理（7897），而 GitHub 的
# 两台主机在代理上**需要相反的处理**：
#
#   github.com        —— 直连 443 超时，**必须走代理**
#                       （git push、资产下载都在这台）
#   api.github.com    —— 代理出口 IP 被 GitHub 限流，返回 403
#     uploads.github.com —— **必须绕开代理**（建 Release、传资产）
#
# 所以兜底**只**把 API 那两台加进 NO_PROXY，github.com 留给代理走 ——
# 早先版本把 github.com 也列进去，结果 push 卡在
# `Failed to connect to github.com port 443`。
#
# 探针必须看 **HTTP 状态码**，不能只看 curl 的退出码：限流时 curl 退出码是 0，
# 响应却是 403 —— 只看退出码会把「不通」当成「通」，兜底就永远不触发。
#
# 探针还必须放在 push 之前：脚本的顺序是「先推代码、再建 Release」，两处都要走
# 这条路，只修后半段等于没修。
api_reachable() {
  local code
  code="$(curl -sS -o /dev/null -w '%{http_code}' --max-time 20 "$1" 2>/dev/null || echo 000)"
  [ "$code" = "200" ]
}
if ! api_reachable "https://api.github.com/"; then
  GH_API_HOSTS="api.github.com,uploads.github.com"
  export NO_PROXY="${NO_PROXY:+$NO_PROXY,}$GH_API_HOSTS"
  export no_proxy="$NO_PROXY"
  if api_reachable "https://api.github.com/"; then
    echo "（本机代理到 api.github.com 不可用，已对 API 主机绕过代理）"
  else
    echo "!! 连不上 api.github.com（绕过代理后仍不通），发版大概率会失败。" >&2
  fi
fi

# 上一版（用来比对证书、列提交）
PREV_TAG="$(git tag --list 'v*' --sort=-v:refname | head -1)"
[ -n "$PREV_TAG" ] || PREV_TAG=""
echo "上一版 tag: ${PREV_TAG:-（无）}"

# ---------------------------------------------------------------- 提版本号
echo ""
echo "== 提版本号 =="
OLD_CODE="$(sed -n 's/^[[:space:]]*versionCode[[:space:]]*=[[:space:]]*\([0-9]\+\).*/\1/p' app/build.gradle.kts | head -1)"
OLD_NAME="$(sed -n 's/^[[:space:]]*versionName[[:space:]]*=[[:space:]]*"\([^"]*\)".*/\1/p' app/build.gradle.kts | head -1)"
NEW_CODE=$((OLD_CODE + 1))
[ -n "$OLD_CODE" ] && [ -n "$OLD_NAME" ] || { echo "!! 读不到 versionCode / versionName。" >&2; exit 1; }
# 版本号只许往前，不许倒退。
#
# 以前只校验「长得像 vX.Y.Z」，于是 v1.0.2 之后发一个 v1.0.1 也能过 ——
# 结果是 GitHub 上多一个比当前更旧的 tag，用户点「最新 release」拿到的是旧版。
# sort -V 取两个版本里较小的那个：它等于 OLD_NAME 才说明目标版本不低于当前版本。
if [ "$(printf '%s\n%s\n' "$OLD_NAME" "$VERSION_NO_V" | sort -V | head -1)" != "$OLD_NAME" ]; then
  echo "!! 目标版本 $VERSION_NO_V 低于当前 $OLD_NAME —— 发版不允许版本号倒退" >&2; exit 1
fi
echo "versionCode $OLD_CODE → $NEW_CODE    versionName $OLD_NAME → $VERSION_NO_V"

# .tmp 在 .gitignore 里，干净克隆的仓库上**没有**这个目录 ——
# 不先建，后面的备份 cp 与重定向会直接报 "No such file or directory"。
mkdir -p .tmp

# 备份（dry-run 要还原）
cp app/build.gradle.kts ".tmp/release_backup_build.gradle.kts"
cp README.md ".tmp/release_backup_README.md"
cp desktop/package.json ".tmp/release_backup_desktop_package.json"
cp desktop/package-lock.json ".tmp/release_backup_desktop_package-lock.json"

# **任何退出都要还原**（dry-run 模式下）。
# 上一版就栽在这：构建失败时脚本直接退出，版本号就留在 1.0.2 没还原，
# 下一跑又因为「工作区不干净」直接拒绝 —— 一个失败卡死后面所有操作。
restore_versions() {
  if [ "$DRY_RUN" -eq 1 ] && [ "$RESTORED" -eq 0 ] && [ -f ".tmp/release_backup_build.gradle.kts" ]; then
    cp ".tmp/release_backup_build.gradle.kts" app/build.gradle.kts
    cp ".tmp/release_backup_README.md" README.md
    cp ".tmp/release_backup_desktop_package.json" desktop/package.json
    cp ".tmp/release_backup_desktop_package-lock.json" desktop/package-lock.json
    RESTORED=1
    echo ""
    echo "（已还原版本号到 $OLD_NAME / versionCode $OLD_CODE）"
  fi
}
RESTORED=0
trap restore_versions EXIT

sed -i "s/^\([[:space:]]*\)versionCode[[:space:]]*=[[:space:]]*[0-9]\+/\1versionCode = $NEW_CODE/" app/build.gradle.kts
sed -i "s/^\([[:space:]]*\)versionName[[:space:]]*=[[:space:]]*\"[^\"]*\"/\1versionName = \"$VERSION_NO_V\"/" app/build.gradle.kts

# 桌面端的版本号**跟着这一版走**。
#
# 以前它是手写在「关于」面板里的，于是每次发版都要另开一条
# `chore(desktop): 版本号对齐` 的提交去补；忘了补，界面上就摆着一个旧版本号
# —— 而那是用户能直接看到的地方。现在发版时一起提，不再靠人记。
#
# 只改**前几处** version：package-lock.json 里每个依赖项也各有自己的 version，
# 一把 sed 全表替换会把依赖的版本号也改掉。
"$PY" - "$VERSION_NO_V" <<'PYEOF'
import re, sys
version = sys.argv[1]
# package.json 1 处（根包）；package-lock.json 2 处（根包 + packages.""）
for path, limit in (("desktop/package.json", 1), ("desktop/package-lock.json", 2)):
    with open(path, encoding="utf-8") as f:
        text = f.read()
    text, n = re.subn(
        r'("version"\s*:\s*")[^"]*(")',
        lambda m: m.group(1) + version + m.group(2),
        text,
        count=limit,
    )
    if n != limit:
        sys.exit("!! %s 里改了 %d 处 version（预期 %d 处）" % (path, n, limit))
    with open(path, "w", encoding="utf-8", newline="") as f:
        f.write(text)
PYEOF
# README 的下载入口跟着换
if grep -q 'MiuList-v[0-9.]*\.apk' README.md; then
  sed -i "s/MiuList-v[0-9.]*\.apk/$APK_NAME/g" README.md
  echo "README 下载入口 → $APK_NAME"
else
  echo "（README 里没找到 APK 文件名的下载入口，跳过）"
fi
grep -n 'versionCode\|versionName' app/build.gradle.kts | head -2

# ---------------------------------------------------------------- 构建
echo ""
echo "== 构建 release APK =="
if [ -d "$JDK_HOME" ]; then
  export JAVA_HOME="$JDK_HOME"
  export PATH="$JAVA_HOME/bin:$PATH"
fi
# 签名密码从 keystore.properties 导进环境变量（构建脚本读的是环境变量）。
#
# **必须去掉行尾的 \r**：这个文件是 CRLF 行尾，不剥掉的话密码末尾会多一个 \r，
# 签名校验直接失败（报 validateSigningRelease FAILED），而报错里一个字都不提密码。
while IFS='=' read -r key value; do
  key="${key%$'\r'}"
  value="${value%$'\r'}"
  value="$(printf '%s' "$value" | sed 's/^[[:space:]]*//; s/[[:space:]]*$//')"
  case "$key" in
    KEYSTORE_PATH|STORE_PASSWORD|KEY_PASSWORD|KEY_ALIAS) export "$key=$value" ;;
  esac
done < keystore.properties
export KEYSTORE_PATH="${KEYSTORE_PATH:-$ROOT/my-upload-key.jks}"

# 构建日志全量落盘。
#
# 以前只 tail -5 行，Gradle 失败时那 5 行几乎全是无关噪音，
# 真正的原因（哪个任务、哪条报错）在几百行之前，谁也翻不到。
# tee 一份完整的，屏幕上仍然只看末尾 40 行。
if ! ./gradlew.bat :app:assembleRelease --console=plain 2>&1 | tee .tmp/build.log | tail -40; then
  echo "!! 构建失败，完整日志见 .tmp/build.log" >&2
  exit 1
fi
SRC_APK="$OUT_APK_DIR/app-release.apk"
[ -f "$SRC_APK" ] || { echo "!! 没构建出 $SRC_APK" >&2; exit 1; }
cp "$SRC_APK" "$OUT_APK_DIR/$APK_NAME"
# 再留一份到 .tmp：app/build/outputs 会被下一次 gradle clean 抹掉，
# 发版中断后再跑发布脚本时，原路径的产物可能已经没了。
mkdir -p .tmp && cp "$OUT_APK_DIR/$APK_NAME" ".tmp/$APK_NAME"
echo "产物: $OUT_APK_DIR/$APK_NAME ($(du -m "$OUT_APK_DIR/$APK_NAME" | cut -f1) MB)"

# ---------------------------------------------------------------- 校验
echo ""
echo "== 校验 =="
# build-tools 找不到时，ls 的非零退出码会顺着 pipefail 让脚本**无声地**退出 ——
# 用户只看到命令没跑完，看不出是 SDK 路径不对。所以这里把管道兜住，再自己给一句人话。
BT="$(ls -d "$SDK_ROOT"/build-tools/*/ 2>/dev/null | sort -V | tail -1 || true)"
[ -n "$BT" ] || { echo "!! 在 $SDK_ROOT/build-tools 下找不到 build-tools，请检查 local.properties 的 sdk.dir" >&2; exit 1; }
AAPT="$BT/aapt.exe"; [ -f "$AAPT" ] || AAPT="$BT/aapt"
APKSIGNER="$BT/apksigner.bat"; [ -f "$APKSIGNER" ] || APKSIGNER="$BT/apksigner"

BADGING="$("$AAPT" dump badging "$OUT_APK_DIR/$APK_NAME" 2>/dev/null | head -1)"
echo "$BADGING"
case "$BADGING" in
  *"versionCode='$NEW_CODE'"*"versionName='$VERSION_NO_V'"*) echo "✓ 版本号对得上" ;;
  *) echo "!! 版本号与预期不符（预期 versionCode=$NEW_CODE / versionName=$VERSION_NO_V）" >&2; exit 1 ;;
esac

CERT="$("$APKSIGNER" verify --print-certs "$OUT_APK_DIR/$APK_NAME" 2>/dev/null | grep -m1 'SHA-256 digest' | sed 's/.*digest: //')"
[ -n "$CERT" ] || { echo "!! 签名校验没通过" >&2; exit 1; }
echo "✓ 签名有效，证书 SHA-256 = $CERT"

# 跟上一版已发布的 APK 比证书 —— 决定能不能覆盖升级
if [ -n "$PREV_TAG" ]; then
  PREV_APK=".tmp/prev_release.apk"
  if curl -sSL -o "$PREV_APK" "https://github.com/$REPO_OWNER/$REPO_NAME/releases/download/$PREV_TAG/MiuList-${PREV_TAG}.apk" 2>/dev/null && [ -s "$PREV_APK" ]; then
    PREV_CERT="$("$APKSIGNER" verify --print-certs "$PREV_APK" 2>/dev/null | grep -m1 'SHA-256 digest' | sed 's/.*digest: //')"
    if [ "$CERT" = "$PREV_CERT" ]; then
      echo "✓ 与 $PREV_TAG 同一把密钥，可直接覆盖升级"
    else
      echo "!! 警告：证书与 $PREV_TAG 不同（$PREV_CERT）" >&2
      echo "   用户装这一版需要先卸载旧版，数据会丢。确认是有意换密钥再继续。" >&2
    fi
  else
    echo "（拿不到 $PREV_TAG 的已发布 APK，跳过证书比对）"
  fi
fi

# ---------------------------------------------------------------- 桌面单文件网页版
#
# 这一段是**必须有**的：以前脚本只做「文件在就上传、不在就跳过」，
# 于是「改了版本号但忘了重新构建」也会静静地把**旧产物**发出去 ——
# 不报错，界面上还摆着上一个版本号。
#
# 现在改成：发版时**当场构建**，并检查产物里的版本号就是这一版。
# 构建失败 / 版本号对不上 → 直接退出，不发布。
echo ""
echo "== 构建桌面单文件网页版 =="
WEB_HTML="desktop/dist/MiuList-Web.html"
if [ ! -d desktop/node_modules ]; then
  echo "（没装依赖，先 npm ci）"
  (cd desktop && npm ci --no-audit --no-fund)
fi
(cd desktop && npm run build)
[ -f "$WEB_HTML" ] || { echo "!! 没构建出 $WEB_HTML" >&2; exit 1; }
# 版本号就在「关于」面板里（构建期从 package.json 注入）。
# 找「about-version」附近的那个版本号 —— 单独 grep 版本号会误伤别的数字。
if ! grep -o "about-version[^}]\{0,40\}$VERSION_NO_V" "$WEB_HTML" | grep -q "$VERSION_NO_V"; then
  echo "!! 桌面产物里没有版本号 $VERSION_NO_V（重新构建后再发）" >&2
  exit 1
fi
echo "✓ 桌面产物 $WEB_HTML ($(du -m "$WEB_HTML" | cut -f1) MB)，版本号 $VERSION_NO_V"

# ---------------------------------------------------------------- 发布说明
echo ""
echo "== 发布说明 =="
NOTES=".tmp/release_notes_${VERSION_NO_V}.md"
if [ -n "$NOTES_FILE" ]; then
  cp "$NOTES_FILE" "$NOTES"
  echo "用指定的说明文件：$NOTES_FILE"
else
  COMMITS=".tmp/release_commits_${VERSION_NO_V}.txt"
  if [ -n "$PREV_TAG" ]; then
    git log --pretty=format:'%s' "$PREV_TAG..HEAD" > "$COMMITS"
  else
    git log --pretty=format:'%s' -20 > "$COMMITS"
  fi
  # 参数先拼成数组再展开：空字符串（比如 --summary 没给）才不会变成一个空参数，
  # 而「正式版」这个开关更不能靠空串去表达 —— 只有 --stable 这个标志本身算数。
  NOTES_ARGS=(--version "$VERSION" --prev-tag "${PREV_TAG:-$VERSION_NO_V}"
              --summary "$SUMMARY" --apk-name "$APK_NAME"
              --commits-file "$COMMITS" --out "$NOTES")
  if [ "$STABLE" -eq 1 ]; then
    NOTES_ARGS+=(--stable)
  fi
  "$PY" tools/release_helper.py notes "${NOTES_ARGS[@]}"
fi

# API 的 JSON 载荷在发布那一步生成（见 tools/release_publish.sh）。

# ---------------------------------------------------------------- dry-run 收尾
if [ "$DRY_RUN" -eq 1 ]; then
  echo ""
  echo "== dry-run：还原版本号 =="
  restore_versions
  echo "工作区状态：$(git status --porcelain | wc -l) 项变更"
  echo ""
  echo "dry-run 结果："
  echo "  版本号   $OLD_NAME → $VERSION_NO_V（versionCode $NEW_CODE）"
  echo "  产物     $OUT_APK_DIR/$APK_NAME"
  echo "  桌面产物 $WEB_HTML（版本号 $VERSION_NO_V）"
  echo "  证书     $CERT"
  echo "  说明     $NOTES"
  exit 0
fi

# ---------------------------------------------------------------- 提交与 tag
echo ""
echo "== 提交与 tag =="
git add app/build.gradle.kts README.md desktop/package.json desktop/package-lock.json
MSG=".tmp/release_commit_msg_${VERSION_NO_V}.txt"
cat > "$MSG" <<EOF
release: $VERSION $([ "$STABLE" -eq 1 ] && echo '' || echo '测试版')

versionName $VERSION_NO_V / versionCode $NEW_CODE，release APK 用同一把上传密钥签名。
EOF
# 这里必须用 if，不能写 `[ -n "$SUMMARY" ] && printf ...`：
# 脚本开着 `set -e`，而 `&&` 链在条件为假时整条命令的退出码是 1 —— 脚本会在
# 「已提交、已打 tag、还没 push」的**最尴尬的位置**直接退出，把仓库留在
# 「本地有 tag、远端没有」的半截状态。
if [ -n "$SUMMARY" ]; then
  printf '%s\n' "$SUMMARY" >> "$MSG"
fi
git commit -F "$MSG" >/dev/null
git tag -a "$VERSION" -m "MiuList ${VERSION}$([ "$STABLE" -eq 1 ] || echo '（测试版）')"
git log --oneline -1

# ---------------------------------------------------------------- 推送
echo ""
echo "== 推送 =="
# fetch 的错误**不能吞**：v1.0.2 那次 fetch 静默失败，origin/main 还是旧的，
# 于是 BEHIND 算成 0、跳过 rebase，推送被拒（fetch first），
# 而报错只说「远端有东西」，完全看不出远端领先了多少个提交。
git fetch origin || { echo "!! fetch 失败，先不推送。" >&2; exit 1; }
BEHIND="$(git rev-list --count HEAD..origin/main 2>/dev/null || echo 0)"
if [ "$BEHIND" -gt 0 ]; then
  echo "远端领先 $BEHIND 个提交，先 rebase"
  git pull --rebase origin main
fi
# 被拒就再 rebase 一次重试（远端可能在这几秒里又进了新提交）
git push origin main || {
  echo "推送被拒，rebase 后重试"
  git pull --rebase origin main
  git push origin main
}
# gitee 镜像顺带推一份：README 里推荐用户走 gitee，镜像却停在旧提交上，
# 用户按 README 拉到的却不是刚发布的版本。推失败只警告，不阻断 GitHub 发布。
if git remote get-url gitee >/dev/null 2>&1; then
  git push gitee main || echo "!! 推 gitee 失败（不影响 GitHub 发布）" >&2
fi

# ---------------------------------------------------------------- 发布
#
# 发布那一半单独成脚本（tools/release_publish.sh）：发版有可能**中断在中间** ——
# 版本号提了、APK 编了、代码也推上去了，却卡在「建 Release」之前。
# 那时候总不能再从头跑一遍（tag 已经在了、工作区也不干净，前置检查会直接拒绝），
# 单独跑那个脚本就能把剩下的补上。
echo ""
echo "== 发布 =="
if [ "$STABLE" -eq 1 ]; then
  PUBLISH_ARGS=(--stable)
else
  PUBLISH_ARGS=()
fi
# 用 bash 显式调用：文件在仓库里的可执行位不一定被检出（Windows 上 clone 常常拿到 100644），
# 直接 ./tools/release_publish.sh 会 "Permission denied"。
bash tools/release_publish.sh "$VERSION" "${PUBLISH_ARGS[@]}" --notes "$NOTES"
