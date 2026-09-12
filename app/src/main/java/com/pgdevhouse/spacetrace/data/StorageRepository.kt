package com.pgdevhouse.spacetrace.data

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import com.pgdevhouse.spacetrace.model.ScanProgress
import com.pgdevhouse.spacetrace.model.StorageCapacity
import com.pgdevhouse.spacetrace.model.StorageLocation
import com.pgdevhouse.spacetrace.model.StorageNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

class StorageRepository(private val context: Context) {

    fun hasFullAccess(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        context.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    fun storageLocations(): List<StorageLocation> {
        val locations = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.getSystemService(StorageManager::class.java).storageVolumes.mapNotNull { volume ->
                volume.directory?.takeIf(File::exists)?.let { directory ->
                    StorageLocation(
                        name = if (volume.isPrimary) "Internal storage" else volume.getDescription(context),
                        path = directory.absolutePath,
                        removable = volume.isRemovable
                    )
                }
            }
        } else {
            context.getExternalFilesDirs(null).mapNotNull { appDirectory ->
                appDirectory ?: return@mapNotNull null
                val rootPath = appDirectory.absolutePath.substringBefore("/Android/data/")
                val root = File(rootPath)
                if (!root.exists()) return@mapNotNull null
                StorageLocation(
                    name = if (rootPath == Environment.getExternalStorageDirectory().absolutePath) "Internal storage" else "SD card",
                    path = rootPath,
                    removable = rootPath != Environment.getExternalStorageDirectory().absolutePath
                )
            }
        }
        return locations.distinctBy { it.path }
            .sortedWith(compareByDescending<StorageLocation> { it.removable }.thenBy { it.name })
    }

    suspend fun scan(
        location: StorageLocation,
        onProgress: (ScanProgress) -> Unit
    ): StorageNode = withContext(Dispatchers.IO) {
        var progress = ScanProgress(currentPath = location.path)

        suspend fun scanFile(file: File): StorageNode {
            coroutineContext.ensureActive()
            if (file.isDirectory) {
                progress = progress.copy(
                    foldersVisited = progress.foldersVisited + 1,
                    currentPath = file.absolutePath
                )
                if ((progress.filesVisited + progress.foldersVisited) % 25 == 0) onProgress(progress)
                val children = runCatching { file.listFiles()?.toList().orEmpty() }
                    .getOrDefault(emptyList())
                    .map { scanFile(it) }
                return StorageNode(
                    uri = Uri.fromFile(file),
                    name = file.name.ifBlank { location.name },
                    displayPath = file.absolutePath,
                    mimeType = null,
                    ownSizeBytes = 0L,
                    totalSizeBytes = children.sumOf { it.totalSizeBytes },
                    lastModified = file.lastModified(),
                    isDirectory = true,
                    children = children
                )
            }

            val size = file.length().coerceAtLeast(0L)
            progress = progress.copy(
                filesVisited = progress.filesVisited + 1,
                bytesFound = progress.bytesFound + size,
                currentPath = file.absolutePath
            )
            if (progress.filesVisited % 25 == 0) onProgress(progress)
            return StorageNode(
                uri = Uri.fromFile(file),
                name = file.name,
                displayPath = file.absolutePath,
                mimeType = null,
                ownSizeBytes = size,
                totalSizeBytes = size,
                lastModified = file.lastModified(),
                isDirectory = false
            )
        }

        scanFile(File(location.path)).also { onProgress(progress) }
    }

    suspend fun delete(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        val path = uri.path ?: return@withContext false
        val target = File(path)
        if (!target.exists()) return@withContext true
        if (target.isDirectory) target.deleteRecursively() else target.delete()
    }

    fun capacityFor(location: StorageLocation): StorageCapacity? = runCatching {
        val stats = StatFs(location.path)
        StorageCapacity(stats.totalBytes, stats.availableBytes)
    }.getOrNull()
}
