package com.tiddlywikibrowser

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

object ThreadManager {
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
    }
}