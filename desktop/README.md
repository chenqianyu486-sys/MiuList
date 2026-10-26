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
2. 电脑上点「扫描手机二维码」，对着手机屏幕上的**地址二维码**扫；
   没有摄像头就手动填地址（手机界面上有复制按钮），或上传二维码截图；
3. 电脑上出现配对码，用**手机相机**扫它，在手机上点「允许」；
4. 两端开始同步。地址与配对会被记住，下次打开自动连。

> 第一次是**两步扫码**：电脑扫手机地址 → 手机扫电脑配对码。
> 这是配对协议决定的：secret 只能从电脑的屏幕经二维码到手机，不能走网络。
> 之后地址已经记住，打开即用。

### 数据在哪里

| | 位置 | 说明 |
|---|---|---|
| 实时数据 | 浏览器 localStorage | 打开即用；手机不在也能编辑 |
| 可选镜像 | 你选择的 `miulist-snapshot.json` | 自动写入；**不含配对 secret / 同步密码** |
| 配对密钥 | 浏览器 localStorage | 换浏览器或清缓存后需要重新扫码 |
| 手机 | Room 数据库 | 两端各持一份完整快照，同步时按所选方向合并 |

- 「同步 → 快照」可以**绑定已有 JSON**、**保存到新文件**、立即保存、导出/导入。
- 自动写入依赖 Chrome/Edge 的 File System Access API；其他浏览器用导出/导入。
- 快照是明文的个人数据（课程、待办、考试）；请像备份文件一样保管。

---

## 功能

- **总览**：今天的课、今天的待办、考试与课程摘要；课程时间轴与「正在上」状态。
- **课表**：手机算好的真实日期周课表（停课、补课、开课周都已处理），课程/考试两个只读列表。
- **清单**：未完成/已完成/全部；搜索、按类别筛选；新建、编辑、勾选、星标。
- **同步**：扫码/手动地址、配对二维码、实时状态、冲突时选择「以本页为准 / 以手机为准 / 合并」。
- **外观**：四组透光背景；毛玻璃卡片；桌面侧栏**鼠标靠近展开、可钉住**，窄屏自动切底部导航。
- **本机快照**：JSON 文件自动落盘；导出/导入兜底。

> 课表、考试、分类规则、自动生成待办的配置仍在手机 App 上做；桌面端只同步和展示手机算好的结果。

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

- `tools/capture-web.mjs` 是旧网页端的截图矩阵；新桌面端可以用 Playwright 打开单文件 HTML，
  在 `/tmp` 写一次截图脚本即可（参考本次改动时的 `smoke` 流程）。
- 手机侧同步单测：
  ```bash
  gradlew.bat testDebugUnitTest --tests "com.example.data.sync.web.WebSyncApiTest"
  ```
- 已经验过：`file://` 下 crypto、扫码上传、自动配对、同步、JSON 导出（无密钥）、
  局域网 IP + PNA、390px 窄屏无页面横向溢出、侧栏 hover 展开/钉住。

---

## 代码结构

| 文件 | 职责 |
|---|---|
| `src/core/crypto.ts` | 复用 `web/crypto.js` 的签名/加密/派生 |
| `src/core/sync.ts` | 配对、拉取/推送/合并、冲突、SSE、任务变更、JSON 快照调度 |
| `src/core/store.ts` | Zustand 应用状态与 UI 状态 |
| `src/core/storage.ts` | localStorage 键、快照规范化、持久化 |
| `src/core/snapshotFile.ts` | File System Access API + 导出/导入兜底 |
| `src/core/format.ts` | 日期、截止标签、排序、考试倒计时 |
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
- **手机 App 必须同批 beta**：同步请求体已从请求头改为 HTTP body；旧手机版本会 401。
- 摄像头扫码在 `file://` 下可用（Chrome/Edge 视为安全上下文）；没有摄像头时用上传截图。
- 天气没有放进桌面端；总览只显示课程/待办/考试。

---

## 安全

- 配对 secret 只在浏览器 localStorage 和二维码里，**不进网络、不进 JSON 文件**。
- JSON 快照是明文个人数据；配对密钥不在里面，但请不要随意分享。
- 同步请求/响应全程签名 + 密文；手机侧仍然只开 `INTERNET` / `ACCESS_NETWORK_STATE` 权限。
- 单文件 HTML 没有可执行代码签名问题；也无需安装任何服务。
