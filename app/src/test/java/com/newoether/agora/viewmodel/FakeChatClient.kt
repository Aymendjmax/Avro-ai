package com.newoether.agora.viewmodel

/** Recording [ChatClient] for unit tests; every effect is a replaceable hook. */
internal open class FakeChatClient(
    var open: String? = null,
    var visible: Boolean = false,
    override val branchTransitions: BranchReplacementTransitionCoordinator =
        BranchReplacementTransitionCoordinator(),
) : ChatClient {
    override val openConversationId: String? get() = open
    override val renderStore = ConversationRenderStore()
    override fun isConversationVisible(conversationId: String) = visible && open == conversationId

    var onAwaitProjectedPath: suspend (String, String) -> Unit = { _, _ -> }
    var onScroll: (conversationId: String, messageId: String, attachedOnly: Boolean) -> Unit =
        { _, _, _ -> }
    var onAccepted: (conversationId: String, messageId: String) -> Unit = { _, _ -> }
    var onApplyCommittedNewConversationState: suspend (String) -> Unit = {}
    var onPublishAcceptedNewConversation: suspend (String, String, Long) -> Boolean =
        { _, _, _ -> false }
    var onBeginTreeMutation: suspend (String, Boolean) -> Long? = { _, _ -> null }
    var onSettleTreeMutation: (Long?, String?) -> Unit = { _, _ -> }
    var onFailTreeMutation: (Long?) -> Unit = {}
    val snackbars = mutableListOf<String>()

    override suspend fun awaitProjectedPath(conversationId: String, messageId: String) =
        onAwaitProjectedPath(conversationId, messageId)
    override fun requestScrollToBottomAfter(
        conversationId: String,
        messageId: String,
        attachedOnly: Boolean,
    ) = onScroll(conversationId, messageId, attachedOnly)
    override fun onSendAccepted(conversationId: String, messageId: String) =
        onAccepted(conversationId, messageId)
    override suspend fun applyCommittedNewConversationState(conversationId: String) =
        onApplyCommittedNewConversationState(conversationId)
    override suspend fun publishAcceptedNewConversation(
        conversationId: String,
        modelId: String,
        entryId: Long,
    ) = onPublishAcceptedNewConversation(conversationId, modelId, entryId)
    override suspend fun beginTreeMutation(conversationId: String, scrollToTarget: Boolean) =
        onBeginTreeMutation(conversationId, scrollToTarget)
    override fun settleTreeMutation(requestId: Long?, targetMessageId: String?) =
        onSettleTreeMutation(requestId, targetMessageId)
    override fun failTreeMutation(requestId: Long?) = onFailTreeMutation(requestId)
    override fun showSnackbar(message: String) {
        snackbars += message
    }
    val openedConversations = mutableListOf<String>()
    override fun openConversation(conversationId: String) {
        openedConversations += conversationId
    }
    val shareTexts = mutableListOf<String>()
    override fun showShareText(text: String) {
        shareTexts += text
    }
    var onSettleDeleted: (String) -> Unit = {}
    override fun settleDeletedConversation(conversationId: String) = onSettleDeleted(conversationId)
    var frozen = false
    override fun isSubmissionFrozen(conversationId: String) = frozen
    val activityChanges = mutableListOf<Pair<String, Boolean>>()
    override fun onGenerationActivityChanged(conversationId: String, active: Boolean) {
        activityChanges += conversationId to active
    }
}
