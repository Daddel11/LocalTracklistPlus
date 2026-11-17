package com.libreplayer.app

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

class LibrePlayerApplication : Application() {
    lateinit var appContainer: AppContainer
        private set
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val recoveryStarted = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        appContainer = AppContainer(this)
    }

    fun recoverOfflineDownloads() {
        if (!recoveryStarted.compareAndSet(false, true)) return
        applicationScope.launch {
            try {
                appContainer.offlineRepository.recoverDownloads()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                recoveryStarted.set(false)
                android.util.Log.w("LocalTracklist", "Offline download recovery deferred until retry")
            }
        }
    }
}

