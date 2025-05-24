package com.tiddlywikibrowser.webview

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import android.widget.FrameLayout
import android.widget.Toast
import androidx.webkit.WebViewAssetLoader
import com.tiddlywikibrowser.MainActivity
import com.tiddlywikibrowser.R
import com.tiddlywikibrowser.ThreadManager
import com.tiddlywikibrowser.ScreenUtils
import com.tiddlywikibrowser.WebViewDownloadManager
import com.tiddlywikibrowser.media.MediaSessionManager

object WebViewFactory {
    
    private const val TAG = "WebViewFactory"
    
    @SuppressLint("SetJavaScriptEnabled")
    fun createWebView(context: Context): WebView {
        val webView = WebView(context.applicationContext)
        
        ThreadManager.runOnMain {
            try {
                configureWebViewSettings(webView, context)
                setupWebViewClient(webView, context)
                setupWebChromeClient(webView, context)
                setupJavaScriptInterfaces(webView, context)
                initializeStatePreservation(webView)
                
                // Setup download manager
                val downloadManager = WebViewDownloadManager(context.applicationContext)
                downloadManager.setupDownloadListener(webView)
                
            } catch (e: Exception) {
                Log.e(TAG, "Error creating WebView", e)
            }
        }
        
        return webView
    }
    
