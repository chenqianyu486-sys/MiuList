/* 浏览器端的密码学原语（纯 JS，零依赖）。
 *
 * ## 为什么不用 WebCrypto
 *
 * `crypto.subtle` **只在 secure context 里存在**（https 或 localhost）。
 * 我们的网页端是手机在局域网里用 http://192.168.x.x 直接发的，
 * 那个页面**不是 secure context** —— `crypto.subtle` 是 undefined。
 * `crypto.getRandomValues` 不受限（非 secure context 也有），所以随机数用它。
 *
 * 于是 HMAC 与 ChaCha20 只能自己实现。
 *
 * ## 为什么是 ChaCha20 而不是 AES-GCM
 *
 * - ChaCha20 的核心是 4 个加法/异或/旋转，**没有查表**，纯 JS 写起来短，
 *   而且可以用 **RFC 8439 的官方测试向量**逐位验证；
 * - AES 除了轮函数还得搭 GHASH，JavaScript 里没有 64 位运算，
 *   GHASH 只能用 BigInt 或拆成 32 位对，慢且更容易写错；
 * - 两端（手机用 Kotlin、网页用 JS）能实现**同一份** ChaCha20，
 *   不需要挑一个「Java 有、浏览器没有」的算法。
 *
 * 完整性与机密性分开做（encrypt-then-MAC）：
 * ChaCha20 是流密码，只管机密性；防篡改交给 HMAC-SHA256。
 *
 * ⚠️ 这是**自定义组合**，不是标准 AEAD。它的安全性依赖
 * 「先加密再认证」这个顺序和两把独立的密钥 —— 见 [deriveKeys]。
 */

/* ── 字节工具 ──────────────────────────────────────────────── */

export function utf8Bytes(str) {
  return new TextEncoder().encode(str);
}

export function toBase64(bytes) {
  let bin = '';
  for (let i = 0; i < bytes.length; i++) bin += String.fromCharCode(bytes[i]);
  return btoa(bin);
}

