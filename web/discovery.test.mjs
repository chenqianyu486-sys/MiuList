/* 自动发现的纯逻辑自检。
 *
 * 测的是「电脑怎么找到手机」这件事里**不碰网络**的那部分：
 * 要扫哪些主机、端口顺序对不对、扫描会不会提前收工。
 *
 * 跑法：node web/discovery.test.mjs
 */

import { PHONE_PORTS, DEFAULT_PHONE_PORT, subnetHosts, sweepPhone } from './discovery.js';

let failures = 0;
function check(name, ok, extra = '') {
  if (ok) {
    console.log(`  ok   ${name}`);
  } else {
    failures++;
    console.log(`  FAIL ${name} ${extra}`);
  }
}

console.log('端口候选');
{
  check('第一个端口是固定的 38780', DEFAULT_PHONE_PORT === 38780);
  check('候选端口不重复', new Set(PHONE_PORTS).size === PHONE_PORTS.length);
  check('候选端口都是合法端口', PHONE_PORTS.every((p) => p > 1024 && p < 65536));
}

console.log('要扫哪些主机');
{
  const hosts = subnetHosts('http://192.168.1.20:38780');
  check('扫的是同一个 /24', hosts.every((h) => h.startsWith('192.168.1.')));
  check('共 254 个地址', hosts.length === 254, `实际 ${hosts.length}`);
  check('自己那个地址排第一个', hosts[0] === '192.168.1.20');
  check('不重复扫自己', hosts.filter((h) => h === '192.168.1.20').length === 1);
  check('不扫网络号与广播地址', !hosts.includes('192.168.1.0') && !hosts.includes('192.168.1.255'));

  check('主机名扫不出来', subnetHosts('http://my-nas.local:38780').length === 0);
  check('空地址扫不出来', subnetHosts('').length === 0);
  check('乱码扫不出来', subnetHosts('不是地址').length === 0);
  check('10.x 网段也认', subnetHosts('http://10.0.0.5:38780').every((h) => h.startsWith('10.0.0.')));
}

console.log('扫描');
{
  // 探针：只在 192.168.1.77 上应答
  const probe = async (host, port) => host === '192.168.1.77' && port === 38780;

  const hit = await sweepPhone({
    hosts: ['192.168.1.1', '192.168.1.77', '192.168.1.88'],
    ports: PHONE_PORTS,
    probe,
  });
  check('扫到了手机', !!hit && hit.host === '192.168.1.77' && hit.port === 38780);

  const none = await sweepPhone({
    hosts: ['192.168.1.1', '192.168.1.2'],
    ports: PHONE_PORTS,
    probe,
  });
  check('没人应答时返回空', none === null);

  // 端口不对时也要继续试下一个端口
  const hitOnSecondPort = await sweepPhone({
    hosts: ['192.168.1.77'],
    ports: [38780, 38781],
    probe: async (host, port) => host === '192.168.1.77' && port === 38781,
  });
  check('端口不对时会试下一个', !!hitOnSecondPort && hitOnSecondPort.port === 38781);

  // 探针抛异常不能让整个扫描挂掉
  const survived = await sweepPhone({
    hosts: ['192.168.1.1', '192.168.1.77'],
    ports: [38780],
    probe: async (host) => {
      if (host === '192.168.1.1') throw new Error('连不上');
      return true;
    },
  });
  check('探针抛异常不影响别人', !!survived && survived.host === '192.168.1.77');

  // 提前收工：找到之后不该再敲后面的
  let probed = 0;
  await sweepPhone({
    hosts: Array.from({ length: 100 }, (_, i) => `192.168.1.${i + 1}`),
    ports: [38780],
    concurrency: 4,
    probe: async (host) => {
      probed++;
      return host === '192.168.1.1';
    },
  });
  check('找到之后不再继续敲', probed < 100, `实际敲了 ${probed} 次`);
}

console.log(failures === 0 ? '\n全部通过' : `\n${failures} 条失败`);
process.exit(failures === 0 ? 0 : 1);
