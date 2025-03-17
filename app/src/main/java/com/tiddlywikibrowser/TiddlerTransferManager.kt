package com.tiddlywikibrowser

import android.content.Context
import android.webkit.WebView
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.math.ceil
import kotlin.math.min
import org.json.JSONArray

/**
 * Manages transferring tiddlers between different TiddlyWiki instances
 */
object TiddlerTransferManager {

    /**
     * JavaScript for extracting open tiddlers from a TiddlyWiki
     */
    private val EXTRACT_OPEN_TIDDLERS_SCRIPT = """
        (function() {
            // Get all tiddler frames in the story river
            const tiddlerFrames = document.querySelectorAll('.tc-tiddler-frame');
            const tiddlers = [];
            
            // Iterate through each frame to extract data
            tiddlerFrames.forEach(frame => {
                // Get tiddler title
                const title = frame.getAttribute('data-tiddler-title');
                if (!title) return;
                
                // Extract tags
                const tagsElement = frame.querySelector('.tc-tiddler-tags');
                let tags = [];
                if (tagsElement) {
                    const tagPills = tagsElement.querySelectorAll('.tc-tag-label');
                    tags = Array.from(tagPills).map(tag => tag.getAttribute('data-tag') || tag.textContent.trim());
                }
                
                // Get content preview
                const bodyElement = frame.querySelector('.tc-tiddler-body');
                let preview = bodyElement ? bodyElement.textContent.trim().substring(0, 100) : '';
                if (bodyElement && bodyElement.textContent.length > 100) {
                    preview += '...';
                }
                
                // Add to result array
                tiddlers.push({
                    title: title,
                    tags: tags,
                    preview: preview
                });
            });
            
            return JSON.stringify(tiddlers);
        })()
    """

    /**
     * JavaScript for getting complete tiddler data including all fields
     */
    private val GET_FULL_TIDDLER_SCRIPT = """
        (function(title) {
            try {
                // Check if TiddlyWiki is available
                if (!window.${'$'}tw || !window.${'$'}tw.wiki) {
                    console.error("TiddlyWiki not available for getTiddler: " + title);
                    return JSON.stringify({error: "TiddlyWiki not available"});
                }
                
                console.log("Attempting to get tiddler: " + title);
                const tiddler = window.${'$'}tw.wiki.getTiddler(title);
                
                if (!tiddler) {
                    console.error("Tiddler not found: " + title);
                    return JSON.stringify({error: "Tiddler not found"});
                }
                
                // Create a serializable version of the tiddler
                const fields = {};
                
                // Make sure the title is set
                fields.title = title;
                
                // Handle text field separately to ensure it exists
                fields.text = tiddler.fields.text || "";
                
                // Loop through all fields
                Object.keys(tiddler.fields).forEach(field => {
                    // Skip title and text as we've already handled them
                    if (field === 'title' || field === 'text') return;
                    
                    try {
                        // Handle special cases
                        if (field === 'created' || field === 'modified') {
                            fields[field] = tiddler.fields[field].valueOf();
                        } else if (field === 'tags') {
                            // Make sure tags are handled correctly
                            if (Array.isArray(tiddler.fields.tags)) {
                                fields.tags = Array.from(tiddler.fields.tags);
                            } else if (typeof tiddler.fields.tags === 'string') {
                                fields.tags = tiddler.fields.tags.split(' ');
                            } else {
                                fields.tags = [];
                            }
                        } else {
                            fields[field] = tiddler.fields[field];
                        }
                    } catch (fieldError) {
                        console.error("Error processing field: " + field, fieldError);
                        // Provide a fallback for this field
                        fields[field] = String(tiddler.fields[field]);
                    }
                });
                
                console.log("Successfully extracted tiddler: " + title + " with fields: " + Object.keys(fields).join(", "));
                
                // Verify the critical fields
                if (!fields.title) console.error("Missing title in extracted data");
                if (!fields.text && fields.text !== "") console.error("Missing text in extracted data");
                
                return JSON.stringify(fields);
            } catch (e) {
                console.error("Error getting tiddler: " + title, e);
                return JSON.stringify({error: "Exception: " + e.message});
            }
        })
    """