export function fromBase64(b64) {
  const bin = atob(b64);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

/** URL 安全的 base64（无 padding）。配对码与 nonce 都用它。 */
export function toBase64Url(bytes) {
  return toBase64(bytes).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

export function fromBase64Url(s) {
  let b64 = s.replace(/-/g, '+').replace(/_/g, '/');
  while (b64.length % 4) b64 += '=';
  return fromBase64(b64);
}

export function concatBytes(...parts) {
  let len = 0;
  for (const p of parts) len += p.length;
  const out = new Uint8Array(len);
  let off = 0;
  for (const p of parts) { out.set(p, off); off += p.length; }
  return out;
}

/**
 * 随机字节。
 *
 * **必须分块取**：`getRandomValues` 单次最多 65536 字节，
 * 超了会抛 `QuotaExceededError`（浏览器与 Node 都一样）。
 * 这里按 64KB 一块循环，调用方要多少给多少。
 */
export function randomBytes(n) {
  const out = new Uint8Array(n);
  const CHUNK = 65536;
  for (let off = 0; off < n; off += CHUNK) {
    const len = Math.min(CHUNK, n - off);
    const tmp = new Uint8Array(len);
    // getRandomValues 在非 secure context 里也可用（受限的只有 crypto.subtle）。
    crypto.getRandomValues(tmp);
    out.set(tmp, off);
  }
  return out;
}

/* ── SHA-256 ───────────────────────────────────────────────── */

const K = new Uint32Array([
  0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
  0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
  0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
  0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
  0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
  0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
  0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
  0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
]);

const rotr = (x, n) => (x >>> n) | (x << (32 - n));

/** SHA-256（同步）。输入输出都是字节。 */
export function sha256(message) {
  const h = new Uint32Array([
    0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a,
    0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19,
  ]);

  // 填充：0x80 + 若干 0 + 8 字节长度（大端）。
  const bitLenHi = Math.floor((message.length * 8) / 0x100000000);
  const bitLenLo = (message.length * 8) >>> 0;
  const withPad = new Uint8Array(((message.length + 9 + 63) >> 6) << 6);
  withPad.set(message);
  withPad[message.length] = 0x80;
  const dv = new DataView(withPad.buffer);
  dv.setUint32(withPad.length - 8, bitLenHi, false);
  dv.setUint32(withPad.length - 4, bitLenLo, false);

  const w = new Uint32Array(64);
  for (let off = 0; off < withPad.length; off += 64) {
    for (let i = 0; i < 16; i++) w[i] = dv.getUint32(off + i * 4, false);
    for (let i = 16; i < 64; i++) {
      const s0 = rotr(w[i - 15], 7) ^ rotr(w[i - 15], 18) ^ (w[i - 15] >>> 3);
      const s1 = rotr(w[i - 2], 17) ^ rotr(w[i - 2], 19) ^ (w[i - 2] >>> 10);
      w[i] = (w[i - 16] + s0 + w[i - 7] + s1) >>> 0;
    }
    let [a, b, c, d, e, f, g, hh] = h;
    for (let i = 0; i < 64; i++) {
      const S1 = rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25);
      const ch = (e & f) ^ (~e & g);
      const t1 = (hh + S1 + ch + K[i] + w[i]) >>> 0;
      const S0 = rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22);
      const maj = (a & b) ^ (a & c) ^ (b & c);
      const t2 = (S0 + maj) >>> 0;
      hh = g; g = f; f = e;
      e = (d + t1) >>> 0;
      d = c; c = b; b = a;
      a = (t1 + t2) >>> 0;
    }
    h[0] = (h[0] + a) >>> 0; h[1] = (h[1] + b) >>> 0;
    h[2] = (h[2] + c) >>> 0; h[3] = (h[3] + d) >>> 0;
    h[4] = (h[4] + e) >>> 0; h[5] = (h[5] + f) >>> 0;
    h[6] = (h[6] + g) >>> 0; h[7] = (h[7] + hh) >>> 0;
  }

  const out = new Uint8Array(32);
  const outView = new DataView(out.buffer);
  for (let i = 0; i < 8; i++) outView.setUint32(i * 4, h[i], false);
  return out;
}

/* ── HMAC-SHA256 ───────────────────────────────────────────── */

/** HMAC-SHA256。key 可以是任意长度。 */
export function hmacSha256(key, message) {
  const BLOCK = 64;
  let k = key;
  if (k.length > BLOCK) k = sha256(k);
  const padded = new Uint8Array(BLOCK);
  padded.set(k);
  const ipad = new Uint8Array(BLOCK);
  const opad = new Uint8Array(BLOCK);
  for (let i = 0; i < BLOCK; i++) {
    ipad[i] = padded[i] ^ 0x36;
    opad[i] = padded[i] ^ 0x5c;
  }
  const inner = sha256(concatBytes(ipad, message));
  return sha256(concatBytes(opad, inner));
}

/* ── ChaCha20 ──────────────────────────────────────────────── */

function rotl32(v, n) {
  return ((v << n) | (v >>> (32 - n))) >>> 0;
}

function quarterRound(x, a, b, c, d) {
  x[a] = (x[a] + x[b]) >>> 0; x[d] = rotl32(x[d] ^ x[a], 16);
  x[c] = (x[c] + x[d]) >>> 0; x[b] = rotl32(x[b] ^ x[c], 12);
  x[a] = (x[a] + x[b]) >>> 0; x[d] = rotl32(x[d] ^ x[a], 8);
  x[c] = (x[c] + x[d]) >>> 0; x[b] = rotl32(x[b] ^ x[c], 7);
}

