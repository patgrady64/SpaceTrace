package com.pgdevhouse.spacetrace.model

import android.net.Uri

data class StorageLocation(
    val name: String,
    val path: String,
    val removable: Boolean
)

enum class FileCategory(val label: String) {
    ALL("All"),
    FOLDER("Folders"),
    VIDEO("Videos"),
    IMAGE("Images"),
    AUDIO("Audio"),
    DOCUMENT("Documents"),
    ARCHIVE("Archives"),
    APK("APKs"),
    OTHER("Other")
}

enum class SortMode(val label: String) {
    SIZE_DESC("Largest"),
    NAME_ASC("Name"),
    MODIFIED_DESC("Newest")
}

data class StorageNode(
    val uri: Uri,
    val name: String,
    val displayPath: String,
    val mimeType: String?,
    val ownSizeBytes: Long,
    val totalSizeBytes: Long,
    val lastModified: Long,
    val isDirectory: Boolean,
    val children: List<StorageNode> = emptyList()
) {
    val category: FileCategory
        get() = if (isDirectory) FileCategory.FOLDER else FileClassifier.category(name, mimeType)

    val directFileCount: Int
        get() = children.count { !it.isDirectory }

    val directFolderCount: Int
        get() = children.count { it.isDirectory }

    val directContentsLabel: String
        get() {
            val parts = mutableListOf<String>()
            if (directFileCount > 0) parts += "$directFileCount ${if (directFileCount == 1) "file" else "files"}"
            if (directFolderCount > 0) parts += "$directFolderCount ${if (directFolderCount == 1) "folder" else "folders"}"
            return if (parts.isEmpty()) "Empty folder" else parts.joinToString(" • ")
        }
}

data class StorageCapacity(
    val totalBytes: Long,
    val freeBytes: Long
) {
    val usedBytes: Long get() = (totalBytes - freeBytes).coerceAtLeast(0L)
}

data class ScanProgress(
    val filesVisited: Int = 0,
    val foldersVisited: Int = 0,
    val bytesFound: Long = 0L,
    val currentPath: String = ""
)

object FileClassifier {
    private val video = setOf("mp4", "mkv", "avi", "mov", "webm", "m4v", "3gp", "ts")
    private val image = setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp", "svg")
    private val audio = setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "wma")
    private val document = setOf("pdf", "txt", "md", "doc", "docx", "odt", "rtf", "xls", "xlsx", "ods", "csv", "ppt", "pptx", "odp", "epub")
    private val archive = setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz")

    fun category(name: String, mimeType: String?): FileCategory {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when {
            ext == "apk" || mimeType == "application/vnd.android.package-archive" -> FileCategory.APK
            ext in video || mimeType?.startsWith("video/") == true -> FileCategory.VIDEO
            ext in image || mimeType?.startsWith("image/") == true -> FileCategory.IMAGE
            ext in audio || mimeType?.startsWith("audio/") == true -> FileCategory.AUDIO
            ext in document || mimeType?.startsWith("text/") == true -> FileCategory.DOCUMENT
            ext in archive || mimeType in setOf("application/zip", "application/x-rar-compressed", "application/x-7z-compressed") -> FileCategory.ARCHIVE
            else -> FileCategory.OTHER
        }
    }
}

fun Long.formatBytes(): String {
    if (this < 1024) return "$this B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = toDouble()
    var index = -1
    while (value >= 1024 && index < units.lastIndex) {
        value /= 1024
        index++
    }
    return if (value >= 100) "%.0f %s".format(value, units[index])
    else "%.1f %s".format(value, units[index])
}
