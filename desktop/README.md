# MiuList 桌面网页端

把 MiuList 的网页端做成**一个可以直接双击的单文件 HTML**：

- 没有 exe、没有安装包、没有本地服务；
- 不会触发 Windows 的「未知发布者 / 未知应用」；
- 手机不在也能看课表、清单和考试，能改，连上手机后自动同步；
- 数据默认在浏览器 localStorage，**可选**绑定一个本机 JSON 文件做镜像（不含配对密钥）。

> 旧版 `tools/pc-web` 的 Python 启动器 / 单 exe 已退役；beta 起桌面端以这里为准。

---

## 快速开始

```bash
cd desktop
npm install
npm run build
```

产物：`desktop/dist/MiuList-Web.html`（约 540 KB，单文件）。

用 **Chrome / Edge** 双击打开。也可以把 HTML 拖进浏览器；建议收藏或固定标签页。

### 第一次连接手机

1. 手机上打开 MiuList → **更多 → 网页同步** → 打开「局域网服务」；
2. 电脑上点「扫描手机地址码」，对着手机屏幕上的**地址码**扫；
   没有摄像头就手动填地址（手机界面上有复制按钮），或上传二维码截图；
3. 电脑会自动生成配对码，用**手机相机**扫它，在手机上点「允许」；
4. 两端开始同步。地址与配对会被记住，下次打开自动连。

> 第一次是**两步扫码**：电脑扫手机地址 → 手机扫电脑配对码。
> 地址只用来找到手机；配对码始终由电脑生成、手机来扫。
> 这是配对协议决定的：secret 只能从电脑的屏幕经二维码到手机，不能走网络。
> 之后地址已经记住，打开即出码。

### 数据在哪里

| | 位置 | 说明 |
|---|---|---|
| 实时数据 | 浏览器 localStorage | 打开即用；手机不在也能编辑 |
| 可选镜像 | 你选择的 `miulist-snapshot.json` | 自动写入；**不含配对 secret / 同步密码** |
| 配对密钥 | 浏览器 localStorage | 换浏览器或清缓存后需要重新扫码 |
| 待办 | 两端各一份，自动同步 | 手机不在时，本机改的会排队，手机上线后自动并进去 |
| 课表 / 考试 / 分类 | 两端**各一份**，不自动同步 | 见下面「课表与考试：两端各管各的」 |

- 「同步 → 快照」可以**绑定已有 JSON**、**保存到新文件**、立即保存、导出/导入。
- 自动写入依赖 Chrome/Edge 的 File System Access API；其他浏览器用导出/导入。
- 快照是明文的个人数据（课程、待办、考试）；请像备份文件一样保管。

---

## 课表与考试：两端各管各的

**网页端是独立的一端，不是手机的镜像。**

- 自动同步只交换**待办**。课表、考试、分类是**配置**，各留各的：
  - 手机上改了课表，网页端**不会**被改掉；
  - 网页端也不会把手机的课表覆盖掉。
- 要搬的时候，在「同步 → 连接」里点：
  - **「用手机的课表和考试」** → 把手机的课表搬到这个浏览器（覆盖本机那份）；
  - **「把本页课表推给手机」** → 用这个浏览器的课表覆盖手机上的（**动待办**）。
- 「今天的课」是**本机按自己的课表算**的（周课表里已经算好了停课、补课、开课周次），
  不是手机算好的那一份 —— 手机那份是「快照生成那一刻」的，隔天再看会挂着一张别的天的课。

> 第一次用：连上手机后先去「同步 → 连接」点一次「用手机的课表和考试」，否则课表是空的。

---

## 功能

- **总览**：今天的课、全部待办（所有未完成任务，不限截止日期）、考试与课程摘要；课程时间轴与「正在上」状态。
- **课表**：周课表（停课、补课、开课周都已处理），课程 / 考试两个只读列表。
- **清单**：未完成 / 已完成 / 全部；搜索、按类别筛选；新建、编辑、勾选、星标。
- **同步**：扫码 / 手动地址、配对二维码、实时状态、冲突时选择「以本页待办为准 / 以手机待办为准 / 合并待办」；课表与考试可**手动搬**。
- **外观**：四组透光背景；毛玻璃卡片；桌面侧栏**鼠标靠近展开、可钉住**，窄屏自动切底部导航。
- **本机快照**：JSON 文件自动落盘；导出/导入兜底。

> 课表、考试、分类规则、自动生成待办的配置仍在手机 App 上做；
> 桌面端**只同步和展示**这些配置，不负责改它们 —— 但桌面端自己那份是独立的，
> 不会被手机自动改掉（见「课表与考试：两端各管各的」）。

---

## 开发

### 技术栈

- React 18 + TypeScript + Vite
- Zustand 状态
- `vite-plugin-singlefile`：把 JS/CSS 内联成单个 HTML
- `jsqr` 扫码、`qrcode.react` 配对码
- 协议与课表推导复用仓库里的 `web/crypto.js` / `web/timetable.js`，没有另写一套

