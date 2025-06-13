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
import com.tiddlywikibrowser.media.SimpleMediaManager
import com.tiddlywikibrowser.utils.MediaPlaybackOptimizer

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
                
                // Initialize MediaPlaybackOptimizer to prevent black screen and unresponsiveness
                MediaPlaybackOptimizer.optimizeWebViewForMedia(webView, context)
                
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
                
                // Inject custom select popup handler
                view?.let { injectCustomSelectPopup(it) }
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
                "ScrollInterface"            )
            webView.addJavascriptInterface(
                MediaInterface(context.applicationContext), 
                "Android"
            )
            webView.addJavascriptInterface(
                MediaInterface(context.applicationContext), 
                "MediaInterface"
            )
            
            // Connect WebView to media manager for control callbacks
            val mediaManager = SimpleMediaManager.getInstance(context.applicationContext)
            mediaManager.setWebView(webView)
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
        // Try multiple timing strategies for injection
        
        // Strategy 1: Immediate injection
        webView.post {
            performSelectPopupInjection(webView, "immediate")
        }
        
        // Strategy 2: Delayed injection (in case DOM is not ready)
        webView.postDelayed({
            performSelectPopupInjection(webView, "delayed-500ms")
        }, 500)
        
        // Strategy 3: Very delayed injection (for dynamic content)
        webView.postDelayed({
            performSelectPopupInjection(webView, "delayed-2000ms")
        }, 2000)
    }
    
    private fun performSelectPopupInjection(webView: WebView, strategy: String) {
        try {
            webView.evaluateJavascript("""
                (function() {
                    console.log('TidWeb: Starting select popup injection [$strategy]...');
                    
                    // Create unique injection key for this strategy
                    const injectionKey = '__tidwebSelectPopupInjected_$strategy';
                    
                    if (window[injectionKey]) {
                        console.log('TidWeb: Select popup already injected for strategy $strategy, skipping');
                        return 'ALREADY_INJECTED';
                    }
                    window[injectionKey] = true;
                    
                    console.log('TidWeb: Injecting custom select popup handler [$strategy]');
                    
                    // Test if we can find any select elements immediately
                    const existingSelects = document.querySelectorAll('select');
                    console.log('TidWeb: Found ' + existingSelects.length + ' existing select elements [$strategy]');
                    
                    // Add a test function to manually trigger (unique per strategy)
                    window['testSelectPopup_$strategy'] = function() {
                        console.log('TidWeb: Testing select popup [$strategy]...');
                        const select = document.querySelector('select');
                        if (select) {
                            console.log('TidWeb: Found select element for test:', select);
                            createSelectPopup(select);
                        } else {
                            console.log('TidWeb: No select elements found for test [$strategy]');
                            
                            // Create a test select element
                            const testSelect = document.createElement('select');
                            testSelect.id = 'test-select-$strategy';
                            testSelect.style.cssText = 'position: fixed; top: 10px; left: 10px; z-index: 1000; background: red; color: white;';
                            const option1 = document.createElement('option');
                            option1.value = '1';
                            option1.text = 'Test Option 1';
                            const option2 = document.createElement('option');
                            option2.value = '2';
                            option2.text = 'Test Option 2';
                            testSelect.appendChild(option1);
                            testSelect.appendChild(option2);
                            document.body.appendChild(testSelect);
                            console.log('TidWeb: Created test select element [$strategy]');
                        }
                    };
                    
                    function getComputedStyleSafe(element, property) {
                        try {
                            const style = window.getComputedStyle(element);
                            return style.getPropertyValue(property) || '';
                        } catch (e) {
                            console.error('Error getting computed style:', e);
                            return '';
                        }
                    }
                    
                    function createSelectPopup(select) {
                        console.log('TidWeb: Creating select popup for', select);
                        
                        // Prevent multiple popups
                        let oldPopup = document.getElementById('tidweb-select-popup');
                        if (oldPopup) document.body.removeChild(oldPopup);
                        
                        // Create overlay
                        const overlay = document.createElement('div');
                        overlay.id = 'tidweb-select-popup';
                        Object.assign(overlay.style, {
                            position: 'fixed',
                            left: '0',
                            top: '0',
                            width: '100vw',
                            height: '100vh',
                            background: 'rgba(0,0,0,0.5)',
                            zIndex: '99999',
                            display: 'flex',
                            alignItems: 'center',
                            justifyContent: 'center',
                            backdropFilter: 'blur(2px)'
                        });
                        
                        // Create modal container
                        const modal = document.createElement('div');
                        Object.assign(modal.style, {
                            background: '#2c3e50',
                            borderRadius: '12px',
                            minWidth: '280px',
                            maxWidth: '90vw',
                            maxHeight: '70vh',
                            overflowY: 'auto',
                            boxShadow: '0 4px 20px rgba(0,0,0,0.3)',
                            padding: '8px 0',
                            fontFamily: 'sans-serif'
                        });
                        
                        // Add title
                        const title = document.createElement('div');
                        title.textContent = select.title || 'Select an option';
                        title.style.padding = '12px 16px';
                        title.style.fontWeight = 'bold';
                        title.style.borderBottom = '1px solid rgba(255,255,255,0.1)';
                        title.style.color = '#ecf0f1';
                        modal.appendChild(title);
                        
                        // Create options container
                        const optionsContainer = document.createElement('div');
                        optionsContainer.style.maxHeight = '50vh';
                        optionsContainer.style.overflowY = 'auto';
                        
                        // Add options
                        Array.from(select.options).forEach((opt, idx) => {
                            const btn = document.createElement('button');
                            btn.textContent = opt.text;
                            
                            // Preserve original option styles
                            const originalColor = opt.style.color || getComputedStyleSafe(opt, 'color');
                            const originalBg = opt.style.backgroundColor || getComputedStyleSafe(opt, 'background-color');
                            
                            Object.assign(btn.style, {
                                display: 'flex',
                                alignItems: 'center',
                                width: '100%',
                                padding: '14px 20px',
                                background: idx === select.selectedIndex ? 'rgba(52, 152, 219, 0.2)' : 'transparent',
                                border: 'none',
                                borderBottom: '1px solid rgba(255,255,255,0.05)',
                                color: originalColor || (idx === select.selectedIndex ? '#3498db' : '#ecf0f1'),
                                textAlign: 'left',
                                fontSize: '16px',
                                cursor: 'pointer',
                                outline: 'none',
                                transition: 'all 0.2s ease',
                                fontFamily: 'inherit',
                                justifyContent: 'space-between',
                                backgroundColor: originalBg || 'transparent'
                            });
                            
                            // Add checkmark for selected item
                            if (idx === select.selectedIndex) {
                                const checkmark = document.createElement('span');
                                checkmark.textContent = '✓';
                                checkmark.style.marginLeft = '10px';
                                checkmark.style.color = '#3498db';
                                btn.appendChild(checkmark);
                            }
                            
                            btn.onmouseover = () => {
                                if (idx !== select.selectedIndex) {
                                    btn.style.background = 'rgba(255,255,255,0.1)';
                                }
                            };
                            
                            btn.onmouseout = () => {
                                if (idx !== select.selectedIndex) {
                                    btn.style.background = 'transparent';
                                }
                            };
                            
                            btn.onclick = (e) => {
                                e.stopPropagation();
                                if (select.selectedIndex !== idx) {
                                    select.selectedIndex = idx;
                                    select.value = opt.value;
                                    const event = new Event('change', { bubbles: true });
                                    select.dispatchEvent(event);
                                }
                                document.body.removeChild(overlay);
                            };
                            
                            optionsContainer.appendChild(btn);
                        });
                        
                        modal.appendChild(optionsContainer);
                        
                        // Add cancel button
                        const cancel = document.createElement('button');
                        cancel.textContent = 'Cancel';
                        Object.assign(cancel.style, {
                            display: 'block',
                            width: '100%',
                            padding: '14px',
                            background: 'transparent',
                            color: '#e74c3c',
                            border: 'none',
                            borderTop: '1px solid rgba(255,255,255,0.1)',
                            textAlign: 'center',
                            fontSize: '16px',
                            cursor: 'pointer',
                            fontWeight: 'bold',
                            borderRadius: '0 0 12px 12px',
                            transition: 'background 0.2s ease'
                        });
                        
                        cancel.onmouseover = () => {
                            cancel.style.background = 'rgba(231, 76, 60, 0.1)';
                        };
                        
                        cancel.onmouseout = () => {
                            cancel.style.background = 'transparent';
                        };
                        
                        cancel.onclick = () => {
                            document.body.removeChild(overlay);
                        };
                        
                        // Close on overlay click (outside modal)
                        overlay.onclick = (e) => {
                            if (e.target === overlay) {
                                document.body.removeChild(overlay);
                            }
                        };
                        
                        // Close on Escape key
                        const handleKeyDown = (e) => {
                            if (e.key === 'Escape') {
                                document.body.removeChild(overlay);
                                document.removeEventListener('keydown', handleKeyDown);
                            }
                        };
                        
                        document.addEventListener('keydown', handleKeyDown);
                        
                        // Cleanup on remove
                        overlay.addEventListener('removed', () => {
                            document.removeEventListener('keydown', handleKeyDown);
                        });
                        
                        modal.appendChild(cancel);
                        overlay.appendChild(modal);
                        document.body.appendChild(overlay);
                        
                        // Focus the selected option or first option
                        const selectedBtn = optionsContainer.children[select.selectedIndex] || optionsContainer.firstElementChild;
                        if (selectedBtn) selectedBtn.focus();
                    }
                    
                    // Store references to our handlers for potential cleanup (strategy-specific)
                    const handlersKey = '__tidwebSelectHandlers_$strategy';
                    window[handlersKey] = window[handlersKey] || [];
                    
                    // Handle select element clicks with better event handling
                    const clickHandler = function(e) {
                        try {
                            let target = e.target;
                            console.log('TidWeb: Click event on', target.tagName, target.className || 'no-class', target.id || 'no-id', '[$strategy]');
                            
                            // Find the select element (could be a parent of the clicked element)
                            while (target && target !== document && target.tagName !== 'SELECT') {
                                target = target.parentElement;
                            }
                            
                            if (target && target.tagName === 'SELECT') {
                                console.log('TidWeb: Select element clicked! Preventing default and showing popup [$strategy]');
                                e.preventDefault();
                                e.stopPropagation();
                                e.stopImmediatePropagation();
                                createSelectPopup(target);
                                return false;
                            }
                        } catch (error) {
                            console.error('TidWeb: Error in click handler [$strategy]:', error);
                        }
                    };
                    
                    // Also handle mousedown to catch events earlier
                    const mousedownHandler = function(e) {
                        try {
                            let target = e.target;
                            while (target && target !== document && target.tagName !== 'SELECT') {
                                target = target.parentElement;
                            }
                            
                            if (target && target.tagName === 'SELECT') {
                                console.log('TidWeb: Select mousedown, preventing default [$strategy]');
                                e.preventDefault();
                                e.stopPropagation();
                                e.stopImmediatePropagation();
                                return false;
                            }
                        } catch (error) {
                            console.error('TidWeb: Error in mousedown handler [$strategy]:', error);
                        }
                    };
                    
                    // Handle touch events for better mobile support
                    const touchHandler = function(e) {
                        try {
                            let target = e.target;
                            while (target && target !== document && target.tagName !== 'SELECT') {
                                target = target.parentElement;
                            }
                            
                            if (target && target.tagName === 'SELECT') {
                                console.log('TidWeb: Select touch event, preventing default and showing popup [$strategy]');
                                e.preventDefault();
                                e.stopPropagation();
                                e.stopImmediatePropagation();
                                createSelectPopup(target);
                                return false;
                            }
                        } catch (error) {
                            console.error('TidWeb: Error in touch handler [$strategy]:', error);
                        }
                    };
                    
                    // Remove old handlers if they exist
                    window[handlersKey].forEach(handler => {
                        document.removeEventListener('click', handler.click, true);
                        document.removeEventListener('mousedown', handler.mousedown, true);
                        document.removeEventListener('touchstart', handler.touch, { passive: false });
                        document.removeEventListener('touchend', handler.touch, { passive: false });
                    });
                    
                    // Add new handlers
                    document.addEventListener('click', clickHandler, true);
                    document.addEventListener('mousedown', mousedownHandler, true);
                    document.addEventListener('touchstart', touchHandler, { passive: false });
                    document.addEventListener('touchend', touchHandler, { passive: false });
                    
                    // Store current handlers
                    window[handlersKey] = [{
                        click: clickHandler,
                        mousedown: mousedownHandler,
                        touch: touchHandler
                    }];
                    
                    console.log('TidWeb: Custom select popup injection complete');
                    
                    // Monitor for dynamically added select elements
                    if (window.MutationObserver) {
                        const observerKey = '__tidwebSelectObserver_$strategy';
                        if (window[observerKey]) {
                            window[observerKey].disconnect();
                        }
                        
                        const observer = new MutationObserver(function(mutations) {
                            mutations.forEach(function(mutation) {
                                if (mutation.type === 'childList') {
                                    mutation.addedNodes.forEach(function(node) {
                                        if (node.nodeType === 1) { // Element node
                                            const selects = node.tagName === 'SELECT' ? [node] : node.querySelectorAll && node.querySelectorAll('select') || [];
                                            if (selects.length > 0) {
                                                console.log('TidWeb: Found', selects.length, 'new select elements [$strategy]');
                                            }
                                        }
                                    });
                                }
                            });
                        });
                        
                        observer.observe(document.body, {
                            childList: true,
                            subtree: true
                        });
                        
                        // Store observer for cleanup
                        window[observerKey] = observer;
                    }
                    
                    console.log('TidWeb: Custom select popup injection completed successfully! [$strategy]');
                    
                    // Also try to notify Android that injection is complete
                    if (window.Android && window.Android.onSelectPopupInjected) {
                        window.Android.onSelectPopupInjected('$strategy');
                    }
                    
                    return 'SUCCESS_$strategy';
                })();
            """) { result ->
                Log.d("TidWeb", "Select popup injection result [$strategy]: $result")
            }
        } catch (e: Exception) {
            Log.e("TidWeb", "Error injecting custom select popup [$strategy]", e)
        }
    }
    
    private fun injectSmallScreenCSS(webView: WebView?) {
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
    private val mediaManager = com.tiddlywikibrowser.media.SimpleMediaManager.getInstance(context)
    private val notificationManager = com.tiddlywikibrowser.media.MediaNotificationManager(context)
    
    @JavascriptInterface
    fun onMediaStateChange(title: String?, artist: String?, duration: Long, position: Long, isPlaying: Boolean) {
        Log.d("MediaInterface", "onMediaStateChange - Title: $title, IsPlaying: $isPlaying")
        
        ThreadManager.runOnMain {
            try {
                if (duration > 0) {
                    mediaManager.updateMetadata(title, artist, duration)
                }
                mediaManager.updatePlaybackState(isPlaying, position)
                
                // Update notification
                val mediaInfo = mediaManager.getCurrentMediaInfo()
                if (mediaInfo.isActive) {
                    mediaManager.getMediaSession()?.let { session ->
                        notificationManager.showNotification(session, mediaInfo)
                    }
                } else {
                    notificationManager.hideNotification()
                }
            } catch (e: Exception) {
                Log.e("MediaInterface", "Error in onMediaStateChange", e)
            }
        }
    }
    
    @JavascriptInterface
    fun updateMediaMetadata(title: String?, artist: String?, duration: Long) {
        Log.d("MediaInterface", "updateMediaMetadata - title='$title', artist='$artist', duration='$duration'")
        ThreadManager.runOnMain {
            try {
                mediaManager.updateMetadata(title, artist, duration)
                
                // Update notification if media is active
                val mediaInfo = mediaManager.getCurrentMediaInfo()
                if (mediaInfo.isActive) {
                    mediaManager.getMediaSession()?.let { session ->
                        notificationManager.showNotification(session, mediaInfo)
                    }
                }
            } catch (e: Exception) {
                Log.e("MediaInterface", "Error in updateMediaMetadata", e)
            }
        }
    }
    
    @JavascriptInterface
    fun updatePlaybackState(isPlaying: Boolean, position: Long) {
        Log.d("MediaInterface", "updatePlaybackState - isPlaying='$isPlaying', position='$position'")
        ThreadManager.runOnMain {
            try {
                mediaManager.updatePlaybackState(isPlaying, position)
                
                // Update notification
                val mediaInfo = mediaManager.getCurrentMediaInfo()
                if (mediaInfo.isActive) {
                    mediaManager.getMediaSession()?.let { session ->
                        notificationManager.showNotification(session, mediaInfo)
                    }
                } else {
                    notificationManager.hideNotification()
                }
            } catch (e: Exception) {
                Log.e("MediaInterface", "Error in updatePlaybackState", e)
            }
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
            try {
                val effectiveTitle = if (title.isNullOrBlank()) "TiddlyWiki Audio" else title
                val durationMs = (duration * 1000).toLong()
                val positionMs = (currentTime * 1000).toLong()
                
                when (event.lowercase()) {
                    "play", "playing" -> {
                        mediaManager.updateMetadata(effectiveTitle, "TiddlyWiki", durationMs)
                        mediaManager.updatePlaybackState(true, positionMs)
                    }
                    "pause" -> {
                        mediaManager.updatePlaybackState(false, positionMs)
                    }
                    "ended" -> {
                        mediaManager.updatePlaybackState(false, durationMs)
                        mediaManager.onMediaInactive()
                        notificationManager.hideNotification()
                        return@runOnMain
                    }
                    "loadedmetadata" -> {
                        mediaManager.updateMetadata(effectiveTitle, "TiddlyWiki", durationMs)
                    }
                }
                
                // Update notification for active events
                val mediaInfo = mediaManager.getCurrentMediaInfo()
                if (mediaInfo.isActive) {
                    mediaManager.getMediaSession()?.let { session ->
                        notificationManager.showNotification(session, mediaInfo)
                    }
                }
            } catch (e: Exception) {
                Log.e("MediaInterface", "Error in onMediaEvent", e)
            }
        }
    }
}