#!/usr/bin/env bash
# 发布**已经构建好的**那一半：修正并推送 tag → 建/更新 GitHub Release → 传资产 → 核对。
#
# ## 为什么要单独成一个脚本
#
# 发版有可能**中断在中间**：版本号提了、APK 编了、代码也推上去了，
# 却卡在「建 Release」之前。这时候总不能再从头跑一遍 ——
# tag 已经在了、工作区也不干净，前置检查会直接拒绝，发布就卡死了。
#
# 单独跑这个脚本能把剩下的补上：
#
#   tools/release_publish.sh v1.1.0 --stable --notes .tmp/release_notes_v1.1.0.md
#
# 它只做「发布」，不做「构建」—— 产物必须已经在那儿，
# 而且**版本号要对得上**（这里会自己查一遍，别信来路不明的旧产物）。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

REPO_OWNER="chenqianyu486-sys"
REPO_NAME="MiuList"
OUT_APK_DIR="app/build/outputs/apk/release"
WEB_HTML="desktop/dist/MiuList-Web.html"
SDK_ROOT="${ANDROID_SDK_ROOT:-$HOME/.workbuddy/binaries/android-sdk}"
PY="$(command -v python3 || command -v python)"

VERSION=""
STABLE=0
NOTES_FILE=""

usage() {
  sed -n '2,22p' "$0"
  exit 1
}

while [ $# -gt 0 ]; do
  case "$1" in
    --stable) STABLE=1 ;;
    --notes) NOTES_FILE="$2"; shift ;;
    -h|--help) usage ;;
    -*) echo "未知参数：$1" >&2; usage ;;
    *) VERSION="$1" ;;
  esac
  shift
done

[ -n "$VERSION" ] || usage
case "$VERSION" in
  v[0-9]*.[0-9]*.[0-9]*) ;;
  *) echo "版本号要写成 vX.Y.Z 的样子，比如 v1.1.0（收到的是：$VERSION）" >&2; exit 1 ;;
esac

VERSION_NO_V="${VERSION#v}"
APK_NAME="MiuList-${VERSION}.apk"
RELEASE_NAME="MiuList ${VERSION}"
if [ "$STABLE" -eq 0 ]; then
  RELEASE_NAME="MiuList ${VERSION}（测试版）"
fi
[ -n "$PY" ] || { echo "!! 找不到 python。" >&2; exit 1; }

# ---------------------------------------------------------------- 产物检查
echo "== 产物检查 =="
git rev-parse -q --verify "refs/tags/$VERSION" >/dev/null || {
  echo "!! 本地没有 tag $VERSION —— 先把发版提交打上 tag。" >&2
  exit 1
}
[ -f "$OUT_APK_DIR/$APK_NAME" ] || { echo "!! 没有 $OUT_APK_DIR/$APK_NAME" >&2; exit 1; }
[ -f "$WEB_HTML" ] || { echo "!! 没有 $WEB_HTML" >&2; exit 1; }

BT="$(ls -d "$SDK_ROOT"/build-tools/*/ 2>/dev/null | sort -V | tail -1)"
AAPT="$BT/aapt.exe"; [ -f "$AAPT" ] || AAPT="$BT/aapt"
BADGING="$("$AAPT" dump badging "$OUT_APK_DIR/$APK_NAME" 2>/dev/null | head -1)"
echo "$BADGING"
case "$BADGING" in
  *"versionName='$VERSION_NO_V'"*) echo "✓ APK 版本号对得上" ;;
  *) echo "!! APK 的版本号不是 $VERSION_NO_V —— 别拿旧产物发版。" >&2; exit 1 ;;
esac

# 桌面产物里的版本号（构建期注入在「关于」面板里）
if ! grep -o "about-version[^}]\{0,40\}$VERSION_NO_V" "$WEB_HTML" | grep -q "$VERSION_NO_V"; then
  echo "!! 桌面产物里没有版本号 $VERSION_NO_V —— 重新构建后再发。" >&2
  exit 1
fi
echo "✓ 桌面产物版本号对得上"

# ---------------------------------------------------------------- tag 指向
echo ""
echo "== tag =="
# tag 是在 rebase **之前**打的，而 rebase 会改写提交哈希 —— 于是 tag 会指着一个
# 已经不在 main 上的旧提交（v1.0.3 就撞上了：远端有个 Actions 的定时天气提交，
# rebase 之后 tag 指向的提交成了孤儿）。
#
# 所以推完之后要重新对一次：把 tag 移到 main 上那个「release: $VERSION」的提交。
#
# **匹配时不能要求版本号后面有空格**：正式版的标题就是「release: v1.1.0」，
# 提交时 git 会把行尾空格去掉 —— 带着空格的模式一条都匹配不上，
# 脚本会在这里直接退出，而那时代码已经推上去了。
REL_COMMIT="$(git log --format='%H' --grep="^release: $VERSION\( \|$\)" -1 main)"
if [ -z "$REL_COMMIT" ]; then
  echo "!! 在 main 上找不到「release: $VERSION」的提交，tag 先不动。" >&2
  exit 1