```bash
npm run dev        # Vite dev server
npm run typecheck  # tsc --noEmit
npm run build      # tsc + vite build + 单文件重命名
```

### 用 mock 手机联调

```bash
# 仓库根目录
node tools/web-mock-server.mjs --port 8801 --auto-secret
```

它会打印一把 devsecret。构建后直接打开：

```
desktop/dist/MiuList-Web.html?base=http://127.0.0.1:8801&devsecret=<打印出来的值>
```

- `?base=`：预填手机地址（开发用）；
- `?devsecret=`：mock 没有摄像头，用它代替扫码；只有开发时存在；
- `?reset`：清掉本地快照/配对/快照文件句柄后重载；
- mock 加 `--no-auto-approve` 可以停在「等待手机允许」，方便看配对二维码。

> 新桌面端的同步请求密文走 **HTTP body**（`X-ML-Enc: 1` 标记），
> 响应密文也在 body。旧版「密文塞请求头」已删除：完整快照会超过手机侧 16 KB 的头上限。

### 验收

- `tools/capture-desktop-web.mjs` 是**桌面端**的行为验证 + 多尺寸截图矩阵：
  ```bash
  node tools/web-mock-server.mjs --port 8801 --auto-secret   # 另开一个终端
  node tools/capture-desktop-web.mjs --port 8801 --secret <打印出来的值> --out .tmp/desktop-shots
  ```
  它先跑九条断言（待办同步过来了 / 配置没被自动搬 / 手动搬得到 /
  手机改配置不动本机 / 手动推得上去 / 今天的课是本机算的），再按
  phone-390 / tablet-768 / desktop-1280 / desktop-1600 四档出图。
- `tools/capture-web.mjs` 是**旧网页端**（手机同源那个）的截图矩阵。
- 手机侧同步单测：
  ```bash
  gradlew.bat testDebugUnitTest --tests "com.example.data.sync.web.SnapshotApplierTest"
  gradlew.bat testDebugUnitTest --tests "com.example.data.sync.web.WebSyncApiTest"
  ```
  `SnapshotApplierTest` 里钉住了「远端没带课表时，本机课表不动，
  但待办仍然按自然键挂到本机课程上」—— 这条要是坏了，手机上会出现
  「网页端推过来的待办突然没有课」。
- 已经验过：`file://` 下 crypto、扫码上传、自动配对、同步、JSON 导出（无密钥）、
  局域网 IP + PNA、390px 窄屏无页面横向溢出、侧栏 hover 展开/钉住。

---

## 代码结构

| 文件 | 职责 |
|---|---|
| `src/core/crypto.ts` | 复用 `web/crypto.js` 的签名/加密/派生 |
| `src/core/sync.ts` | 配对、拉取/推送/合并、冲突、SSE、课表搬运、任务变更、JSON 快照调度 |
| `src/core/store.ts` | Zustand 应用状态与 UI 状态 |
| `src/core/storage.ts` | localStorage 键、快照规范化、持久化 |
| `src/core/snapshotFile.ts` | File System Access API + 导出/导入兜底 |
| `src/core/format.ts` | 日期、截止标签、排序、考试倒计时 |
| `src/core/todayCourses.ts` | 「今天的课」——按本机的周课表现算 |
| `src/core/timetable.ts` | 复用 `web/timetable.js` 的渲染器 |
| `src/views/*` | 总览 / 课表 / 清单 / 欢迎页 |
| `src/components/*` | 侧栏、同步面板、配对卡、扫码器、任务编辑器等 |

状态持久化沿用旧网页端的 `miulist.*` 键，因此同浏览器里已有的本地快照/配对可以继续用；
**旧版 exe 的 DPAPI 镜像 `web-state.bin` 不会自动导入**，请用 JSON 导入或重新配对。

---

## 限制（beta）

- **没有 UDP 自动发现**：浏览器不能发广播；地址扫/填一次并记住。
- **首次两步扫码**：之后自动同步；手动地址也只需要填一次。
- **File System Access API** 只在 Chromium（Chrome/Edge）可用；其他浏览器用导出/导入。
- **删除待办、课程/考试编辑、分类与生成规则** 仍在手机上做；桌面端只读课程/考试。
- **课表不会自动同步**：两端各留一份，要搬得手动点一下（见「课表与考试：两端各管各的」）。
- **手机 App 必须同批 beta**：同步请求体已从请求头改为 HTTP body；旧手机版本会 401。
- 摄像头扫码在 `file://` 下可用（Chrome/Edge 视为安全上下文）；没有摄像头时用上传截图。
- 天气没有放进桌面端；总览只显示课程/待办/考试。

---

## 安全

- 配对 secret 只在浏览器 localStorage 和二维码里，**不进网络、不进 JSON 文件**。
- JSON 快照是明文个人数据；配对密钥不在里面，但请不要随意分享。
- 同步请求/响应全程签名 + 密文；手机侧仍然只开 `INTERNET` / `ACCESS_NETWORK_STATE` 权限。
- 单文件 HTML 没有可执行代码签名问题；也无需安装任何服务。
