package com.newoether.agora.ui.chat.message

/**
 * Latest-value commit gate used while an embedded code block owns a horizontal gesture.
 *
 * Parsing continues and conflates normally, but Compose keeps the currently measured tree until
 * every active interaction ends. The newest completed snapshot is then committed exactly once.
 */
internal class StreamingInteractionCommitGate<T : Any> {
    private val activeOwners = mutableSetOf<Any>()
    private var pending: T? = null

    fun offer(value: T): T? {
        if (activeOwners.isNotEmpty()) {
            pending = value
            return null
        }
        return value
    }

    fun setActive(owner: Any, active: Boolean): T? {
        if (active) {
            activeOwners += owner
            return null
        }
        activeOwners -= owner
        if (activeOwners.isNotEmpty()) return null
        return pending.also { pending = null }
    }
}
