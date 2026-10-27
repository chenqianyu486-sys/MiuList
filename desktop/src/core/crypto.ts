// 协议与加密实现只有一份：直接复用 web/crypto.js（手机端 / 旧网页端 / mock 共用）。
// @ts-ignore - 共享的纯 JS 模块
import * as crypto from '../../../web/crypto.js';

export const {
  sha256,
  hmacSha256,
  deriveKeys,
  seal,
  open,
  signRequest,
  toBase64Url,
  fromBase64Url,
  randomBytes,
  utf8Bytes,
  dhGenerateKeypair,
  dhPublicKey,
  dhSharedSecret,
  pairCodeOf,
} = crypto as {
  sha256: (data: Uint8Array) => Uint8Array;
  hmacSha256: (key: Uint8Array, data: Uint8Array) => Uint8Array;
  deriveKeys: (secret: Uint8Array, passcode: string) => unknown;
  seal: (keys: unknown, plain: Uint8Array) => Uint8Array;
  open: (keys: unknown, sealed: Uint8Array) => Uint8Array;
  signRequest: (
    keys: unknown,
    request: {
      method: string;
      path: string;
      ts: number;
      nonce: string;
      body: Uint8Array;
    },
  ) => string;
  toBase64Url: (data: Uint8Array) => string;
  fromBase64Url: (text: string) => Uint8Array;
  randomBytes: (length: number) => Uint8Array;
  utf8Bytes: (text: string) => Uint8Array;
  /** 生成一对 DH 临时密钥（配对用）。 */
  dhGenerateKeypair: () => { priv: Uint8Array; pub: Uint8Array };
  /** 由私钥算公钥。 */
  dhPublicKey: (priv: Uint8Array) => Uint8Array;
  /** 算出共享密钥（32 字节）。 */
  dhSharedSecret: (priv: Uint8Array, peerPub: Uint8Array) => Uint8Array;
  /** 公钥的 6 位配对码（用户核对用）。 */
  pairCodeOf: (pub: Uint8Array) => string;
};
