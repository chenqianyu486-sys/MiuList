/* 配对纯逻辑的自检：地址归一化 + 配对码申请时机。
 *
 * 为什么要单独测：
 *
 * - **地址归一化**是「手机上复制过来就能用」这条承诺的最后一环 ——
 *   归一化写错了，粘贴出来的地址会连不上，而症状是
 *   「这个地址上没有 MiuList 服务」，用户完全看不出是哪里错了；
 * - **配对码申请时机**决定会不会一次要两张码 ——
 *   要了两张，手机上会多出一条待确认的请求，用户批准了错的那条，
 *   网页端就永远等不到批准（症状是「点了允许，电脑那边没反应」）。
 *
 * 跑法：node web/pairing.test.mjs
 */

import { normalizeBase, shouldRequestPairCode } from './pairing.js';

let failures = 0;
function check(name, actual, expected) {
  const ok = actual === expected;
  if (ok) {
    console.log(`  ok   ${name}`);
  } else {
    failures++;
    console.log(`  FAIL ${name}`);
    console.log(`       期望 ${JSON.stringify(expected)}`);
    console.log(`       实际 ${JSON.stringify(actual)}`);
  }
}

// ── 地址归一化 ─────────────────────────────────────────────────

console.log('normalizeBase');

check(
  '手机复制的连接链接（带末尾斜杠）',
  normalizeBase('http://192.168.1.20:8765/'),
  'http://192.168.1.20:8765',
);
check(
  '同一个地址，不带斜杠',
  normalizeBase('http://192.168.1.20:8765'),
  'http://192.168.1.20:8765',
);
check(
  '没写 scheme（最常见的手打写法）',
  normalizeBase('192.168.1.20:8765'),
  'http://192.168.1.20:8765',
);
check(
  '前后有空白',
  normalizeBase('  http://192.168.1.20:8765/  '),
  'http://192.168.1.20:8765',
);
check(
  '带路径也只取 origin',
  normalizeBase('http://192.168.1.20:8765/some/path'),
  'http://192.168.1.20:8765',
);
check(
  '带查询串也只取 origin',
  normalizeBase('http://192.168.1.20:8765/?a=1'),
  'http://192.168.1.20:8765',
);
check(
  'https 也认',
  normalizeBase('https://miulist.example.com'),
  'https://miulist.example.com',
);
check('localhost 也认', normalizeBase('localhost:8765'), 'http://localhost:8765');

// ── 认不出来的一律给空串（不是原样返回） ───────────────────────

check('空串', normalizeBase(''), '');
check('null', normalizeBase(null), '');
check('undefined', normalizeBase(undefined), '');
check('一段乱码', normalizeBase('不是地址'), '');
check('只有 scheme', normalizeBase('http://'), '');
check('不是 http/https', normalizeBase('ftp://192.168.1.20'), '');
check('javascript 伪协议', normalizeBase('javascript:alert(1)'), '');

// ── 配对码申请时机 ─────────────────────────────────────────────

console.log('shouldRequestPairCode');

check('空闲时要申请', shouldRequestPairCode('idle'), true);
check('申请中不要再要', shouldRequestPairCode('requesting'), false);
check('等待批准时不要再要', shouldRequestPairCode('waiting'), false);
check('失败后可以重来', shouldRequestPairCode('error'), true);
check('过期后可以重来', shouldRequestPairCode('expired'), true);
check('被拒后可以重来', shouldRequestPairCode('denied'), true);
check('用户明确换码：等待中也要换', shouldRequestPairCode('waiting', true), true);
check('用户明确换码：申请中也要换', shouldRequestPairCode('requesting', true), true);

console.log(failures ? `\n${failures} 个用例失败` : '\n全部通过');
process.exit(failures ? 1 : 0);
