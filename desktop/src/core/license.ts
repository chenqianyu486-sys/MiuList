/**
 * 开源许可载荷的读取。
 *
 * ## 这份数据从哪来
 *
 * 正式构建产物（`dist/index.html` 与 `dist/MiuList-Web.html`）里会带一个
 * `<script type="application/json" id="miulist-license-payload">`，
 * 由 `scripts/finish-build.mjs` 在 `vite build` 之后注入 —— 里面是 GPL-3.0
 * 全文和每一个运行时依赖的许可全文。
 *
 * **为什么文本要随产物走**：这个网页端是单文件产物，用户手上只有这一个
 * HTML。GPLv3 第 4 条要求「随附许可证副本」——许可只放在 GitHub 仓库里，
 * 用户本地没有副本，这一条就不成立。
 *
 * 开发模式（`npm run dev`）下没有这个脚本标签 → 载荷为 `null`，
 * 界面退化成「去仓库看许可证」的链接，不报错。
 */

/** 运行时依赖的许可条目。 */
export interface LicensePackageNotice {
  name: string;
  version: string;
  license: string;
  copyright: string;
  /** 指向 `texts` 里的哪一份正文；同样内容的依赖共用一份。 */
  textId: string;
  /** 没找到许可文本时为 true，正常情况下不会有。 */
  missing?: boolean;
}

export interface LicensePayload {
  project: {
    name: string;
    license: string;
    repo: string;
    licenseFile: string;
    inheritsFrom: string;
  };
  /** 本项目（GPL-3.0）的许可全文。 */
  gplText: string;
  packages: LicensePackageNotice[];
  /** 按 textId 索引的许可正文。 */
  texts: Record<string, string>;
}

const PAYLOAD_ELEMENT_ID = 'miulist-license-payload';

/**
 * 读取产物里内嵌的许可载荷。
 *
 * 读不到（开发模式 / 脚本被删）时返回 `null`，调用方负责降级。
 */
export function readLicensePayload(): LicensePayload | null {
  if (typeof document === 'undefined') return null;
  const el = document.getElementById(PAYLOAD_ELEMENT_ID);
  if (!el || !el.textContent) return null;
  try {
    const parsed = JSON.parse(el.textContent) as LicensePayload;
    if (!parsed || !parsed.project || !parsed.project.license) return null;
    return parsed;
  } catch {
    // 载荷坏了就当没有：界面退化成链接，不能因为一段法律文本把整个应用弄崩。
    return null;
  }
}

/** 某个依赖的许可正文。 */
export function licenseTextOf(payload: LicensePayload, item: LicensePackageNotice): string {
  if (!item.textId) return '';
  return payload.texts?.[item.textId] ?? '';
}
