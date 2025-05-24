package com.tiddlywikibrowser.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Offset
import androidx.activity.ComponentActivity
import android.content.Intent
import android.widget.Toast
import com.tiddlywikibrowser.MainActivity
import com.tiddlywikibrowser.R
import com.tiddlywikibrowser.WikiViewModel
import com.tiddlywikibrowser.WikiInstance
import com.tiddlywikibrowser.ui.dialogs.*
import com.tiddlywikibrowser.WikiViewComposable
import com.tiddlywikibrowser.TiddlerTransferManager
import com.tiddlywikibrowser.webview.ReloadBlockingWebViewClient
import kotlin.math.absoluteValue
import kotlin.math.roundToInt
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: WikiViewModel,
    onAddClick: () -> Unit,
    onShowRenameDialog: () -> Unit
) {
    val context = LocalContext.current
    val currentWiki by viewModel.currentWiki.collectAsState()
    val wikis by viewModel.allWikis.collectAsState()
    val isDarkMode by viewModel.isDarkMode.collectAsState()
    val isFrameVisible by viewModel.isFrameVisible.collectAsState()

    var showMenu by remember { mutableStateOf(false) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var showShareMenu by remember { mutableStateOf(false) }
    var showTagManagement by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    var draggedWiki by remember { mutableStateOf<WikiInstance?>(null) }
    var isDragging by remember { mutableStateOf(false) }
    var dragOffset by remember { mutableStateOf(0f) }
    var showTrashCan by remember { mutableStateOf(false) }
    val trashCanAlpha by animateFloatAsState(if (showTrashCan) 1f else 0f)
    val dragStartTime = remember { mutableStateOf(0L) }
    val holdThreshold = 500L // 500ms hold time to show trash can
    var wikiToDelete by remember { mutableStateOf<WikiInstance?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            AnimatedVisibility(
                visible = isFrameVisible,
                enter = slideInVertically(
                    initialOffsetY = { -it },
                    animationSpec = tween(
                        durationMillis = 200,
                        easing = FastOutSlowInEasing
                    )
                ),
                exit = slideOutVertically(
                    targetOffsetY = { -it },
                    animationSpec = tween(
                        durationMillis = 200,
                        easing = FastOutLinearInEasing
                    )
                )
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 3.dp,
                    shadowElevation = 3.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    TopAppBar(
                        title = { Text(currentWiki?.name ?: "TiddlyWiki Browser") },
                        actions = {
                            TopAppBarActions(
                                viewModel = viewModel,
                                currentWiki = currentWiki,
                                showShareMenu = showShareMenu,
                                onShareMenuChange = { showShareMenu = it },
                                showMenu = showMenu,
                                onMenuChange = { showMenu = it },
                                onAddClick = onAddClick,
                                onShowRenameDialog = onShowRenameDialog,
                                onShowSettings = { showSettings = true },
                                onShowDeleteConfirm = { showDeleteConfirmDialog = true }
                            )
                        }
                    )
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                currentWiki?.let { wiki ->
                    WikiViewComposable(wiki = wiki, viewModel = viewModel)
                } ?: Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Add your first TiddlyWiki using the menu button",
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }

            AnimatedVisibility(
                visible = isFrameVisible,
                enter = slideInVertically(
                    initialOffsetY = { it },
                    animationSpec = tween(
                        durationMillis = 200,
                        easing = FastOutSlowInEasing
                    )
                ),
                exit = slideOutVertically(
                    targetOffsetY = { it },
                    animationSpec = tween(
                        durationMillis = 200,
                        easing = FastOutLinearInEasing
                    )
                )
            ) {
                BottomNavigationBar(
                    wikis = wikis,
                    currentWiki = currentWiki,
                    viewModel = viewModel,
                    onAddClick = onAddClick,
                    draggedWiki = draggedWiki,
                    isDragging = isDragging,
                    dragOffset = dragOffset,
                    showTrashCan = showTrashCan,
                    dragStartTime = dragStartTime,
                    holdThreshold = holdThreshold,
                    onDragStart = { wiki ->
                        dragStartTime.value = System.currentTimeMillis()
                        draggedWiki = wiki
                        isDragging = true
                    },
                    onDrag = { offsetX ->
                        dragOffset = offsetX
                        if (System.currentTimeMillis() - dragStartTime.value > holdThreshold) {
                            showTrashCan = true
                        }
                    },
                    onDragEnd = { offsetX, index ->
                        if (showTrashCan && dragOffset.absoluteValue < 100) {
                            wikiToDelete = draggedWiki
                            showDeleteConfirmDialog = true
                        } else {
                            val itemWidth = 100f // Approximate item width
                            val newPosition = (dragOffset / itemWidth).roundToInt()
                            if (newPosition != 0) {
                                val targetIndex = (index + newPosition).coerceIn(0, wikis.size - 1)
                                if (targetIndex != index) {
                                    viewModel.reorderWikis(index, targetIndex)
                                }
                            }
                        }
                        draggedWiki = null
                        isDragging = false
                        dragOffset = 0f
                        showTrashCan = false
                    }
                )
            }
        }

        // Handle dialogs
        if (showDeleteConfirmDialog && (wikiToDelete != null || currentWiki != null)) {
            val wiki = wikiToDelete ?: currentWiki
            DeleteWikiConfirmDialog(
                wikiName = wiki?.name ?: "",
                onConfirm = {
                    wiki?.let { viewModel.deleteWiki(it) }
                    showDeleteConfirmDialog = false
                    wikiToDelete = null
                },
                onDismiss = {
                    showDeleteConfirmDialog = false
                    wikiToDelete = null
                }
            )
        }

        if (showTagManagement) {
            TagManagementDialog(
                tags = viewModel.quickTags.collectAsState().value,
                onAddTag = { viewModel.addQuickTag(it) },
                onRemoveTag = { viewModel.removeQuickTag(it) },
                onReorderTags = { from, to -> viewModel.reorderQuickTags(from, to) },
                onDismiss = { showTagManagement = false }
            )
        }

        if (showSettings) {
            SettingsDialog(
                isDarkMode = isDarkMode,
                onDarkModeChange = { newMode ->
                    viewModel.setDarkMode(newMode)
                },
                onManageQuickTags = {
                    showTagManagement = true
                },
                onDismiss = {
                    showSettings = false
                }
            )
        }
    }
}

