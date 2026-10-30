/**
 * 开源许可采集与注入。
 *
 * 跑法：`npm test`（Node 自带的 test runner）。
 *
 * ## 这一层守的是什么
 *
 * 产物（`MiuList-Web.html`）是要公开发布的，它是**一份被再分发的 GPL 作品**。
 * GPLv3 第 4 条要求「随附许可证副本」，Apache-2.0 第 4 条要求再分发时附带
 * 许可证并保留声明。
 *
 * 这件事靠构建脚本做，做砸了**不会报错**——产物照样生成，只是少了一段声明。
 * 所以它必须有测试盯着：
 *
 * - 随产物分发的**直接依赖、传递依赖与内嵌组件**都必须在清单里（独立列出，
 *   不拿 RUNTIME_DEPS 自己跟自己对）；
 * - 每个组件的许可正文都要真的能取到；
 * - 缺组件或缺正文时，构建判定必须明确失败；
 * - 本项目的 GPL-3.0 全文必须被读进来；
 * - 注入用的 JSON 不能含 `</script` / `<!--`，否则会把脚本块提前截断 ——
 *   这一条最阴：截断后页面照样能跑，只是许可声明静默地少了一截。
 * - 正式产物里确实带着这份载荷，而不只是采集函数返回值正确。
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  copyFileSync,
  existsSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  rmSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import {
  RUNTIME_DEPS,
  buildLicensePayload,
  assertLicensePayloadComplete,
  missingLicenseComponents,
  serializeLicensePayload,
} from '../scripts/licenses.mjs';

const desktopRoot = fileURLToPath(new URL('../', import.meta.url));
const nodeModulesDir = path.join(desktopRoot, 'node_modules');
const artifactPath = path.join(desktopRoot, 'dist', 'MiuList-Web.html');

const EXPECTED_COMPONENTS = [
  'idb-keyval',
  'lucide-react',
  'react',
  'react-dom',
  'scheduler',
  'use-sync-external-store',
  'zustand',
];

const payload = buildLicensePayload();

function packageByName(name) {
  return payload.packages.find((item) => item.name === name);
}

test('随产物分发的组件一个不少，也没有盲加开发工具', () => {
  const names = payload.packages.map((item) => item.name);
  assert.deepEqual([...names].sort(), [...EXPECTED_COMPONENTS].sort());
  assert.equal(new Set(names).size, names.length, '同名组件只能有一条');
  assert.deepEqual(
    payload.packages.filter((item) => item.missing).map((item) => item.name),
    [],
    '缺许可的组件必须让构建失败，而不是静默跳过'
  );
});

test('本项目的 GPL-3.0 全文被读进来了', () => {
  assert.ok(payload.gplText.length > 10000, 'GPL 正文不该这么短');
  assert.match(payload.gplText, /GNU GENERAL PUBLIC LICENSE/);
  assert.match(payload.gplText, /Version 3/);
});

test('每个组件都带名称、许可标识和可解析的正文', () => {
  for (const item of payload.packages) {
    assert.ok(item.name, '缺名字');
    assert.ok(item.license, `${item.name} 缺许可标识`);
    assert.ok(item.textId, `${item.name} 缺正文索引`);
    assert.ok(payload.texts[item.textId], `${item.name} 的许可正文取不到`);
    assert.ok(payload.texts[item.textId].length > 100, `${item.name} 的许可正文太短`);
    assert.ok(item.version, `${item.name} 缺版本号`);
  }
});

test('传递依赖 scheduler 与 use-sync-external-store 都在清单里', () => {
  for (const name of ['scheduler', 'use-sync-external-store']) {
    const item = packageByName(name);
    assert.ok(item, `${name} 没有被采集`);
    assert.equal(item.license, 'MIT');
    assert.match(item.version, /^\d+\.\d+\.\d+/);
    assert.match(payload.texts[item.textId], /MIT License/);
    assert.match(item.copyright, /Copyright/i);
  }
});

test('许可证标识与实际许可文本对得上（Apache 的必须含 Apache 字样）', () => {
  const apache = payload.packages.filter((item) => item.license === 'Apache-2.0');
  assert.ok(apache.length >= 1, 'Apache-2.0 的依赖应该还在清单里');
  for (const item of apache) {
    assert.match(payload.texts[item.textId], /Apache License/i);
  }
});

test('同样的许可正文只存一份（去重）', () => {
  const ids = new Set(payload.packages.map((item) => item.textId));
  assert.ok(
    ids.size < payload.packages.length,
    'MIT / Apache 这类共用正文应该合并，产物里不该出现重复的许可证全文'
  );
});

test('版权行被抠出来了', () => {
  const react = packageByName('react');
  assert.ok(react, '找不到 react');
  assert.match(react.copyright, /Copyright/i);
});

test('缺少必需组件或许可正文时明确报错', () => {
  const temp = mkdtempSync(path.join(tmpdir(), 'miulist-license-'));
  try {
    // 所有包都在，只把 react 的许可正文拿掉：它必须被标成 missing。
    for (const name of RUNTIME_DEPS) {
      const src = path.join(nodeModulesDir, name);
      const dst = path.join(temp, name);
      mkdirSync(dst, { recursive: true });
      for (const file of [
        'package.json',
        'LICENSE',
        'LICENCE',
        'LICENSE.md',
        'LICENCE.md',
        'LICENSE.txt',
        'COPYING',
      ]) {
        if (existsSync(path.join(src, file))) {
          copyFileSync(path.join(src, file), path.join(dst, file));
        }
      }
    }
    rmSync(path.join(temp, 'react', 'LICENSE'));

    const broken = buildLicensePayload({ nodeModulesDir: temp });
    const react = broken.packages.find((item) => item.name === 'react');
    assert.equal(react.missing, true);
    assert.deepEqual(missingLicenseComponents(broken), ['react']);
    assert.throws(
      () => assertLicensePayloadComplete(broken),
      /许可证载荷不完整.*react/
    );

    const noGpl = buildLicensePayload({ licensePath: path.join(temp, 'missing-LICENSE') });
    assert.deepEqual(missingLicenseComponents(noGpl), ['MiuList LICENSE（GPL-3.0）']);
    assert.throws(() => assertLicensePayloadComplete(noGpl), /GPL-3\.0/);
  } finally {
    rmSync(temp, { recursive: true, force: true });
  }
});

test('注入用的 JSON 不会提前截断脚本块', () => {
  const serialized = serializeLicensePayload(payload);
  assert.ok(!serialized.includes('</script'), '含 </script，会把 script 块截断');
  assert.ok(!serialized.includes('<!--'), '含 <!--，会把 script 块截断');
  // `\/` 与 `\u0021` 都是合法 JSON 转义：不必先反转义就能解析回来。
  const round = JSON.parse(serialized);
  assert.equal(round.project.license, 'GPL-3.0');
  assert.equal(round.packages.length, payload.packages.length);

  const hostile = serializeLicensePayload({
    project: { license: 'GPL-3.0' },
    gplText: '</script><!--',
    packages: [],
    texts: {},
  });
  assert.ok(!hostile.includes('</script'));
  assert.ok(!hostile.includes('<!--'));
  assert.equal(JSON.parse(hostile).gplText, '</script><!--');
});

test(
  '正式产物里带着完整载荷（先运行 npm run build）',
  { skip: existsSync(artifactPath) ? false : 'dist/MiuList-Web.html 还不存在' },
  () => {
    const html = readFileSync(artifactPath, 'utf8');
    const match = /<script type="application\/json" id="miulist-license-payload">([\s\S]*?)<\/script>/.exec(
      html
    );
    assert.ok(match, '产物里找不到许可载荷');
    assert.ok(!match[1].includes('</script'), '载荷里的正文不能提前结束脚本块');

    const artifact = JSON.parse(match[1]);
    assertLicensePayloadComplete(artifact);

    for (const name of EXPECTED_COMPONENTS) {
      assert.ok(
        artifact.packages.some((item) => item.name === name && !item.missing),
        `${name} 没有进正式产物`
      );
    }

    assert.match(artifact.gplText, /GNU GENERAL PUBLIC LICENSE/);
    assert.ok(artifact.gplText.length > 10000);
  }
);
