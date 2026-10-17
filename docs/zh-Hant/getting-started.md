# 快速开始

Avro 是 Android BYOK 客户端：你需要自行設定模型提供商、凭据和模型。

## 安装

可从 F-Droid、Google Play 或 GitHub Releases 安装。不同分发渠道的功能可能不同；集成 Alpine 沙盒只在 F-Droid 构建中提供。

## 首次設定

1. 打开對話抽屉并进入**設定**。
2. 打开**提供商**，选择提供商并填写 API Key 与可选 Base URL。
3. 打开**模型**，同步提供商模型并启用需要的模型。
4. 回到對話，在底栏选择模型。
5. 发送消息。

请求会直接发往所选提供商設定的端点。参见[提供商](provider.md)、[模型](models.md)和[隐私与安全](privacy.md)。

## 从源码构建

当前项目目标为 Android SDK 36，并按仓库工作流使用 JDK 21。请安装 Android Studio 及所需 SDK/NDK 组件，并使用根目录构建脚本，不要沿用旧的 SDK/JDK 基线。
