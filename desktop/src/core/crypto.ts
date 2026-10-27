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
  dhValidatePublicKey,
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
  /**
   * 校验对端公钥是否合法（协议 v3.2 起强制）。
   *
   * 退化公钥（0 / 1 / p-1）会让共享密钥变成**全世界都知道的常量**，
   * 所以拿到对端公钥的第一件事就是查它。
   */
  dhValidatePublicKey: (pub: Uint8Array) => boolean;
  /** 算出共享密钥（32 字节）。 */
  dhSharedSecret: (priv: Uint8Array, peerPub: Uint8Array) => Uint8Array;
  /**
   * 双方公钥的 6 位配对码（用户核对用）。
   *
   * `pubA` = 发起方（电脑）的公钥，`pubB` = 响应方（手机）的公钥 —— 顺序不能反。
   */
  pairCodeOf: (pubA: Uint8Array, pubB: Uint8Array) => string;
};
