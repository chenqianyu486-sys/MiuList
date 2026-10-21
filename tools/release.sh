#!/usr/bin/env bash
# MiuList 发版脚本
#
# ## 它做什么
#
# 一条命令走完整个发版流程，顺序是「先验证、后发布」：
#
#   1. 提版本号（versionCode +1 / versionName）
#   2. 同步 README 的下载入口
#   3. 用上传密钥构建 release APK，并复制成 MiuList-vX.Y.Z.apk
#   4. 校验：版本号对不对、签名有效吗、证书和上一版**同一把**吗（决定能不能覆盖升级）
#   5. 生成发布说明
#   6. 提交 → 打 tag → 推送
#   7. 建 GitHub Release（测试版 = pre-release）并上传 APK
#
# ## 用法
#
#   tools/release.sh v1.0.2                 # 发测试版（pre-release）
#   tools/release.sh v1.0.2 --stable        # 发正式版
#   tools/release.sh v1.0.2 --dry-run       # 只构建与校验，不提交不推送不发布（版本号会还原）
#   tools/release.sh v1.0.2 --notes my.md   # 用自己写的发布说明
#   tools/release.sh v1.0.2 --summary "一句话概述"
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
  sed -n '2,40p' "$0"
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
echo "versionCode $OLD_CODE → $NEW_CODE    versionName $OLD_NAME → $VERSION_NO_V"

# 备份（dry-run 要还原）
cp app/build.gradle.kts ".tmp/release_backup_build.gradle.kts"
cp README.md ".tmp/release_backup_README.md"

sed -i "s/^\([[:space:]]*\)versionCode[[:space:]]*=[[:space:]]*[0-9]\+/\1versionCode = $NEW_CODE/" app/build.gradle.kts
sed -i "s/^\([[:space:]]*\)versionName[[:space:]]*=[[:space:]]*\"[^\"]*\"/\1versionName = \"$VERSION_NO_V\"/" app/build.gradle.kts
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
# 签名密码从 keystore.properties 导进环境变量（构建脚本读的是环境变量）
while IFS='=' read -r key value; do
  case "$key" in
    KEYSTORE_PATH|STORE_PASSWORD|KEY_PASSWORD) export "$key=$value" ;;
  esac
done < keystore.properties
export KEYSTORE_PATH="${KEYSTORE_PATH:-$ROOT/my-upload-key.jks}"

./gradlew.bat :app:assembleRelease --console=plain 2>&1 | tail -5
SRC_APK="$OUT_APK_DIR/app-release.apk"
[ -f "$SRC_APK" ] || { echo "!! 没构建出 $SRC_APK" >&2; exit 1; }
cp "$SRC_APK" "$OUT_APK_DIR/$APK_NAME"
echo "产物: $OUT_APK_DIR/$APK_NAME ($(du -m "$OUT_APK_DIR/$APK_NAME" | cut -f1) MB)"

# ---------------------------------------------------------------- 校验
echo ""
echo "== 校验 =="
BT="$(ls -d "$SDK_ROOT"/build-tools/*/ 2>/dev/null | sort -V | tail -1)"
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
  "$PY" tools/release_helper.py notes \
    --version "$VERSION" \
    --prev-tag "${PREV_TAG:-$VERSION_NO_V}" \
    --summary "$SUMMARY" \
    --apk-name "$APK_NAME" \
    --commits-file "$COMMITS" \
    --out "$NOTES"
fi

# API 的 JSON 载荷也在这里生成（拼 JSON 的事交给 Python，见 release_helper.py 的说明）
"$PY" tools/release_helper.py payload \
  --name "$RELEASE_NAME" \
  --notes-file "$NOTES" \
  --out "$NOTES.payload.json" \
  $([ "$STABLE" -eq 0 ] && echo "--prerelease")

# ---------------------------------------------------------------- dry-run 收尾
if [ "$DRY_RUN" -eq 1 ]; then
  echo ""
  echo "== dry-run：还原版本号 =="
  cp ".tmp/release_backup_build.gradle.kts" app/build.gradle.kts
  cp ".tmp/release_backup_README.md" README.md
  echo "已还原（工作区状态：$(git status --porcelain | wc -l) 项变更）"
  echo ""
  echo "dry-run 结果："
  echo "  版本号   $OLD_NAME → $VERSION_NO_V（versionCode $NEW_CODE）"
  echo "  产物     $OUT_APK_DIR/$APK_NAME"
  echo "  证书     $CERT"
  echo "  说明     $NOTES"
  exit 0
