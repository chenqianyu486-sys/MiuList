import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import { viteSingleFile } from 'vite-plugin-singlefile';
import path from 'node:path';
import { readFileSync } from 'node:fs';

/**
 * 版本号只有一个来源：`package.json`。
 *
 * 以前「关于」面板里的版本号是**手写死的**，于是每次发版都要另开一条
 * `chore(desktop): 版本号对齐` 的提交去改它 ——
 * 忘了改，界面上就摆着一个旧版本号，而那正是用户能直接看到的地方。
 *
 * 现在由构建期注入：改 `package.json` 一处，产物与界面自动跟着走。
 */
const pkg = JSON.parse(
  readFileSync(path.resolve(__dirname, 'package.json'), 'utf8'),
) as { version: string };

export default defineConfig({
  plugins: [react(), viteSingleFile()],
  define: {
    __APP_VERSION__: JSON.stringify(pkg.version),
  },
  server: {
    fs: {
      // 复用仓库里的 web/crypto.js 与 web/timetable.js（协议和课表推导只有一份）
      allow: [path.resolve(process.cwd(), '..')],
    },
  },
  build: {
    target: 'es2020',
    cssCodeSplit: false,
    assetsInlineLimit: 100_000_000,
    sourcemap: false,
    reportCompressedSize: false,
  },
});
