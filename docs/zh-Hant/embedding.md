# 嵌入 / RAG

嵌入模型把對話文本转换为向量，供语义對話搜尋使用。请在**設定 → 對話搜尋**中設定。

## 提供商与预设

当前预设包括：

- OpenAI：`text-embedding-3-small`、`text-embedding-3-large`、`text-embedding-ada-002`
- Mistral：`mistral-embed`
- Voyage AI：`voyage-3-large`、`voyage-3-lite`、`voyage-code-3`
- SiliconFlow：`BAAI/bge-m3`、`BAAI/bge-large-en-v1.5`
- OpenRouter 的 OpenAI 嵌入模型路由
- Ollama、本地嵌入模型或自定义端点

远程嵌入使用所选提供商的凭据和 Base URL，待嵌入文本会离开裝置；本地嵌入留在裝置上。

## RAG 控制

- 上下文范围：4–32 个對話步骤，步长 4
- 结果数：5–30，步长 5
- 相似度阈值：0–1，預設 0.5

更换嵌入模型后，旧索引可能需要重新生成。详见[對話搜尋](search.md)和[隐私与安全](privacy.md)。
