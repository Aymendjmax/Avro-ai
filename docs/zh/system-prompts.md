# 系统提示词

打开**设置 → 系统提示词**管理可复用提示词。

新提示词可从**空白**或**默认**开始。编辑器包含三类有序模板：

- **System** 定义模型可见的完整系统消息。
- **User** 定义普通用户消息。
- **Assistant** 定义普通助手消息。

User 与 Assistant 各自包含且仅包含一个结构化 `Prompt` 项。它代表原始消息正文，不能删除、移动或重复插入。可以在它的上方或下方添加文本和变量。

当前变量包括 `{time}`、`{date}`、`{sent_time}`、`{sent_date}`、`{active_memory}`、`{skill_catalog}`、`{current_model_id}` 与 `{message_model_id}`。`{current_model_id}` 表示本次出站请求选择的模型；`{message_model_id}` 按普通历史消息逐条解析为创建该消息的模型，没有模型身份时为空。旧 `{model_id}` 仍可读取，并等价于 `{current_model_id}`，但不会再提供给新模板插入。除 `{active_memory}` 外，所有变量都在每次 Provider 请求实际出站前实时解析，包括初始请求、工具续轮与 transport retry。`{active_memory}` 在一轮回复开始时读取一次，该轮所有请求保持不变，以稳定提示缓存；回复过程中的修改可通过 `read_active_memory` 读取，并在下一轮进入提示词。编辑器预览只使用示例值，不会冻结后续请求的变量值。

普通生成的 system prompt 完全由所选结构化 System 模板定义。Avro 不会隐式追加 memory、skill、runtime metadata、tool guidance 或其他隐藏文本。权限设置只控制受保护变量能否解析以及相应工具是否可用。

User 与 Assistant 模板只应用于普通对话消息。工具消息、Context Compact、标题生成和其他特殊生成路径继续使用各自的专用格式。

提示词可编辑、复制、删除并设为全局默认。对话可继承默认项或选择其他已保存提示词。内置 Default 来自应用源码，并不存在旧文档所说的四类模板库。
