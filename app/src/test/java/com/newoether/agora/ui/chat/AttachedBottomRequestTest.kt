package com.newoether.agora.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachedBottomRequestTest {
    @Test
    fun attachedRequestPreservesReceiptEligibilityAcrossTargetRelayout() {
        assertTrue(
            shouldHonorAttachedBottomRequest(
                attachedOnly = true,
                attachedAtRequest = true,
                userDragRevisionAtRequest = 4L,
                currentUserDragRevision = 4L,
            ),
        )
    }

    @Test
    fun attachedRequestDoesNotAcquireEligibilityAfterReceipt() {
        assertFalse(
            shouldHonorAttachedBottomRequest(
                attachedOnly = true,
                attachedAtRequest = false,
                userDragRevisionAtRequest = 4L,
                currentUserDragRevision = 4L,
            ),
        )
    }

    @Test
    fun userDragRevokesAnAttachedRequestBeforeItsTargetCommits() {
        assertFalse(
            shouldHonorAttachedBottomRequest(
                attachedOnly = true,
                attachedAtRequest = true,
                userDragRevisionAtRequest = 4L,
                currentUserDragRevision = 5L,
            ),
        )
    }

    @Test
    fun unconditionalRequestIgnoresAttachmentAndDragRevisions() {
        assertTrue(
            shouldHonorAttachedBottomRequest(
                attachedOnly = false,
                attachedAtRequest = false,
                userDragRevisionAtRequest = 4L,
                currentUserDragRevision = 5L,
            ),
        )
    }
}
