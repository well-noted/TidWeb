package com.tiddlywikibrowser

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.util.Log
import kotlinx.coroutines.*
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentLinkedQueue

object ThreadManager {
    private const val TAG = "ThreadManager"
    
    // Operation queue for batching
    private val operationQueue = ConcurrentLinkedQueue<() -> Unit>()
    private var isProcessingQueue = false
    private val queueLock = Any()
    
    // Coroutine dispatchers
    private val backgroundDispatcher = Dispatchers.Default
    private val ioDispatcher = Dispatchers.IO
    private val mainDispatcher = Dispatchers.Main
    
    // Use a higher priority thread for background operations that need to be responsive
    private val backgroundThread = HandlerThread("TidWebBackground", Process.THREAD_PRIORITY_BACKGROUND).apply { 
        start() 
    }
    
    // Use a lower priority thread for non-critical operations
    private val lowPriorityThread = HandlerThread("TidWebLowPriority", Process.THREAD_PRIORITY_LOWEST).apply { 
        start() 
    }
    
    private val backgroundHandler = Handler(backgroundThread.looper)
    private val lowPriorityHandler = Handler(lowPriorityThread.looper)
    private val mainHandler = Handler(Looper.getMainLooper())
    
    // Use a custom thread factory to set thread priorities
    private val threadFactory = object : ThreadFactory {
        private val threadNumber = AtomicInteger(1)
        private val group: ThreadGroup = Thread.currentThread().threadGroup
        
        override fun newThread(r: Runnable): Thread {
            val thread = Thread(group, r, "TidWeb-pool-${threadNumber.getAndIncrement()}", 0)
            if (thread.isDaemon) thread.isDaemon = false
            if (thread.priority != Thread.NORM_PRIORITY) thread.priority = Thread.NORM_PRIORITY
            return thread
        }
    }
    
    // Use a properly configured ThreadPoolExecutor instead of fixed pool for better resource management
    private val executor = ThreadPoolExecutor(
        2, // Core pool size
        4, // Max pool size
        60L, TimeUnit.SECONDS, // Keep alive time for idle threads
        LinkedBlockingQueue<Runnable>(20), // Queue with bounded capacity to prevent OOM
        threadFactory,
        ThreadPoolExecutor.CallerRunsPolicy() // If queue is full, run task in caller's thread
    )
    
    // Use a separate executor for file IO operations
    private val ioExecutor = Executors.newSingleThreadExecutor(threadFactory)
    
    // Coroutine scope for background tasks
    private val backgroundScope = CoroutineScope(backgroundDispatcher + SupervisorJob())
    private val ioScope = CoroutineScope(ioDispatcher + SupervisorJob())
    
    // Queue for WebView operations
    private val webViewOperationQueue = ConcurrentLinkedQueue<Pair<Int, () -> Unit>>()
    private var isProcessingWebViewQueue = false
    
    /**
     * Enqueue an operation with a priority (higher number = higher priority)
     */
    fun enqueueWebViewOperation(priority: Int = 0, operation: () -> Unit) {
        webViewOperationQueue.add(Pair(priority, operation))
        processWebViewOperationQueue()
    }
    
    /**
     * Process the WebView operation queue in order of priority
     */
    private fun processWebViewOperationQueue() {
        synchronized(queueLock) {
            if (isProcessingWebViewQueue) return
            isProcessingWebViewQueue = true
        }
        
        // Process operations on a background coroutine
        backgroundScope.launch {
            try {
                while (webViewOperationQueue.isNotEmpty()) {
                    // Sort by priority and take the highest one
                    val sortedOperations = webViewOperationQueue.sortedByDescending { it.first }
                    val nextOperation = webViewOperationQueue.firstOrNull()
                    
                    // Remove it from the queue
                    webViewOperationQueue.remove(nextOperation)
                    
                    // Execute the operation on the main thread
                    withContext(Dispatchers.Main) {
                        try {
                            nextOperation?.second?.invoke()
                        } catch (e: Exception) {
                            Log.e(TAG, "Error running WebView operation: ${e.message}")
                        }
                    }
                    
                    // Small delay to avoid blocking the main thread
                    delay(16) // Roughly one frame
                }
            } finally {
                synchronized(queueLock) {
                    isProcessingWebViewQueue = false
                }
                
                // If any new operations were added during processing, restart
                if (webViewOperationQueue.isNotEmpty()) {
                    processWebViewOperationQueue()
                }
            }
        }
    }
    
