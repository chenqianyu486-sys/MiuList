import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import { viteSingleFile } from 'vite-plugin-singlefile';
import path from 'node:path';

export default defineConfig({
  plugins: [react(), viteSingleFile()],
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
