package com.lichiai.memory.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.lichiai.memory.manager.LichiMemoryEngine

/**
 * Android WorkManager CoroutineWorker for periodic memory consolidation and maintenance.
 * Compresses vector index, prunes tombstones, and reconciles Room data with HNSW graph.
 */
class MemoryConsolidationWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "MemoryConsolidationWork"
    }

    override suspend fun doWork(): Result {
        return try {
            Log.i(TAG, "Starting periodic WorkManager memory consolidation task...")
            val engine = LichiMemoryEngine.getInstance(applicationContext)
            val success = engine.performMaintenance()
            if (success) {
                Log.i(TAG, "Periodic memory consolidation completed successfully.")
                Result.success()
            } else {
                Log.w(TAG, "Periodic memory consolidation returned false, scheduling retry.")
                Result.retry()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during periodic memory consolidation", e)
            Result.retry()
        }
    }
}
