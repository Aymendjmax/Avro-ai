package com.newoether.agora.ui.chat.message

import com.newoether.agora.model.AttachmentItem
import org.junit.Assert.assertEquals
import org.junit.Test

class UserMessageMediaOccurrenceTest {
    @Test
    fun duplicateImageAndVideoValuesRetainFirstAndSecondOccurrenceIdentity() {
        val duplicateImage = "/ready/duplicate.jpg"
        val duplicateVideo = "file:///ready/duplicate.mp4"
        val projection = projectStoredMediaOccurrences(
            listOf(
                duplicateImage to AttachmentItem(type = "image", imageIndex = 0),
                duplicateImage to AttachmentItem(type = "image", imageIndex = 1),
                "/ready/frame-a.jpg" to AttachmentItem(
                    type = "video",
                    originalUri = duplicateVideo,
                    imageIndex = 2,
                ),
                "/ready/frame-b.jpg" to AttachmentItem(
                    type = "video",
                    originalUri = duplicateVideo,
                    imageIndex = 3,
                ),
                "/missing.jpg" to AttachmentItem(
                    type = "image",
                    imageIndex = 4,
                    unavailable = true,
                ),
                "" to AttachmentItem(type = "file"),
            ),
        )

        assertEquals(
            listOf(duplicateImage, duplicateImage, duplicateVideo, duplicateVideo),
            projection.urls,
        )
        assertEquals(listOf(0, 1, 2, 3, null, null), projection.indexByDisplayItem)
    }

    @Test
    fun legacyImageOccurrencesRemainOrderedWithoutMetadata() {
        val projection = projectStoredMediaOccurrences(
            listOf(
                "/legacy/same.jpg" to null,
                "/legacy/same.jpg" to null,
            ),
        )

        assertEquals(listOf("/legacy/same.jpg", "/legacy/same.jpg"), projection.urls)
        assertEquals(listOf(0, 1), projection.indexByDisplayItem)
    }
}
