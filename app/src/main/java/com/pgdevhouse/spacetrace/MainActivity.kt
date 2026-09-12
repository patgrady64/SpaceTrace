package com.pgdevhouse.spacetrace

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.core.content.FileProvider
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.pgdevhouse.spacetrace.model.FileCategory
import com.pgdevhouse.spacetrace.model.SortMode
import com.pgdevhouse.spacetrace.model.StorageLocation
import com.pgdevhouse.spacetrace.model.StorageNode
import com.pgdevhouse.spacetrace.model.formatBytes

class MainActivity : ComponentActivity() {
    private val viewModel: SpaceTraceViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SpaceTraceTheme { SpaceTraceApp(viewModel) } }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshAccess()
    }
}

private val colors = darkColorScheme(
    primary = Color(0xFFE5484D),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF5C2023),
    onPrimaryContainer = Color(0xFFFFDADB),
    background = Color(0xFF0D0E10),
    surface = Color(0xFF15171A),
    surfaceVariant = Color(0xFF202328),
    onBackground = Color(0xFFF2F3F5),
    onSurface = Color(0xFFF2F3F5),
    onSurfaceVariant = Color(0xFFB8BDC7),
    outline = Color(0xFF363A41),
    error = Color(0xFFFF6B6B)
)

@Composable
private fun SpaceTraceTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SpaceTraceApp(viewModel: SpaceTraceViewModel) {
    val state by viewModel.uiState.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var confirmDelete by remember { mutableStateOf(false) }
    var showTreemap by remember { mutableStateOf(true) }
    var treemapDetails by remember { mutableStateOf<StorageNode?>(null) }
    var fileManagerNode by remember { mutableStateOf<StorageNode?>(null) }
    var deleteNode by remember { mutableStateOf<StorageNode?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val openInFileManager: (StorageNode) -> Unit = { node ->
        val folderPath = if (node.isDirectory) node.displayPath else node.displayPath.substringBeforeLast('/', node.displayPath)
        val folderUri = externalStorageDocumentUriForPath(folderPath)
        if (folderUri == null) {
            viewModel.showMessage("This folder cannot be mapped to Android Files.")
        } else {
            val baseIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(folderUri, DocumentsContract.Document.MIME_TYPE_DIR)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            // Android has no public universal "browse this folder" intent. Try the two
            // common DocumentsUI package names first, then any third-party file manager.
            val candidates = listOf("com.google.android.documentsui", "com.android.documentsui")
            val launched = candidates.any { packageName ->
                runCatching {
                    context.startActivity(Intent(baseIntent).setPackage(packageName))
                    true
                }.getOrDefault(false)
            }
            if (!launched) {
                runCatching { context.startActivity(Intent.createChooser(baseIntent, "Open folder with")) }
                    .onFailure { viewModel.showMessage("No installed file manager can browse this folder. Use Show folder in SpaceTrace instead.") }
            }
        }
    }
    val openFile: (StorageNode) -> Unit = { node ->
        val file = File(node.displayPath)
        runCatching {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val mime = node.mimeType ?: context.contentResolver.getType(uri) ?: "*/*"
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Open file with"))
        }.onFailure {
            viewModel.showMessage("No installed app can open this file.")
        }
    }
    val settingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        viewModel.refreshAccess()
        viewModel.startSelectedScan()
    }
    val legacyPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        viewModel.refreshAccess()
        viewModel.startSelectedScan()
    }
    val requestAccess = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val appSettings = Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )
            val fallback = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
            settingsLauncher.launch(if (appSettings.resolveActivity(context.packageManager) != null) appSettings else fallback)
        } else {
            legacyPermissionLauncher.launch(
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            )
        }
    }

    BackHandler(enabled = state.scanStarted && !state.scanning) {
        if (state.pathStack.size > 1) viewModel.navigateUp()
        else viewModel.returnToStorageSelection()
    }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.dismissMessage()
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(38.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(11.dp)),
                        contentAlignment = Alignment.Center
                    ) { Text("S", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Black, fontSize = 20.sp) }
                    Column(Modifier.padding(start = 11.dp)) {
                        Text("SpaceTrace", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("Storage, explained.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (state.permissionGranted && state.scanStarted && !state.scanning) {
                    OutlinedButton(onClick = { viewModel.rescan() }, shape = RoundedCornerShape(10.dp)) { Text("Rescan") }
                }
            }

            if (!state.scanStarted) {
                StorageSelectionState(
                    locations = state.locations,
                    selectedPaths = state.selectedLocationPaths,
                    permissionGranted = state.permissionGranted,
                    onToggle = viewModel::toggleScanLocation,
                    onGrant = requestAccess,
                    onStart = viewModel::startSelectedScan
                )
            } else if (!state.permissionGranted) {
                PermissionState(onGrant = requestAccess)
            } else if (state.scanning) {
                ScanState(
                    files = state.progress.filesVisited,
                    folders = state.progress.foldersVisited,
                    bytes = state.progress.bytesFound,
                    path = state.progress.currentPath,
                    startedAtMs = state.scanStartedAtMs,
                    expectedUsedBytes = state.capacity?.usedBytes,
                    onCancel = viewModel::cancelScan
                )
            } else {
                state.root?.let { root ->
                    CapacityCard(root, state.capacity?.totalBytes, state.capacity?.freeBytes)
                    Spacer(Modifier.height(10.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            viewModel.currentFolder()?.displayPath.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)
                        )
                    }
                    ScanSummary(state.lastScanFiles, state.lastScanFolders, root.totalSizeBytes, state.lastScanDurationMs)
                    OutlinedTextField(
                        value = state.searchQuery,
                        onValueChange = viewModel::setSearchQuery,
                        label = { Text("Search scanned files") },
                        placeholder = { Text("FLAC, backup, IMG_, video…") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                    ViewModeRow(showTreemap = showTreemap, onChange = { showTreemap = it })
                    if (!showTreemap && state.searchQuery.isBlank()) {
                        FilterRow(state.category, viewModel::setCategory)
                        SortRow(state.sortMode, viewModel::setSortMode)
                    }

                    if (state.selected.isNotEmpty() && !showTreemap) {
                        SelectionBar(
                            count = state.selected.size,
                            busy = state.deleting,
                            onClear = viewModel::clearSelection,
                            onDelete = { confirmDelete = true }
                        )
                    }

                    if (state.searchQuery.isNotBlank()) {
                        SectionHeader("SEARCH RESULTS")
                        val results = viewModel.searchResults()
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                            items(results, key = { it.uri.toString() }) { node ->
                                StorageRow(node, false, false, onOpen = { if (node.isDirectory) viewModel.openFolder(node.uri) else treemapDetails = node }, onSelect = { fileManagerNode = node })
                            }
                            if (results.isEmpty()) item { Text("No scanned items match this search.", modifier = Modifier.padding(vertical = 20.dp)) }
                        }
                    } else if (showTreemap) {
                        if (state.pathStack.size > 1) {
                            OutlinedButton(onClick = { viewModel.navigateUp() }, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                                Text("‹  Parent folder")
                            }
                        }
                        SectionHeader("FOLDERS")
                        val folders = viewModel.heatmapFolders()
                        if (folders.isEmpty()) {
                            Text("No subfolders", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 12.dp))
                        } else {
                            TreemapView(
                                nodes = folders,
                                height = if (state.pathStack.size > 1) 140.dp else 280.dp,
                                onNodeTap = { viewModel.openFolder(it.uri) },
                                onNodeLongPress = { fileManagerNode = it }
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        SectionHeader("FILES IN THIS FOLDER")
                        val files = viewModel.currentFiles()
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                            items(files, key = { it.uri.toString() }) { node ->
                                StorageRow(
                                    node = node,
                                    selected = false,
                                    selectionActive = false,
                                    onOpen = { treemapDetails = node },
                                    onSelect = { fileManagerNode = node }
                                )
                            }
                            if (files.isEmpty()) item { Text("No files in this folder.", modifier = Modifier.padding(vertical = 16.dp)) }
                        }
                    } else {
                        val visible = viewModel.visibleChildren()
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (state.pathStack.size > 1) {
                                item { OutlinedButton(onClick = { viewModel.navigateUp() }, modifier = Modifier.fillMaxWidth()) { Text("‹  Parent folder") } }
                            }
                            items(visible, key = { it.uri.toString() }) { node ->
                                StorageRow(
                                    node = node,
                                    selected = node.uri in state.selected,
                                    selectionActive = state.selected.isNotEmpty(),
                                    onOpen = { if (node.isDirectory) viewModel.openFolder(node.uri) else viewModel.toggleSelected(node.uri) },
                                    onSelect = { viewModel.toggleSelected(node.uri) }
                                )
                            }
                            if (visible.isEmpty()) item { Text("No items match this filter.", modifier = Modifier.padding(vertical = 24.dp)) }
                        }
                    }
                }
                if (state.root == null) Text("No readable storage volume was found.", modifier = Modifier.padding(vertical = 24.dp))
            }
        }
    }

    fileManagerNode?.let { node ->
        AlertDialog(
            onDismissRequest = { fileManagerNode = null },
            title = { Text(node.name) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (node.isDirectory) {
                        TextButton(onClick = { fileManagerNode = null; viewModel.openFolder(node.uri) }) { Text("Open in SpaceTrace") }
                        TextButton(onClick = { fileManagerNode = null; openInFileManager(node) }) { Text("Open in file manager") }
                        TextButton(onClick = { fileManagerNode = null; deleteNode = node }) { Text("Delete folder") }
                    } else {
                        TextButton(onClick = { fileManagerNode = null; openFile(node) }) { Text("Open file") }
                        TextButton(onClick = { fileManagerNode = null; openInFileManager(node) }) { Text("Open containing folder") }
                        TextButton(onClick = { fileManagerNode = null; viewModel.showContainingFolder(node) }) { Text("Show folder in SpaceTrace") }
                        TextButton(onClick = { fileManagerNode = null; deleteNode = node }) { Text("Delete file") }
                    }
                    TextButton(onClick = { fileManagerNode = null; treemapDetails = node }) { Text("Details") }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { fileManagerNode = null }) { Text("Cancel") } }
        )
    }

    deleteNode?.let { node ->
        AlertDialog(
            onDismissRequest = { deleteNode = null },
            title = { Text(if (node.isDirectory) "Delete folder?" else "Delete file?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(node.name, fontWeight = FontWeight.Bold)
                    Text(node.totalSizeBytes.formatBytes())
                    if (node.isDirectory) Text("This permanently deletes the folder and everything inside it (${node.directContentsLabel}).")
                    else Text("This file may not be recoverable after deletion.")
                    Text(node.displayPath, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                Button(onClick = { deleteNode = null; viewModel.deleteNode(node) }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleteNode = null }) { Text("Cancel") } }
        )
    }

    treemapDetails?.let { node ->
        AlertDialog(
            onDismissRequest = { treemapDetails = null },
            title = { Text(node.name) },
            text = {
                val parentSize = findParentSize(state.root, node.uri)
                val percentOfParent = if (parentSize != null && parentSize > 0L) node.totalSizeBytes * 100.0 / parentSize else null
                val recursive = recursiveCounts(node)
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    DetailLine("Type", if (node.isDirectory) "Folder" else node.category.label.removeSuffix("s"))
                    DetailLine("Size", "${node.totalSizeBytes.formatBytes()} (${formatNumber(node.totalSizeBytes)} bytes)")
                    if (!node.isDirectory && node.ownSizeBytes != node.totalSizeBytes)
                        DetailLine("File size", "${node.ownSizeBytes.formatBytes()} (${formatNumber(node.ownSizeBytes)} bytes)")
                    if (percentOfParent != null) DetailLine("Share of parent", "%.2f%%".format(percentOfParent))
                    if (node.isDirectory) {
                        DetailLine("Direct contents", node.directContentsLabel)
                        DetailLine("Total contents", "${recursive.first} ${if (recursive.first == 1) "file" else "files"} • ${recursive.second} ${if (recursive.second == 1) "folder" else "folders"}")
                    } else {
                        val extension = node.name.substringAfterLast('.', "").ifBlank { "None" }
                        DetailLine("Extension", if (extension == "None") extension else ".${extension.lowercase()}")
                        DetailLine("MIME type", node.mimeType ?: "Unknown")
                    }
                    DetailLine("Modified", formatTimestamp(node.lastModified))
                    DetailLine("Name", node.name)
                    DetailLine("Path", node.displayPath)
                    DetailLine("URI", node.uri.toString())
                    DetailLine("Readable", if (File(node.displayPath).canRead()) "Yes" else "No")
                    DetailLine("Writable", if (File(node.displayPath).canWrite()) "Yes" else "No")
                }
            },
            confirmButton = { TextButton(onClick = { treemapDetails = null }) { Text("Close") } }
        )
    }

    if (confirmDelete) {
        val selectedNodes = viewModel.selectedNodes()
        val selectedFolders = selectedNodes.count { it.isDirectory }
        val pathPreview = selectedNodes.take(3).joinToString("\n") { it.displayPath }
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${state.selected.size} items?") },
            text = {
                Text(
                    buildString {
                        if (selectedFolders > 0) {
                            append("Warning: $selectedFolders selected folder(s) and everything inside them will be deleted.\n\n")
                        }
                        append(pathPreview)
                        if (selectedNodes.size > 3) append("\n…and ${selectedNodes.size - 3} more")
                        append("\n\nDeleted items may not be recoverable.")
                    }
                )
            },
            confirmButton = {
                Button(onClick = {
                    confirmDelete = false
                    viewModel.deleteSelected()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
        )
    }
}

private fun recursiveCounts(node: StorageNode): Pair<Int, Int> {
    var files = 0
    var folders = 0
    node.children.forEach { child ->
        if (child.isDirectory) {
            folders++
            val nested = recursiveCounts(child)
            files += nested.first
            folders += nested.second
        } else files++
    }
    return files to folders
}

private fun findParentSize(root: StorageNode?, target: Uri): Long? {
    if (root == null) return null
    if (root.children.any { it.uri == target }) return root.totalSizeBytes
    root.children.filter { it.isDirectory }.forEach { child ->
        findParentSize(child, target)?.let { return it }
    }
    return null
}

private fun formatTimestamp(value: Long): String {
    if (value <= 0L) return "Unknown"
    return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM).format(Date(value))
}