    /**
     * JavaScript for importing a tiddler into a TiddlyWiki
     */
    private val IMPORT_TIDDLER_SCRIPT = """
        (function(tiddlerData) {
            try {
                // Check if TiddlyWiki is available
                if (!window.${'$'}tw || !window.${'$'}tw.wiki) {
                    console.error("TiddlyWiki not available for import");
                    return false;
                }
                
                // Parse the tiddler data
                const tiddlerFields = JSON.parse(tiddlerData);
                
                // Handle date fields
                if (tiddlerFields.created) {
                    tiddlerFields.created = new Date(tiddlerFields.created);
                }
                if (tiddlerFields.modified) {
                    tiddlerFields.modified = new Date(tiddlerFields.modified);
                }
                
                // Set a new modified time to ensure it's seen as new
                tiddlerFields.modified = new Date();
                
                // Add "Imported" tag if not already present
                if (!tiddlerFields.tags) {
                    tiddlerFields.tags = ["Imported"];
                } else if (typeof tiddlerFields.tags === 'string') {
                    tiddlerFields.tags = tiddlerFields.tags.split(' ').concat(["Imported"]);
                } else if (Array.isArray(tiddlerFields.tags)) {
                    if (!tiddlerFields.tags.includes("Imported")) {
                        tiddlerFields.tags.push("Imported");
                    }
                }
                
                // Create tiddler object
                const newTiddler = new window.${'$'}tw.Tiddler(tiddlerFields);
                
                // Add the tiddler to the wiki
                window.${'$'}tw.wiki.addTiddler(newTiddler);
                
                // Make the tiddler visible in the story river
                if (window.${'$'}tw.story && typeof window.${'$'}tw.story.navigateTiddler === 'function') {
                    try {
                        // Navigate to the tiddler, making it visible in the story river
                        window.${'$'}tw.story.navigateTiddler(tiddlerFields.title);
                        console.log("Navigated to tiddler: " + tiddlerFields.title);
                    } catch (navError) {
                        console.error("Error navigating to tiddler: " + navError);
                    }
                } else if (window.${'$'}tw.wiki.getTiddlerText("$:/StoryList")) {
                    // Alternative approach - directly modify the StoryList
                    try {
                        // Get current story list
                        const storyListTiddler = window.${'$'}tw.wiki.getTiddler("$:/StoryList");
                        if (storyListTiddler && storyListTiddler.fields.list) {
                            // Add the tiddler to the list if not already there
                            const list = storyListTiddler.fields.list.slice(0);
                            if (!list.includes(tiddlerFields.title)) {
                                list.push(tiddlerFields.title);
                                window.${'$'}tw.wiki.addTiddler(new window.${'$'}tw.Tiddler(
                                    {title: "$:/StoryList", list: list}
                                ));
                                console.log("Added tiddler to StoryList: " + tiddlerFields.title);
                            }
                        }
                    } catch (storyError) {
                        console.error("Error updating story list: " + storyError);
                    }
                }
                
                // Force a save if autosave is enabled
                if (window.${'$'}tw.wiki.autosave) {
                    window.${'$'}tw.wiki.autosave.save();
                }
                
                console.log("Successfully imported tiddler: " + tiddlerFields.title);
                return true;
            } catch (e) {
                console.error("Error importing tiddler: " + e);
                return false;
            }
        })
    """

    /**
     * Data class to hold tiddler information for the selection UI
     */
    data class TiddlerInfo(
        val title: String,
        val tags: List<String>,
        val preview: String
    )

