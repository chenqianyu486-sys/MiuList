# MiuList 局域网同步协议（v3）

> 手机 App 与网页端**对等**，各自持有一份完整快照，都能独立运行。
> 同步时**由用户选方向**；两端都在线且没有冲突时自动同步。
> 传输全程**签名 + 密文**。

---

## 1. 为什么要「双端快照」而不是「手机当主机、网页端当镜像」

镜像模型下，网页端离开手机就不能用 —— 它的每一行数据都是手机给的。
于是「手机上删了一条待办」这类事，网页端要么看不到，要么被静默改写。

对等模型下：

| | 手机 App | 网页端 |
|---|---|---|
| 快照存哪 | Room（`/data/data/.../databases`） | 浏览器 localStorage |
| 断网能用吗 | 能 | **能**（打开就有数据，改了先记着） |
| 能改吗 | 能 | 能 |
| 谁是权威 | 取决于用户选的方向 | 同左 |

「权威」不是一个固定的东西，而是**每次同步时用户选的方向**。

---

## 2. 配对：secret 只走视觉通道

```
网页端                                         手机
  │ 本地生成 32 字节随机 secret                    │
  │ requestId = base64url(sha256(secret)前16字节) │
  │ POST /api/pair/request {requestId, deviceName}│
  │ ───────────────────────────────────────────► │  服务端**只见到摘要**
  │                                             │
  │ 把 miulist://pair?v=3&s=<secret>&r=<requestId>│
  │ 画成二维码                                   │
  │                                             │
  │              用户用手机相机扫 → 唤起 App ────►│  弹窗「允许这台设备连接吗」
  │                                             │  点「允许」
  │                                             │  手机用 secret 重算摘要，
  │                                             │  与 requestId 对上才批准
  │ GET /api/pair/status?requestId=…（轮询）      │
  │ ───────────────────────────────────────────► │
  │ ◄──────── {state:"approved", deviceId} ──────│
  │                                             │
  │ 两边各自用 secret 派生密钥 ──────────────────│
```

### 为什么 secret 不能走网络

v2 的配对码由服务端保存、网页端用**查询参数**轮询状态 ——
局域网里抓到那次轮询请求的人就拿到了配对码，紧接着能自己去领 token。
**抓包 = 拿到钥匙。**

v3 把 secret 的传递限制在「二维码 → 手机相机」这条**视觉通道**上：
服务端只见到它的摘要（sha256，不可逆），抓包的人拿不到 secret。

### 派生

```
effective = passcode 为空 ? secret : HMAC-SHA256(secret, "miulist-pw-v3" + passcode)
master    = HMAC-SHA256(effective, "miulist-master-v3")
kEnc      = HMAC-SHA256(master, "miulist-enc-v3")    // ChaCha20 密钥
kAuth     = HMAC-SHA256(master, "miulist-auth-v3")   // 请求签名
kMac      = HMAC-SHA256(master, "miulist-mac-v3")    // 密文 MAC
```

- 标签（label）分开，是为了让同一把 secret 在不同用途上不产生相同的子密钥。
- **kMac 必须与 kEnc 不同**：流密码的密钥复用会毁掉整个结构（用它认证等于用同一把钥匙既锁又签）。
- 同步密码（4 位数字）会**先混进 secret** —— 于是「二维码被别人拍到」也不足以解密。
  代价要说清楚：4 位数字只有 10⁴ 种，拿到 secret 的人可以离线毫秒级爆破它。
  它挡的是「旁边有人瞄到屏幕」，不是密码学意义上的第二因子。

### 配对码的规则

- `requestId` = 摘要的前 16 字节（128 位）转 base64url。
- 二维码里的 secret 与 requestId 不匹配 → **批准不了**（二维码被改过的防线）。
- 配对请求 5 分钟过期、**一次性**：批准后这个码立刻作废。
- token 不再存在 —— v3 用**每设备一把密钥**，可单独撤销。

---

## 3. 请求：挑战-应答签名

每个请求带四个头：

```
X-ML-Device: <deviceId>
X-ML-Ts:     <epoch 毫秒>
X-ML-Nonce:  <一次性随机串>
X-ML-Sig:    <base64url(HMAC(kAuth, "v3\nMETHOD\n/path\nts\nnonce\nbase64url(sha256(body))"))>
```

抓包拿到 sig **用不了**：

- **不能重放** —— nonce 每个请求随机，服务端记着「用过的 nonce」，同一个只能用一次；
- **不能伪造** —— 没有 kAuth 就算不出新的 sig；
- **不能挪到别的设备** —— deviceId 在签名之外也被校验；
- **不能换方法/路径/请求体** —— 它们都在签名覆盖范围内。

时间戳窗口 ±90 秒再挡掉「隔天重放」。

SSE（`/api/events`）用**查询串**带同样四个参数 ——
EventSource 不能设请求头，这是唯一的例外。

---

## 4. 负载：密文

请求体放在 `X-ML-Enc` 头里（base64url 的密文），响应体也是密文。