private fun formatNumber(value: Long): String = String.format(Locale.getDefault(), "%,d", value)

@Composable
private fun DetailLine(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun StorageSelectionState(
    locations: List<StorageLocation>,
    selectedPaths: Set<String>,
    permissionGranted: Boolean,
    onToggle: (StorageLocation) -> Unit,
    onGrant: () -> Unit,
    onStart: () -> Unit
) {
    val internal = locations.firstOrNull { !it.removable }
    val sd = locations.firstOrNull { it.removable }
    Column(
        Modifier.fillMaxSize().padding(top = 34.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Choose storage", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Select one or both locations to analyze.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 5.dp, bottom = 24.dp))
        StorageChoice("Internal storage", "Photos, downloads and shared files", internal, selectedPaths, onToggle)
        Spacer(Modifier.height(10.dp))
        StorageChoice("SD card", if (sd == null) "Not detected" else sd.name, sd, selectedPaths, onToggle)
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = { if (permissionGranted) onStart() else onGrant() },
            enabled = selectedPaths.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = RoundedCornerShape(12.dp)
        ) { Text(if (permissionGranted) "Scan selected storage" else "Grant access & continue", fontWeight = FontWeight.SemiBold) }
        if (!permissionGranted) {
            Text(
                "SpaceTrace needs Android's all-files access to measure storage accurately.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp, start = 8.dp, end = 8.dp)
            )
        }
    }
}

@Composable
private fun StorageChoice(
    title: String,
    subtitle: String,
    location: StorageLocation?,
    selectedPaths: Set<String>,
    onToggle: (StorageLocation) -> Unit
) {
    val selected = location?.path in selectedPaths
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = .12f) else MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary.copy(alpha = .65f) else MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth().combinedClickable(
            enabled = location != null,
            onClick = { if (location != null) onToggle(location) },
            onLongClick = {}
        )
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(38.dp).background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) { Text(if (location?.removable == true) "SD" else "IN", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium) }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Checkbox(checked = selected, onCheckedChange = { if (location != null) onToggle(location) }, enabled = location != null)
        }
    }
}

