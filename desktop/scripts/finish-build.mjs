/**
 * 构建收尾：把开源许可信息内嵌进单文件产物。
 *
 * ## 这一步在补什么
 *
 * `vite build` 之后产物里**一条许可声明都没有**（实测 `GPL`、`Apache License`、
 * `开源`、`许可` 全部命中 0）：压缩器把依赖的 `/** @license ... *\/` 横幅当注释删了，
 * 而本项目自己的 GPL 声明从来没有进过产物。
 *
 * 这个产物是要公开发布的（GitHub Release 的 `MiuList-Web.html`，官网直链），
 * 于是它就是一份「被再分发的 GPL 作品」，而 GPLv3 第 4 条要求：
 *
 * - 每一份副本都要有醒目的版权声明；
 * - 保留所有「本作品适用本许可」的声明；
 * - 保留所有「absence of warranty」的声明；
 * - **随附许可证副本**。
 *
 * 第 5(d) 条还要求交互式界面显示「适当法律声明」。界面上那块在
 * `src/components/LicenseNotice.tsx`（渲染在「同步与设置 → 关于」里），
 * 它读的正是这里注入的载荷。
 *
 * ## 为什么是「注入 JSON」而不是「把文本直接拼进 HTML」
 *
 * 界面要用同一份文本（用户在「关于」里展开看的就是它），
 * 注入成 JSON 后界面直接读，省掉第二份真相，也不会出现
 * 「页面上一份、弹层里另一份」的不一致。
 */
import { copyFileSync, readFileSync, statSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import {
  buildLicensePayload,
  missingLicenseComponents,
  serializeLicensePayload,
} from './licenses.mjs';

const dir = fileURLToPath(new URL('../dist/', import.meta.url));
const from = dir + 'index.html';
const to = dir + 'MiuList-Web.html';

/**
 * 采集许可载荷。
 *
 * 任何一个随包组件找不到许可文本都直接让构建失败 ——
 * 产物一旦带着缺失的声明发出去，就是一次已经发生的合规缺口，
 * 比构建失败难补救得多。
 */
const payload = buildLicensePayload();

const missing = missingLicenseComponents(payload);
if (missing.length > 0) {
  console.error(`\n构建中止：许可载荷缺少以下组件或正文：${missing.join('、')}`);
  console.error('产物会缺少第三方许可声明，不能发布。请检查这些包的 LICENSE 文件。');
  process.exit(1);
}

const html = readFileSync(from, 'utf8');
const marker = '</body>';
const at = html.lastIndexOf(marker);
if (at < 0) {
  console.error('\n构建中止：产物里找不到 </body>，许可声明无处安放。');
  process.exit(1);
}

const block =
  `<!-- MiuList 开源许可载荷：GPL-3.0 全文 + 全部运行时依赖的许可全文 -->\n` +
  `<script type="application/json" id="miulist-license-payload">` +
  `${serializeLicensePayload(payload)}</script>\n`;

writeFileSync(from, html.slice(0, at) + block + html.slice(at), 'utf8');

copyFileSync(from, to);
const kb = (statSync(to).size / 1024).toFixed(0);
console.log(`\n单文件产物：${to}（${kb} KB）`);
console.log('双击即可在 Chrome / Edge 打开，不需要 exe 或本地服务。');
console.log(
  `已内嵌许可声明：本项目 GPL-3.0 全文 + ${payload.packages.length} 个随包组件的许可全文。`
);
