import { copyFileSync, statSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const dir = fileURLToPath(new URL('../dist/', import.meta.url));
const from = dir + 'index.html';
const to = dir + 'MiuList-Web.html';

copyFileSync(from, to);
const kb = (statSync(to).size / 1024).toFixed(0);
console.log(`\n单文件产物：${to}（${kb} KB）`);
console.log('双击即可在 Chrome / Edge 打开，不需要 exe 或本地服务。');