@Composable
private fun PermissionState(onGrant: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Storage access is required", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text("SpaceTrace needs Android's Manage all files access to measure the complete SD card and show the paths behind its used space.")
                Spacer(Modifier.height(16.dp))
                Button(onClick = onGrant) { Text("Grant storage access") }
            }
        }
    }
}

@Composable
private fun LocationRow(
    locations: List<StorageLocation>,
    active: StorageLocation?,
    onSelected: (StorageLocation) -> Unit
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        locations.forEach { location ->
            FilterChip(
                selected = active?.path == location.path,
                onClick = { onSelected(location) },
                label = { Text(if (location.removable) "SD card • ${location.name}" else location.name) }
            )
        }
    }
}

@Composable
private fun ScanState(files: Int, folders: Int, bytes: Long, path: String, startedAtMs: Long, expectedUsedBytes: Long?, onCancel: () -> Unit) {
    val elapsedMs = (System.currentTimeMillis() - startedAtMs).coerceAtLeast(1L)
    val progress = if (expectedUsedBytes != null && expectedUsedBytes > 0) (bytes.toFloat() / expectedUsedBytes).coerceIn(0f, .99f) else null
    val rate = bytes.toDouble() / (elapsedMs / 1000.0)
    val etaSeconds = if (progress != null && elapsedMs >= 3000 && rate > 0 && expectedUsedBytes!! > bytes) ((expectedUsedBytes - bytes) / rate).toLong() else null
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text("Tracing storage…", fontWeight = FontWeight.Bold)
            Text("$files files • $folders folders • ${bytes.formatBytes()}")
            if (progress != null) {
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                Text("~${(progress * 100).toInt()}% analyzed", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (etaSeconds != null) Text("About ${formatDuration(etaSeconds * 1000)} remaining", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
            else Text("Estimating time remaining…", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            Text(path, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = onCancel) { Text("Cancel scan") }
        }
    }
}

@Composable
private fun ScanSummary(files: Int, folders: Int, bytes: Long, durationMs: Long) {
    if (durationMs <= 0L) return
    Text(
        "${bytes.formatBytes()} scanned • ${formatNumber(files.toLong())} files • ${formatNumber(folders.toLong())} folders • ${formatDuration(durationMs)}",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 7.dp)
    )
}

private fun formatDuration(ms: Long): String {
    val seconds = (ms / 1000).coerceAtLeast(0)
    return when {
        seconds < 60 -> "${seconds}s"
        seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
        else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
    }
}

@Composable
private fun CapacityCard(root: StorageNode, total: Long?, free: Long?) {
    val used = if (total != null && free != null) (total - free).coerceAtLeast(0L) else null
    val ratio = if (used != null && total != null && total > 0L) (used.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(15.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text(root.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Text("${root.totalSizeBytes.formatBytes()} accessible files scanned", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (used != null) Text(used.formatBytes(), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            }
            if (total != null && free != null) {
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(progress = { ratio }, modifier = Modifier.fillMaxWidth().height(6.dp), trackColor = MaterialTheme.colorScheme.surfaceVariant)
                Row(Modifier.fillMaxWidth().padding(top = 7.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${free.formatBytes()} free", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${total.formatBytes()} total", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun FilterRow(selected: FileCategory, onSelected: (FileCategory) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FileCategory.entries.forEach { category ->
            FilterChip(selected = selected == category, onClick = { onSelected(category) }, label = { Text(category.label) })
        }
    }
}

@Composable
private fun ViewModeRow(showTreemap: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(if (showTreemap) "Heatmap visible" else "Heatmap hidden", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        FilterChip(
            selected = showTreemap,
            onClick = { onChange(!showTreemap) },
            label = { Text(if (showTreemap) "Hide" else "Show") }
        )
    }
}

private data class TreemapRect(val node: StorageNode, val x: Float, val y: Float, val width: Float, val height: Float)

private fun treemapRects(nodes: List<StorageNode>, width: Float, height: Float): List<TreemapRect> {
    val positive = nodes.filter { it.totalSizeBytes > 0 }.sortedByDescending { it.totalSizeBytes }
    if (positive.isEmpty() || width <= 0f || height <= 0f) return emptyList()
    val total = positive.sumOf { it.totalSizeBytes }.toDouble()
    val result = mutableListOf<TreemapRect>()
    var x = 0f
    var y = 0f
    var w = width
    var h = height
    var remaining = total
    positive.forEachIndexed { index, node ->
        val last = index == positive.lastIndex
        val fraction = if (last || remaining <= 0.0) 1f else (node.totalSizeBytes / remaining).toFloat().coerceIn(0f, 1f)
        if (w >= h) {
            val slice = if (last) w else w * fraction
            result += TreemapRect(node, x, y, slice, h)
            x += slice
            w = (w - slice).coerceAtLeast(0f)
        } else {
            val slice = if (last) h else h * fraction
            result += TreemapRect(node, x, y, w, slice)
            y += slice
            h = (h - slice).coerceAtLeast(0f)
        }
        remaining -= node.totalSizeBytes.toDouble()
    }
    return result
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun TreemapView(
    nodes: List<StorageNode>,
    height: androidx.compose.ui.unit.Dp,
    onNodeTap: (StorageNode) -> Unit,
    onNodeLongPress: (StorageNode) -> Unit
) {
    if (nodes.none { it.totalSizeBytes > 0 }) {
        Text("No sized items to map.", modifier = Modifier.padding(vertical = 24.dp))
        return
    }
    BoxWithConstraints(Modifier.fillMaxWidth().height(height)) {
        val rects = remember(nodes, maxWidth, maxHeight) {
            treemapRects(nodes, maxWidth.value, maxHeight.value)
        }
        rects.forEachIndexed { index, rect ->
            val shade = folderCategoryColor(rect.node)
            Surface(
                color = shade,
                shape = RoundedCornerShape(7.dp),
                modifier = Modifier
                    .offset(x = rect.x.dp, y = rect.y.dp)
                    .width(rect.width.dp.coerceAtLeast(1.dp))
                    .height(rect.height.dp.coerceAtLeast(1.dp))
                    .padding(1.dp)
                    .combinedClickable(onClick = { onNodeTap(rect.node) }, onLongClick = { onNodeLongPress(rect.node) })
            ) {
                if (rect.width >= 54f && rect.height >= 34f) {
                    Column(Modifier.padding(5.dp)) {
                        Text(rect.node.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                        if (rect.height >= 50f) Text(rect.node.totalSizeBytes.formatBytes(), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
    Text("Block size = storage used • Color = dominant file type • Tap to drill down", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 7.dp, bottom = 3.dp))
    Text("● Audio   ● Video   ● Images   ● Apps   ● Docs   ● Archives   ● Mixed", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun folderCategoryColor(node: StorageNode): Color {
    val bytes = mutableMapOf<FileCategory, Long>()
    fun collect(n: StorageNode) {
        if (n.isDirectory) n.children.forEach(::collect) else bytes[n.category] = (bytes[n.category] ?: 0L) + n.totalSizeBytes
    }
    collect(node)
    val total = bytes.values.sum()
    val dominant = bytes.maxByOrNull { it.value }
    val category = if (dominant != null && total > 0 && dominant.value.toDouble() / total >= .70) dominant.key else FileCategory.OTHER
    return when (category) {
        FileCategory.AUDIO -> Color(0xFF3976D6)
        FileCategory.VIDEO -> Color(0xFFE07A32)
        FileCategory.IMAGE -> Color(0xFF8B5BD6)
        FileCategory.APK -> Color(0xFF3A9B63)
        FileCategory.DOCUMENT -> Color(0xFFD3A52D)
        FileCategory.ARCHIVE -> Color(0xFF2A9AA0)
        else -> Color(0xFF555B66)
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        letterSpacing = 0.8.sp,
        modifier = Modifier.padding(top = 2.dp, bottom = 7.dp)
    )
}

@Composable
private fun SortRow(selected: SortMode, onSelected: (SortMode) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        SortMode.entries.forEach { sort ->
            FilterChip(selected = selected == sort, onClick = { onSelected(sort) }, label = { Text(sort.label) })
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun StorageRow(
    node: StorageNode,
    selected: Boolean,
    selectionActive: Boolean,
    onOpen: () -> Unit,
    onSelect: () -> Unit
) {
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = .17f) else MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth().combinedClickable(onClick = {
            if (selectionActive) onSelect() else onOpen()
        }, onLongClick = onSelect)
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(34.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(9.dp)),
                contentAlignment = Alignment.Center
            ) { Text(if (node.isDirectory) "D" else "F", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium) }
            Column(Modifier.weight(1f).padding(horizontal = 11.dp)) {
                Text(node.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (node.isDirectory) node.directContentsLabel else node.category.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(node.displayPath, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .72f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(node.totalSizeBytes.formatBytes(), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
            if (selectionActive) Checkbox(checked = selected, onCheckedChange = { onSelect() })
        }
    }
}

private fun externalStorageDocumentUriForPath(path: String): Uri? {
    val normalized = path.replace('\\', '/').trimEnd('/')
    if (!normalized.startsWith("/storage/")) return null
    val rest = normalized.removePrefix("/storage/")
    val volume = rest.substringBefore('/')
    val relative = rest.substringAfter('/', "")
    val documentVolume = if (volume.equals("emulated", ignoreCase = true)) {
        // /storage/emulated/0/... is Android's primary shared storage.
        if (relative == "0") return DocumentsContract.buildDocumentUri(
            "com.android.externalstorage.documents", "primary:"
        )
        if (!relative.startsWith("0/")) return null
        "primary"
    } else volume
    val documentRelative = if (volume.equals("emulated", ignoreCase = true)) relative.removePrefix("0/") else relative
    val documentId = "$documentVolume:$documentRelative"
    return DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", documentId)
}

@Composable
private fun SelectionBar(count: Int, busy: Boolean, onClear: () -> Unit, onDelete: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp)).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text("$count selected", fontWeight = FontWeight.Bold)
        Row {
            TextButton(onClick = onClear, enabled = !busy) { Text("Clear") }
            Button(onClick = onDelete, enabled = !busy) { Text(if (busy) "Deleting…" else "Delete") }
        }
    }
}
