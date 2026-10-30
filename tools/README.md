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
| `capture-dialogs.sh` | **弹层**矩阵：逐个打开各弹层并截图（页面矩阵只抓四个页面，弹层要另跑） |
| `capture-onboarding.sh` | **上手引导**矩阵：逐张抓引导的每一步 |
| `capture-quickgenerate.sh` | **「一键生成待办」开关**矩阵：开关开 / 关两态下的顶栏、抽屉、引导 |
| `emulator.sh` | **模拟器启停**：按需起（两种渲染模式）、按需停（回收 ~2 GB 内存） |
| `contact_sheet.py` / `make_sheets.py` | 把一批截图拼成联络表（一断点一张） |
| `compare_sheet.py` | 把「改动前 / 改动后」并排拼成对照表 |
| `release.sh` | **发版**：提版本号 → 构建 → 校验 → 提交打 tag → 推送 → 发 Release |

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

## 弹层矩阵（`capture-dialogs.sh`）

页面矩阵只抓四个**页面**；弹层要另跑：

```bash
tools/capture-dialogs.sh                 # 跑默认断点
tools/capture-dialogs.sh C411 G800       # 只跑指定断点
TASKS_ONLY=1 tools/capture-dialogs.sh    # 只重抓「待办编辑弹层」那四张，不清空目录
```

`TASKS_ONLY=1` 是改完待办编辑弹层之后**只想重验它**时用的：
整轮弹层要跑二十分钟，而待办那四张（编辑 / 新建 / 课程滚筒展开 / 键盘弹起）只要五分钟。

弹层是**同窗口 overlay**，合成树里排在页面内容之后 —— 要点弹层里那个和页面同名的节点
（待办标题就是典型），必须用脚本里的 `tap_label_last`（取最后一个匹配），
用取第一个的那档会点在**被遮住的**页面上，等于点到了遮罩、把弹层关掉。

## 引导矩阵（`capture-onboarding.sh`）

上手引导是**同窗口 overlay**，而页面矩阵只抓四个页面、弹层矩阵只抓那十几个弹层 ——
引导两边都没覆盖。它的面板高度又**跟着内容走**（每一步加一句说明就长一截），
窄屏 + 大字号时最容易把按钮顶出面板，所以最该看截图。

```bash
tools/capture-onboarding.sh                 # 跑默认断点
tools/capture-onboarding.sh R332f116 C411   # 只跑指定断点
```

每断点六张：引导五步各一张、第 4 步的预览弹层一张。

**引导走几步取决于「一键生成待办」开没开**：关着是轻量三步
（同步课表 → 一键生成开关 → 壁纸），开着是完整五步。
脚本从面板上读步数（「共 N 步」）再决定怎么走 —— 写死的话开关一改就拍错。

## 开关矩阵（`capture-quickgenerate.sh`）

「一键生成待办」这个开关改的是**界面上有没有东西**：顶栏那颗 ✨、
「更多」抽屉里那两行规则、以及新手引导走几步。所以要在一档屏幕尺寸下
把两态都看一遍。

```bash
tools/capture-quickgenerate.sh                # 跑默认断点
tools/capture-quickgenerate.sh C411           # 只跑指定断点
tools/capture-quickgenerate.sh --guide-only   # 只抓引导（少一半图，跑得快）
```

每断点 15 张：顶栏（开 / 关各一张）、抽屉（开 / 关各一张）、
引导的轻量三步、按下「开启」之后落到「课程分类」那一张、引导的完整五步。

**开关是点出来的**：脚本在抽屉里找那个 `checkable="true"` 的节点
（Compose 把它暴露成一个 checkable 的 View，class 不是 `Switch`），
读它的 `checked` 属性决定要不要拨 —— 所以这一轮同时也是「开关真的能用」的验证。

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

---

## 模拟器：`tools/emulator.sh`

```bash
tools/emulator.sh start            # 起（默认 swiftshader，可与基线逐像素比）
tools/emulator.sh start --gpu host # 起（宿主 GPU，快，但像素与基线不可比）
tools/emulator.sh stop             # 关（立刻回收约 2 GB）
tools/emulator.sh status           # 看跑没跑、占多少、当前屏幕/密度/字号
```

