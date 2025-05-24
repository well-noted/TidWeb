package com.tiddlywikibrowser.handlers

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.result.ActivityResultLauncher
import com.tiddlywikibrowser.MainActivity
import com.tiddlywikibrowser.WikiViewModel
import com.tiddlywikibrowser.ui.DialogStateManager

/**
 * Handles intent processing for the MainActivity
 */
class IntentHandler(
    private val activity: MainActivity,
    private val viewModel: WikiViewModel,
    private val dialogStateManager: DialogStateManager,
    private val filePickerLauncher: ActivityResultLauncher<String>
) {
    
    /**
     * Handle the intent received by the activity
     */
    fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_SEND -> {
                if (intent.type == "text/plain") {
                    val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
                    if (sharedText != null) {
                        dialogStateManager.showWikiSelectorWithText(sharedText)
                    }
                }
            }
        }
    }
    
    /**
     * Create a file picker launcher
     */
    fun setupFilePicker() {
        // This is called from MainActivity during setup
        // The actual launcher is passed in as a parameter
    }
    
    /**
     * Handle the result from file picker
     */
    fun handleFilePickerResult(uri: Uri?) {
        uri?.let { contentUri ->
            // Try to get a file name from the URI
            val fileName = getFileNameFromUri(contentUri)
            val displayName = dialogStateManager.pendingWikiName ?: fileName?.substringBeforeLast('.') ?: "Local Wiki"
            
            // Reset the pending name
            dialogStateManager.pendingWikiName = null
            
            // Import the file using WikiViewModel
            viewModel.importLocalWikiFile(contentUri, fileName)
            
            // Close the dialog
            dialogStateManager.showAddDialog = false
        }
    }
    
    /**
     * Helper function to get file name from URI
     */
    private fun getFileNameFromUri(uri: Uri): String? {
        val cursor = activity.contentResolver.query(uri, null, null, null, null)
        return cursor?.use {
            val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            it.moveToFirst()
            it.getString(nameIndex)
        }
    }
} 