    /**
     * Add an operation to the batch queue
     */
    fun enqueueBatch(operation: () -> Unit) {
        operationQueue.add(operation)
        processBatchQueue()
    }
    
    /**
     * Process the batch queue when safe to do so
     */
    private fun processBatchQueue() {
        synchronized(queueLock) {
            if (isProcessingQueue) return
            isProcessingQueue = true
        }
        
        backgroundScope.launch {
            try {
                // Process up to 10 operations at a time to prevent long-running batches
                var count = 0
                val batchSize = 10
                
                while (operationQueue.isNotEmpty() && count < batchSize) {
                    val operation = operationQueue.poll() ?: break
                    try {
                        operation.invoke()
                        count++
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in batch operation: ${e.message}")
                    }
                    
                    // Small yield to avoid blocking
                    if (count % 3 == 0) {
                        delay(5)
                    }
                }
            } finally {
                synchronized(queueLock) {
                    isProcessingQueue = false
                }
                
                // If more operations remain, continue processing
                if (operationQueue.isNotEmpty()) {
                    processBatchQueue()
                }
            }
        }
    }
    
    // Using coroutines for background tasks
    fun runOnBackgroundCoroutine(task: suspend () -> Unit) {
        backgroundScope.launch {
            try {
                task()
            } catch (e: Exception) {
                Log.e(TAG, "Error in background coroutine: ${e.message}")
            }
        }
    }
    
    fun runOnIOCoroutine(task: suspend () -> Unit) {
        ioScope.launch {
            try {
                task()
            } catch (e: Exception) {
                Log.e(TAG, "Error in IO coroutine: ${e.message}")
            }
        }
    }
    
    fun <T> withMainCoroutine(task: suspend () -> T): Deferred<T> {
        return CoroutineScope(mainDispatcher).async {
            task()
        }
    }
    
    // Maintain existing API for compatibility
    fun runOnBackground(task: () -> Unit) {
        backgroundHandler.post(task)
    }
    
    fun runOnLowPriority(task: () -> Unit) {
        lowPriorityHandler.post(task)
    }
    
    fun runOnBackgroundWithDelay(delayMillis: Long, task: () -> Unit) {
        backgroundHandler.postDelayed(task, delayMillis)
    }
    
    fun runOnMain(task: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            task()
        } else {
            mainHandler.post(task)
        }
    }
    
    fun runOnMainWithDelay(delayMillis: Long, task: () -> Unit) {
        mainHandler.postDelayed(task, delayMillis)
    }
    
    fun executeTask(task: () -> Unit) {
        executor.execute(task)
    }
    
    fun executeIOTask(task: () -> Unit) {
        ioExecutor.execute(task)
    }
    
    fun <T> executeWithResult(task: () -> T, onComplete: (T) -> Unit) {
        executor.execute {
            try {
                val result = task()
                runOnMain { onComplete(result) }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    
    fun shutdown() {
        executor.shutdown()
        ioExecutor.shutdown()
        try {
            if (!executor.awaitTermination(1, TimeUnit.SECONDS)) {
                executor.shutdownNow()
            }
            if (!ioExecutor.awaitTermination(1, TimeUnit.SECONDS)) {
                ioExecutor.shutdownNow()
            }
        } catch (e: InterruptedException) {
            executor.shutdownNow()
            ioExecutor.shutdownNow()
            Thread.currentThread().interrupt()
        }
        
        backgroundThread.quitSafely()
        lowPriorityThread.quitSafely()
        
        // Cancel coroutine jobs
        backgroundScope.cancel()
        ioScope.cancel()
    }
}