@Composable
private fun TopAppBarActions(
    viewModel: WikiViewModel,
    currentWiki: WikiInstance?,
    showShareMenu: Boolean,
    onShareMenuChange: (Boolean) -> Unit,
    showMenu: Boolean,
    onMenuChange: (Boolean) -> Unit,
    onAddClick: () -> Unit,
    onShowRenameDialog: () -> Unit,
    onShowSettings: () -> Unit,
    onShowDeleteConfirm: () -> Unit
) {
    val context = LocalContext.current
    val isOffline by viewModel.isOffline.collectAsState()
    val isLocalFile = currentWiki?.isLocalFile ?: false

    // Offline indicator
    if (isOffline && !isLocalFile) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(end = 8.dp)
        ) {
            Icon(
                Icons.Default.CloudOff,
                contentDescription = "Offline Mode",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp)
            )
            Text(
                "Offline",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 4.dp)
            )
        }
    }

    // Share button
    IconButton(onClick = { onShareMenuChange(true) }) {
        Icon(Icons.Default.Share, contentDescription = "Share")
    }

    // Share menu
    ShareMenu(
        expanded = showShareMenu,
        onDismiss = { onShareMenuChange(false) },
        onShareCurrentTiddler = {
            onShareMenuChange(false)
            shareCurrentTiddler(context)
        },
        onShareCurrentUrl = {
            onShareMenuChange(false)
            shareCurrentUrl(context)
        }
    )

    // More options menu
    IconButton(onClick = { onMenuChange(true) }) {
        Icon(Icons.Default.MoreVert, contentDescription = "More options")
    }
    
    MoreOptionsMenu(
        expanded = showMenu,
        onDismiss = { onMenuChange(false) },
        currentWiki = currentWiki,
        viewModel = viewModel,
        onAddClick = onAddClick,
        onShowRenameDialog = onShowRenameDialog,
        onShowSettings = onShowSettings,
        onShowDeleteConfirm = onShowDeleteConfirm
    )
}

