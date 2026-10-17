package com.newoether.agora.service

import android.content.Context
import androidx.work.Constraints
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.ExistingWorkPolicy
import com.newoether.agora.AgoraApplication
import com.newoether.agora.data.BackupResult
import com.newoether.agora.util.DebugLog
import java.util.concurrent.TimeUnit

class AutoBackupWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        DebugLog.d("AutoBackup", "Worker: checking backup")
        val manager = (applicationContext as AgoraApplication)
            .awaitContainer()
            ?.autoBackupManager
            ?: return Result.failure()

        return try {
            // This worker ticks hourly but backups are far less frequent; exit silently when
            // nothing is due so the foreground notification only shows for actual exports.
            if (!manager.isBackupDue()) return Result.success()

            // A full export can exceed the background execution window on large databases,
            // so promote to a foreground service before starting it.
            setForeground(AutomationForegroundInfo.forAutoBackup(applicationContext))

            when (manager.checkAndBackup()) {
                BackupResult.FAILED -> {
                    DebugLog.w("AutoBackup", "Worker: backup failed, retrying")
                    Result.retry()
                }
                else -> Result.success()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            DebugLog.e("AutoBackup", "Worker: unexpected error", e)
            Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "auto_backup_periodic"
        private const val TAG = "auto_backup"

        fun schedule(context: Context) {
            // Startup checks use the same foreground execution owner as periodic backups.
            WorkManager.getInstance(context).enqueueUniqueWork(
                "auto_backup_startup",
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<AutoBackupWorker>().addTag(TAG).build(),
            )
            val request = PeriodicWorkRequestBuilder<AutoBackupWorker>(1, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    30, TimeUnit.SECONDS
                )
                .addTag(TAG)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork("auto_backup_startup")
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
