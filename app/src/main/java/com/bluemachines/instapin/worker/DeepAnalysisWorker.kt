package com.bluemachines.instapin.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.bluemachines.instapin.InstaPinApp

/**
 * Phase 2 Background Worker: Runs deep multimodal/caption/transcript analysis and generates
 * the Repurpose Script & Shot List via Google Gemini API without blocking the UI.
 */
class DeepAnalysisWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val pinId = inputData.getLong(KEY_PIN_ID, -1L)
        if (pinId <= 0L) return Result.failure()

        val app = applicationContext as? InstaPinApp ?: return Result.failure()
        return runCatching {
            val analysis = app.repository.runPhase2DeepAnalysis(pinId)
            if (analysis != null) Result.success() else Result.retry()
        }.getOrElse {
            Result.retry()
        }
    }

    companion object {
        private const val KEY_PIN_ID = "pin_id"

        fun enqueue(context: Context, pinId: Long) {
            val request = OneTimeWorkRequestBuilder<DeepAnalysisWorker>()
                .setInputData(workDataOf(KEY_PIN_ID to pinId))
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                "phase2_analysis_$pinId",
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }
}