    private fun configureWebViewSettings(webView: WebView, context: Context) {
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        webView.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            cacheMode = WebSettings.LOAD_DEFAULT
            allowFileAccess = true
            
            // Critical: Initialize proper caching mode for state persistence
            cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK
            domStorageEnabled = true
            databaseEnabled = true
            
            // Set save state flags
            saveFormData = true
            savePassword = true
            
            // Ensure proper viewport settings
            useWideViewPort = true
            loadWithOverviewMode = true
            
            // Important for state preservation
            setGeolocationEnabled(false)
            mediaPlaybackRequiresUserGesture = false
            
            // Apply text zoom based on screen size
            val textZoom = ScreenUtils.getWebViewTextZoom(context)
            setTextZoom(textZoom)
            
            // Force accessibility mode to ensure pinch-to-zoom always works
            if (ScreenUtils.shouldForceWebViewZoom(context)) {
                builtInZoomControls = true
                displayZoomControls = false
            }
            
            // Optimize for very small screens
            if (ScreenUtils.isVerySmallScreen(context)) {
                defaultFontSize = (defaultFontSize * 0.9).toInt()
                minimumFontSize = 8
                layoutAlgorithm = WebSettings.LayoutAlgorithm.TEXT_AUTOSIZING
            }
            
            // Critical: Initialize DOM/Database storage
            try {
                databasePath = context.getDir("database", Context.MODE_PRIVATE).path
            } catch (e: Exception) {
                e.printStackTrace()
            }
            
            // Safely enable file access
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                try {
                    javaClass.getMethod("setAllowFileAccessFromFileURLs", Boolean::class.java)
                        .invoke(this, true)
                    javaClass.getMethod("setAllowUniversalAccessFromFileURLs", Boolean::class.java)
                        .invoke(this, true)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        
        // CRITICAL: Initialize WebView state flags
        webView.setTag(R.string.prevent_reload_tag, false)
    }
    
    private fun setupWebViewClient(webView: WebView, context: Context) {
        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
            .addPathHandler("/res/", WebViewAssetLoader.ResourcesPathHandler(context))
            .build()
        
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                request?.url?.let { url ->
                    if (url.scheme == "file") {
                        return assetLoader.shouldInterceptRequest(url)
                    }
                }
                return super.shouldInterceptRequest(view, request)
            }
            
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                
                val mainActivity = context as? MainActivity
                if (mainActivity != null && mainActivity.isBackgroundEnabled.value) {
                    handleBackgroundModeRegistration(mainActivity, view, url)
                }
                
                if (ScreenUtils.isVerySmallScreen(context)) {
                    injectSmallScreenCSS(view)
                }
            }
        }
    }
    
    private fun setupWebChromeClient(webView: WebView, context: Context) {
        webView.webChromeClient = object : WebChromeClient() {
            private var customView: View? = null
            private var customViewCallback: CustomViewCallback? = null
            private var originalSystemUiVisibility = 0
            
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                customView = view
                customViewCallback = callback
                (context as? MainActivity)?.let { activity ->
                    handleFullscreenVideo(activity, view, true)
                }
            }
            
            override fun onHideCustomView() {
                (context as? MainActivity)?.let { activity ->
                    handleFullscreenVideo(activity, customView, false)
                }
                customView = null
                customViewCallback?.onCustomViewHidden()
                customViewCallback = null
            }
            
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                
                if (newProgress > 50 && !view?.settings?.loadsImagesAutomatically!!) {
                    ThreadManager.runOnBackground {
                        Thread.sleep(100)
                        ThreadManager.runOnMain {
                            view.settings.loadsImagesAutomatically = true
                        }
                    }
                }
                
                if (newProgress > 75 && ScreenUtils.isVerySmallScreen(context)) {
                    injectSmallScreenCSS(view)
                }
            }
            
            override fun onJsBeforeUnload(
                view: WebView?,
                url: String?,
                message: String?,
                result: JsResult?
            ): Boolean {
                result?.cancel()
                return true
            }
            
            override fun onJsAlert(
                view: WebView?,
                url: String?,
                message: String?,
                result: JsResult?
            ): Boolean {
                ThreadManager.runOnMain {
                    Toast.makeText(context, message ?: "Alert", Toast.LENGTH_SHORT).show()
                }
                result?.confirm()
                return true
            }
        }
    }
    
    private fun setupJavaScriptInterfaces(webView: WebView, context: Context) {
        try {
            webView.addJavascriptInterface(
                ScrollInterface(context.applicationContext), 
                "ScrollInterface"
            )
            webView.addJavascriptInterface(
                MediaInterface(context.applicationContext), 
                "Android"
            )
            webView.addJavascriptInterface(
                MediaInterface(context.applicationContext), 
                "MediaInterface"
            )
            webView.addJavascriptInterface(
                ExoPlayerInterface(context), 
                "ExoPlayerInterface"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error adding JavaScript interfaces", e)
        }
    }
    
    private fun initializeStatePreservation(webView: WebView) {
        webView.evaluateJavascript("""
            (function() {
                if (!window.__stateInitialized) {
                    window.__savedState = {};
                    
                    document.addEventListener('pause', function() {
                        try {
                            console.log('[State] Saved state:', JSON.stringify(window.__savedState));
                        } catch(e) {}
                    });
                    
                    document.addEventListener('resume', function() {
                        try {
                        } catch(e) {}
                    });
                    
                    window.__stateInitialized = true;
                    window.__statePreservationHandlersAttached = true;
                    console.log('[State] State preservation initialized');
                }
                return true;
            })();
        """.trimIndent(), null)
        
        injectCustomSelectPopup(webView)
    }
    
    private fun injectCustomSelectPopup(webView: WebView) {
        webView.post {
            webView.evaluateJavascript("""
                (function() {
                    if (window.__tidwebSelectPopupInjected) return;
                    window.__tidwebSelectPopupInjected = true;
                    function createSelectPopup(select) {
                        let oldPopup = document.getElementById('tidweb-select-popup');
                        if (oldPopup) oldPopup.parentNode.removeChild(oldPopup);
                        let overlay = document.createElement('div');
                        overlay.id = 'tidweb-select-popup';
                        overlay.style.position = 'fixed';
                        overlay.style.left = '0';
                        overlay.style.top = '0';
                        overlay.style.width = '100vw';
                        overlay.style.height = '100vh';
                        overlay.style.background = 'rgba(0,0,0,0.4)';
                        overlay.style.zIndex = '99999';
                        overlay.style.display = 'flex';
                        overlay.style.alignItems = 'center';
                        overlay.style.justifyContent = 'center';
                        let modal = document.createElement('div');
                        modal.style.background = '#74992e';
                        modal.style.borderRadius = '8px';
                        modal.style.minWidth = '220px';
                        modal.style.maxWidth = '90vw';
                        modal.style.maxHeight = '70vh';
                        modal.style.overflowY = 'auto';
                        modal.style.boxShadow = '0 2px 16px rgba(0,0,0,0.25)';
                        modal.style.padding = '12px 0';
                        Array.from(select.options).forEach(function(opt, idx) {
                            let btn = document.createElement('button');
                            btn.textContent = opt.text;
                            btn.style.display = 'block';
                            btn.style.width = '100%';
                            btn.style.padding = '12px 18px';
                            btn.style.background = idx === select.selectedIndex ? '#0076bd' : 'transparent';
                            btn.style.border = 'none';
                            btn.style.textAlign = 'left';
                            btn.style.fontSize = '16px';
                            btn.style.cursor = 'pointer';
                            btn.style.outline = 'none';
                            btn.onmouseover = function() { btn.style.background = '#f0f4ff'; };
                            btn.onmouseout = function() { btn.style.background = idx === select.selectedIndex ? '#e0e7ff' : 'transparent'; };
                            btn.onclick = function(e) {
                                select.selectedIndex = idx;
                                select.value = opt.value;
                                select.dispatchEvent(new Event('change', {bubbles:true}));
                                document.body.removeChild(overlay);
                            };
                            modal.appendChild(btn);
                        });
                        let cancel = document.createElement('button');
                        cancel.textContent = 'Cancel';
                        cancel.style.display = 'block';
                        cancel.style.width = '100%';
                        cancel.style.padding = '12px 18px';
                        cancel.style.background = '#ef4a53';
                        cancel.style.border = 'none';
                        cancel.style.textAlign = 'center';
                        cancel.style.fontSize = '16px';
                        cancel.style.marginTop = '8px';
                        cancel.onclick = function() {
                            document.body.removeChild(overlay);
                        };
                        modal.appendChild(cancel);
                        overlay.appendChild(modal);
                        document.body.appendChild(overlay);
                    }
                    document.addEventListener('click', function(e) {
                        if (e.target && e.target.tagName === 'SELECT') {
                            e.preventDefault();
                            createSelectPopup(e.target);
                        }
                    }, true);
                })();
            """, null)
        }
    }
    
    fun injectSmallScreenCSS(webView: WebView?) {
        webView?.evaluateJavascript("""
            (function() {
                let existingStyle = document.getElementById('tidweb-small-screen-styles');
                if (existingStyle) {
                    existingStyle.parentNode.removeChild(existingStyle);
                }
                
                let styleEl = document.createElement('style');
                styleEl.id = 'tidweb-small-screen-styles';
                styleEl.textContent = `
                    /* Optimize TiddlyWiki for very small screens */
                    .tc-tiddler-frame {
                        padding: 0.5em !important;
                        margin: 0.25em 0 !important;
                    }
                    
                    .tc-tiddler-title, .tc-site-title {
                        font-size: 1.2em !important;
                        line-height: 1.2 !important;
                        margin: 0 0 0.5em 0 !important;
                    }
                    
                    .tc-titlebar {
                        margin-bottom: 0.3em !important;
                    }
                    
                    .tc-subtitle {
                        font-size: 0.7em !important;
                        margin: 0 !important;
                    }
                    
                    .tc-tiddler-controls {
                        font-size: 0.85em !important;
                    }
                    
                    .tc-tiddler-controls .tc-btn-invisible {
                        padding: 0.15em !important;
                        margin: 0 0.1em !important;
                    }
                    
                    .tc-drop-down {
                        padding: 0.3em !important;
                        font-size: 0.9em !important;
                    }
                    
                    .tc-drop-down a {
                        padding: 0.2em 0.4em !important;
                    }
                    
                    .tc-tiddler-body {
                        margin: 0.3em 0 !important;
                        font-size: 0.95em !important;
                        line-height: 1.3 !important;
                    }
                    
                    .tc-sidebar-lists {
                        padding: 0.3em !important;
                    }
                    
                    .tc-sidebar-tab-open {
                        font-size: 0.9em !important;
                    }
                    
                    pre, code, .tc-table-of-contents {
                        max-width: 100% !important;
                        overflow-x: auto !important;
                    }
                    
                    button, .tc-btn-invisible, a {
                        min-height: 22px !important;
                        min-width: 22x !important;
                    }
                    
                    img {
                        max-width: 100% !important;
                        height: auto !important;
                    }
                    
                    .tc-modal {
                        padding: 0.3em !important;
                    }
                    
                    input, select, textarea {
                        font-size: 0.95em !important;
                    }
                `;
                
                document.head.appendChild(styleEl);
                console.log('Small screen CSS adaptations applied');
                return true;
            })();
        """, null)
    }
    
    private fun handleBackgroundModeRegistration(
        activity: MainActivity,
        view: WebView?,
        url: String?
    ) {
        val viewModel = MainActivity.getViewModel(activity)
        viewModel.currentWiki.value?.let { wiki ->
            val key = wiki.idFromUrl ?: wiki.url
            if (url?.contains(key) == true || url?.contains(wiki.url) == true) {
                activity.backgroundWebViewManager.registerWebView(key, view!!)
                Log.d(TAG, "Registered WebView on page finished for background mode")
            }
        }
    }
    
    private fun handleFullscreenVideo(
        activity: MainActivity,
        view: View?,
        isFullscreen: Boolean
    ) {
        if (isFullscreen) {
            val originalSystemUiVisibility = activity.window.decorView.systemUiVisibility
            
            activity.window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
            
            val decorView = activity.window.decorView as FrameLayout
            decorView.addView(view, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT))
            
            activity.actionBar?.hide()
            activity.setMainContentVisible(false)
            
            if (view is androidx.media3.ui.PlayerView) {
                activity.exoPlayerManager.getOrCreatePlayer().also { player ->
                    view.player = player
                }
            }
            
            activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity.window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
            
            val decorView = activity.window.decorView as FrameLayout
            if (view != null) {
                decorView.removeView(view)
            }
            
            activity.actionBar?.show()
            activity.setMainContentVisible(true)
            
            activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}

// JavaScript Interface classes
class ScrollInterface(private val context: Context) {
    @JavascriptInterface
    fun onScroll(showBars: Boolean) {
        ThreadManager.runOnMain {
            try {
                MainActivity.getViewModel(context).setFrameVisible(showBars)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    
    @JavascriptInterface
    fun preventClose() {
        // Do nothing, just a hook to keep the app open
    }
    
    @JavascriptInterface
    fun reportPerformance(metric: String, value: String) {
        // Could be used for telemetry or debugging
    }
}

class MediaInterface(private val context: Context) {
    @JavascriptInterface
    fun onMediaStateChange(title: String?, artist: String?, duration: Long, position: Long, isPlaying: Boolean) {
        Log.d("MediaInterface", "onMediaStateChange - Title: $title, IsPlaying: $isPlaying")
        
        ThreadManager.runOnMain {
            try {
                val msm = MediaSessionManager.getInstance(context)
                msm.updateMetadata(title, artist, duration)
                msm.updatePlaybackState(isPlaying, position)
            } catch (e: Exception) {
                Log.e("MediaInterface", "Error in onMediaStateChange", e)
            }
        }
    }
    
    @JavascriptInterface
    fun updateMediaMetadata(title: String?, artist: String?, album: String?, artworkUrl: String?, duration: Long) {
        Log.d("MediaInterface", "updateMediaMetadata - title='$title', artist='$artist'")
        ThreadManager.runOnMain {
            val msm = MediaSessionManager.getInstance(context)
            msm.updateMetadata(title, artist, duration, null)
        }
    }
    
    @JavascriptInterface
    fun updatePlaybackState(isPlaying: Boolean, position: Long) {
        Log.d("MediaInterface", "updatePlaybackState - isPlaying='$isPlaying', position='$position'")
        ThreadManager.runOnMain {
            val msm = MediaSessionManager.getInstance(context)
            msm.updatePlaybackState(isPlaying, position)
        }
    }
    
    @JavascriptInterface
    fun onMediaEvent(
        event: String,
        elementId: String,
        currentTime: Float,
        duration: Float,
        src: String?,
        title: String?
    ) {
        Log.d("MediaInterface", "onMediaEvent - event='$event', title='$title'")
        ThreadManager.runOnMain {
            val effectiveTitle = if (title.isNullOrBlank()) "TiddlyWiki Audio" else title
            val msm = MediaSessionManager.getInstance(context)
            
            when (event.lowercase()) {
                "play", "playing" -> {
                    msm.updateMetadata(effectiveTitle, "TiddlyWiki", (duration * 1000).toLong())
                    msm.updatePlaybackState(true, (currentTime * 1000).toLong())
                }
                "pause" -> {
                    msm.updatePlaybackState(false, (currentTime * 1000).toLong())
                }
                "ended" -> {
                    msm.updatePlaybackState(false, (duration * 1000).toLong())
                }
                "timeupdate" -> {
                    msm.updatePlaybackState(true, (currentTime * 1000).toLong())
                }
                "loadedmetadata" -> {
                    msm.updateMetadata(effectiveTitle, "TiddlyWiki", (duration * 1000).toLong())
                }
            }
        }
    }
}

class ExoPlayerInterface(private val context: Context) {
    @JavascriptInterface
    fun playMedia(url: String) {
        (context as? MainActivity)?.let { activity ->
            activity.runOnUiThread {
                activity.exoPlayerManager.playMedia(url)
            }
        }
    }
} 