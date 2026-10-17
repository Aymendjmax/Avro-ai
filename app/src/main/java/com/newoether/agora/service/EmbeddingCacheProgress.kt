package com.newoether.agora.service

internal enum class EmbeddingCacheWorkKind {
    RECONCILE,
    EXACT,
}

internal data class EmbeddingCacheProgress(
    val generationRevision: Long,
    val kind: EmbeddingCacheWorkKind,
    val processed: Int,
    val total: Int,
) {
    init {
        require(generationRevision >= 0L)
        require(total > 0)
        require(processed in 0..total)
    }

    val remaining: Int
        get() = total - processed

    val progressPermille: Int
        get() = ((processed.toLong() * 1000L) / total).toInt()
}
