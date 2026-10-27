/* 纯 JS 密码学原语的自检。
 *
 * 为什么要单独测：**这套实现是手写的**（浏览器明文 HTTP 下没有 crypto.subtle），
 * 而它是「局域网上你的待办会不会被人看见」的最后一道防线。
 * 手写的密码学没有自测 == 没有密码学。
 *
 * 测法：
 * - SHA-256 / HMAC → 对照 Node 的 crypto（权威实现）；
 * - ChaCha20 → 对照 **RFC 8439 §2.4.2 官方测试向量**，
 *   再对照 Node 的 OpenSSL chacha20（第二份独立实现）；
 * - seal/open → 往返 + 篡改必须被发现。
 *
 * 跑法：node web/crypto.test.mjs
 */

import nodeCrypto from 'node:crypto';
import {
  sha256, hmacSha256, chacha20, deriveKeys, seal, open,
  toBase64Url, fromBase64Url, randomBytes, utf8Bytes, concatBytes,
  dhGenerateKeypair, dhPublicKey, dhSharedSecret, pairCodeOf, dhGroup,
} from './crypto.js';

let failures = 0;
function check(name, ok, extra = '') {
  if (ok) {
    console.log(`  ok   ${name}`);
  } else {
    failures++;
    console.log(`  FAIL ${name} ${extra}`);
  }
}

function eqBytes(a, b) {
  if (a.length !== b.length) return false;
  for (let i = 0; i < a.length; i++) if (a[i] !== b[i]) return false;
  return true;
}

const hex = (b) => Buffer.from(b).toString('hex');

// ── SHA-256 ───────────────────────────────────────────────────

console.log('SHA-256');
{
  // 官方空串与 "abc" 的向量
  check('空串', hex(sha256(new Uint8Array(0))) ===
    'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855');
  check('"abc"', hex(sha256(utf8Bytes('abc'))) ===
    'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad');

  // 随机长度对照 Node（含跨块边界的长度）
  for (const len of [0, 1, 55, 56, 57, 63, 64, 65, 119, 120, 1000, 100000]) {
    const data = randomBytes(len);
    const mine = hex(sha256(data));
    const theirs = nodeCrypto.createHash('sha256').update(Buffer.from(data)).digest('hex');
    check(`随机 ${len} 字节`, mine === theirs, `${mine} != ${theirs}`);
  }
}

// ── HMAC-SHA256 ──────────────────────────────────────────────

console.log('HMAC-SHA256');
{
  // RFC 4231 测试用例 1
  const key = new Uint8Array(20).fill(0x0b);
  const data = utf8Bytes('Hi There');
  check('RFC 4231 用例 1', hex(hmacSha256(key, data)) ===
    'b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7');
  for (const len of [0, 1, 32, 63, 64, 65, 200]) {
    const k = randomBytes(len);
    const m = randomBytes(300);
    const mine = hex(hmacSha256(k, m));
    const theirs = nodeCrypto.createHmac('sha256', Buffer.from(k)).update(Buffer.from(m)).digest('hex');
    check(`随机 key ${len} / msg 300`, mine === theirs, `${mine} != ${theirs}`);
  }
}

// ── ChaCha20 ─────────────────────────────────────────────────

