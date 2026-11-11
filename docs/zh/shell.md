# 远程 Shell

打开**设置 → Shell 设备**配置供代理工具使用的 Conch 或 SSH 设备。

## Conch

配置服务器 URL、可选 API Key 和命令确认策略。有 API Key 时，Avro 获取服务器签名公钥，派生临时共享密钥，并发送经过认证的 AES-GCM 请求。空 Key 时应用层载荷是明文 JSON，机密性只依赖 HTTPS。

前台命令会创建持久任务并等待有限时间。等待超时不会杀死或重放命令，而是返回 `job_id`；之后可查询、等待、停止和确认删除。Conch 还支持远程读取、写入、编辑、glob、grep 与图像查看。

## 运行模式与 Shell 隔离

在 Avro 2.3.0 及更新版本中，对话采用专属运行模式：
- **Ask 模式（对话模式）**：标准对话模式。所有 Ask 对话（包括旧对话）中均明确停用 Shell 执行和终端工具，以避免模型幻觉调用终端命令或发生意外执行。
- **Work 模式（自主智能体）**：启用本地 PRoot Alpine Linux 沙箱、终端命令执行（`execute_shell_command`）、远程 Shell、FileStore 附件与文件操作工具。如需终端能力，可从输入框加号菜单或工作模式标签切换至 Work 模式。
