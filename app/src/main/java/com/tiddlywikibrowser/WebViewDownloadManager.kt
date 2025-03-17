package com.tiddlywikibrowser

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import android.webkit.WebView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.util.Base64
import android.webkit.JavascriptInterface
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import android.app.AlertDialog
import android.content.DialogInterface
import android.webkit.WebSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.content.Intent
import androidx.core.content.FileProvider
import android.os.Build

/**
 * Manages downloads initiated from the WebView
 */
class WebViewDownloadManager(private val context: Context) {

    private val downloadManager: DownloadManager by lazy {
        context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    }
    
    private var pendingBlobUrl: String? = null
    private var pendingBlobFileName: String? = null
    private var pendingMimeType: String? = null
    
    private var currentWiki: WikiInstance? = null
    private var viewModel: WikiViewModel? = null
    
    /**
     * Set the current wiki and view model for auto-replacement
     */
    fun setCurrentWiki(wiki: WikiInstance?, model: WikiViewModel?) {
        this.currentWiki = wiki
        this.viewModel = model
    }
    
    /**
     * Javascript interface to handle blob URL data
     */
    inner class BlobDownloadInterface {
        @JavascriptInterface
        fun processBase64Data(base64Data: String) {
            Log.d(TAG, "Received base64 data, length: ${base64Data.length}")
            pendingBlobUrl?.let { url ->
                val fileName = pendingBlobFileName ?: generateFileName(url, pendingMimeType ?: "application/octet-stream")
                handleBase64Download(base64Data, fileName, pendingMimeType ?: "application/octet-stream")
                
                // Reset pending data
                pendingBlobUrl = null
                pendingBlobFileName = null
                pendingMimeType = null
            }
        }
    }
    
    companion object {
        private const val TAG = "WebViewDownloadManager"
        
        // TiddlyWiki file types
        private val TIDDLYWIKI_EXTENSIONS = arrayOf(".html", ".htm", ".tid", ".hta")
        
        // Check if a file is potentially a TiddlyWiki file
        fun isTiddlyWikiFile(fileName: String): Boolean {
            val lowerName = fileName.lowercase()
            return TIDDLYWIKI_EXTENSIONS.any { lowerName.endsWith(it) }
        }
    }

    /**
     * Sets up download listener for the provided WebView
     */
    fun setupDownloadListener(webView: WebView) {
        // Add JavaScript interface for blob downloads
        webView.addJavascriptInterface(BlobDownloadInterface(), "BlobDownloader")
        
        webView.setDownloadListener { url, userAgent, contentDisposition, mimetype, contentLength ->
            Log.d(TAG, "Download request: $url, mimetype: $mimetype")
            
            if (url.startsWith("blob:")) {
                // Handle blob URL downloads with JS conversion
                handleBlobDownload(webView, url, contentDisposition, mimetype)
            } else {
                // Handle regular URL downloads
                handleRegularDownload(url, userAgent, contentDisposition, mimetype)
            }
        }
    }
    
    /**
     * Handle blob URL by using JavaScript to convert it to base64
     */
    private fun handleBlobDownload(webView: WebView, url: String, contentDisposition: String, mimeType: String) {
        Log.d(TAG, "Handling blob download for URL: $url")
        
        // Store pending blob info
        pendingBlobUrl = url
        pendingBlobFileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
        pendingMimeType = mimeType
        
        // Show a toast to indicate download has started
        ThreadManager.runOnMain {
            Toast.makeText(context, "Preparing download...", Toast.LENGTH_SHORT).show()
        }
        
        // Execute JavaScript to fetch the blob content and convert to base64
        val script = """
            (function() {
                var blobUrl = '${url}';
                console.log('Fetching blob: ' + blobUrl);
                
                fetch(blobUrl)
                    .then(response => response.blob())
                    .then(blob => {
                        console.log('Blob received, size: ' + blob.size + ', type: ' + blob.type);
                        var reader = new FileReader();
                        reader.onloadend = function() {
                            var base64data = reader.result;
                            var base64Content = base64data.split(',')[1];
                            console.log('Base64 conversion complete, sending data');
                            BlobDownloader.processBase64Data(base64Content);
                        };
                        reader.readAsDataURL(blob);
                    })
                    .catch(error => {
                        console.error('Error processing blob: ' + error);
                    });
            })();
        """.trimIndent()
        
        webView.evaluateJavascript(script) { result ->
            Log.d(TAG, "JavaScript evaluation result: $result")
        }
    }
    
