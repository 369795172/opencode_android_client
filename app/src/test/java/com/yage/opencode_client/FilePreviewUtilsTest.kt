package com.yage.opencode_client

import com.yage.opencode_client.ui.files.FilePreviewUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FilePreviewUtilsTest {
    @Test
    fun `isImagePath recognizes common image extensions case-insensitively`() {
        assertTrue(FilePreviewUtils.isImagePath("foo/bar/image.png"))
        assertTrue(FilePreviewUtils.isImagePath("foo/bar/photo.JPEG"))
        assertTrue(FilePreviewUtils.isImagePath("foo/bar/icon.WebP"))
        assertFalse(FilePreviewUtils.isImagePath("foo/bar/readme.md"))
        assertFalse(FilePreviewUtils.isImagePath("foo/bar/no_extension"))
    }

    @Test
    fun `imageMimeType maps common image extensions`() {
        assertEquals("image/png", FilePreviewUtils.imageMimeType("image.png"))
        assertEquals("image/jpeg", FilePreviewUtils.imageMimeType("image.jpg"))
        assertEquals("image/webp", FilePreviewUtils.imageMimeType("image.webp"))
        assertEquals("image/*", FilePreviewUtils.imageMimeType("image.unknown"))
    }
}