/** ChaCha20 密钥流块（RFC 8439 §2.3）。 */
function chacha20Block(key, counter, nonce) {
  const state = new Uint32Array(16);
  state[0] = 0x61707865; state[1] = 0x3320646e; state[2] = 0x79622d32; state[3] = 0x6b206574;
  for (let i = 0; i < 8; i++) {
    state[4 + i] = (key[i * 4] | (key[i * 4 + 1] << 8) | (key[i * 4 + 2] << 16) | (key[i * 4 + 3] << 24)) >>> 0;
  }
  state[12] = counter >>> 0;
  for (let i = 0; i < 3; i++) {
    state[13 + i] = (nonce[i * 4] | (nonce[i * 4 + 1] << 8) | (nonce[i * 4 + 2] << 16) | (nonce[i * 4 + 3] << 24)) >>> 0;
  }
  const x = state.slice();
  for (let i = 0; i < 10; i++) {
    quarterRound(x, 0, 4, 8, 12);
    quarterRound(x, 1, 5, 9, 13);
    quarterRound(x, 2, 6, 10, 14);
    quarterRound(x, 3, 7, 11, 15);
    quarterRound(x, 0, 5, 10, 15);
    quarterRound(x, 1, 6, 11, 12);
    quarterRound(x, 2, 7, 8, 13);
    quarterRound(x, 3, 4, 9, 14);
  }
  const out = new Uint32Array(16);
  for (let i = 0; i < 16; i++) out[i] = (x[i] + state[i]) >>> 0;
  return out;
}

/**
 * ChaCha20 流密码。
 *
 * @param {Uint8Array} key 32 字节
 * @param {Uint8Array} nonce 12 字节
 * @param {number} counter 起始块计数
 * @param {Uint8Array} data 明文或密文
 * @returns {Uint8Array} 与 data 等长（异或密钥流）
 */
export function chacha20(key, nonce, counter, data) {
  if (key.length !== 32) throw new Error('ChaCha20 密钥必须是 32 字节');
  if (nonce.length !== 12) throw new Error('ChaCha20 nonce 必须是 12 字节');
  const out = new Uint8Array(data.length);
  let offset = 0;
  let c = counter >>> 0;
  while (offset < data.length) {
    const block = chacha20Block(key, c, nonce);
    c = (c + 1) >>> 0;
    const take = Math.min(64, data.length - offset);
    for (let i = 0; i < take; i++) {
      out[offset + i] = data[offset + i] ^ (block[i >> 2] >>> ((i & 3) * 8)) & 0xff;
    }
    offset += take;
  }
  return out;
}

/* ── 组合：加密 + 认证（encrypt-then-MAC） ─────────────────── */

/**
 * 从配对 secret 派生两把子密钥。
 *
 * - `kEnc` —— ChaCha20 密钥（32 字节）
 * - `kAuth` —— HMAC 密钥（认证请求用）
 * - `kMac` —— 密文的 MAC 密钥（**必须与 kEnc 不同**）
 *
 * **为什么不用 kEnc 直接当 MAC 密钥**：流密码的密钥复用会毁掉整个结构
 * （用它认证等于用同一把钥匙既锁又签）。分开派生是必须的。
 *
 * 标签（label）分开是为了让同一把 secret 在不同用途上不产生相同的子密钥。
 *
 * @param {Uint8Array} secret 配对 secret（32 字节）
 * @param {string} passcode 同步密码（可空）。**它会先混进 secret** ——
 *   于是「二维码被别人拍到了」不足以解密，还得知道这个密码。
 */
export function deriveKeys(secret, passcode = '') {
  const effective = passcode
    ? hmacSha256(secret, utf8Bytes('miulist-pw-v3' + passcode))
    : secret;
  const master = hmacSha256(effective, utf8Bytes('miulist-master-v3'));
  return {
    master,
    kEnc: hmacSha256(master, utf8Bytes('miulist-enc-v3')),
    kAuth: hmacSha256(master, utf8Bytes('miulist-auth-v3')),
    kMac: hmacSha256(master, utf8Bytes('miulist-mac-v3')),
  };
}

/**
 * 加密一段负载。
 *
 * @returns {Uint8Array} nonce(12) ‖ 密文 ‖ MAC(32)
 */
