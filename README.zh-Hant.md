<div align="center">

  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="avro-brand-kit/logo/png/avro-lockup-white.png">
    <source media="(prefers-color-scheme: light)" srcset="avro-brand-kit/logo/png/avro-lockup-black.png">
    <img src="avro-brand-kit/logo/png/avro-lockup-white.png" alt="Avro Logo" width="220" />
  </picture>

  <br />

  **內建 Linux 環境且無中間伺服器的自主 AI 安卓客戶端**

  <br />

  [![License: GPL v3](https://img.shields.io/badge/License-GPLv3-black.svg)](LICENSE)
  [![Platform: Android](https://img.shields.io/badge/Platform-Android-black.svg)](https://developer.android.com)
  [![Kotlin](https://img.shields.io/badge/Kotlin-Native-black.svg)](https://kotlinlang.org/)
  [![Jetpack Compose](https://img.shields.io/badge/UI-Jetpack%20Compose%20M3-black.svg)](https://developer.android.com/jetpack/compose)

  <br />

  [ English ](README.md) · [ العربية ](README.ar.md) · [ 简体中文 ](README.zh.md) · [ 繁體中文 ](README.zh-Hant.md)

  <br />

  <img src="avro-brand-kit/cover/avro-cover-A-light-left.png" alt="Avro 介面" width="100%" />

</div>

---

## 概述

Avro 是一款注重本地隱私的 Android 客戶端與自主 AI Agent 執行環境。它直接從裝置連接到模型提供商 API（自帶金鑰 BYOK），不經任何第三方中間代理伺服器。

應用程式內建完整的 Alpine Linux 沙盒環境，可在手機上直接執行程式碼、管理系統軟體包並完成多步驟工具呼叫。

---

## 架構原則

- **端到端直接傳輸**: 所有 API 請求直接發送至官方提供商端點，無遙測中繼或代理服務。
- **內嵌 PRoot 沙盒**: 無需 Root 權限即可在 Android 上執行 Alpine Linux 使用者空間。支援 `apk` 軟體包安裝，可執行 Python、Shell 及預編譯二進位程式。
- **原生雙向排版**: 原生支援 RTL（從右至左）和 LTR，確保阿拉伯語、英語及中文的視覺層次對齊。
- **數據主權**: 對話紀錄、工具設定、上下文記憶及加密憑證均保存在本地加密儲存中。

---

## 主要特性

### 模型接入
- **原生支援 10+ 提供商**: OpenAI, Anthropic Claude, Google Gemini, DeepSeek, Qwen / 通義千問, Groq, Ollama, OpenRouter, OpenCode, 以及本地 llama.cpp。
- **自訂端點適配**: 可設定 OpenAI 相容、Gemini 與 Anthropic 協定的反向代理位址。
- **本地 GGUF 推理**: 透過內嵌 llama.cpp 引擎實現完全離線的模型推理與向量嵌入。

### 自主工具與協定
- **Linux 終端環境**: 隔離的容器執行環境，支援檔案持久化及後台任務管理。
- **聯網檢索**: 支援 DuckDuckGo 與 Brave Search 即時搜尋並串流生成引用來源。
- **模型上下文協定 (MCP)**: 內建客戶端支援透過 SSE 和 HTTP 連接遠端與本地 MCP 工具伺服器。
- **檢索增強生成 (RAG)**: 對歷史對話及長期記憶進行向量相似度檢索。
- **後台常駐執行**: 借助 Android 前台服務支援長時 Agent 循環與定時任務。

---

## 倉庫結構

```text
├── app/                      # Android 應用程式模組
├── avro-brand-kit/           # 品牌視覺資產、向量圖標與設計指南
├── docs/                     # 完整開發與使用者文件 (ar, en, zh, zh-Hant)
├── development/              # 系統架構技術規範及 RFC
├── scripts/                  # 自動化與建構輔助腳本
├── server/                   # 可選伴生同步服務
├── thirdparty/               # PRoot, talloc 與 llama.cpp 原生依賴
└── build.gradle.kts          # 頂級 Gradle 建構設定
```

---

## 建構指南

### 環境要求
- JDK 17 或 JDK 21
- Android SDK (相容 API 36 / Android 16)
- Gradle 9+

### 常用指令

```bash
git clone https://github.com/fifikoridek2-boop/Avro.git
cd Avro
gradle assembleDebug
gradle :app:testDebugUnitTest
```

---

## 授權與致謝

Avro 採用 **GNU General Public License v3.0 (GPL-3.0)** 授權發布（詳見 LICENSE）。  
基於 Newo Ether 的開源專案 Agora 建構並保持相容，具備專屬 Avro 視覺標識、阿拉伯語 RTL 修復與工程改進。
