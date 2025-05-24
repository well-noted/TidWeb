package com.tiddlywikibrowser.ui.dialogs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import android.widget.Toast
import com.tiddlywikibrowser.MainActivity
import com.tiddlywikibrowser.R
import com.tiddlywikibrowser.WikiViewModel
import com.tiddlywikibrowser.WikiInstance
import com.tiddlywikibrowser.model.TiddlerTemplate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddWikiDialog(
    onDismiss: () -> Unit,
    onAdd: (String, String) -> Unit,
    onAddLocalFile: () -> Unit,
    onCreateSingleFileWiki: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add TiddlyWiki") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                TextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Wiki Name") },
                    modifier = Modifier.fillMaxWidth(),
                    isError = name.isBlank(),
                    singleLine = true
                )

                TextField(
                    value = url,
                    onValueChange = { url = it; error = null },
                    label = { Text("Wiki URL") },
                    placeholder = { Text("e.g., http://example.com or 192.168.1.1:8080") },
                    modifier = Modifier.fillMaxWidth(),
                    isError = error != null || url.isBlank(),
                    supportingText = error?.let { { Text(it) } },
                    singleLine = true
                )

                // Add a button to select a local file
                OutlinedButton(
                    onClick = onAddLocalFile,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Folder,
                            contentDescription = "Select File",
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Text("Select Local TiddlyWiki File")
                    }
                }

                // Add button to create a single-file wiki
                OutlinedButton(
                    onClick = onCreateSingleFileWiki,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Create Single-File Wiki",
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Text("Create Single-File Wiki")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (name.isBlank() || url.isBlank()) {
                        error = "Name and URL are required"
                        return@TextButton
                    }

                    // Use URL validation and make sure to call onAdd with the formatted URL
                    try {
                        val formattedUrl = com.tiddlywikibrowser.WikiInstance.formatUrl(url)
                        onAdd(name, formattedUrl)
                        Toast.makeText(context, "Wiki added successfully", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        error = e.message ?: "Invalid URL format"
                    }
                },
                enabled = name.isNotBlank() && url.isNotBlank()
            ) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RenameWikiDialog(
    currentName: String,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit
) {
    var newName by remember { mutableStateOf(currentName) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename Wiki") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                TextField(
                    value = newName,
                    onValueChange = { newName = it; error = null },
                    label = { Text("Wiki Name") },
                    modifier = Modifier.fillMaxWidth(),
                    isError = error != null || newName.isBlank(),
                    supportingText = error?.let { { Text(it) } },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (newName.isBlank()) {
                        error = "Name cannot be empty"
                        return@TextButton
                    }

                    if (newName == currentName) {
                        onDismiss()
                        return@TextButton
                    }

                    onRename(newName)
                },
                enabled = newName.isNotBlank()
            ) {
                Text("Rename")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun DeleteWikiConfirmDialog(
    wikiName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete Wiki") },
        text = { Text("Are you sure you want to delete '$wikiName'?") },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WikiSelectionDialog(
    wikis: List<WikiInstance>,
    quickTags: List<String>,
    onDismiss: () -> Unit,
    onWikiSelected: (WikiInstance, List<String>) -> Unit,
    onAddNew: () -> Unit
) {
    var selectedTags by remember { mutableStateOf(setOf("Shared")) }  // Using Set to prevent duplicates

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Wiki and Tags") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = "Select Tags",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                // Quick tags selection using simple Row + wrapping
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        FilterChip(
                            selected = true,  // Shared is always selected
                            enabled = false,  // Cannot deselect Shared
                            onClick = { },
                            label = { Text("Shared") }
                        )
                        quickTags.filter { it != "Shared" }.forEach { tag ->
                            FilterChip(
                                selected = selectedTags.contains(tag),
                                onClick = {
                                    selectedTags = if (selectedTags.contains(tag)) {
                                        selectedTags - tag
                                    } else {
                                        selectedTags + tag
                                    }
                                },
                                label = { Text(tag) }
                            )
                        }
                    }
                }

                Divider(modifier = Modifier.padding(vertical = 16.dp))

                Text(
                    text = "Select Wiki",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                // Wiki selection
                Column(modifier = Modifier.fillMaxWidth()) {
                    wikis.forEach { wiki ->
                        TextButton(
                            onClick = { onWikiSelected(wiki, selectedTags.toList()) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(wiki.name)
                        }
                    }
                    TextButton(
                        onClick = onAddNew,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("+ Add New Wiki")
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagManagementDialog(
    tags: List<String>,
    onAddTag: (String) -> Unit,
    onRemoveTag: (String) -> Unit,
    onReorderTags: (Int, Int) -> Unit,
    onDismiss: () -> Unit
) {
    var newTag by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Manage Quick Tags") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextField(
                        value = newTag,
                        onValueChange = { newTag = it },
                        label = { Text(text = "Enter new tag") },
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = {
                            if (newTag.isNotBlank()) {
                                onAddTag(newTag)
                                newTag = ""
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Add Tag"
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Existing Tags:",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 200.dp)
                ) {
                    items(tags) { tag ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = tag,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = { onRemoveTag(tag) }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "Delete Tag"
                                )
                            }
                        }
                        Divider()
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "Done")
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TiddlerTemplateSelectionDialog(
    onDismiss: () -> Unit,
    onTemplateSelected: (TiddlerTemplate) -> Unit
) {
    val context = LocalContext.current
    val viewModel: WikiViewModel = remember { MainActivity.getViewModel(context) }
    val templates by viewModel.tiddlerTemplates.collectAsState()
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        viewModel.loadTiddlerTemplates()
        isLoading = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Wiki Template") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                if (isLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                } else if (templates.isEmpty()) {
                    Text(
                        text = "No templates found. Please add template files to the assets/tiddler_templates folder.",
                        modifier = Modifier.padding(16.dp)
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(templates) { template ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onTemplateSelected(template) }
                                    .padding(16.dp)
                            ) {
                                Text(
                                    text = template.name,
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = template.description ?: "",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Divider()
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun SettingsDialog(
    isDarkMode: Boolean,
    onDarkModeChange: (Boolean) -> Unit,
    onManageQuickTags: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val mainActivity = context as? MainActivity
    val viewModel: WikiViewModel = remember { MainActivity.getViewModel(context) }
    val useSmallScreenCSS by viewModel.useSmallScreenCSS.collectAsState()
    val isBackgroundEnabled by mainActivity?.isBackgroundEnabled?.collectAsState() ?: remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                // Dark Mode Toggle
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.dark_mode))
                    Switch(
                        checked = isDarkMode,
                        onCheckedChange = onDarkModeChange
                    )
                }

                // Small Screen CSS Toggle
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Small Screen Adaptations")
                        Text(
                            "Optimize layout for very small screens",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = useSmallScreenCSS,
                        onCheckedChange = { enabled ->
                            viewModel.setUseSmallScreenCSS(enabled)
                        }
                    )
                }

                // Background Mode Toggle
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Background Mode")
                        Text(
                            "Keep TiddlyWiki running when minimized",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = isBackgroundEnabled,
                        onCheckedChange = { enabled ->
                            mainActivity?.setBackgroundEnabled(enabled)
                        }
                    )
                }

                // Manage Quick Tags Button
                OutlinedButton(
                    onClick = onManageQuickTags,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                ) {
                    Text("Manage Quick Tags")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
} 