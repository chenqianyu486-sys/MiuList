# MiuList

[![Repository](https://img.shields.io/badge/GitHub-chenqianyu486--sys%2FMiuList-brightgreen?logo=github)](https://github.com/chenqianyu486-sys/MiuList)
[![License: GPL-3.0](https://img.shields.io/badge/License-GPL--3.0-blue.svg)](LICENSE)

基于课程表的自动化轻量作业待办软件，能够根据每日课程智能联动生成预习与课后作业待办，提供分类清单与日程表双重视图。

- 🔗 **开源仓库**：[https://github.com/chenqianyu486-sys/MiuList](https://github.com/chenqianyu486-sys/MiuList)
- 📥 **下载安装**：见 [Releases](https://github.com/chenqianyu486-sys/MiuList/releases) 页面，下载 `MiuList-v1.0.0.apk` 直接安装（Android 7.0 / API 24 及以上）。当前为**测试版**，请先在测试机上安装。

## ✨ 特性

- 📚 **课表智能联动**：根据每日课程自动触发预习、复习与作业任务生成，支持自定义联动规则。
- 📋 **双重视图**：兼备高效直观的清单待办（Checklist）与清晰的课程日程表（Schedule）。
- 🏫 **校历与课表支持**：支持浙大（ZJU）课表与校历导入，智能识别调休与学期周次。
- 🌤️ **天气联动**：集成天气信息展示，为日常学习出行提供参考。天气数据来自心知天气（地面观测），空气质量来自 Open-Meteo，由 GitHub Actions 定时合成为静态托管文件，客户端不持有任何密钥。
- 🎨 **现代拟物质感**：基于 Jetpack Compose 构建，界面简洁轻盈。

## 🛠️ 构建与运行

- **环境要求**：Android Studio、JDK 17+、Android SDK (minSdk 24, targetSdk 36)
- **构建 Debug APK**：
  ```bash
  ./gradlew assembleDebug
  ```

## 🙏 鸣谢

本项目在开发过程中参考并受益于以下优秀开源项目与服务，特此向原作者及各位贡献者致以由衷感谢：

- [心知天气 (Seniverse)](https://www.seniverse.com/) — **心知天气团队 (Seniverse)**：提供天气数据服务支持（由 GitHub Actions 定时同步静态托管，客户端不持有任何密钥）
- [Open-Meteo](https://open-meteo.com/) — **Open-Meteo 团队；空气质量由哥白尼大气监测服务（CAMS）提供**：提供空气质量（AQI）数据，按 [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/) 授权；数据由数值模式生成，可能与地面实测存在偏差，不作为健康决策依据
- [Celechron/Celechron](https://github.com/Celechron/Celechron) — **@n0sig, @Phil-Fan, @Quinn-DJ, @lelexia0131, @bno3bno3 及所有贡献者**：校园日程与时间管理设计参考，GPLv3 许可证继承来源
- [Elyyyyyyyyxer/Elychron](https://github.com/Elyyyyyyyyxer/Elychron) — **@Elyyyyyyyyxer 及其对应贡献者**：课表与日程管理相关设计与灵感来源
- [cxz66666/zju-ical](https://github.com/cxz66666/zju-ical) — **@cxz66666 及其对应贡献者**（源自 determ1ne/grsical）：浙大课表日历导出与转换参考
- [Xecades/zju-ical-py](https://github.com/Xecades/zju-ical-py) — **@Xecades 及其对应贡献者**：浙大课表解析与 iCal 转换 Python 实现参考
- [破晓壁纸 (PXLab)](https://pxlab.cn/index.html) — **PXLab 团队**：提供精选高质量壁纸资源、设计美学与透光质感参考

## 📄 开源许可证

本项目基于 [GNU General Public License v3.0 (GPL-3.0)](LICENSE) 协议开源。

> ⚠️ 本项目的 GPLv3 许可证继承自 [Celechron](https://github.com/Celechron/Celechron)。