@Composable
private fun ShareMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onShareCurrentTiddler: () -> Unit,
    onShareCurrentUrl: () -> Unit
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss
    ) {
        DropdownMenuItem(
            text = { Text("Share Current Tiddler") },
            onClick = onShareCurrentTiddler
        )
        DropdownMenuItem(
            text = { Text("Share Current URL") },
            onClick = onShareCurrentUrl
        )
    }
}

@Composable
private fun MoreOptionsMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    currentWiki: WikiInstance?,
    viewModel: WikiViewModel,
    onAddClick: () -> Unit,
    onShowRenameDialog: () -> Unit,
    onShowSettings: () -> Unit,
    onShowDeleteConfirm: () -> Unit
) {
    val context = LocalContext.current
    val isOffline by viewModel.isOffline.collectAsState()
    val isLocalFile = currentWiki?.isLocalFile ?: false

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss
    ) {
        if (currentWiki != null) {
            RefreshMenuItems(
                onDismiss = onDismiss,
                currentWiki = currentWiki,
                viewModel = viewModel,
                isOffline = isOffline,
                isLocalFile = isLocalFile
            )

            DropdownMenuItem(
                text = { Text("Rename Wiki") },
                onClick = {
                    onDismiss()
                    onShowRenameDialog()
                }
            )

            DropdownMenuItem(
                text = { Text("Transfer Tiddlers") },
                onClick = {
                    onDismiss()
                    TiddlerTransferManager.initiateTransfer(
                        context as MainActivity,
                        currentWiki,
                        viewModel
                    )
                },
                enabled = currentWiki != null
            )
        }

        DropdownMenuItem(
            text = { Text("Add new wiki") },
            onClick = {
                onDismiss()
                onAddClick()
            }
        )

        DropdownMenuItem(
            text = { Text("Settings") },
            onClick = {
                onDismiss()
                onShowSettings()
            }
        )

        if (currentWiki != null) {
            DropdownMenuItem(
                text = { Text("Delete this Wiki", color = MaterialTheme.colorScheme.error) },
                onClick = {
                    onDismiss()
                    onShowDeleteConfirm()
                }
            )
        }
    }
}

@Composable
private fun RefreshMenuItems(
    onDismiss: () -> Unit,
    currentWiki: WikiInstance,
    viewModel: WikiViewModel,
    isOffline: Boolean,
    isLocalFile: Boolean
) {
    val context = LocalContext.current

    DropdownMenuItem(
        text = { Text("Refresh") },
        onClick = {
            onDismiss()
            refreshWiki(context, viewModel, currentWiki)
        }
    )

    if (!isLocalFile) {
        DropdownMenuItem(
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (isOffline) "Force Online Refresh" else "Refresh from Network",
                        color = if (isOffline) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                    )
                    if (isOffline) {
                        Icon(
                            Icons.Default.CloudOff,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier
                                .padding(start = 4.dp)
                                .size(16.dp)
                        )
                    }
                }
            },
            onClick = {
                onDismiss()
                forceOnlineRefresh(context, viewModel, currentWiki)
            }
        )
    }
}

