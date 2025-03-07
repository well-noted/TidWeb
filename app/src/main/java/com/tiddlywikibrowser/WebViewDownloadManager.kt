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
            val mediaScanIntent = android.content.Intent(android.content.Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
            val contentUri = Uri.fromFile(file)
            mediaScanIntent.data = contentUri
            context.sendBroadcast(mediaScanIntent)
            
            // Show success message
            ThreadManager.runOnMain {
                Toast.makeText(
                    context,
                    "Downloaded $fileName",
                    Toast.LENGTH_SHORT
                ).show()
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
            downloadManager.enqueue(request)
            
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
}