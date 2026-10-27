/**
 * 找到手机：**按上次的网段扫一遍**。
 *
 * ## 为什么浏览器需要「扫」
 *
 * 浏览器**不能发 UDP 广播**（见 `docs/lan-sync-protocol.md` §1），
 * 所以网页端没法像旧启动器那样喊一嗓子「谁在局域网里」。
 *
 * 剩下的办法只有一个：**挨个敲**。而这要成立有两个前提：
 *
 * 1. **端口固定** —— 否则电脑不知道该敲哪个端口；
 * 2. **知道网段** —— 上次配对时记住的手机地址里就有（`192.168.1.20` → `192.168.1.x`）。
 *
 * 于是「配好一次，之后不用再填地址」这件事就成立了：
 * 手机换了 IP也不要紧，电脑扫一遍网段就把它找回来。
 *
 * ## 为什么不写在 `sync.ts` 里
 *
 * 因为这段逻辑**不碰状态、不碰 localStorage**，纯输入纯输出 ——
 * 可以像 `web/crypto.js`、`web/pairing.js` 那样用 node 直接跑断言
 * （见 `web/discovery.test.mjs`）。
 */

/**
 * 手机监听端口的候选。**第一个是固定的**（与手机端
 * `WebSyncService.PORT_CANDIDATES` 一致），后面几个是兜底。
 */
export const PHONE_PORTS = [38780, 38781, 38782, 38783, 38784];

/** 手机在局域网里用的那个固定端口。 */
export const DEFAULT_PHONE_PORT = PHONE_PORTS[0];

/**
 * 从「上次记住的手机地址」推出**要扫哪些主机**。
 *
 * 只处理 IPv4（`192.168.1.20` 这种）。别的主机名（`my-nas.local`）
 * 扫不出来 —— 返回空数组，调用方就当没得扫。
 *
 * @param {string} base 上次记住的地址（`http://192.168.1.20:38780`）
 * @returns {string[]} 主机地址（不含端口）
 */
export function subnetHosts(base) {
  let host = '';
  try {
    host = new URL(String(base || '')).hostname;
  } catch {
    return [];
  }
  const m = /^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/.exec(host);
  if (!m) return [];
  const octets = m.slice(1, 5).map(Number);
  if (octets.some((part) => part > 255)) return [];
  const [a, b, c, d] = octets;
  const out = [];
  // **自己那个地址排最前面** —— 手机换了 IP 的概率比端口变了小得多，
  // 先试它，命中就整个扫都省了。
  out.push(host);
  for (let last = 1; last <= 254; last++) {
    // 跳过手机原来那个地址（上面已经试过了）。
    if (last === d) continue;
    out.push(`${a}.${b}.${c}.${last}`);
  }
  return out;
}

/**
 * 挨个敲，直到有人应答。
 *
 * ## 为什么是「并发地」扫
 *
 * 254 个地址一个一个敲，每个最多等 800ms —— 那就是三分钟。
 * 并发 24 路扫，一轮 24 个，约 11 轮就扫完。
 *
 * ## 提前收工
 *
 * 一旦找到就停：剩下的 worker 在下一轮开头就看到 `found` 已经填了，
 * 直接退出 —— 不用等整个网段扫完。
 *
 * @param {object} options
 * @param {string[]} options.hosts 要试的主机
 * @param {number[]} [options.ports] 每个主机试哪些端口
 * @param {(host: string, port: number) => Promise<boolean>} options.probe 探针
 * @param {number} [options.concurrency] 并发数
 * @returns {Promise<{host: string, port: number} | null>}
 */
export async function sweepPhone({
  hosts,
  ports = PHONE_PORTS,
  probe,
  concurrency = 24,
}) {
  if (!Array.isArray(hosts) || !hosts.length) return null;
  if (typeof probe !== 'function') return null;

  let cursor = 0;
  let found = null;

  const worker = async () => {
    while (!found) {
      const index = cursor++;
      if (index >= hosts.length) return;
      const host = hosts[index];
      for (const port of ports) {
        if (found) return;
        try {
          const hit = await probe(host, port);
          if (hit) {
            found = { host, port };
            return;
          }
        } catch {
          // 这一台没响应 —— 试下一个端口/主机。
        }
      }
    }
  };

  const workers = [];
  const count = Math.max(1, Math.min(concurrency, hosts.length));
  for (let i = 0; i < count; i++) workers.push(worker());
  await Promise.all(workers);
  return found;
}

/**
 * 探针的默认实现：问一句 `/api/health`，看回不回 MiuList。
 *
 * @param {string} host
 * @param {number} port
 * @param {number} [timeoutMs]
 * @returns {Promise<boolean>}
 */
export async function probeHealth(host, port, timeoutMs = 800) {
  const controller = typeof AbortController !== 'undefined' ? new AbortController() : null;
  const timer = controller ? setTimeout(() => controller.abort(), timeoutMs) : null;
  try {
    const res = await fetch(`http://${host}:${port}/api/health`, {
      method: 'GET',
      cache: 'no-store',
      signal: controller ? controller.signal : undefined,
    });
    if (!res.ok) return false;
    const data = await res.json();
    return data?.app === 'MiuList';
  } catch {
    return false;
  } finally {
    if (timer) clearTimeout(timer);
  }
}