fi
if [ "$REL_COMMIT" != "$(git rev-list -n1 "$VERSION")" ]; then
  echo "tag 指向的提交被 rebase 改写了，重新指向 $REL_COMMIT"
  OLD_TREE="$(git rev-parse "$(git rev-list -n1 "$VERSION")^{tree}")"
  NEW_TREE="$(git rev-parse "$REL_COMMIT^{tree}")"
  if [ "$OLD_TREE" != "$NEW_TREE" ]; then
    CHANGED="$(git diff --name-only "$(git rev-list -n1 "$VERSION")" "$REL_COMMIT")"
    echo "   差异文件: ${CHANGED:-（无）}"
    case "$CHANGED" in
      *weather.json|"") ;;
      *)
        echo "!! 差异里有非 CI 生成的文件，tag 先不动，请人工确认。" >&2
        exit 1
        ;;
    esac
    echo "   差异只在 CI 自动生成的文件上，安全"
  fi
  git tag -f -a "$VERSION" "$REL_COMMIT" -m "MiuList ${VERSION}$([ "$STABLE" -eq 1 ] || echo '（测试版）')"
fi
git push --force origin "$VERSION"

# ---------------------------------------------------------------- GitHub Release
echo ""
echo "== GitHub Release =="
NOTES=".tmp/release_notes_${VERSION_NO_V}.md"
if [ -n "$NOTES_FILE" ]; then
  # 同一个路径就别拷了 —— `cp a a` 会直接报错退出。
  if [ "$NOTES_FILE" != "$NOTES" ]; then
    cp "$NOTES_FILE" "$NOTES"
  fi
  echo "用指定的说明文件：$NOTES_FILE"
else
  COMMITS=".tmp/release_commits_${VERSION_NO_V}.txt"
  PREV_TAG="$(git tag --list 'v*' --sort=-v:refname | grep -v "^$VERSION$" | head -1)"
  if [ -n "$PREV_TAG" ]; then
    git log --pretty=format:'%s' "$PREV_TAG..HEAD" > "$COMMITS"
  else
    git log --pretty=format:'%s' -20 > "$COMMITS"
  fi
  "$PY" tools/release_helper.py notes \
    --version "$VERSION" \
    --prev-tag "${PREV_TAG:-$VERSION_NO_V}" \
    --apk-name "$APK_NAME" \
    --commits-file "$COMMITS" \
    --out "$NOTES"
fi

export GCM_INTERACTIVE=never
export GCM_GUI_PROMPT=0
TOKEN="$(printf 'protocol=https\nhost=github.com\n\n' | git credential fill | sed -n 's/^password=//p')"
[ -n "$TOKEN" ] || { echo "!! 拿不到 GitHub 凭据（代码与 tag 已推上去，Release 没建）" >&2; exit 1; }

API="https://api.github.com/repos/$REPO_OWNER/$REPO_NAME"
AUTH=(-H "Authorization: Bearer $TOKEN"
      -H "Accept: application/vnd.github+json"
      -H "X-GitHub-Api-Version: 2022-11-28"
      -H "User-Agent: MiuList-release")

# --tag 不能省：tag_name 是 GitHub 建 release 的必填项，缺了 API 直接 422。
"$PY" tools/release_helper.py payload \
  --name "$RELEASE_NAME" \
  --tag "$VERSION" \
  --notes-file "$NOTES" \
  --out "$NOTES.payload.json" \
  $([ "$STABLE" -eq 0 ] && echo "--prerelease")

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

# 上传桌面单文件网页版（产物检查已经保证它在、且版本号对）
echo "上传 MiuList-Web.html"
UPLOAD_WEB="https://uploads.github.com/repos/$REPO_OWNER/$REPO_NAME/releases/$REL_ID/assets?name=MiuList-Web.html"
curl -sS -X POST "${AUTH[@]}" -H 'Content-Type: text/html; charset=utf-8' \
  --data-binary "@$WEB_HTML" "$UPLOAD_WEB" >/dev/null

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
echo "发布完成：$VERSION  ($APK_NAME)"
