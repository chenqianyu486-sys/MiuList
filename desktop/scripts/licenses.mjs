/**
 * 采集开源许可信息，供构建产物内嵌。
 *
 * ## 为什么网页端要自带这些文本
 *
 * 这个网页端是**单文件**产物：用户拿到手的就是一个 `MiuList-Web.html`，
 * 双击用浏览器打开，没有任何安装过程。
 *
 * GPLv3 第 4 条要求「随附许可证副本」——光在 GitHub 仓库里放一份 LICENSE
 * 是不够的：用户手上只有这一个 HTML，他的机器上不联网就看不到那份许可证。
 * **许可文本必须跟着文件走**，这一条才成立。
 *
 * 同理，Apache-2.0 第 4 条要求再分发时附带许可证并保留声明；
 * MIT / ISC 也都要求「版权声明与许可声明出现在所有副本中」。
 * 压缩器会把这些横幅当注释删掉（实测产物里 `Apache License` 命中数为 0），
 * 所以只能在构建后由脚本重新塞回去。
 *
 * ## 只收随产物分发的代码
 *
 * 构建期依赖（vite / typescript 等）不会进产物，也就没有被「再分发」，
 * 不需要随附它们的许可。进产物的是运行时依赖（含传递依赖）与内嵌组件。
 */
