package com.physics.tuitionmanager

import android.content.Context
import androidx.work.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class BackupWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        val store = DataStore(applicationContext)
        if (store.batches.isEmpty() && store.students.isEmpty()) return Result.success()
        val dir = File(applicationContext.filesDir, "backups")
        if (!dir.exists()) dir.mkdirs()
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        File(dir, "snapshot_$ts.json").writeText(store.exportJson())
        val files = dir.listFiles()?.sortedBy { it.name } ?: return Result.success()
        if (files.size > 8) files.take(files.size - 8).forEach { it.delete() }
        applicationContext.getSharedPreferences("physics_data", Context.MODE_PRIVATE)
            .edit().putString("lastInternalBackup",
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())).apply()
        return Result.success()
    }

    companion object {
        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<BackupWorker>(7, TimeUnit.DAYS).build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                "auto_backup", ExistingPeriodicWorkPolicy.KEEP, req
            )
        }
    }
}