```
plaintext ──ChaCha20(kEnc, nonce)──► ciphertext
                     │
                     └──► MAC = HMAC-SHA256(kMac, nonce ‖ ciphertext)
sealed = nonce(12) ‖ ciphertext ‖ MAC(32)
```

**先加密再认证（encrypt-then-MAC）**：解密前先验 MAC，不对就拒。

- 算法：**ChaCha20 + HMAC-SHA256**。
  - 为什么不用浏览器自带的 `crypto.subtle`？它在**非 secure context 里不存在**，
    而我们的网页端是手机在局域网里用 `http://192.168.x.x` 直接发的 ——
    那个页面不是 secure context，`crypto.subtle` 是 undefined。
  - 为什么不用 AES-GCM？它的 GHASH 在 JavaScript 里没有 64 位运算，
    只能靠 BigInt 或拆 32 位对，又慢又容易写错；ChaCha20 的核心只有
    加法/异或/旋转，能用 **RFC 8439 官方测试向量**逐位验证，
    而且手机端与网页端能实现同一份逻辑（见 `web/crypto.js` 与 `WebCrypto.kt`）。
- 两端实现必须**逐字节一致** —— 密钥派生标签、密文格式、MAC 覆盖范围全都要对齐。
  `web/crypto.test.mjs` 与 `WebCryptoTest` 用同一组向量交叉验证。

---

## 5. 同步的规则

| 情况 | 行为 |
|---|---|
| 两端都在线 **且没有冲突** | **双向自动同步**，不用点任何东西 |
| 有一端离线 | 手动选方向 |
| 同一条待办两边都改过 | 手动选方向，并提示**两端快照的最新编辑时间** |

### 两条落库路径

`POST /api/snapshot/apply` 的请求体：

```jsonc
{
  "mode": "merge" | "replace",
  "snapshot": { /* 一份完整的快照 */ }
}
```

- **`merge`（增量）** —— 自动同步走这条：只把对方给的那几条按「后写覆盖」并进来，
  其余一概不动，**不删任何东西**。
  双方都没冲突时，只传各自改过的那几条就够。
- **`replace`（整份）** —— 手动选方向走这条：本机整体换成对方那一份。
  **删除能传播全靠它** —— 增量路径分不清「对方没有这条」是「还没同步」还是「被删了」。

### 护栏：三个 `hasXxx` 标记

课程 / 考试 / 自定义分类各自带一个 `hasXxx`：
**这一块是有效内容，还是这一端还不知道。**

网页端**还没成功拉过一次**手机数据时，它的快照里这三块是空的 ——
那不是「手机上没有课程」，而是「网页端还不知道」。没有标记就照单全收，
会把手机上的课表整个清空 —— 而「刚打开网页端就点同步」最容易踩这个坑。

### 快照里的周课表（`timetable`）

网页端课表页有一张**周课表栅格**：七列 = 这一周里真实的七个日期，
每列按节次排着当天的课与考试。它随快照一起走，形状是：

```jsonc
"timetable": {
  "totalWeeks": 16,          // 学期总周数（翻周箭头的上界）
  "currentWeek": 11,         // 当前周次
  "periodsPerDay": 13,       // 一天几节
  "periods": [{ "period": 1, "startMinute": 480, "endMinute": 525 }],
  "slotList": [{             // 去重后的全部时段，周计划里按下标引用它
    "courseKey": "PHYS1001|大学物理|王芳",
    "startPeriod": 3, "endPeriod": 4, "classroom": "教十 201"
  }],
  "weeks": [{
    "week": 11,
    "days": [{
      "date": "2026-10-26",  // 这一列是**真实日期**，不是星期几
      "dayOfWeek": 1,
      "offReason": null,      // NO_CLASS / VACATED / WEEKEND / OUT_OF_TERM
      "makeupDayOfWeek": null,// 补课日：补的是星期几的课
      "slotIndexes": [0],     // 下标指向 slotList
      "exams": [{ "courseName": "大学物理", "startPeriod": 2, "endPeriod": 4 }]
    }]
  }]
}
```

**为什么整张表在手机侧算好，而不是让网页端算**

一列是「这一周里的一个**真实日期**」，不是「星期几」——
补课只有在这一层才表达得出来：10-17 是周六，它要出周五的课，
而同一周的 10-16（周五）反而一节都没有。

哪些课落在哪一天牵扯四件事：开课周次、停课日、补课日、学期范围，
而这些规则在 App 里已经收口到 `ScheduleResolver` 一个地方了。
在网页端重写一份的代价不是「多写几十行」，而是**两边迟早给出不同的答案**，
而用户看到不一致时只会觉得网页端算错了 —— 他不知道手机那边有一份权威实现。

所以：**手机算好发过去，网页端只负责画。**
`snapshot.timetable` 为 null 表示「这一端还没有周课表」
（老版本 App，或课表还没生成过）—— 网页端据此把「周视图」那一档藏起来。

**载荷为什么长这样**

- **时段只存一份**（`slotList`），周计划里存下标（`slotIndexes`）：
  一门课在一学期里要出几十次，全文重复就是几十份一模一样的 JSON；
