package com.bluemachines.instapin.worker

import android.content.Context
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.bluemachines.instapin.InstaPinApp

/**
 * Phase 3 Background Worker: Parses Instagram's `liked_posts.json` or `saved_posts.json`,
 * deduplicates links, enriches metadata, and auto-categorizes into Boards & Domains.
 */
class HistoricalImportWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val fileUriStr = inputData.getString(KEY_FILE_URI) ?: return Result.failure()
        val fileName = inputData.getString(KEY_FILE_NAME) ?: "liked_posts.json"
        val enrichOnline = inputData.getBoolean(KEY_ENRICH_ONLINE, false)

        val app = applicationContext as? InstaPinApp ?: return Result.failure()

        return runCatching {
            val uri = Uri.parse(fileUriStr)
            val content = applicationContext.contentResolver.openInputStream(uri)
                ?.bufferedReader()
                ?.use { it.readText() }
                ?: return Result.failure()

            app.repository.importHistoricalInstagramJson(
                fileName = fileName,
                rawJsonContent = content,
                enrichOnlineMetadata = enrichOnline
            ) { processed, total ->
                setProgress(
                    workDataOf(
                        PROGRESS_PROCESSED to processed,
                        PROGRESS_TOTAL to total
                    )
                )
            }
            Result.success()
        }.getOrElse {
            Result.failure()
        }
    }

    companion object {
        private const val KEY_FILE_URI = "file_uri"
        private const val KEY_FILE_NAME = "file_name"
        private const val KEY_ENRICH_ONLINE = "enrich_online"
        const val PROGRESS_PROCESSED = "processed"
        const val PROGRESS_TOTAL = "total"
        const val UNIQUE_WORK_NAME = "phase3_historical_import"

        fun enqueue(
            context: Context,
            fileUri: Uri,
            fileName: String,
            enrichOnline: Boolean
        ) {
            val request = OneTimeWorkRequestBuilder<HistoricalImportWorker>()
                .setInputData(
                    workDataOf(
                        KEY_FILE_URI to fileUri.toString(),
                        KEY_FILE_NAME to fileName,
                        KEY_ENRICH_ONLINE to enrichOnline
                    )
                )
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }
}