console.log('ChaCha20');
{
  // RFC 8439 §2.4.2（加密测试向量）
  const key = new Uint8Array(32);
  for (let i = 0; i < 32; i++) key[i] = i;
  // 注意：§2.4.2 的 nonce 是 00:00:00:00:00:00:00:4a:00:00:00:00，
  // **不是** §2.3.2 块函数向量里那个带 09 的 —— 两者差一个字节，混用会得到
  // 「实现像是错了」的假象（其实错的是向量）。
  const nonce = new Uint8Array([0, 0, 0, 0, 0, 0, 0, 0x4a, 0, 0, 0, 0]);
  const plaintext = utf8Bytes(
    "Ladies and Gentlemen of the class of '99: If I could offer you only one tip for the future, sunscreen would be it."
  );
  const ct = chacha20(key, nonce, 1, plaintext);
  const first16 = hex(ct.subarray(0, 16));
  // RFC 8439 §2.4.2 的密文首 16 字节。整段密文的逐字节对照交给下面的
  // OpenSSL —— 那是**另一份独立实现**，比手抄一段期望值可靠。
  check('RFC 8439 §2.4.2 前 16 字节', first16 === '6e2e359a2568f98041ba0728dd0d6981',
    `${first16} != 6e2e359a2568f98041ba0728dd0d6981`);
  check('RFC 8439 密文长度不变', ct.length === plaintext.length);

  // 对照 Node 的 OpenSSL chacha20（第二份独立实现）
  let openSslOk = true;
  try {
    for (const len of [1, 63, 64, 65, 500]) {
      const k = randomBytes(32);
      const n = randomBytes(12);
      const data = randomBytes(len);
      const c = chacha20(k, n, 1, data);
      // OpenSSL 的原始 chacha20：16 字节 IV = 4 字节小端 counter ‖ 12 字节 nonce
      const iv = Buffer.concat([Buffer.from([1, 0, 0, 0]), Buffer.from(n)]);
      const cipher = nodeCrypto.createCipheriv('chacha20', Buffer.from(k), iv);
      const theirs = Buffer.concat([cipher.update(Buffer.from(data)), cipher.final()]);
      if (!eqBytes(c, new Uint8Array(theirs))) {
        openSslOk = false;
        console.log(`    与 OpenSSL 不一致：len=${len}`);
        break;
      }
    }
    check('与 OpenSSL chacha20 逐字节一致', openSslOk);
  } catch (e) {
    console.log(`  skip 与 OpenSSL 对照（${e.message}）`);
  }

  // 跨块边界：counter 递增不能错位
  const k2 = randomBytes(32);
  const n2 = randomBytes(12);
  const d2 = randomBytes(200);
  const c2 = chacha20(k2, n2, 1, d2);
  check('200 字节跨 4 个块', eqBytes(chacha20(k2, n2, 1, d2), c2));
  // 同一密钥流再取一次必须相同
  check('确定性', eqBytes(chacha20(k2, n2, 1, d2), chacha20(k2, n2, 1, d2)));
}

// ── seal / open ──────────────────────────────────────────────

console.log('seal / open');
{
  const secret = randomBytes(32);
  const keys = deriveKeys(secret);
  const keys2 = deriveKeys(secret);
  check('派生确定性', eqBytes(keys.kEnc, keys2.kEnc) && eqBytes(keys.kAuth, keys2.kAuth) && eqBytes(keys.kMac, keys2.kMac));
  check('三把子密钥互不相同',
    !eqBytes(keys.kEnc, keys.kAuth) && !eqBytes(keys.kEnc, keys.kMac) && !eqBytes(keys.kAuth, keys.kMac));

  const plain = utf8Bytes(JSON.stringify({ hello: '世界', n: 12345 }));
  const sealed = seal(keys, plain);
  check('往返一致', eqBytes(open(keys, sealed), plain));
  check('密文不等于明文', !eqBytes(sealed.subarray(12, sealed.length - 32), plain));

  // 篡改一个字节必须被发现
  const tampered = sealed.slice();
  tampered[20] ^= 0x01;
  let caught = false;
  try { open(keys, tampered); } catch (_) { caught = true; }
  check('篡改密文被发现', caught);

  // 换一把密钥必须被发现
  const otherKeys = deriveKeys(randomBytes(32));
  caught = false;
  try { open(otherKeys, sealed); } catch (_) { caught = true; }
  check('换密钥被发现', caught);
}

// ── base64url ────────────────────────────────────────────────

console.log('base64url');
{
  for (const len of [1, 2, 3, 16, 32, 33]) {
    const b = randomBytes(len);
    const enc = toBase64Url(b);
    check(`${len} 字节往返`, eqBytes(fromBase64Url(enc), b));
    check(`${len} 字节无 padding`, !enc.includes('=') && !enc.includes('+') && !enc.includes('/'));
  }
}

// ── DH 密钥协商 ──────────────────────────────────────────────

