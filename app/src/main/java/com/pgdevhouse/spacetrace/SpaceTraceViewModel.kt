package com.pgdevhouse.spacetrace

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pgdevhouse.spacetrace.data.StorageRepository
import com.pgdevhouse.spacetrace.model.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SpaceTraceUiState(
    val permissionGranted: Boolean = false,
    val locations: List<StorageLocation> = emptyList(),
    val selectedLocationPaths: Set<String> = emptySet(),
    val scanStarted: Boolean = false,
    val root: StorageNode? = null,
    val currentFolderUri: Uri? = null,
    val pathStack: List<Uri> = emptyList(),
    val capacity: StorageCapacity? = null,
    val scanning: Boolean = false,
    val progress: ScanProgress = ScanProgress(),
    val category: FileCategory = FileCategory.ALL,
    val sortMode: SortMode = SortMode.SIZE_DESC,
    val selected: Set<Uri> = emptySet(),
    val deleting: Boolean = false,
    val message: String? = null
)

class SpaceTraceViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = StorageRepository(application)
    private val _uiState = MutableStateFlow(SpaceTraceUiState())
    val uiState: StateFlow<SpaceTraceUiState> = _uiState.asStateFlow()
    private var scanJob: Job? = null

    init { refreshAccess() }

    fun refreshAccess() {
        val granted = repository.hasFullAccess()
        val locations = repository.storageLocations()
        val oldSelected = _uiState.value.selectedLocationPaths.intersect(locations.map { it.path }.toSet())
        val defaults = if (oldSelected.isNotEmpty()) oldSelected
        else locations.filter { !it.removable }.map { it.path }.toSet().ifEmpty { locations.firstOrNull()?.let { setOf(it.path) }.orEmpty() }
        _uiState.update { it.copy(permissionGranted = granted, locations = locations, selectedLocationPaths = defaults) }
    }

    fun toggleScanLocation(location: StorageLocation) = _uiState.update { state ->
        val next = state.selectedLocationPaths.toMutableSet().apply {
            if (!add(location.path)) remove(location.path)
        }
        state.copy(selectedLocationPaths = next)
    }

    fun startSelectedScan() {
        if (!_uiState.value.permissionGranted) return
        val selectedLocations = _uiState.value.locations.filter { it.path in _uiState.value.selectedLocationPaths }
        if (selectedLocations.isEmpty()) {
            _uiState.update { it.copy(message = "Choose at least one storage location.") }
            return
        }
        scan(selectedLocations)
    }

    fun rescan() {
        val selectedLocations = _uiState.value.locations.filter { it.path in _uiState.value.selectedLocationPaths }
        if (selectedLocations.isNotEmpty()) scan(selectedLocations)
    }

    private fun scan(locations: List<StorageLocation>) {
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            _uiState.update { it.copy(scanStarted = true, scanning = true, progress = ScanProgress(), selected = emptySet()) }
            runCatching {
                val roots = mutableListOf<StorageNode>()
                var filesBefore = 0
                var foldersBefore = 0
                var bytesBefore = 0L
                locations.forEach { location ->
                    var last = ScanProgress()
                    val root = repository.scan(location) { p ->
                        last = p
                        _uiState.update { state ->
                            state.copy(progress = ScanProgress(
                                filesVisited = filesBefore + p.filesVisited,
                                foldersVisited = foldersBefore + p.foldersVisited,
                                bytesFound = bytesBefore + p.bytesFound,
                                currentPath = p.currentPath
                            ))
                        }
                    }
                    roots += root
                    filesBefore += last.filesVisited
                    foldersBefore += last.foldersVisited
                    bytesBefore += last.bytesFound
                }
                val root = if (roots.size == 1) roots.first() else StorageNode(
                    uri = Uri.parse("spacetrace://selected-storage"),
                    name = "Selected storage",
                    displayPath = "Internal storage + SD card",
                    mimeType = null,
                    ownSizeBytes = 0,
                    totalSizeBytes = roots.sumOf { it.totalSizeBytes },
                    lastModified = roots.maxOfOrNull { it.lastModified } ?: 0,
                    isDirectory = true,
                    children = roots
                )
                val capacities = locations.mapNotNull(repository::capacityFor)
                root to StorageCapacity(capacities.sumOf { it.totalBytes }, capacities.sumOf { it.freeBytes })
            }.onSuccess { (root, capacity) ->
                _uiState.update { it.copy(root = root, currentFolderUri = root.uri, pathStack = listOf(root.uri), capacity = capacity, scanning = false) }
            }.onFailure { error ->
                if (error is kotlinx.coroutines.CancellationException) return@onFailure
                _uiState.update { it.copy(scanning = false, message = error.message ?: "Scan failed.") }
            }
        }
    }

    fun cancelScan() { scanJob?.cancel(); _uiState.update { it.copy(scanning = false, message = "Scan cancelled.") } }
    fun setCategory(category: FileCategory) = _uiState.update { it.copy(category = category) }
    fun setSortMode(sortMode: SortMode) = _uiState.update { it.copy(sortMode = sortMode) }
    fun showMessage(message: String) = _uiState.update { it.copy(message = message) }

    fun dismissMessage() = _uiState.update { it.copy(message = null) }
    fun toggleSelected(uri: Uri) = _uiState.update { state -> val next = state.selected.toMutableSet().apply { if (!add(uri)) remove(uri) }; state.copy(selected = next) }
    fun clearSelection() = _uiState.update { it.copy(selected = emptySet()) }

    fun openFolder(uri: Uri) {
        val node = findNode(_uiState.value.root, uri) ?: return
        if (!node.isDirectory) return
        _uiState.update { it.copy(currentFolderUri = uri, pathStack = it.pathStack + uri, selected = emptySet()) }
    }
    fun navigateUp(): Boolean {
        val state = _uiState.value
        if (state.pathStack.size <= 1) return false
        val stack = state.pathStack.dropLast(1)
        _uiState.update { it.copy(pathStack = stack, currentFolderUri = stack.last(), selected = emptySet()) }
        return true
    }
    fun returnToStorageSelection() {
        scanJob?.cancel()
        _uiState.update {
            it.copy(
                scanStarted = false,
                root = null,
                currentFolderUri = null,
                pathStack = emptyList(),
                capacity = null,
                scanning = false,
                progress = ScanProgress(),
                selected = emptySet(),
                deleting = false
            )
        }
    }
    fun currentFolder(): StorageNode? = findNode(_uiState.value.root, _uiState.value.currentFolderUri)

    fun showContainingFolder(node: StorageNode) {
        val root = _uiState.value.root ?: return
        val parent = findParent(root, node.uri) ?: return
        val path = findPath(root, parent.uri) ?: listOf(root.uri, parent.uri).distinct()
        _uiState.update { it.copy(currentFolderUri = parent.uri, pathStack = path, selected = emptySet()) }
    }
    fun visibleChildren(): List<StorageNode> = sortAndFilter(currentFolder()?.children.orEmpty(), true)
    fun heatmapFolders(): List<StorageNode> = currentFolder()?.children.orEmpty().filter { it.isDirectory }.sortedByDescending { it.totalSizeBytes }
    fun currentFiles(): List<StorageNode> = sortAndFilter(currentFolder()?.children.orEmpty().filter { !it.isDirectory }, false)

    private fun sortAndFilter(nodes: List<StorageNode>, includeFolders: Boolean): List<StorageNode> {
        val state = _uiState.value
        val filtered = nodes.filter { state.category == FileCategory.ALL || (includeFolders && it.isDirectory && state.category == FileCategory.FOLDER) || (!it.isDirectory && it.category == state.category) }
        return when (state.sortMode) {
            SortMode.SIZE_DESC -> filtered.sortedWith(compareByDescending<StorageNode> { it.totalSizeBytes }.thenBy { it.name.lowercase() })
            SortMode.NAME_ASC -> filtered.sortedBy { it.name.lowercase() }
            SortMode.MODIFIED_DESC -> filtered.sortedByDescending { it.lastModified }
        }
    }

    fun selectedNodes(): List<StorageNode> = _uiState.value.selected.mapNotNull { findNode(_uiState.value.root, it) }
    fun deleteNode(node: StorageNode) {
        viewModelScope.launch {
            _uiState.update { it.copy(deleting = true) }
            val deleted = repository.delete(node.uri)
            _uiState.update { it.copy(deleting = false, message = if (deleted) "Deleted ${node.name}." else "Could not delete ${node.name}.") }
            if (deleted) rescan()
        }
    }
    fun deleteSelected() {
        val targets = _uiState.value.selected.toList(); if (targets.isEmpty()) return
        viewModelScope.launch {
            _uiState.update { it.copy(deleting = true) }
            var deleted = 0; targets.forEach { if (repository.delete(it)) deleted++ }
            _uiState.update { it.copy(deleting = false, selected = emptySet(), message = "Deleted $deleted of ${targets.size} selected items.") }
            rescan()
        }
    }
    private fun findParent(node: StorageNode, target: Uri): StorageNode? {
        if (node.children.any { it.uri == target }) return node
        node.children.filter { it.isDirectory }.forEach { child ->
            findParent(child, target)?.let { return it }
        }
        return null
    }

    private fun findPath(node: StorageNode, target: Uri): List<Uri>? {
        if (node.uri == target) return listOf(node.uri)
        node.children.filter { it.isDirectory }.forEach { child ->
            findPath(child, target)?.let { return listOf(node.uri) + it }
        }
        return null
    }

    private fun findNode(node: StorageNode?, uri: Uri?): StorageNode? {
        if (node == null || uri == null) return null
        if (node.uri == uri) return node
        node.children.forEach { findNode(it, uri)?.let { found -> return found } }
        return null
    }
}