import { existsSync, readFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const desktopRoot = fileURLToPath(new URL('../', import.meta.url));
const repoRoot = fileURLToPath(new URL('../../', import.meta.url));

/**
 * 会进产物的运行时依赖（含传递依赖）。
 *
 * `scheduler` 来自 react-dom，`use-sync-external-store` 来自 zustand：
 * 它们不写在 package.json 里，但被打进了同一个产物，同样是被再分发的代码。
 */
export const RUNTIME_DEPS = [
  'idb-keyval',
  'jsqr',
  'lucide-react',
  'qrcode.react',
  'react',
  'react-dom',
  'scheduler',
  'use-sync-external-store',
  'zustand',
];

/**
 * 随包内嵌、但在 node_modules 里没有自己那一份许可文件的组件。
 *
 * qrcode.react 的 LICENSE 明确写着内嵌的 QR Code Generator 另采用 MIT；
 * 这份正文取自 qrcode.react v4.2.0 的 `src/third-party/qrcodegen/LICENSE`
 * （`node_modules/qrcode.react` 不发布 src，所以原始正文只能随采集器自带）。
 * 它没有独立版本号，面板上就不写版本。
 */
const EMBEDDED_COMPONENTS = [
  {
    name: 'QR Code Generator (embedded in qrcode.react)',
    license: 'MIT',
    licensePath: path.join(desktopRoot, 'scripts', 'third-party', 'qrcodegen-LICENSE.txt'),
  },
];

/** 产物里必须齐全的组件名。 */
const REQUIRED_COMPONENT_NAMES = [
  ...RUNTIME_DEPS,
  ...EMBEDDED_COMPONENTS.map((item) => item.name),
];

/** 本项目自身的许可信息。 */
export const PROJECT = {
  name: 'MiuList',
  license: 'GPL-3.0',
  repo: 'https://github.com/chenqianyu486-sys/MiuList',
  licenseFile: 'https://github.com/chenqianyu486-sys/MiuList/blob/main/LICENSE',
  inheritsFrom: 'Celechron (https://github.com/Celechron/Celechron)',
};

/** 许可证文件在这些名字里找；找不到就当没有文本，记一笔待人工确认。 */
const LICENSE_FILE_NAMES = ['LICENSE', 'LICENCE', 'LICENSE.md', 'LICENCE.md', 'LICENSE.txt', 'COPYING'];

/**
 * 取一个包的许可文本。
 *
 * @returns {{text: string, file: string} | null}
 */
function readPackageLicense(pkgDir) {
  for (const name of LICENSE_FILE_NAMES) {
    const file = path.join(pkgDir, name);
    if (existsSync(file)) {
      return { text: readFileSync(file, 'utf8'), file: name };
    }
  }
  return null;
}

/**
 * 从许可文本里抠出版权行。
 *
 * 只取第一段里含 Copyright 的行：Apache-2.0 的正文里到处都有 "Copyright"
 * 字样（全大写的 "COPYRIGHT AND LICENSE" 之类的标题），只取开头几行才准。
 */
function extractCopyright(text) {
  const head = text.split(/\r?\n/).slice(0, 6);
  const hit = head.find((line) => /copyright/i.test(line));
  return hit ? hit.trim() : '';
}

/**
 * 采集完整的许可载荷。
 *
 * @param {{nodeModulesDir?: string, licensePath?: string}} [options]
 * @returns {object} 会被 JSON.stringify 后注入产物的载荷
 */
export function buildLicensePayload(options = {}) {
  const nodeModulesDir = options.nodeModulesDir ?? path.join(desktopRoot, 'node_modules');
  const licensePath = options.licensePath ?? path.join(repoRoot, 'LICENSE');

  const packages = [];
  const texts = {};

  for (const name of RUNTIME_DEPS) {
    const pkgDir = path.join(nodeModulesDir, name);
    if (!existsSync(pkgDir)) {
      packages.push({ name, version: '', license: '', copyright: '', textId: '', missing: true });
      continue;
    }

    let version = '';
    let licenseId = '';
    try {
      const pkg = JSON.parse(readFileSync(path.join(pkgDir, 'package.json'), 'utf8'));
      version = pkg.version ?? '';
      licenseId = typeof pkg.license === 'string' ? pkg.license : pkg.license?.type ?? '';
    } catch {
      // package.json 读不到就不写版本号，不让这一步炸掉整个构建。
    }

    const license = readPackageLicense(pkgDir);
    if (!license) {
      packages.push({ name, version, license: licenseId, copyright: '', textId: '', missing: true });
      continue;
    }

    // 同样的文本只存一份：Apache-2.0 有两个包共用，MIT 有三个包共用。
    const textId = createHash('sha256').update(license.text).digest('hex').slice(0, 12);
    texts[textId] = license.text;

    packages.push({
      name,
      version,
      license: licenseId,
      copyright: extractCopyright(license.text),
      textId,
      missing: false,
    });
  }

  for (const component of EMBEDDED_COMPONENTS) {
    const text = existsSync(component.licensePath)
      ? readFileSync(component.licensePath, 'utf8')
      : '';
    if (!text.trim()) {
      packages.push({
        name: component.name,
        version: '',
        license: component.license,
        copyright: '',
        textId: '',
        missing: true,
      });
      continue;
    }

    const textId = createHash('sha256').update(text).digest('hex').slice(0, 12);
    texts[textId] = text;
    packages.push({
      name: component.name,
      version: '',
      license: component.license,
      copyright: extractCopyright(text),
      textId,
      missing: false,
    });
  }

  const gplText = existsSync(licensePath) ? readFileSync(licensePath, 'utf8') : '';

  return {
    project: PROJECT,
    gplText,
    packages,
    texts,
  };
}

/**
 * 载荷里缺失的必需组件或正文。空数组才算齐全。
 *
 * 不能只看 `missing` 标记：包目录不存在时早先会直接跳过，产物照样生成，
 * 面板于是少一块而没有任何报错。
 */
export function missingLicenseComponents(payload) {
  const usable = new Set(
    (payload.packages ?? [])
      .filter(
        (item) =>
          item &&
          !item.missing &&
          item.textId &&
          (payload.texts?.[item.textId] ?? '').trim()
      )
      .map((item) => item.name)
  );
  const missing = REQUIRED_COMPONENT_NAMES.filter((name) => !usable.has(name));
  if (!(payload.gplText ?? '').trim()) missing.push('MiuList LICENSE（GPL-3.0）');
  return missing;
}

/** 缺组件就抛错，构建脚本与测试共用这一条判定。 */
export function assertLicensePayloadComplete(payload) {
  const missing = missingLicenseComponents(payload);
  if (missing.length > 0) {
    throw new Error(`许可证载荷不完整：${missing.join('、')}`);
  }
  return payload;
}

/**
 * 把载荷序列化成能安全塞进 `<script type="application/json">` 的字符串。
 *
 * `</script` 和 `<!--` 都会提前结束脚本块，JSON 里再转义一次就安全了
 * （`\/` 与 `\u0021` 都是合法 JSON 转义）。
 */
export function serializeLicensePayload(payload) {
  return JSON.stringify(payload)
    .replace(/<\//g, '<\\/')
    .replace(/<!--/g, '<\\u0021--');
}