    /**
     * Extracts open tiddlers from a TiddlyWiki
     * @param webView The WebView containing the TiddlyWiki
     * @return List of TiddlerInfo objects
     */
    suspend fun extractOpenTiddlers(webView: WebView): List<TiddlerInfo> = withContext(Dispatchers.Main) {
        return@withContext suspendCancellableCoroutine { continuation ->
            webView.evaluateJavascript(EXTRACT_OPEN_TIDDLERS_SCRIPT) { result ->
                try {
                    val tiddlers = mutableListOf<TiddlerInfo>()
                    
                    // Parse JSON result
                    if (result != "null" && result.isNotEmpty()) {
                        // Remove quotes at start and end
                        val jsonString = result.substring(1, result.length - 1).replace("\\\"", "\"")
                        val jsonArray = JSONArray(jsonString)
                        
                        for (i in 0 until jsonArray.length()) {
                            val tiddlerObj = jsonArray.getJSONObject(i)
                            val title = tiddlerObj.getString("title")
                            
                            // Parse tags array
                            val tagsArray = tiddlerObj.getJSONArray("tags")
                            val tags = mutableListOf<String>()
                            for (j in 0 until tagsArray.length()) {
                                tags.add(tagsArray.getString(j))
                            }
                            
                            val preview = tiddlerObj.getString("preview")
                            tiddlers.add(TiddlerInfo(title, tags, preview))
                        }
                    }
                    
                    continuation.resume(tiddlers)
                } catch (e: Exception) {
                    android.util.Log.e("TiddlerTransfer", "Error parsing tiddlers", e)
                    continuation.resume(emptyList())
                }
            }
        }
    }

