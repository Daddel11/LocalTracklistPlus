package com.libreplayer.data.download

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.libreplayer.app.LibrePlayerApplication

class OfflineDownloadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val trackId = inputData.getString(TRACK_ID) ?: return Result.failure()
        val app = applicationContext as? LibrePlayerApplication ?: return Result.failure()
        return app.appContainer.offlineRepository.performDownload(trackId)
    }

    companion object {
        const val TRACK_ID = "trackId"
    }
}