    /**
     * Generate a filename with timestamp to avoid overwriting
     */
    private fun generateFileName(url: String, mimeType: String): String {
        var fileName = URLUtil.guessFileName(url, null, mimeType)
        
        // Add timestamp to prevent overwriting files
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileNameWithoutExt = fileName.substringBeforeLast(".", fileName)
        val extension = fileName.substringAfterLast(".", "")
        
        return if (extension.isNotEmpty()) {
            "${fileNameWithoutExt}_$timestamp.$extension"
        } else {
            "${fileName}_$timestamp"
        }
    }
    
    /**
     * Handle base64-encoded data download
     */
    private fun handleBase64Download(base64Data: String, fileName: String, mimeType: String) {
        try {
            Log.d(TAG, "Handling base64 download, filename: $fileName, mime: $mimeType")
            val decodedBytes = Base64.decode(base64Data, Base64.DEFAULT)
            
            // Create a temporary file
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!downloadsDir.exists()) {
                downloadsDir.mkdirs()
            }
            
            val file = File(downloadsDir, fileName)
            
            // Write decoded data to file
            FileOutputStream(file).use { it.write(decodedBytes) }
            
            // Notify media scanner to make the file visible
            val mediaScanIntent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
            val contentUri = Uri.fromFile(file)
            mediaScanIntent.data = contentUri
            context.sendBroadcast(mediaScanIntent)
            
            // If this is a TiddlyWiki file, offer to associate it with current wiki
            if (isTiddlyWikiFile(fileName)) {
                offerToReplaceCurrentWiki(file)
            } else {
                // Show success message for non-wiki files
                ThreadManager.runOnMain {
                    Toast.makeText(
                        context,
                        "Downloaded $fileName",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Base64 download error", e)
            ThreadManager.runOnMain {
                Toast.makeText(
                    context,
                    "Download failed: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /**
     * Handles regular HTTP/HTTPS download requests from WebView
     */
    private fun handleRegularDownload(
        url: String,
        userAgent: String,
        contentDisposition: String,
        mimeType: String
    ) {
        try {
            // Generate a file name based on the URL or content disposition
            val fileName = generateFileName(url, mimeType)
            
            // Create download request
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                // Set download description and notification visibility
                setDescription("Downloading file")
                setTitle(fileName)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                
                // Set destination directory
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                
                // Add cookies if needed
                val cookieManager = CookieManager.getInstance()
                val cookie = cookieManager.getCookie(url)
                if (cookie != null) {
                    addRequestHeader("Cookie", cookie)
                }
                
                // Add user agent
                addRequestHeader("User-Agent", userAgent)
            }

            // Enqueue download
            val downloadId = downloadManager.enqueue(request)
            
            // Register a receiver to listen for download completion
            if (isTiddlyWikiFile(fileName)) {
                trackHttpDownload(downloadId, fileName)
            }
            
            // Show success message
            ThreadManager.runOnMain {
                Toast.makeText(
                    context,
                    "Downloading $fileName",
                    Toast.LENGTH_SHORT
                ).show()
            }
            
        } catch (e: Exception) {
            // Show error message
            Log.e(TAG, "Download error", e)
            ThreadManager.runOnMain {
                Toast.makeText(
                    context,
                    "Download failed: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
    
    /**
     * Track HTTP download to handle file when complete
     */
    private fun trackHttpDownload(downloadId: Long, fileName: String) {
        // In a production app, you would register a BroadcastReceiver for ACTION_DOWNLOAD_COMPLETE
        // Here we'll use a polling approach for simplicity
        GlobalScope.launch(Dispatchers.IO) {
            var isComplete = false
            while (!isComplete) {
                try {
                    val query = DownloadManager.Query().setFilterById(downloadId)
                    val cursor = downloadManager.query(query)
                    
                    if (cursor.moveToFirst()) {
                        val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                        if (statusIndex != -1) {
                            val status = cursor.getInt(statusIndex)
                            
                            if (status == DownloadManager.STATUS_SUCCESSFUL) {
                                // Get the downloaded file
                                val localUriIndex = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                                if (localUriIndex != -1) {
                                    val uri = cursor.getString(localUriIndex)
                                    val file = File(Uri.parse(uri).path ?: "")
                                    
                                    // When file is successfully downloaded, offer to replace current wiki
                                    withContext(Dispatchers.Main) {
                                        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                                        val downloadedFile = File(downloadsDir, fileName)
                                        if (downloadedFile.exists()) {
                                            offerToReplaceCurrentWiki(downloadedFile)
                                        }
                                    }
                                }
                                isComplete = true
                            } else if (status == DownloadManager.STATUS_FAILED) {
                                isComplete = true
                            }
                        }
                    }
                    cursor.close()
                } catch (e: Exception) {
                    Log.e(TAG, "Error tracking download", e)
                    isComplete = true
                }
                
                // Wait a bit before checking again
                kotlinx.coroutines.delay(1000)
            }
        }
    }
    
    /**
     * Offer to replace the current wiki with the downloaded file
     */
    private fun offerToReplaceCurrentWiki(file: File) {
        // Only show dialog if we have current wiki and view model
        if (currentWiki == null || viewModel == null) {
            return
        }
        
        ThreadManager.runOnMain {
            val dialog = AlertDialog.Builder(context)
                .setTitle("Replace Wiki?")
                .setMessage("Do you want to use this downloaded file (${file.name}) as the current wiki?\n\nThe wiki will reload with this new file.")
                .setPositiveButton("Replace") { _, _ ->
                    replaceCurrentWiki(file)
                }
                .setNegativeButton("Just Save") { _, _ ->
                    Toast.makeText(context, "Downloaded ${file.name}", Toast.LENGTH_SHORT).show()
                }
                .create()
            
            dialog.show()
        }
    }
    
    /**
     * Replace the current wiki with the downloaded file
     */
    private fun replaceCurrentWiki(file: File) {
        try {
            val currentWikiCopy = currentWiki
            val viewModelRef = viewModel
            
            if (currentWikiCopy == null || viewModelRef == null) {
                Toast.makeText(context, "Cannot replace wiki: missing references", Toast.LENGTH_SHORT).show()
                return
            }
            
            // Create a content URI for the file using FileProvider
            val contentUri = try {
                // Get application ID for authority
                val authority = context.packageName + ".fileprovider"
                Log.d(TAG, "Creating FileProvider URI with authority: $authority")
                
                FileProvider.getUriForFile(context, authority, file)
            } catch (e: Exception) {
                Log.e(TAG, "Error creating FileProvider URI: ${e.message}", e)
                
                // Fallback to direct file URI for API < 24 (not recommended but as emergency fallback)
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                    Uri.fromFile(file)
                } else {
                    // Show detailed error and return without replacing
                    ThreadManager.runOnMain {
                        val errorMessage = "Unable to create FileProvider URI: ${e.message}\n" +
                                "Authority: ${context.packageName}.fileprovider\n" +
                                "File: ${file.absolutePath}\n" +
                                "Exists: ${file.exists()}"
                        
                        Toast.makeText(
                            context,
                            "Failed to replace Wiki: $errorMessage",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    return
                }
            }
            
            // Grant read permission to our own app for this URI
            context.grantUriPermission(
                context.packageName, 
                contentUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            
            Log.d(TAG, "Created content URI: $contentUri")
            
            // Create a new WikiInstance with the same name but pointing to the local file
            val newWiki = WikiInstance(
                name = currentWikiCopy.name,
                url = contentUri.toString(),
                isLocalFile = true,
                sourceUrl = currentWikiCopy.url // Keep original URL as reference
            )
            
            // Update the wiki in ViewModel
            viewModelRef.updateWiki(currentWikiCopy, newWiki)
            
            // Show success message
            Toast.makeText(
                context,
                "Wiki updated with the downloaded file",
                Toast.LENGTH_SHORT
            ).show()
            
        } catch (e: Exception) {
            Log.e(TAG, "Error replacing wiki", e)
            Toast.makeText(
                context,
                "Failed to replace wiki: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }
}