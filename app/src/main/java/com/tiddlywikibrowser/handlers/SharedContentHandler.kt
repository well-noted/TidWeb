package com.tiddlywikibrowser.handlers

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebView
import android.widget.Toast
import com.tiddlywikibrowser.MainActivity
import com.tiddlywikibrowser.WikiInstance
import com.tiddlywikibrowser.WikiViewModel
import org.json.JSONArray
import org.json.JSONObject

/**
 * Handles shared content operations and wiki selection for sharing
 */
class SharedContentHandler(
    private val activity: MainActivity,
    private val viewModel: WikiViewModel
) {
    private val TAG = "SharedContentHandler"
    
    /**
     * Handle wiki selection for sharing content
     */
    fun handleWikiSelection(
        selectedWiki: WikiInstance, 
        textToShare: String?, 
        selectedTags: List<String>
    ) {
        viewModel.setCurrentWiki(selectedWiki)
        Handler(Looper.getMainLooper()).postDelayed({
            activity.getCurrentWebView()?.evaluateJavascript("""
                (function() {
                    try {
                        console.log('Checking TiddlyWiki state...');
                        var tiddlywiki = window['${'$'}tw'];
                        if (!tiddlywiki || !tiddlywiki.wiki) {
                            console.log('TiddlyWiki object not found');
                            return { status: 'not_ready' };
                        }
                        
                        var title = 'Shared Content ' + new Date().toISOString();
                        var processedText = ${if (textToShare != null) "\"${textToShare.replace("\"", "\\\"").replace("\n", "\\n")}\"" else "\"\""}
                          .replace(/\\\\n/g, "\n").replace(/\\\\t/g, "\t").replace(/\\n/g, "\n").replace(/\\t/g, "\t");
                        processedText = processedText.replace(/(\n\s*){5,}/g, "\n".repeat(5));
                        var tags = ${JSONArray(selectedTags).toString()};
                        
                        tiddlywiki.wiki.addTiddler({
                            title: title,
                            text: processedText,
                            tags: tags
                        });
                        
                        // Verify tiddler was created
                        var tiddler = tiddlywiki.wiki.getTiddler(title);
                        if (!tiddler) {
                            return { status: 'error', message: 'Failed to create tiddler' };
                        }
                        
                        // Try to navigate to the new tiddler
                        if (tiddlywiki.story && typeof tiddlywiki.story.navigateTiddler === 'function') {
                            tiddlywiki.story.navigateTiddler(title);
                            console.log('Navigated to tiddler:', title);
                        }
                        
                        // Attempt to trigger a save if possible
                        if (typeof tiddlywiki.wiki.saveWiki === 'function') {
                            tiddlywiki.wiki.saveWiki();
                        }
                        
                        return { status: 'success', title: title };
                    } catch (e) {
                        console.error('Error creating tiddler:', e);
                        return { status: 'error', message: e.toString() };
                    }
                })();
            """.trimIndent()) { result ->
                handleTiddlerCreationResult(result, selectedWiki, textToShare, selectedTags)
            }
        }, 1000)
    }
    
    private fun handleTiddlerCreationResult(
        result: String,
        selectedWiki: WikiInstance,
        textToShare: String?,
        selectedTags: List<String>
    ) {
        try {
            val jsonResult = JSONObject(result)
            when (jsonResult.getString("status")) {
                "not_ready" -> {
                    // Try again after a longer delay
                    Handler(Looper.getMainLooper()).postDelayed({
                        handleWikiSelection(selectedWiki, textToShare, selectedTags)
                    }, 2000)
                }
                "success" -> {
                    Toast.makeText(activity, "Content shared to ${selectedWiki.name}", Toast.LENGTH_SHORT).show()
                }
                "error" -> {
                    val message = jsonResult.optString("message", "Unknown error")
                    Toast.makeText(activity, "Failed to create tiddler: $message", Toast.LENGTH_LONG).show()
                }
                else -> {
                    Toast.makeText(activity, "Unknown response from TiddlyWiki", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            Toast.makeText(activity, "Error processing tiddler creation", Toast.LENGTH_LONG).show()
            e.printStackTrace()
        }
    }
} 