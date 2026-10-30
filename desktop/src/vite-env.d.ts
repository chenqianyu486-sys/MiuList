/// <reference types="vite/client" />

/**
 * 构建期注入的版本号（来源：`package.json`，见 `vite.config.ts`）。
 *
 * 界面上任何要显示版本号的地方都用它 —— 不再手写字符串，
 * 免得「产物里的版本」和「仓库里的版本」各说各话。
 */
declare const __APP_VERSION__: string;
