import { useMemo } from 'react';
import { licenseTextOf, readLicensePayload } from '../core/license';

/**
 * 开源许可声明。
 *
 * ## 为什么这块必须存在
 *
 * GPLv3 第 5(d) 条：带交互式界面的作品，界面必须显示「适当法律声明」，
 * 也就是四件事：
 *
 * 1. 醒目的版权声明；
 * 2. 告诉用户**本程序不提供任何担保**；
 * 3. 说明可以在本许可下传递本作品；
 * 4. 告诉用户**到哪儿去看许可证副本**。
 *
 * 以前这块是没有的：设置页写着「GPL-3.0」，但既没说担保、也没说去哪看全文；
 * 而发布出去的 `MiuList-Web.html` 里**连一条声明都没有**（压缩器把
 * 依赖的 `@license` 横幅删了，本项目的声明从来没进过产物）。
 *
 * 完整文本由构建脚本注入产物（见 `scripts/finish-build.mjs`），这里读它 ——
 * 页面上看到的就是随文件分发的那一份，不会出现两处不一致。
 */
export function LicenseNotice() {
  const payload = useMemo(() => readLicensePayload(), []);

  if (!payload) {
    // 开发模式：产物里没有载荷。只给仓库链接，不假装有全文。
    return (
      <div className="license-notice">
        <h3 className="about-heading">开源许可</h3>
        <p className="about-text">
          MiuList 以 GNU GPL v3.0 发布，许可证继承自 Celechron。您可以在同样条款下复制、修改与再分发本程序。
        </p>
        <p className="about-text">
          本程序不提供任何担保；在法律允许的最大范围内，作者不对使用本程序造成的后果承担责任。
        </p>
        <p className="about-text">
          完整的许可证与第三方声明见
          <a
            href="https://github.com/chenqianyu486-sys/MiuList/blob/main/LICENSE"
            target="_blank"
            rel="noopener"
          >
            仓库里的 LICENSE
          </a>
          。
        </p>
      </div>
    );
  }

  const { project, packages, gplText } = payload;

  return (
    <div className="license-notice">
      <h3 className="about-heading">开源许可</h3>
      <p className="about-text">
        {project.name} 以 {project.license} 发布，许可证继承自 {project.inheritsFrom}。
        您可以在同样条款下复制、修改与再分发本程序。
      </p>
      <p className="about-text">
        本程序不提供任何担保；在法律允许的最大范围内，作者不对使用本程序造成的后果承担责任。
      </p>
      <p className="about-text">
        <a href={project.repo} target="_blank" rel="noopener">
          源代码仓库
        </a>
        {' · '}
        <a href={project.licenseFile} target="_blank" rel="noopener">
          许可证副本
        </a>
      </p>

      <details className="license-details">
        <summary>查看完整许可证与第三方声明</summary>
        <div className="license-scroll">
          <h4 className="license-sub">本项目许可证（{project.license}）全文</h4>
          <pre className="license-text">{gplText}</pre>

          <h4 className="license-sub">第三方组件</h4>
          <p className="license-note">
            以下组件随本程序一同分发，各自的许可文本原样附上。
          </p>
          {packages.map((item) => (
            <section className="license-pkg" key={item.name}>
              <div className="license-pkg-head">
                <strong>
                  {item.name}
                  {item.version ? ` ${item.version}` : ''}
                </strong>
                <span className="license-pkg-license">{item.license}</span>
              </div>
              {item.copyright ? <div className="license-copyright">{item.copyright}</div> : null}
              <pre className="license-text">{licenseTextOf(payload, item)}</pre>
            </section>
          ))}
        </div>
      </details>
    </div>
  );
}