@Composable
private fun BottomNavigationBar(
    wikis: List<WikiInstance>,
    currentWiki: WikiInstance?,
    viewModel: WikiViewModel,
    onAddClick: () -> Unit,
    draggedWiki: WikiInstance?,
    isDragging: Boolean,
    dragOffset: Float,
    showTrashCan: Boolean,
    dragStartTime: MutableState<Long>,
    holdThreshold: Long,
    onDragStart: (WikiInstance) -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: (Float, Int) -> Unit
) {
    NavigationBar {
        if (wikis.isEmpty()) {
            NavigationBarItem(
                icon = { Icon(Icons.Default.Add, contentDescription = "Add Wiki") },
                label = { Text("Add Wiki") },
                selected = false,
                onClick = onAddClick
            )
        } else {
            wikis.forEachIndexed { index, wiki ->
                val isSelected = wiki == currentWiki
                var offsetX by remember { mutableStateOf(0f) }

                NavigationBarItem(
                    icon = {
                        DraggableWikiIcon(
                            wiki = wiki,
                            viewModel = viewModel,
                            isDragging = isDragging && draggedWiki == wiki,
                            offsetX = offsetX,
                            onDragStart = { onDragStart(wiki) },
                            onDrag = { dragAmount ->
                                offsetX += dragAmount.x
                                onDrag(offsetX)
                            },
                            onDragEnd = {
                                onDragEnd(offsetX, index)
                                offsetX = 0f
                            }
                        )
                    },
                    label = { Text(wiki.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    selected = isSelected,
                    onClick = { viewModel.setCurrentWiki(wiki) }
                )
            }
        }
    }
}

@Composable
private fun DraggableWikiIcon(
    wiki: WikiInstance,
    viewModel: WikiViewModel,
    isDragging: Boolean,
    offsetX: Float,
    onDragStart: () -> Unit,
    onDrag: (dragAmount: Offset) -> Unit,
    onDragEnd: () -> Unit
) {
    val faviconMap by viewModel.faviconMap.collectAsState()
    val favicon = faviconMap[wiki.url]
    
    val modifier = Modifier
        .pointerInput(Unit) {
            detectDragGestures(
                onDragStart = { onDragStart() },
                onDrag = { _, dragAmount -> onDrag(dragAmount) },
                onDragEnd = { onDragEnd() }
            )
        }
        .offset { IntOffset(offsetX.roundToInt(), 0) }
        .scale(if (isDragging) 1.1f else 1f)

    if (favicon != null) {
        Image(
            bitmap = favicon.asImageBitmap(),
            contentDescription = wiki.name,
            modifier = modifier.size(24.dp)
        )
    } else {
        Icon(
            Icons.Default.Book,
            contentDescription = wiki.name,
            modifier = modifier
        )
    }
}

// Helper functions
private fun shareCurrentTiddler(context: android.content.Context) {
    val activity = context as? MainActivity
    activity?.getCurrentWebView()?.evaluateJavascript("""
        (function() {
            var currentTiddler = document.querySelector(".tc-tiddler-frame:not(.tc-tiddler-preview)");
            if (currentTiddler) {
                var title = currentTiddler.querySelector(".tc-tiddler-title");
                var content = currentTiddler.querySelector(".tc-tiddler-body");
                if (title && content) {
                    return JSON.stringify({
                        title: title.textContent.trim(),
                        content: content.textContent.trim()
                    });
                }
            }
            return null;
        })();
    """.trimIndent()) { result ->
        if (result != "null") {
            try {
                val tiddler = JSONObject(result.trim('"').replace("\\\"", "\""))
                val shareIntent = Intent().apply {
                    action = Intent.ACTION_SEND
                    putExtra(Intent.EXTRA_TITLE, tiddler.getString("title"))
                    putExtra(Intent.EXTRA_TEXT, tiddler.getString("content"))
                    type = "text/plain"
                }
                context.startActivity(Intent.createChooser(shareIntent, "Share Tiddler"))
            } catch (e: Exception) {
                Toast.makeText(context, "Failed to share tiddler", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

private fun shareCurrentUrl(context: android.content.Context) {
    val activity = context as? MainActivity
    activity?.getCurrentWebView()?.let { webView ->
        val currentUrl = webView.url
        if (currentUrl != null) {
            val shareIntent = Intent().apply {
                action = Intent.ACTION_SEND
                putExtra(Intent.EXTRA_TEXT, currentUrl)
                type = "text/plain"
            }
            context.startActivity(Intent.createChooser(shareIntent, "Share URL"))
        }
    }
}

private fun refreshWiki(context: android.content.Context, viewModel: WikiViewModel, wiki: WikiInstance) {
    val webView = viewModel.getOrCreateWebView(wiki, context)
    val client = webView.webViewClient as? ReloadBlockingWebViewClient
    if (client != null) {
        client.forceReload(webView, wiki.url)
    } else {
        webView.loadUrl(wiki.url)
    }
}

private fun forceOnlineRefresh(context: android.content.Context, viewModel: WikiViewModel, wiki: WikiInstance) {
    val webView = viewModel.getOrCreateWebView(wiki, context)
    
    // First, update cache mode to bypass cache
    webView.settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
    
    val client = webView.webViewClient as? ReloadBlockingWebViewClient
    if (client != null) {
        client.forceReload(webView, wiki.url)
    } else {
        webView.loadUrl(wiki.url)
    }
    
    viewModel.setOfflineState(false)
    Toast.makeText(context, "Refreshing from network...", Toast.LENGTH_SHORT).show()
} 