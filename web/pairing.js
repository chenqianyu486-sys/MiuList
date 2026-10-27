/**
 * 配对流程里的**纯逻辑**：地址归一化、配对码申请时机。
 *
 * 为什么要单独一个文件：这两件事是「配对只扫一次码」这条承诺的地基 ——
 *
 * - [normalizeBase] 决定**手机复制过来的那个地址能不能用**；
 * - [shouldRequestPairCode] 决定**会不会一次要两张配对码**。
 *
 * 它们都不碰网络、不碰 localStorage，所以可以像 `web/crypto.js` 那样
 * 用 node 直接跑断言（见 `web/pairing.test.mjs`）。
 */

/**
 * 把用户可能粘贴/输入的各种东西，归一成「接口根地址」。
 *
 * ## 为什么手机那边要「复制连接链接」而不是让人手打
 *
 * `http://192.168.1.20:8765` —— 手打容易少一个字符（端口最常错）。
 * 手机上那个按钮复制的就是这个串，粘到这里只要**归一化一次**就能用。
 *
 * ## 归一化做了什么
 *
 * - 没写 scheme 的补 `http://`（`192.168.1.20:8765` 是常见写法）；
 * - 去掉路径、查询串、片段（`origin`）—— 接口都在根路径上；
 * - 末尾的 `/` 去掉（`origin` 本来就不带）。
 *
 * ## 认不出来的一律返回空串
 *
 * 这一步很要紧：`new URL()` 对**几乎任何**字符串都能解析出一个主机名 ——
 * `不是地址` 会被解析成 `xn--ihqq6tnb086g`（中文的 punycode），
 * `ftp://192.168.1.20` 补完 scheme 后会变成主机名 `ftp`。
 *
 * 若把它们放过去，用户看到的是「连不上 http://xn--ihqq6tnb086g」——
 * 完全看不出是自己地址填错了。而**地址填错时应当当场说地址不对**。
 *
 * @param {string} input
 * @returns {string} 归一化后的地址；**认不出来时返回空串**（不是原样返回）。
 */
export function normalizeBase(input) {
  let text = String(input || '').trim();
  if (!text) return '';

  const scheme = /^([a-z][a-z0-9+.-]*):\/\//i.exec(text);
  if (scheme) {
    // **写了 scheme：只认 http / https。**
    if (!/^https?$/i.test(scheme[1])) return '';
  } else {
    // 「host:端口」这种写法 —— 端口得是数字。
    // 否则它就是个别的 scheme（javascript:、file: …），直接拒。
    // 注意 `localhost:8765` 走的是这一支：它长得像 scheme，其实是主机名。
    const hostPort = /^([a-z][a-z0-9+.-]*):(.*)$/i.exec(text);
    if (hostPort && !/^\d+(-\d+)?$/.test(hostPort[2])) return '';
    if (!/^https?:\/\//i.test(text)) text = `http://${text}`;
  }

  try {
    const url = new URL(text);
    if (url.protocol !== 'http:' && url.protocol !== 'https:') return '';
    if (!url.hostname) return '';
    if (!isPlausibleHost(url.hostname)) return '';
    return url.origin;
  } catch {
    return '';
  }
}

/**
 * 这个主机名「像不像一个真地址」。
 *
 * 局域网里手机那边的地址只有三种形态：**IPv4**、**本机名**（localhost）、
 * **带点的名字**（`my-nas.local`）。按这三种放行，其余一律拒绝。
 *
 * 特意不放行的两类：
 *
 * - **punycode**（`xn--…`）—— 那是一段中文被 URL 编码后的样子，不是地址；
 * - **顶级段里带数字或短横的**（`192.168.1.20` 之外的数字段）——
 *   那是「乱码被当成域名」的特征。
 *
 * @param {string} host `URL` 解析出来的主机名（不含端口）
 * @returns {boolean}
 */
function isPlausibleHost(host) {
  if (!host) return false;
  const lower = host.toLowerCase();
  if (lower === 'localhost') return true;

  // IPv4：四段，且每段 ≤ 255。
  if (/^\d{1,3}(\.\d{1,3}){3}$/.test(lower)) {
    return lower.split('.').every((part) => Number(part) <= 255);
  }

  // 国际化域名（punycode）多半是「一段中文」被编码的结果。
  if (lower.includes('xn--')) return false;

  const labels = lower.split('.');
  const labelOk = (label) => /^[a-z0-9]([a-z0-9-]*[a-z0-9])?$/.test(label);
  if (!labels.every(labelOk)) return false;
  // 带点的：最后一段必须是纯字母（.local / .com / .home …）。
  if (labels.length >= 2 && !/^[a-z]{2,24}$/.test(labels[labels.length - 1])) return false;
  return true;
}

/**
 * 现在该不该（重新）申请一张配对码。
 *
 * ## 为什么要有这条规则
 *
 * 同一时刻可能有两条路都想要一张配对码：
 *
 * - 连上地址之后申请一次；
 * - 配对卡片发现「地址已知、还没配对」又自动申请一次。
 *
 * 两条都跑会拿到**两个不同的 secret**：手机上多出一条待确认的请求，
 * 而网页端只在轮询**后一个**。用户若批准了前一个，
 * 网页端就会一直转圈直到配对码过期 —— 症状是「点了允许，电脑那边没反应」。
 *
 * 所以：正在申请中 / 正在等待批准时，**不要再要第二张**；
 * 只有用户明确点了「换一个配对码」（[force]）才重来。
 *
 * @param {string} status 当前的配对状态（'idle' | 'requesting' | 'waiting' | …）
 * @param {boolean} force 用户明确要换码
 * @returns {boolean}
 */
export function shouldRequestPairCode(status, force = false) {
  if (force) return true;
  return status !== 'requesting' && status !== 'waiting';
}