export function seal(keys, plaintext) {
  const nonce = randomBytes(12);
  const ciphertext = chacha20(keys.kEnc, nonce, 1, plaintext);
  const mac = hmacSha256(keys.kMac, concatBytes(nonce, ciphertext));
  return concatBytes(nonce, ciphertext, mac);
}

/**
 * 验签并解密。MAC 不对就抛错 —— **先验后解**，不要反过来。
 */
export function open(keys, sealed) {
  if (sealed.length < 12 + 32) throw new Error('密文太短');
  const nonce = sealed.subarray(0, 12);
  const ciphertext = sealed.subarray(12, sealed.length - 32);
  const mac = sealed.subarray(sealed.length - 32);
  const expect = hmacSha256(keys.kMac, concatBytes(nonce, ciphertext));
  let diff = 0;
  for (let i = 0; i < 32; i++) diff |= mac[i] ^ expect[i];
  if (diff !== 0) throw new Error('校验失败：数据被改过');
  return chacha20(keys.kEnc, nonce, 1, ciphertext);
}

/* ── DH 密钥协商（RFC 3526 group 14） ────────────────────────────
 *
 * ## 为什么要有它
 *
 * v3 的配对 secret 是**电脑生成、手机从二维码里拿到**的 ——
 * secret 只走「二维码 → 手机相机」这条视觉通道，从不经过网络。
 * 这条性质很好，但它让「手动填地址」这条路走不通：
 * 光有地址，电脑拿不到 secret，配对就停在半路。
 *
 * ## 换成 DH 之后
 *
 * 两端各自生成一对临时密钥，交换**公钥**，各自算出**同一把共享密钥**：
 *
 * ```
 * 电脑                              手机
 *  │  a 随机私钥                    │  b 随机私钥
 *  │  A = g^a mod p                 │  B = g^b mod p
 *  │  ──────── A ─────────────────► │  S = A^b mod p
 *  │  ◄─────── B ────────────────── │  S = B^a mod p
 *  │  secret = sha256(S)            │  secret = sha256(S)
 * ```
 *
 * **过网的只有公钥** —— 公钥本来就是公开的东西，抓包的人看到它也
 * 算不出共享密钥（DH 的单向性）。于是两条路都成立：
 *
 * - 「手机扫电脑的码」：A 走二维码（视觉通道）；
 * - 「只填地址」：A 走 HTTP（公钥过网不泄密）。
 *
 * ## 为什么用 MODP 群而不是 ECDH
 *
 * - 浏览器端：`crypto.subtle` 只在 secure context 里存在，而网页端
 *   可能跑在 `http://192.168.x.x` 上（手机托管网页端那条路）——
 *   那里 `crypto.subtle` 是 undefined，**连 BigInt 之外的密钥协商都做不了**；
 * - 手机端：`KeyAgreement("ECDH")` 在各家 Android 厂商的 JCE 上
 *   能不能用、用的哪条曲线，行为并不一致（老版本 AndroidOpenSSL
 *   干脆不注册 ECDH）。
 *
 * 而 **BigInt / BigInteger 两边都有**，实现只有一份逻辑：
 * 模幂 + 定长编码，两端逐字节一致很容易验证（见测试向量）。
 *
 * ## 参数
 *
 * - 群：RFC 3526 §3 的 **2048 位 MODP 群（group 14）**，生成元 g = 2；
 * - 私钥指数：256 位随机（够用：2048 位群配 256 位指数 ≈ 128 位安全强度，
 *   而且模幂只跑 256 轮，比跑 2048 轮快一个数量级）；
 * - 共享值 S 定长编码成 256 字节（大端、补零），再 `sha256` 成 32 字节的 secret。
 */

