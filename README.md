# MiuList

[![Release](https://img.shields.io/github/v/release/chenqianyu486-sys/MiuList?label=Release&color=blue)](https://github.com/chenqianyu486-sys/MiuList/releases)
[![License: GPL-3.0](https://img.shields.io/badge/License-GPL--3.0-blue.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Android-7.0%2B-green)](https://developer.android.com/about/versions)
[![Stack](https://img.shields.io/badge/Jetpack%20Compose%20%2B%20Room-purple)](https://developer.android.com/jetpack/compose)

基于课程表的自动化轻量作业待办软件：把每天的课程自动转成预习、复习与课后作业待办，
用**清单**和**日程表**两个视角呈现。

- 📥 **下载安装**：[Releases](https://github.com/chenqianyu486-sys/MiuList/releases) 页面下载 `MiuList-v1.0.4.apk`（Android 7.0 / API 24 及以上）
- 🧪 当前为**测试版**，功能仍在调整，遇到问题请提到 [Issues](https://github.com/chenqianyu486-sys/MiuList/issues)
- 🪞 国内网络不稳时可走 [Gitee 镜像](https://gitee.com/zjuchenqianyu/MiuList)

## ✨ 功能特色

清新，美观，易用。

### 课表与考试

- **ZJU 教务网同步**：本地保存学号密码，自动拉取课表、考试安排。
- **不只浙大能用**：课程可以手动添加，学期起止与调休也能在校历里自己填。
- **调休按官方校历自动处理**：节假日停课、补课、两天课程对调都认，不用手动把课挪走。
- **校历可远程更新**：校历有「远程托管 → 本地缓存 → 内置兜底」三层来源，改校历只需维护远程托管的文件，**无需重装 App**。

### 待办

- **自动生成作业类待办（默认关闭，按需开启）**：依据自定义规则按周一键生成预习、作业、实验报告待办。
  这个功能**要自己打开**（新手引导第 2 步，或「更多 → 一键生成待办」）：
  只想手动记几件事的人，不必先配一遍类别与规则。开启后顶栏会出现 ✨，
  「更多」里也会多出课程联动规则与待办生成规则两行。
- **课外待办随心加**：自己的事临时加一条，不受课表约束。

### 其它

- **双视图**：清单适合快速勾选，日程表适合看整周节奏。
- **数据导入导出**：整机状态导出成一份 JSON 快照，换机、重装、备份都能直接恢复。导入前先预览差异，再决定谁覆盖谁。
- **天气与空气质量**：天气来自心知天气，空气质量来自 Open-Meteo，显示在总览里。
- **拟物质感**：Jetpack Compose 编写，课程用粉彩配色，支持自定义壁纸。

## 🔐 安全性

这个 App 要用户输入**统一身份认证的密码**，所以下面几条是硬约束，不是可选项：

- **凭据硬件加密**：学号密码用 Android KeyStore 里的 AES-256-GCM 主密钥加密后落盘，主密钥在 TEE/安全硬件里，App 自己也取不出来。拿不到硬件密钥时**直接报错**、绝不悄悄退化成一把随进程消失的软件密钥（那样界面会显示「已记住密码」，实际下次启动就取不出来）。
- **输入密码时禁止截屏**：两个含密码的弹层挂着期间，所在窗口会带上 `FLAG_SECURE` —— 系统录屏、第三方录屏、任务管理器缩略图都拍不到明文。弹层一关标志就撤，所以给课表截图这种正常需求不受影响。
- **全链路 HTTPS**：`network_security_config` 全局禁用明文流量。历史上曾为已退役的教务接口放行过一个 HTTP 域名，那个口子已经收回。
- **密码不参与输入法纠错**：密码框与学号框都关掉了自动更正与首字母大写，密码不会被存进输入法的学习词库。
- **导出快照不含凭据**：导出的 JSON 只有课程、校历、待办、分类与生成规则，不含学号与密码（有单测钉住）。
- **数据不离开设备**：课表、待办、壁纸与凭据都被排除在 Android 云备份与设备迁移之外 —— 系统不会替用户把数据上传到云端、也不会在换机时自动搬走。换机靠 App 自带的「导出快照 / 导入快照」。
- **只申请两个权限**：`INTERNET` 与 `ACCESS_NETWORK_STATE`，没有别的。

> 上面这些基线由 `app/src/test/java/com/example/security/SecurityBaselineTest.kt` 钉住：清单、网络配置、备份规则一旦被改坏，测试立刻红。

## 📲 安装

1. 打开 [Releases](https://github.com/chenqianyu486-sys/MiuList/releases) 页面，下载 `MiuList-v1.0.4.apk`。
2. 用手机安装（需允许「安装未知来源应用」）。
3. 打开 App：浙大同学填学号密码登录统一身份认证即可同步课表与考试；其他学校手动添加课程并填写学期校历即可。

> 当前是测试版：功能仍在调整。本地数据不上云（Android 的云备份与换机迁移都已关闭），**卸载重装会丢**，重要数据记得先导出快照备份。

## 🛠️ 源码

### 环境要求

- **JDK 17**（必须，AGP 与 Kotlin 插件都卡这个版本）
- Android SDK：`minSdk 24` / `targetSdk 36` / `compileSdk 36`
- Gradle 用仓库自带的 Wrapper，不用预装

### 构建

```bash
# Windows
gradlew.bat assembleDebug

# macOS / Linux
./gradlew assembleDebug
```

产物在 `app/build/outputs/apk/debug/app-debug.apk`。

### 一键预览

Windows 上开发，双击仓库根目录的 **`start-preview.bat`** 就行：构建 → 起模拟器 → 装包 → 启动 App 一条龙，
并且会先把模拟器屏幕摆正到设计基准（411dp），避免拿别的屏幕比例判断排版。

```bash
start-preview.bat                # 默认
start-preview.bat -DryRun        # 只打印计划，不动手
start-preview.bat -SkipBuild     # 跳过 Gradle
start-preview.bat -Display base  # 换成设计基准 411dp
```

### 跑测试

```bash
# JVM 单测（77 个测试类、810 个用例，默认不联网）
gradlew.bat :app:testDebugUnitTest

# 插桩测试（需要连着设备或模拟器）
gradlew.bat :app:connectedDebugAndroidTest
```

少数用例要真的出网，默认跳过，加 `-Pmiulist.liveNet=1` 打开：

```bash
gradlew.bat :app:testDebugUnitTest --tests "*HostedWeatherLiveTest*" -Pmiulist.liveNet=1
```

### release 构建

release 包需要签名密钥。仓库根目录放 `keystore.properties`（已被 `.gitignore` 忽略，切勿提交）：

```properties
KEYSTORE_PATH=C:\path\to\your-upload-key.jks
STORE_PASSWORD=...
KEY_ALIAS=upload
KEY_PASSWORD=...
```

### 改了界面想验证

`tools/` 里有一套断点截图回归矩阵（11 档断点 × 系统字号，含横屏两档），把「排版对不对」变成可重复的检查。用法见 [tools/README.md](tools/README.md)。

## 🏗️ 技术栈

| 层 | 选型 |
|----|------|
| UI | Jetpack Compose + Material 3，自定义设计令牌 |
| 状态 | ViewModel + StateFlow，UI 层不依赖框架 |
| 持久化 | Room 2.7（12 张表，KSP 生成）+ DataStore Preferences |
| 网络 | OkHttp 4.10 + Moshi（KSP codegen） |
| 时间 | `java.time` + epochDay 手算，核心库脱糖以支持 minSdk 24 |
| 时区 | 固定 `Asia/Shanghai`，与设备时区解耦（换手机时区不会把周次算错） |

## 🔐 天气与空气质量从哪来

| 数据 | 来源 | 客户端要密钥吗 |
|------|------|----------------|
| 天气（温度、天气状况） | 心知天气（地面观测），GitHub Actions 每小时同步成静态 JSON 托管 | **不要** |
| 空气质量（AQI） | Open-Meteo / 哥白尼大气监测服务（CAMS） | **不要** |

客户端只做匿名 GET，**APK 里没有任何密钥**，也不存在被刷配额的风险。心知私钥只存在 GitHub Actions 的 Secrets 里。

数据由数值模式生成，可能与地面实测有偏差，**不作为健康决策依据**。

## 📄 许可证

本项目基于 [GNU General Public License v3.0 (GPL-3.0)](LICENSE) 协议开源。

> ⚠️ **GPL-3.0 有传染性**：fork 或基于本项目改造后再发布时，必须同样以 GPL-3.0 开放你的代码，并保留本项目的版权与许可证声明。

本项目的许可证继承自 [Celechron](https://github.com/Celechron/Celechron)（同为 GPLv3）。

## 🙏 鸣谢

### 数据与服务

- [心知天气 (Seniverse)](https://www.seniverse.com/) — **心知天气团队**：天气数据服务
- [Open-Meteo](https://open-meteo.com/) — **Open-Meteo 团队；空气质量由哥白尼大气监测服务（CAMS）提供**：空气质量（AQI）数据，按 [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/) 授权

### 代码与设计参考

- [Celechron/Celechron](https://github.com/Celechron/Celechron) — **@n0sig、@Phil-Fan、@Quinn-DJ、@lelexia0131、@bno3bno3 及所有贡献者**：校园日程与时间管理设计参考，GPLv3 许可证继承来源
- [Elyyyyyyyyxer/Elychron](https://github.com/Elyyyyyyyyxer/Elychron) — **@Elyyyyyyyyxer 及其贡献者**：课表与日程管理相关设计与灵感来源
- [cxz66666/zju-ical](https://github.com/cxz66666/zju-ical) — **@cxz66666 及其贡献者**（源自 determ1ne/grsical）：浙大课表日历导出与转换参考
- [Xecades/zju-ical-py](https://github.com/Xecades/zju-ical-py) — **@Xecades 及其贡献者**：浙大课表解析与 iCal 转换的 Python 实现参考
- [破晓壁纸 (PXLab)](https://pxlab.cn/index.html) — **PXLab 团队**：精选高质量壁纸资源、设计美学与透光质感参考
