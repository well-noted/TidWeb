package com.tiddlywikibrowser.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.tiddlywikibrowser.WikiInstance

/**
 * Manages all dialog states for the MainActivity
 */
class DialogStateManager {
    // Dialog visibility states
    var showWikiSelector by mutableStateOf(false)
    var showAddDialog by mutableStateOf(false)
    var showDeleteConfirmDialog by mutableStateOf(false)
    var showShareMenu by mutableStateOf(false)
    var showTagManagement by mutableStateOf(false)
    var showRenameDialog by mutableStateOf(false)
    var showTemplateSelectionDialog by mutableStateOf(false)
    var showLoadErrorDialog by mutableStateOf(false)
    
    // Dialog data states
    var pendingSharedText by mutableStateOf<String?>(null)
    var loadErrorWiki: WikiInstance? = null
    var pendingWikiName by mutableStateOf<String?>(null)
    
    fun reset() {
        showWikiSelector = false
        showAddDialog = false
        showDeleteConfirmDialog = false
        showShareMenu = false
        showTagManagement = false
        showRenameDialog = false
        showTemplateSelectionDialog = false
        showLoadErrorDialog = false
        pendingSharedText = null
        loadErrorWiki = null
        pendingWikiName = null
    }
    
    fun showWikiSelectorWithText(text: String) {
        pendingSharedText = text
        showWikiSelector = true
    }
    
    fun closeWikiSelector() {
        showWikiSelector = false
        pendingSharedText = null
    }
    
    fun showLoadError(wiki: WikiInstance) {
        loadErrorWiki = wiki
        showLoadErrorDialog = true
    }
    
    fun closeLoadError() {
        showLoadErrorDialog = false
        loadErrorWiki = null
    }
} 