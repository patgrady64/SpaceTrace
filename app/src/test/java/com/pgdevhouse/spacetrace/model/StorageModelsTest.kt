package com.pgdevhouse.spacetrace.model

import org.junit.Assert.assertEquals
import org.junit.Test

class StorageModelsTest {
    @Test fun classifiesCommonTypes() {
        assertEquals(FileCategory.VIDEO, FileClassifier.category("movie.mkv", null))
        assertEquals(FileCategory.IMAGE, FileClassifier.category("photo.HEIC", null))
        assertEquals(FileCategory.DOCUMENT, FileClassifier.category("outline.odt", null))
        assertEquals(FileCategory.ARCHIVE, FileClassifier.category("backup.7z", null))
        assertEquals(FileCategory.APK, FileClassifier.category("app.apk", null))
    }

    @Test fun formatsBytesReadably() {
        assertEquals("0 B", 0L.formatBytes())
        assertEquals("1.0 KB", 1024L.formatBytes())
        assertEquals("1.5 MB", (1536L * 1024L).formatBytes())
    }
}