/** RFC 3526 §3 的 2048 位 MODP 素数（group 14）。 */
const DH_P_HEX =
  'FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD1' +
  '29024E088A67CC74020BBEA63B139B22514A08798E3404DD' +
  'EF9519B3CD3A431B302B0A6DF25F14374FE1356D6D51C245' +
  'E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7ED' +
  'EE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3D' +
  'C2007CB8A163BF0598DA48361C55D39A69163FA8FD24CF5F' +
  '83655D23DCA3AD961C62F356208552BB9ED529077096966D' +
  '670C354E4ABC9804F1746C08CA18217C32905E462E36CE3B' +
  'E39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9' +
  'DE2BCBF6955817183995497CEA956AE515D2261898FA0510' +
  '15728E5A8AACAA68FFFFFFFFFFFFFFFF';

const DH_P = BigInt('0x' + DH_P_HEX);
const DH_G = 2n;
/** 公钥 / 共享值的定长字节数。2048 位 = 256 字节。 */
const DH_KEY_BYTES = 256;
/** 私钥指数的字节数。 */
const DH_PRIV_BYTES = 32;

/** 配对码的标签。**必须与 Kotlin 侧逐字相同**（协议 v3.2）。 */
const PAIR_CODE_LABEL = 'miulist-paircode-v3.2';

/** 配对码的字母表：去掉 I / L / O / U，避免和 1 / 0 混淆。 */
const PAIR_ALPHABET = '0123456789ABCDEFGHJKMNPQRSTVWXYZ';

/** 模幂（平方-乘）。 */
function modPow(base, exponent, modulus) {
  let result = 1n;
  let b = base % modulus;
  let e = exponent;
  while (e > 0n) {
    if (e & 1n) result = (result * b) % modulus;
    b = (b * b) % modulus;
    e >>= 1n;
  }
  return result;
}

/** 大整数 → 定长字节（大端、高位补零）。 */
function bigIntToBytes(value, size) {
  const out = new Uint8Array(size);
  let v = value;
  for (let i = size - 1; i >= 0; i--) {
    out[i] = Number(v & 0xffn);
    v >>= 8n;
  }
  return out;
}

/** 字节 → 大整数（大端）。 */
function bytesToBigInt(bytes) {
  let v = 0n;
  for (let i = 0; i < bytes.length; i++) v = (v << 8n) | BigInt(bytes[i]);
  return v;
}

/**
 * 生成一对临时 DH 密钥。
 *
 * @returns {{priv: Uint8Array, pub: Uint8Array}} priv 32 字节，pub 256 字节
 */
export function dhGenerateKeypair() {
  const priv = randomBytes(DH_PRIV_BYTES);
  return { priv, pub: dhPublicKey(priv) };
}

/**
 * 由私钥算出公钥（A = g^a mod p）。
 *
 * 单独暴露是为了**能对着固定向量测**：随机生成的一对密钥没法写进测试里，
 * 而「给定私钥 → 确定的公钥」可以。
 *
 * @param {Uint8Array} priv 私钥（32 字节）
 * @returns {Uint8Array} 公钥（256 字节）
 */
export function dhPublicKey(priv) {
  return bigIntToBytes(modPow(DH_G, bytesToBigInt(priv), DH_P), DH_KEY_BYTES);
}

/**
 * **公钥合法性校验**（协议 v3.2 起强制）。
 *
 * ## 为什么必须查
 *
 * DH 的安全性有一个前提：**对端给的必须是合法公钥**。
 * 只查长度（256 字节）是不够的 —— 下面这几个值长度都对，却会让共享密钥
 * 退化成**全世界都知道的常量**：
 *
 * | 对端公钥 | 共享值 S | 后果 |
 * |---|---|---|
 * | `0`（全零） | 无意义 | 无法协商 |
 * | `1` | `1` | secret = sha256(定长的 1)，攻击者可以**自己算出来** |
 * | `p-1` | `p-1` | 同上 |
 *
 * 一旦会话密钥是公开常量，抓包的人就能解开全部流量。
 *
 * ## 为什么只查这一条就够了
 *
 * group 14 的 p 是**安全素数**（p 与 (p-1)/2 都是素数，RFC 3526 §3），
 * 所以这个群里的子群只有阶 1 与阶 2 两种 —— 也就是「1」与「p-1」本身。
 * 把这两个（以及 0）排除掉，就不存在别的「小阶公钥」可用。
 *
 * @param {Uint8Array} pub 对端公钥（256 字节）
 * @throws {Error} 公钥不合法
 */