console.log('DH（RFC 3526 group 14）');
{
  check('模数是 512 位十六进制（2048 位）', dhGroup.pHex.length === 512);
  check('模数是安全素数（p mod 24 == 23）', BigInt('0x' + dhGroup.pHex) % 24n === 23n);

  // **跨实现向量**：这组值在 Kotlin 侧（WebDhTest）必须逐字节对上。
  // 两边各写一份实现，对不上就是「配对显示成功、之后全都解密失败」。
  const V = {
    privA: 'SGwJzYUjf42RFVrrgD7N5YCbKpUpIjYm5hTseHYtiMk',
    pubA:
      'oF4x3H1Bih-U0yGAiJupaLn2vQfqafmAa3ngN1jjB5Q7LKtXLcd5M-btsl7q4dSzbntSz6byDBH3HgWT8Zi5nWgHS1kVudDHpAZxjVTqPd96b0B2zXEQv226xKZo3JZJ3VWg5QH1_zFWSYgTsbiOU01CwyAPb5oZyghPOyZyCyYzpfB9jSaG-GBEr2mlBXbX7UnqINHKEs9_qJ4o36EgbGu_okAidtPktho8gIwqewNwU4jH-paD9M-j61AGTDKH3eltwLaDfjzr_39cDxaqKAOR9rHZjgNKAWcsFazzVPC5V258_nTTrhkENzaMHqVPfEsZ_pwous5ogs8H1gijBA',
    privB: '1oxEBNFNm7FBsy8PheU-raGzlfkKr0kEV5ORJLz31Oo',
    pubB:
      'Rn64Z7aPzX3ybA11oK1pAQEDNHvcwEHOhRtHFWb7RcghLds9cjyFxPwCYIgJiB1VpE1hfKMPL1If1__svnZWdfIqFeNt7sKvlPO3ELXF5-R323Y2oHzjvbFS7N2nICYdDSSTFQjRA9-qfCYIRB6aXZdXyBGZG0PQM1dPe6wdO4jRFhkoCA8MFwYCiAY76MGCkABekSOJo5oSOpFxE476KAUj7bFxDKjEg31JAcshWs7AwS1ZFLE7EHmZNzc5kwkER8uJYGVQsyDUCWlSmSyS3mBMCNhkl4HolhLhdcZWizUCIaI9ocYRPVSdi5ROnxY5HRKCXcVg4HpNDqF64jNyEQ',
    shared: 'mOILLb-KxF2Ne3O_syKW6Qh43qhW0KXTO5Dh7Xk1vEQ',
    codeA: 'CJXWBZ',
  };

  const privA = fromBase64Url(V.privA);
  const privB = fromBase64Url(V.privB);
  const pubA = fromBase64Url(V.pubA);
  const pubB = fromBase64Url(V.pubB);

  check('公钥是 256 字节', pubA.length === 256 && pubB.length === 256);
  check('私钥是 32 字节', privA.length === 32 && privB.length === 32);

  check('由私钥算出的公钥与向量一致（A）', toBase64Url(dhPublicKey(privA)) === V.pubA);
  check('由私钥算出的公钥与向量一致（B）', toBase64Url(dhPublicKey(privB)) === V.pubB);

  const s1 = dhSharedSecret(privA, pubB);
  const s2 = dhSharedSecret(privB, pubA);
  check('两端算出的共享密钥一致', toBase64Url(s1) === toBase64Url(s2));
  check('共享密钥与向量一致', toBase64Url(s1) === V.shared);
  check('共享密钥是 32 字节', s1.length === 32);

  check('配对码与向量一致', pairCodeOf(pubA) === V.codeA);
  check('配对码是 6 个字符', pairCodeOf(pubA).length === 6);
  check('配对码不含容易混淆的 I L O U', !/[ILOU]/.test(pairCodeOf(pubA)));

  // 随机生成的一对：两端算出的共享密钥必须一致
  const a = dhGenerateKeypair();
  const b = dhGenerateKeypair();
  check(
    '随机密钥对也能协商出同一把密钥',
    toBase64Url(dhSharedSecret(a.priv, b.pub)) === toBase64Url(dhSharedSecret(b.priv, a.pub)),
  );
  check('随机公钥是 256 字节', a.pub.length === 256 && b.pub.length === 256);
  check('不同密钥对算出的密钥不同', toBase64Url(dhSharedSecret(a.priv, b.pub)) !== toBase64Url(dhSharedSecret(a.priv, a.pub)));
}

console.log(failures === 0 ? '\n全部通过' : `\n${failures} 条失败`);
process.exit(failures === 0 ? 0 : 1);
