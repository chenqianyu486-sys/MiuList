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
};