`start` 会把设备设成**用户真机那一档**（1080×2414 @520dpi = 332×743 dp，
系统字号 1.16）—— 自适应问题多半出在这一档。

> 同一个约定现在也写进了预览启动器：双击 `..\start-preview.bat` 会在 App 起来之前
> 把模拟器放回**用户真机**那一档（`-Display base` 换成设计基准 411dp，
> `-Display keep` 表示别动）。矩阵跑完要是忘了还原，直接开预览就先把屏幕和字号
> 摆正了 —— 留着 1600x1200 或 2.0 字号看 App，看到的是谁都没要过的排版。
> **真机永远不动**：手机自己的屏幕比例和字号就是「正常」的。

### 两种渲染模式不能混用

| 模式 | 快慢 | 能不能和基线逐像素比 |
|------|------|---------------------|
| swiftshader（默认） | 慢（CPU 软画每一帧） | **能** |
| host | 快（走真显卡） | **不能** |

两者的抗锯齿不同，截图差几个灰阶。基线目录 `.tmp/adapt/baseline` 是
**swiftshader** 抓的，所以：

- **调试轮**（反复改、反复看）：`--gpu host`，省时间；
- **验收轮**（要跑 `compare_baseline.py`）：必须回到 swiftshader，
  否则比对报告里的「差异率」全是噪声。

### 为什么值得「用完就关」

无窗口模拟器常驻 **约 2 GB**。这台机器提交上限只有 34 GB，构建期一度占到 71%，
Windows 开始压缩内存页，桌面就跟着发飘（用户说的「电脑越来越卡」）。
`stop` 一下就是 2 GB。

**别用 bash 后台任务起模拟器**：任务有 30 分钟上限，超时会把模拟器一起杀掉。
脚本内部用 PowerShell 的 `Start-Process` 起，进程独立于脚本。

---

## 预览启动器：仓库根目录的 `start-preview.bat`

开发时最常用的一条路：**双击 `..\start-preview.bat`** —— 构建 → 起模拟器 → 安装 → 启动 App，
一条命令走完。它住在**仓库根目录**（不在 `tools/` 里），因为它要跟 `app/`、`gradlew.bat`
并排使用，而且它写出来的日志也落在根目录（都被 `.gitignore` 忽略了）。

| 文件 | 干什么 |
|------|--------|
| `start-preview.bat` | 双击入口。只负责开窗口、夹窗口、转发参数 |
| `preview.ps1` | 真正的流程编排（构建 → 起 AVD → 装 → 启动） |
| `activate-preview.ps1` | 把预览窗口从最小化/屏幕外捞回来并置顶 |
| `clamp-window.ps1` | 把控制台与模拟器窗口夹在桌面可用区内 |
| `diag-preview-window.ps1` | 诊断预览窗口位置用的 |
| `drive-zju-sync.ps1` | 在已启动的模拟器上驱动 App 走一次真实的浙大同步 |

常用开关：

```bash
start-preview.bat                 # 默认：用户真机那一档（1080×2414 @520dpi，字号 1.16）
start-preview.bat -DryRun         # 只打印计划，不动手
start-preview.bat -SkipBuild      # 跳过 Gradle
start-preview.bat -Display base   # 换成设计基准 411dp
start-preview.bat -ColdBoot       # 丢弃快照冷启动
```

### 三个要点

1. **真机永远不动**：手机自己的屏幕比例和字号就是「正常」的，`-Display` 只作用于模拟器。
2. **就绪判据是 `sys.boot_completed=1`**：模拟器对 `adb` 报 "device" 之后仍可能有十几秒
   装不上包（"device is still booting"）。早期那版拿 `pm path android` 有应答当就绪，
   而它比 boot 标志早 12 秒应答 —— 于是经常装到还没启动完的机器上。
3. **签名冲突会自动兜底**：换签名时先把设备上的 `databases` / `shared_prefs` / `files`
   备份到仓库根的 `preview-data-<包名>-<时间戳>/`（已被 gitignore —— 里面是个人数据），
   再卸载旧的装新的，最后打印还原命令。

