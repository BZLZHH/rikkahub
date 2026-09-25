<div align="center">
  <img src="docs/icon.png" alt="App Icon" width="100" />
  <h1>RikkaHub Enhanced</h1>

[RikkaHub](https://github.com/rikkahub/rikkahub) 的独立增强分支 —— 一个原生 Android LLM 聊天客户端。

[English](README.md) | 简体中文 | [繁體中文](README_ZH_TW.md)
</div>

## 概述

RikkaHub Enhanced 跟随上游 RikkaHub，并针对**长时间无人值守运行**做了增强。
本分支使用独立的应用 ID 与版本线，可与官方版同时安装。

| | |
|---|---|
| 应用 ID | `me.rerere.rikkahub.enhanced` |
| 当前版本 | `2.5.4-rhe.1.0`（versionCode `189001`） |
| 上游基线 | RikkaHub `2.5.4`（versionCode `189`） |
| 许可证 | AGPL-3.0 |

## 相比上游新增

**后台持续运行。** 由前台服务支撑的一条静默常驻通知让应用保持存活，退出应用后流式回复、通知与长任务不中断；
提供引导页完成通知权限、电池优化与厂商自启动设置。

**后台任务（`job_*` 工具）。** AI 可在工作区沙箱中运行长时间命令并全程管理：启动、列表、状态、
增量读取日志、等待、停止、重启、删除。同时提供可复用的任务定义与参数、定时触发（延迟 / 周期 / cron）、
可交互 pty 模式、完成通知，以及任务结束后按需唤醒会话。

**工具调用自动审批。** 高影响工具的审批可交由指定模型判断；判断不确定时仍然询问用户。

**上游修复。** 修复了上游存在的导航崩溃（`NavDisplay backstack cannot be empty`）。

## 下载

发布时会在 [Releases](https://github.com/BZLZHH/rikkahub/releases) 附带 APK。

```bash
adb install -r app-universal-release.apk
```

应用 ID 与官方版不同，两者可同时安装；原地覆盖升级需要相同签名。

## 构建

依赖：JDK 21、Android SDK（compileSdk 37）。

```bash
./gradlew :app:assembleRelease
```

仓库不包含 `app/google-services.json`，构建 release 变体前请自行提供 Firebase 配置。

## 版本号规则

- `versionCode` = 上游 versionCode × 1000 + RHE 构建号
- `versionName` = `<上游版本>-rhe.<RHE 版本>`

## 与上游的关系

本项目是分支，与 RikkaHub 项目无从属或背书关系。上游代码是本仓库的基础，原始工作成果归 RikkaHub 作者所有。
仅与本分支相关的问题请提交到本仓库，而非上游；上游不接受 Pull Request，因此改动以分支形式维护在本仓库。

## 许可证

AGPL-3.0，见 [LICENSE](LICENSE)。依据许可证要求，本分支的完整对应源码即本仓库。