export function dhValidatePublicKey(pub) {
  if (!pub || pub.length !== DH_KEY_BYTES) {
    throw new Error('公钥长度不对：必须是 256 字节');
  }
  const v = bytesToBigInt(pub);
  if (v < 2n || v > DH_P - 2n) {
    throw new Error('公钥不在合法范围（退化公钥会让共享密钥变成公开常量）');
  }
  return true;
}

/**
 * 算出共享密钥。
 *
 * **先校验公钥**（[dhValidatePublicKey]），再算 —— 不是算完再查。
 *
 * @param {Uint8Array} priv 本端私钥（32 字节）
 * @param {Uint8Array} peerPub 对端公钥（256 字节）
 * @returns {Uint8Array} 32 字节的 secret（= sha256(共享值)）
 */
export function dhSharedSecret(priv, peerPub) {
  dhValidatePublicKey(peerPub);
  const shared = modPow(bytesToBigInt(peerPub), bytesToBigInt(priv), DH_P);
  // 兜底：共享值同样不能是 1 或 p-1（正常情况下前面那步已经挡住了）。
  if (shared < 2n || shared > DH_P - 2n) {
    throw new Error('共享密钥退化，配对中止');
  }
  return sha256(bigIntToBytes(shared, DH_KEY_BYTES));
}

/**
 * **配对码**：两端公钥的 6 位短码，给用户肉眼核对。
 *
 * ## 为什么是「双方公钥」的哈希（协议 v3.2 改过）
 *
 * v3.1 的配对码是 `H(A)` —— **只有发起方（电脑）的公钥**。
 * 它能证明「我看到的 A 跟你看到的一样」，但**证明不了对面是谁**：
 * 因为对面报上来的本来就是从 A 算出来的码。
 *
 * v3.2 改成 `H(A‖B)`（A = 电脑公钥，B = 手机公钥）—— **转录绑定**。
 * 两端各自都拿得到 A 与 B，所以都能算；一旦中间人换掉其中任何一个，
 * 两端算出来的码就对不上。
 *
 * @param {Uint8Array} pubA 发起方（电脑）的公钥（256 字节）
 * @param {Uint8Array} pubB 响应方（手机）的公钥（256 字节）
 * @returns {string} 6 个字符
 */
export function pairCodeOf(pubA, pubB) {
  const digest = sha256(concatBytes(utf8Bytes(PAIR_CODE_LABEL), pubA, pubB));
  let out = '';
  for (let i = 0; i < 6; i++) out += PAIR_ALPHABET[digest[i] % 32];
  return out;
}

/** 配对码字母表（导出给测试与 Kotlin 侧对齐用）。 */
export const pairAlphabet = PAIR_ALPHABET;

/** DH 群参数（导出给测试用：模数是不是安全素数、长度对不对）。 */
export const dhGroup = {
  pHex: DH_P_HEX,
  keyBytes: DH_KEY_BYTES,
  privBytes: DH_PRIV_BYTES,
};

/* ── 请求签名 ──────────────────────────────────────────────── */

/**
 * 签名一次请求。
 *
 * 覆盖：协议版本、方法、路径、时间戳、nonce、请求体摘要。
 * **请求体也要进签名** —— 否则抓包的人可以把密文换成另一段密文。
 */
export function signRequest(keys, { method, path, ts, nonce, body }) {
  const bodyHash = sha256(body || new Uint8Array(0));
  const canonical = utf8Bytes(
    `v3\n${method.toUpperCase()}\n${path}\n${ts}\n${nonce}\n${toBase64Url(bodyHash)}`
  );
  return toBase64Url(hmacSha256(keys.kAuth, canonical));
}