### `preview.ps1` 怎么找项目目录

向上找**带 `app/build.gradle.kts` 的那一层**，找不到就退回自己所在目录。
所以脚本放在仓库根目录能用，挪到 `tools/` 下面也能用 —— 位置和目录结构是解耦的。
（老写法是「脚本所在目录下的同名子目录 `MiuList`」，脚本进仓库之后那条假设就不成立了。）

---

## 发版：`tools/release.sh`

```bash
tools/release.sh v1.0.2                 # 发测试版（GitHub 上是 pre-release）
tools/release.sh v1.0.2 --stable        # 发正式版
tools/release.sh v1.0.2 --dry-run       # 只构建与校验，不提交不推送不发布
tools/release.sh v1.0.2 --notes my.md   # 用自己写的发布说明
tools/release.sh v1.0.2 --summary "一句话概述"
```

一条命令走完：**提版本号 → 构建 → 校验 → 提交 → 打 tag → 推送 → 发 Release + 上传 APK**。

顺序是「先验证、后发布」，因为发版不可逆 —— APK 一旦传上去，别人就能下到。
本地会先查完这些：

| 查什么 | 不对会怎样 |
|--------|-----------|
| 版本号与 versionCode | 直接退出 |
| 签名是否有效 | 直接退出 |
| **证书与上一版是不是同一把** | 大声警告：不是同一把，用户得先卸载旧版，数据会丢 |
| 上一版 tag 是否已存在 | 直接退出（避免覆盖） |

### 前提

- 工作区**必须干净**（发版前先把改动提交）。
- `keystore.properties` 在仓库根（已被 gitignore）：`KEYSTORE_PATH` / `STORE_PASSWORD` /
  `KEY_ALIAS` / `KEY_PASSWORD`。构建脚本读的是**环境变量**，`release.sh` 负责从 properties 导进去。
- GitHub 凭据走系统凭据管理器（`git credential fill` 能取到），不需要额外配置。

### 三个踩过的坑（脚本里都注释了）

1. `keystore.properties` 是 **CRLF 行尾**，不剥掉 `\r` 的话密码末尾多一个字符，
   签名校验报 `validateSigningRelease FAILED`（报错里一个字都不提密码）。
2. `git credential fill` **不能自己写 username** —— 库里存的是 `chenqianyu486-sys`，
   写错会让凭据管理器转去走交互式认证，整个命令挂住。
3. JSON 载荷用 `release_helper.py`（Python）拼，**不要用 PowerShell 的
   `ConvertTo-Json`** —— 它会把字符串序列化成 `{"value": "..."}` 对象，GitHub 判非法。

### 想知道更多

- `--dry-run` 会在构建与校验之后**把版本号还原**（任何退出路径都还原，包括构建失败）。
- 想验 release 包能不能启动：手写一份临时 AVD（`avdmanager` 在这台机器上不可用），
  用完 `avdmanager delete avd -n <名字>` 删掉。

---

## 电脑端网页端：`desktop/`

**beta 起桌面端改为单文件静态页面**：`desktop/dist/MiuList-Web.html`。
Chrome / Edge 打开，浏览器直接当桌面端；没有 exe、没有本地服务，也没有
Windows 的「未知发布者」弹窗。数据用 localStorage + 可选本机 JSON 快照
（不含配对密钥）；手机地址从手机「网页同步」复制，配对在手机上点「允许」。

```bash
cd desktop
npm install
npm run dev       # 开发（Vite）
npm run build     # → desktop/dist/MiuList-Web.html
```

联调（mock 手机）：

```bash
node tools/web-mock-server.mjs --port 8801
# 打开 …/MiuList-Web.html?base=http://127.0.0.1:8801
```

- 新桌面端源码/说明：[../desktop/README.md](../desktop/README.md)
- 协议（含新的密文 body 传输）：[../docs/lan-sync-protocol.md](../docs/lan-sync-protocol.md)

> `tools/pc-web/` 的 Python 启动器与单 exe 已退役（会触发 Windows 的未签名应用提示）；
> 保留目录仅供历史 / 本地开发参考。它用的仍是同一份 `web/`，但不再随 beta 分发。