fi

# ---------------------------------------------------------------- 提交与 tag
echo ""
echo "== 提交与 tag =="
git add app/build.gradle.kts README.md
MSG=".tmp/release_commit_msg_${VERSION_NO_V}.txt"
cat > "$MSG" <<EOF
release: $VERSION $([ "$STABLE" -eq 1 ] && echo '' || echo '测试版')

versionName $VERSION_NO_V / versionCode $NEW_CODE，release APK 用同一把上传密钥签名。
EOF
[ -n "$SUMMARY" ] && printf '%s\n' "$SUMMARY" >> "$MSG"
git commit -F "$MSG" >/dev/null
git tag -a "$VERSION" -m "MiuList ${VERSION}$([ "$STABLE" -eq 1 ] || echo '（测试版）')"
git log --oneline -1

# ---------------------------------------------------------------- 推送
echo ""
echo "== 推送 =="
git fetch origin >/dev/null 2>&1 || true
BEHIND="$(git rev-list --count HEAD..origin/main 2>/dev/null || echo 0)"
if [ "$BEHIND" -gt 0 ]; then
  echo "远端领先 $BEHIND 个提交，先 rebase"
  git pull --rebase origin main >/dev/null
fi
git push origin main
git push origin "$VERSION"

# ---------------------------------------------------------------- GitHub Release
echo ""
echo "== GitHub Release =="
export GCM_INTERACTIVE=never
export GCM_GUI_PROMPT=0
TOKEN="$(printf 'protocol=https\nhost=github.com\n\n' | git credential fill | sed -n 's/^password=//p')"
[ -n "$TOKEN" ] || { echo "!! 拿不到 GitHub 凭据（发布已跳过，代码与 tag 已推上去）" >&2; exit 1; }

API="https://api.github.com/repos/$REPO_OWNER/$REPO_NAME"
AUTH=(-H "Authorization: Bearer $TOKEN"
      -H "Accept: application/vnd.github+json"
      -H "X-GitHub-Api-Version: 2022-11-28"
      -H "User-Agent: MiuList-release")

# 已存在就更新，不存在就新建
EXISTING="$(curl -sS "${AUTH[@]}" "$API/releases/tags/$VERSION" | "$PY" -c 'import json,sys; d=json.load(sys.stdin); print(d.get("id",""))' 2>/dev/null || true)"
if [ -n "$EXISTING" ]; then
  echo "release 已存在（id=$EXISTING），改为更新"
  REL_ID="$EXISTING"
  curl -sS -X PATCH "${AUTH[@]}" -H 'Content-Type: application/json; charset=utf-8' \
    --data-binary "@$NOTES.payload.json" "$API/releases/$REL_ID" >/dev/null
else
  echo "创建 release（prerelease=$([ "$STABLE" -eq 1 ] && echo false || echo true)）"
  REL_ID="$(curl -sS -X POST "${AUTH[@]}" -H 'Content-Type: application/json; charset=utf-8' \
    --data-binary "@$NOTES.payload.json" "$API/releases" | "$PY" -c 'import json,sys; d=json.load(sys.stdin); print(d.get("id",""))')"
  [ -n "$REL_ID" ] || { echo "!! 建 release 失败" >&2; exit 1; }
fi

# 上传 APK
echo "上传 $APK_NAME"
UPLOAD="https://uploads.github.com/repos/$REPO_OWNER/$REPO_NAME/releases/$REL_ID/assets?name=$APK_NAME"
curl -sS -X POST "${AUTH[@]}" -H 'Content-Type: application/vnd.android.package-archive' \
  --data-binary "@$OUT_APK_DIR/$APK_NAME" "$UPLOAD" >/dev/null

# 核对
echo ""
echo "== 核对 =="
curl -sS "${AUTH[@]}" "$API/releases/tags/$VERSION" | "$PY" -c "
import json,sys
d=json.load(sys.stdin)
print('标题:  ', d.get('name'))
print('tag:   ', d.get('tag_name'))
print('类型:  ', 'Pre-release（测试版）' if d.get('prerelease') else '正式版')
print('页面:  ', d.get('html_url'))
for a in d.get('assets', []):
    print(f'  资产: {a[\"name\"]}  {round(a[\"size\"]/1048576,1)} MB  {a[\"state\"]}')
"

echo ""
echo "发版完成：$VERSION  ($APK_NAME)"
