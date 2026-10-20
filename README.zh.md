<div align="center">

  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="avro-brand-kit/logo/png/avro-lockup-white.png">
    <source media="(prefers-color-scheme: light)" srcset="avro-brand-kit/logo/png/avro-lockup-black.png">
    <img src="avro-brand-kit/logo/png/avro-lockup-white.png" alt="Avro Logo" width="220" />
  </picture>

  <br />

  **内置 Linux 环境且无中间服务器的自主 AI 安卓客户端**

  <br />

  [![License: GPL v3](https://img.shields.io/badge/License-GPLv3-black.svg)](LICENSE)
  [![Platform: Android](https://img.shields.io/badge/Platform-Android-black.svg)](https://developer.android.com)
  [![Kotlin](https://img.shields.io/badge/Kotlin-Native-black.svg)](https://kotlinlang.org/)
  [![Jetpack Compose](https://img.shields.io/badge/UI-Jetpack%20Compose%20M3-black.svg)](https://developer.android.com/jetpack/compose)

  <br />

  [ English ](README.md) · [ العربية ](README.ar.md) · [ 简体中文 ](README.zh.md) · [ 繁體中文 ](README.zh-Hant.md)

  <br />

  <img src="avro-brand-kit/cover/avro-cover-A-light-left.png" alt="Avro 界面" width="100%" />

</div>

---

## 概述

Avro 是一款注重本地隐私的 Android 客户端与自主 AI Agent 运行环境。它直接从设备连接到模型提供商 API（自带密钥 BYOK），不经任何第三方中间代理服务器。

应用内嵌完整的 Alpine Linux 沙盒环境，可在手机上直接执行代码、管理系统软件包并完成多步骤工具调用。

---

## 架构原则

- **端到端直接传输**: 所有 API 请求直接发送至官方提供商端点，无遥测中继或代理服务。
- **内嵌 PRoot 沙盒**: 无需 Root 权限即可在 Android 上运行 Alpine Linux 用户空间。支持 `apk` 软件包安装，可运行 Python、Shell 及预编译二进制程序。
- **原生双向排版**: 原生支持 RTL（从右至左）和 LTR，确保阿拉伯语、英语及中文的视觉层次对齐。
- **数据主权**: 对话记录、工具配置、上下文记忆及加密凭证均保存在本地加密存储中。

---

## 主要特性

### 模型接入
- **原生支持 10+ 提供商**: OpenAI, Anthropic Claude, Google Gemini, DeepSeek, Qwen / 通义千问, Groq, Ollama, OpenRouter, OpenCode, 以及本地 llama.cpp。
- **自定义端点适配**: 可配置 OpenAI 兼容、Gemini 与 Anthropic 协议的反向代理地址。
- **本地 GGUF 推理**: 通过内嵌 llama.cpp 引擎实现完全离线的模型推理与向量嵌入。

### 自主工具与协议
- **Linux 终端环境**: 隔离的容器执行环境，支持文件持久化及后台任务管理。
- **联网检索**: 支持 DuckDuckGo 与 Brave Search 实时搜索并流式生成引用来源。
- **模型上下文协议 (MCP)**: 内置客户端支持通过 SSE 和 HTTP 连接远程与本地 MCP 工具服务器。
- **检索增强生成 (RAG)**: 对历史对话及长期记忆进行向量相似度检索。
- **后台常驻执行**: 借助 Android 前台服务支持长时 Agent 循环与定时任务。

---

## 仓库结构

```text
├── app/                      # Android 应用程序模块
├── avro-brand-kit/           # 品牌视觉资产、矢量图标与设计指南
├── docs/                     # 完整开发与用户文档 (ar, en, zh, zh-Hant)
├── development/              # 系统架构技术规范及 RFC
├── scripts/                  # 自动化与构建辅助脚本
├── server/                   # 可选伴生同步服务
├── thirdparty/               # PRoot, talloc 与 llama.cpp 原生依赖
└── build.gradle.kts          # 顶级 Gradle 构建配置
```

---

## 构建指南

### 环境要求
- JDK 17 或 JDK 21
- Android SDK (兼容 API 36 / Android 16)
- Gradle 9+

### 常用命令

```bash
git clone https://github.com/Aymendjmax/Avro-ai.git
cd Avro
gradle assembleDebug
gradle :app:testDebugUnitTest
```

---

## 许可证与致谢

Avro 采用 **GNU General Public License v3.0 (GPL-3.0)** 许可证发布（详见 LICENSE）。  
基于 Newo Ether 的开源项目 Agora 构建并保持兼容，具备专属 Avro 视觉标识、阿拉伯语 RTL 修复与工程改进。