    /**
     * Gets full tiddler data for a specific tiddler
     * @param webView The WebView containing the TiddlyWiki
     * @param title The title of the tiddler to get
     * @return The tiddler data as a JSON string
     */
    suspend fun getFullTiddler(webView: WebView, title: String): String = withContext(Dispatchers.Main) {
        return@withContext suspendCancellableCoroutine { continuation ->
            try {
                // Escape single quotes and other special characters in the title for JavaScript
                val escapedTitle = title.replace("'", "\\'").replace("\n", "\\n").replace("\r", "\\r")
                
                // Execute the script with better error handling
                val script = """
                    (function() {
                        try {
                            // Check if TiddlyWiki is available
                            if (!window.${'$'}tw || !window.${'$'}tw.wiki) {
                                console.error("TiddlyWiki not available for getTiddler: " + "${escapedTitle}");
                                return JSON.stringify({error: "TiddlyWiki not available"});
                            }
                            
                            console.log("Getting tiddler data for: " + "${escapedTitle}");
                            const tiddler = window.${'$'}tw.wiki.getTiddler("${escapedTitle}");
                            
                            if (!tiddler) {
                                console.error("Tiddler not found: " + "${escapedTitle}");
                                return JSON.stringify({error: "Tiddler not found"});
                            }
                            
                            // Create basic tiddler data with the essential fields
                            const data = {
                                title: "${escapedTitle}",
                                text: tiddler.fields.text || "",
                                tags: []
                            };
                            
                            // Handle tags correctly
                            if (tiddler.fields.tags) {
                                if (Array.isArray(tiddler.fields.tags)) {
                                    data.tags = Array.from(tiddler.fields.tags);
                                } else if (typeof tiddler.fields.tags === 'string') {
                                    data.tags = tiddler.fields.tags.split(' ');
                                }
                            }
                            
                            // Only include essential fields to reduce complexity
                            console.log("Successfully extracted data for: " + "${escapedTitle}");
                            return JSON.stringify(data);
                        } catch (e) {
                            console.error("Error getting tiddler: " + "${escapedTitle}", e);
                            return JSON.stringify({error: e.toString()});
                        }
                    })()
                """
                
                android.util.Log.d("TiddlerTransfer", "Executing getFullTiddler for: $title")
                
                webView.evaluateJavascript(script) { result ->
                    try {
                        if (result == "null" || result.isEmpty()) {
                            android.util.Log.e("TiddlerTransfer", "Null or empty result for tiddler: $title")
                            continuation.resume("")
                            return@evaluateJavascript
                        }
                        
                        // Handle JSON string from JavaScript
                        val jsonString = if (result.startsWith("\"") && result.endsWith("\"")) {
                            // Properly handle escaping in JSON strings
                            result.substring(1, result.length - 1).replace("\\\"", "\"").replace("\\\\", "\\")
                        } else {
                            result
                        }
                        
                        // Check for error
                        if (jsonString.contains("\"error\":")) {
                            android.util.Log.e("TiddlerTransfer", "Error from getFullTiddler: $jsonString")
                            continuation.resume("")
                            return@evaluateJavascript
                        }
                        
                        // Success - return the tiddler data
                        continuation.resume(jsonString)
                    } catch (e: Exception) {
                        android.util.Log.e("TiddlerTransfer", "Error processing result for tiddler: $title", e)
                        continuation.resume("")
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("TiddlerTransfer", "Exception in getFullTiddler", e)
                continuation.resume("")
            }
        }
    }

    /**
     * Imports a tiddler into a TiddlyWiki
     * @param webView The WebView containing the target TiddlyWiki
     * @param tiddlerData The tiddler data as a JSON string
     * @return True if import was successful
     */
    suspend fun importTiddler(webView: WebView, tiddlerData: String): Boolean = withContext(Dispatchers.Main) {
        return@withContext suspendCancellableCoroutine { continuation ->
            // Escape single quotes in the tiddlerData
            val escapedData = tiddlerData.replace("'", "\\'")
            
            val script = "$IMPORT_TIDDLER_SCRIPT('$escapedData')"
            webView.evaluateJavascript(script) { result ->
                continuation.resume(result == "true")
            }
        }
    }

    /**
     * Initiates the tiddler transfer process
     * @param context Android context
     * @param sourceWiki Source wiki instance
     * @param viewModel The WikiViewModel
     */
    fun initiateTransfer(context: Context, sourceWiki: WikiInstance, viewModel: WikiViewModel) {
        // Get the source WebView
        val sourceWebView = viewModel.getOrCreateWebView(sourceWiki, context)
        
        // Launch coroutine to handle the transfer
        CoroutineScope(Dispatchers.IO).launch {
            // Show loading indicator
            withContext(Dispatchers.Main) {
                viewModel.setLoading(true)
            }
            
            try {
                // Extract open tiddlers
                val openTiddlers = extractOpenTiddlers(sourceWebView)
                
                // Hide loading indicator
                withContext(Dispatchers.Main) {
                    viewModel.setLoading(false)
                    
                    if (openTiddlers.isEmpty()) {
                        Toast.makeText(context, "No tiddlers are currently open", Toast.LENGTH_SHORT).show()
                        return@withContext
                    }
                    
                    // Show tiddler selection UI using a mutable state for dialog visibility
                    showTiddlerSelectionDialog(
                        context = context,
                        tiddlers = openTiddlers,
                        viewModel = viewModel,
                        sourceWiki = sourceWiki,
                        sourceWebView = sourceWebView
                    )
                }
            } catch (e: Exception) {
                android.util.Log.e("TiddlerTransfer", "Error initiating transfer", e)
                withContext(Dispatchers.Main) {
                    viewModel.setLoading(false)
                    Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * Shows the tiddler selection dialog
     */
    fun showTiddlerSelectionDialog(
        context: Context,
        tiddlers: List<TiddlerInfo>,
        viewModel: WikiViewModel,
        sourceWiki: WikiInstance,
        sourceWebView: WebView
    ) {
        // Use MainActivity's dialog state management
        val activity = context as? MainActivity ?: return
        
        // Set dialog state variables
        activity.tiddlerTransferState.showTiddlerSelectionDialog = true
        activity.tiddlerTransferState.availableTiddlers = tiddlers
        activity.tiddlerTransferState.sourceWiki = sourceWiki
        activity.tiddlerTransferState.sourceWebView = sourceWebView
    }

    /**
     * Shows the wiki selection dialog
     */
    fun showWikiSelectionDialog(
        context: Context,
        selectedTiddlers: List<String>,
        sourceWiki: WikiInstance,
        sourceWebView: WebView,
        viewModel: WikiViewModel
    ) {
        // Use MainActivity's dialog state management
        val activity = context as? MainActivity ?: return
        
        // Get available wikis excluding the source wiki
        val availableWikis = viewModel.allWikis.value.filter { it.url != sourceWiki.url }
        
        if (availableWikis.isEmpty()) {
            Toast.makeText(context, "No other wikis available to transfer to", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Set dialog state variables
        activity.tiddlerTransferState.showWikiSelectionDialog = true
        activity.tiddlerTransferState.availableWikis = availableWikis
        activity.tiddlerTransferState.selectedTiddlers = selectedTiddlers
    }

    /**
     * Performs the actual tiddler transfer
     */
    fun performTransfer(
        context: Context,
        sourceWebView: WebView,
        targetWiki: WikiInstance,
        selectedTiddlers: List<String>,
        viewModel: WikiViewModel
    ) {
        val targetWebView = viewModel.getOrCreateWebView(targetWiki, context)
        
        // Launch coroutine to handle the transfer
        CoroutineScope(Dispatchers.IO).launch {
            var successCount = 0
            val transferData = mutableListOf<Triple<String, String, List<String>>>()  // title, text, tags
            
            // 1. COLLECT DATA - Gather all tiddler data from source wiki
            withContext(Dispatchers.Main) {
                viewModel.setLoading(true, "Collecting tiddler data...")
            }
            
            android.util.Log.d("TiddlerTransfer", "Starting collection of ${selectedTiddlers.size} tiddlers")
            
            // Use direct bulk collection approach for better reliability
            try {
                withContext(Dispatchers.Main) {
                    val bulkScript = """
                        (function() {
                            try {
                                if (!window.${'$'}tw || !window.${'$'}tw.wiki) {
                                    console.error("TiddlyWiki not available for bulk collection");
                                    return JSON.stringify({error: "TiddlyWiki not available"});
                                }
                                
                                const titlesToGet = ${org.json.JSONArray(selectedTiddlers).toString()};
                                const results = [];
                                
                                console.log("Starting bulk collection of " + titlesToGet.length + " tiddlers");
                                
                                for (let i = 0; i < titlesToGet.length; i++) {
                                    const title = titlesToGet[i];
                                    try {
                                        const tiddler = window.${'$'}tw.wiki.getTiddler(title);
                                        
                                        if (!tiddler) {
                                            console.error("Tiddler not found: " + title);
                                            continue;
                                        }
                                        
                                        // Create basic data structure with essential fields
                                        const data = {
                                            title: title,
                                            text: tiddler.fields.text || "",
                                            tags: []
                                        };
                                        
                                        // Handle tags
                                        if (tiddler.fields.tags) {
                                            if (Array.isArray(tiddler.fields.tags)) {
                                                data.tags = Array.from(tiddler.fields.tags);
                                            } else if (typeof tiddler.fields.tags === 'string') {
                                                data.tags = tiddler.fields.tags.split(' ');
                                            }
                                        }
                                        
                                        // Simple sanity check
                                        if (data.title && (data.text !== undefined)) {
                                            results.push(data);
                                            console.log("Collected data for: " + title);
                                        }
                                    } catch (innerError) {
                                        console.error("Error processing tiddler: " + title, innerError);
                                    }
                                }
                                
                                console.log("Collected data for " + results.length + " tiddlers");
                                return JSON.stringify(results);
                            } catch (e) {
                                console.error("Error collecting tiddlers: ", e);
                                return JSON.stringify({error: e.toString()});
                            }
                        })()
                    """
                    
                    sourceWebView.evaluateJavascript(bulkScript) { bulkResult ->
                        try {
                            android.util.Log.d("TiddlerTransfer", "Bulk collection result length: ${bulkResult.length}")
                            
                            // Parse the JSON result
                            if (bulkResult != "null" && bulkResult.isNotEmpty()) {
                                // Handle the JSON string returned from JavaScript
                                val jsonString = if (bulkResult.startsWith("\"") && bulkResult.endsWith("\"")) {
                                    bulkResult.substring(1, bulkResult.length - 1).replace("\\\"", "\"").replace("\\\\", "\\")
                                } else {
                                    bulkResult
                                }
                                
                                // Check if we got an error
                                if (jsonString.contains("\"error\":")) {
                                    android.util.Log.e("TiddlerTransfer", "Error in bulk collection: $jsonString")
                                } else {
                                    try {
                                        val tiddlersArray = org.json.JSONArray(jsonString)
                                        android.util.Log.d("TiddlerTransfer", "Successfully parsed bulk results, count: ${tiddlersArray.length()}")
                                        
                                        for (i in 0 until tiddlersArray.length()) {
                                            val tiddlerObj = tiddlersArray.getJSONObject(i)
                                            val title = tiddlerObj.getString("title")
                                            val text = tiddlerObj.getString("text")
                                            
                                            // Get tags
                                            val tagsList = mutableListOf<String>()
                                            val tagsArray = tiddlerObj.optJSONArray("tags")
                                            if (tagsArray != null) {
                                                for (j in 0 until tagsArray.length()) {
                                                    tagsList.add(tagsArray.getString(j))
                                                }
                                            }
                                            
                                            // Add "Imported" tag
                                            if (!tagsList.contains("Imported")) {
                                                tagsList.add("Imported")
                                            }
                                            
                                            transferData.add(Triple(title, text, tagsList))
                                            android.util.Log.d("TiddlerTransfer", "Added from bulk: $title")
                                        }
                                    } catch (e: Exception) {
                                        android.util.Log.e("TiddlerTransfer", "Error parsing bulk tiddler data", e)
                                    }
                                }
                            } else {
                                android.util.Log.e("TiddlerTransfer", "Null or empty result from bulk collection")
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("TiddlerTransfer", "Error processing bulk result", e)
                        }
                    }
                }
                
                // Wait for JavaScript execution to complete
                delay(1000)
            } catch (e: Exception) {
                android.util.Log.e("TiddlerTransfer", "Error in bulk collection attempt", e)
            }
            
            // If bulk collection failed, fall back to individual collection
            if (transferData.isEmpty()) {
                android.util.Log.d("TiddlerTransfer", "Bulk collection failed or returned no data, trying individual collection")
                
                // Extract data for each selected tiddler one by one
                for (title in selectedTiddlers) {
                    try {
                        android.util.Log.d("TiddlerTransfer", "Getting data for: $title")
                        val tiddlerData = getFullTiddler(sourceWebView, title)
                        
                        if (tiddlerData.isNotEmpty()) {
                            val tiddlerObj = org.json.JSONObject(tiddlerData)
                            val tiddlerTitle = tiddlerObj.optString("title", title)
                            val tiddlerText = tiddlerObj.optString("text", "")
                            
                            // Extract tags
                            val tagsList = mutableListOf<String>()
                            if (tiddlerObj.has("tags")) {
                                val tags = tiddlerObj.get("tags")
                                if (tags is String) {
                                    tagsList.addAll(tags.split(" "))
                                } else if (tags is org.json.JSONArray) {
                                    for (i in 0 until tags.length()) {
                                        tagsList.add(tags.getString(i))
                                    }
                                }
                            }
                            // Add "Imported" tag
                            if (!tagsList.contains("Imported")) {
                                tagsList.add("Imported")
                            }
                            
                            // Store the tiddler data for later transfer
                            transferData.add(Triple(tiddlerTitle, tiddlerText, tagsList))
                            android.util.Log.d("TiddlerTransfer", "Collected data for: $tiddlerTitle")
                        } else {
                            android.util.Log.e("TiddlerTransfer", "Failed to get data for: $title")
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("TiddlerTransfer", "Error collecting data for $title", e)
                    }
                }
            }
            
            // Check if we have any data to transfer
            if (transferData.isEmpty()) {
                withContext(Dispatchers.Main) {
                    viewModel.setLoading(false)
                    Toast.makeText(context, "No tiddler data could be collected", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }
            
            android.util.Log.d("TiddlerTransfer", "Successfully collected data for ${transferData.size} tiddlers")
            
            // 2. SWITCH WIKIS - Switch to target wiki and wait for it to be ready
            withContext(Dispatchers.Main) {
                viewModel.setLoading(true, "Switching to target wiki...")
                
                // Set the current wiki - this will activate and load the WebView
                viewModel.setCurrentWiki(targetWiki)
            }
            
            // Allow time for the wiki to load and become ready
            delay(1500)
            
            // 3. INITIATE TIDDLER CREATION - Create tiddlers in target wiki
            val createdTiddlers = mutableListOf<String>()
            for ((index, data) in transferData.withIndex()) {
                val (title, text, tags) = data
                
                withContext(Dispatchers.Main) {
                    viewModel.setLoading(true, "Creating tiddler ${index+1} of ${transferData.size}...")
                    
                    // Create the tiddler using a more direct approach
                    val script = """
                        (function() {
                            try {
                                // Check if TiddlyWiki is ready
                                var tiddlywiki = window['${'$'}tw'];
                                if (!tiddlywiki || !tiddlywiki.wiki) {
                                    console.error('TiddlyWiki not ready');
                                    return false;
                                }
                                
                                // Create the tiddler
                                tiddlywiki.wiki.addTiddler({
                                    title: "${title.replace("\"", "\\\"")}",
                                    text: "${text.replace("\"", "\\\"").replace("\n", "\\n")}",
                                    tags: ${org.json.JSONArray(tags).toString()},
                                    modified: new Date()
                                });
                                
                                // Verify tiddler was created
                                var tiddler = tiddlywiki.wiki.getTiddler("${title.replace("\"", "\\\"")}");
                                if (!tiddler) {
                                    console.error('Failed to verify tiddler creation');
                                    return false;
                                }
                                
                                return true;
                            } catch (e) {
                                console.error('Error in tiddler creation:', e);
                                return false;
                            }
                        })();
                    """
                    
                    // Execute and track success
                    targetWebView.evaluateJavascript(script) { result ->
                        if (result == "true") {
                            createdTiddlers.add(title)
                            successCount++
                            android.util.Log.d("TiddlerTransfer", "Created tiddler: $title")
                        } else {
                            android.util.Log.e("TiddlerTransfer", "Failed to create tiddler: $title")
                        }
                    }
                    
                    // Small delay between creations to prevent overwhelm
                    delay(200)
                }
            }
            
            // Allow time for all creations to complete
            delay(500)
            
            // 4. OPEN TIDDLERS - Make created tiddlers visible in story river
            if (createdTiddlers.isNotEmpty()) {
                withContext(Dispatchers.Main) {
                    viewModel.setLoading(true, "Opening tiddlers...")
                    
                    val script = """
                        (function() {
                            try {
                                // Check if TiddlyWiki is ready
                                var tiddlywiki = window['${'$'}tw'];
                                if (!tiddlywiki || !tiddlywiki.wiki) {
                                    return false;
                                }
                                
                                var openCount = 0;
                                var titles = ${org.json.JSONArray(createdTiddlers).toString()};
                                
                                // Try to open tiddlers in story river
                                if (tiddlywiki.story && typeof tiddlywiki.story.navigateTiddler === 'function') {
                                    // Open the first one to navigate to it
                                    if (titles.length > 0) {
                                        tiddlywiki.story.navigateTiddler(titles[0]);
                                        openCount++;
                                    }
                                    
                                    // Add the rest to the story river without navigating
                                    for (var i = 1; i < titles.length && i < 5; i++) {
                                        // Use addTiddler instead of navigateTiddler to avoid losing focus
                                        if (typeof tiddlywiki.story.addTiddler === 'function') {
                                            tiddlywiki.story.addTiddler(titles[i]);
                                            openCount++;
                                        }
                                    }
                                }
                                
                                // Force a save
                                if (typeof tiddlywiki.wiki.saveWiki === 'function') {
                                    tiddlywiki.wiki.saveWiki();
                                }
                                
                                return openCount;
                            } catch (e) {
                                console.error('Error opening tiddlers:', e);
                                return 0;
                            }
                        })();
                    """
                    
                    targetWebView.evaluateJavascript(script) { result ->
                        android.util.Log.d("TiddlerTransfer", "Opened tiddlers result: $result")
                    }
                    
                    // Give the wiki time to update the UI
                    delay(300)
                }
            }
            
            // 5. INFORM USER - Show completion message
            withContext(Dispatchers.Main) {
                // Hide loading indicator
                viewModel.setLoading(false)
                
                // Create completion message
                val message = when {
                    successCount == 0 -> "No tiddlers could be transferred"
                    successCount < selectedTiddlers.size -> 
                        "Transferred $successCount of ${selectedTiddlers.size} tiddlers to ${targetWiki.name}"
                    else -> 
                        "Successfully transferred ${selectedTiddlers.size} tiddlers to ${targetWiki.name}"
                }
                
                // Show toast
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                android.util.Log.d("TiddlerTransfer", message)
            }
        }
    }

    /**
     * Composable for the tiddler selection dialog
     */
    @Composable
    fun TiddlerSelectionDialog(
        tiddlers: List<TiddlerInfo>,
        onConfirm: (List<String>) -> Unit,
        onDismiss: () -> Unit
    ) {
        // State for selected tiddlers
        val selectedTiddlers = remember { mutableStateListOf<String>() }
        
        // State for current page
        var currentPage by remember { mutableStateOf(0) }
        val tiddlersPerPage = 5
        val pageCount = ceil(tiddlers.size.toFloat() / tiddlersPerPage).toInt()
        
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Select Tiddlers to Transfer") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp)
                ) {
                    // Display current page of tiddlers
                    val startIndex = currentPage * tiddlersPerPage
                    val endIndex = min(startIndex + tiddlersPerPage, tiddlers.size)
                    val currentPageTiddlers = tiddlers.subList(startIndex, endIndex)
                    
                    currentPageTiddlers.forEach { tiddler ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp)
                        ) {
                            Checkbox(
                                checked = selectedTiddlers.contains(tiddler.title),
                                onCheckedChange = { checked ->
                                    if (checked) {
                                        selectedTiddlers.add(tiddler.title)
                                    } else {
                                        selectedTiddlers.remove(tiddler.title)
                                    }
                                }
                            )
                            
                            Column(modifier = Modifier.padding(start = 8.dp)) {
                                Text(
                                    text = tiddler.title,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                
                                if (tiddler.tags.isNotEmpty()) {
                                    Text(
                                        text = "Tags: ${tiddler.tags.joinToString(", ")}",
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                
                                Text(
                                    text = tiddler.preview,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        
                        Divider()
                    }
                    
                    // Pagination controls
                    if (pageCount > 1) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            IconButton(
                                onClick = { currentPage = maxOf(0, currentPage - 1) },
                                enabled = currentPage > 0
                            ) {
                                Icon(Icons.Default.ArrowBack, "Previous page")
                            }
                            
                            Text("Page ${currentPage + 1} of $pageCount")
                            
                            IconButton(
                                onClick = { currentPage = minOf(pageCount - 1, currentPage + 1) },
                                enabled = currentPage < pageCount - 1
                            ) {
                                Icon(Icons.Default.ArrowForward, "Next page")
                            }
                        }
                    }
                    
                    // Selection summary
                    Text(
                        text = "${selectedTiddlers.size} tiddlers selected",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { onConfirm(selectedTiddlers.toList()) },
                    enabled = selectedTiddlers.isNotEmpty()
                ) {
                    Text("Transfer Selected")
                }
            },
            dismissButton = {
                Button(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        )
    }

    /**
     * Composable for the wiki selection dialog
     */
    @Composable
    fun WikiSelectionDialog(
        wikis: List<WikiInstance>,
        onWikiSelected: (WikiInstance) -> Unit,
        onDismiss: () -> Unit
    ) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Select Target Wiki") },
            text = {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 300.dp)
                ) {
                    items(wikis) { wiki ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp, horizontal = 8.dp)
                                .clickable { onWikiSelected(wiki) },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.ArrowForward,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            
                            Column(modifier = Modifier.padding(start = 16.dp)) {
                                Text(
                                    text = wiki.name,
                                    style = MaterialTheme.typography.titleMedium
                                )
                                
                                Text(
                                    text = wiki.url,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        
                        Divider()
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                Button(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        )
    }

    /**
     * Class to hold the transfer dialog state
     */
    class TiddlerTransferState {
        var showTiddlerSelectionDialog by mutableStateOf(false)
        var showWikiSelectionDialog by mutableStateOf(false)
        var availableTiddlers by mutableStateOf<List<TiddlerInfo>>(emptyList())
        var selectedTiddlers by mutableStateOf<List<String>>(emptyList())
        var availableWikis by mutableStateOf<List<WikiInstance>>(emptyList())
        var sourceWiki: WikiInstance? = null
        var sourceWebView: WebView? = null
    }
} 