- **考试不重复**：一场考试只落在**一天**上，所以整张表里它只出现一次，
  内容直接内联进那一天。

### 冲突判定

冲突 = **同一条待办，两端都动过，而且动得不一样**。

| 情况 | 是不是冲突 |
|---|---|
| 对方没动过这条 | 否 |
| 对方也动了，两边内容一样 | 否（两边做了同一件事） |
| 对方也动了，两边内容不同 | **是** |
| 对方**没有**这条，而本地改过它 | **是**（一边改一边删） |
| 本地新建（`localOnly`），对方没有 | 否（还没推过去） |

冲突时**自动同步停下**，界面转成手动，并摆出**两端快照的最新编辑时间** ——
用户判断该往哪边同步，靠的是时间，不是「谁的条数多」。

---

## 6. 接口

除 `/api/health` 与 `/api/pair/` 下的接口外，全部要签名。

| 方法 | 路径 | 签名 | 密文 |
|---|---|---|---|
| GET | `/api/health` | 否 | 否 |
| POST | `/api/pair/request` | 否 | 否（此时还没有密钥） |
| GET | `/api/pair/status?requestId=` | 否 | 否（**里面没有凭据**） |
| GET | `/api/snapshot` | 是 | 是 |
| POST | `/api/snapshot/apply` | 是 | 是 |
| GET | `/api/events` | 是（查询串） | 事件载荷是 |
| GET | `/…`（静态文件） | 否 | 否 |

### SSE 事件

| 事件 | 数据 | 什么时候 |
|---|---|---|
| `hello` | 密文 `{revision, protocol}` | 连上时 |
| `change` | 密文 `{revision, reason}` | 本机数据变了 |
| `sync-request` | 密文 `{direction}` | **手机让网页端去同步** |

`sync-request` 的 `direction` 是从**执行方（网页端）**的视角说的：

- `pull` —— 网页端去手机那边拉（手机 → 网页）
- `push` —— 网页端把它的推给手机（网页 → 手机）
- `merge` —— 两边合并

手机发 `sync-request` 是因为**浏览器不能监听端口**：手机推不动数据，
但手机可以**请求**网页端去做，方向由手机选。

---

## 7. 时间

**所有时间都由服务端算成字符串再发出去**，网页端不做时区换算：

- `today`、`dueDateString`、`timeText`、`whenText` 都是服务端时区（Asia/Shanghai）下的字符串；
- 网页端渲染「最后编辑时间」用 `utcOffsetMinutes`，**不用浏览器本地时区** ——
  否则用户会看到「本页 05:14 · 手机 21:14」，以为差了一整个晚上。

---

## 8. 安全边界

- 局域网内**任何**设备都能访问这个服务 —— 凭据只有每设备一把密钥。
- 授权发生在**手机上**（扫深链 + 点「允许」）。
- 传输是密文（应用层），**不是 TLS**：
  - ✅ 抓包看到的是密文，拿不到待办内容，也伪造不了请求；
  - ❌ **同网段的主动中间人仍能篡改被送达的网页脚本**（index.html / app.js 是明文静态文件）。
    要彻底解决只能上 TLS（自签证书 + 指纹固定），代价是浏览器每次都要过证书警告。
- 设备密钥**加密落盘**在手机本地（Keystore AES-GCM），它等价于 secret 本身。
- 会话空闲 30 分钟即失效；每台设备可单独撤销。
- CORS **回显 Origin**，不写 `*`：写 `*` 等于让任何网页都能读本机这个 API。

---

## 9. 实现位置

| 文件 | 职责 |
|---|---|
| `web/crypto.js` | 浏览器端密码学（纯 JS，因为明文 HTTP 下没有 `crypto.subtle`） |
| `web/crypto.test.mjs` | 上述实现的自测（对照 Node / RFC 8439 / OpenSSL） |
| `web/app.js` | 网页端主逻辑：本地快照、差分、冲突判定、签名与加密 |
| `web/timetable.js` | 周课表：课程色两档推导（抄 App 的 `Color.kt`）+ 撞课分列 + 栅格渲染 |
| `tools/web-mock-server.mjs` | 零依赖 mock 服务（同一份协议的参考实现） |
| `app/.../data/sync/web/LanHttpServer.kt` | HTTP/SSE 内核（无新依赖） |
| `app/.../data/sync/web/WebCrypto.kt` | 手机端密码学（与网页端逐字节一致） |
| `app/.../data/sync/web/PairingStore.kt` | 配对状态机、签名校验、防重放 |
| `app/.../data/sync/web/SnapshotApplier.kt` | 落库（merge / replace 两条路径） |
| `app/.../data/sync/web/WebTimetableBuilder.kt` | 周课表计算（调 `ScheduleResolver`，纯函数可单测） |
| `app/.../data/sync/web/WebSyncApi.kt` | 路由与接口 |
| `app/.../data/sync/web/WebSyncService.kt` | 总控：服务生命周期、广播、地址、设备落盘 |
