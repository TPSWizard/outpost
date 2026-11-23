package com.conza.outpost.food

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.conza.outpost.MainActivity
import com.conza.outpost.R
import com.conza.outpost.container
import java.util.concurrent.TimeUnit

/** A best-effort reminder. A dedicated timer and direct canner supervision remain necessary. */
class FoodTimerWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString("batch") ?: return Result.success()
        val stage = inputData.getString("stage") ?: return Result.success()
        val batch = applicationContext.container.food.snapshot().state.batches.firstOrNull { it.id == id } ?: return Result.success()
        if (batch.stage != stage || batch.timerEndsAt != inputData.getLong("ends", -1)) return Result.success()
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(applicationContext, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return Result.success()
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel("food-timers", "Food preservation reminders", NotificationManager.IMPORTANCE_HIGH))
        val intent = PendingIntent.getActivity(applicationContext, id.hashCode(), Intent(applicationContext, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        runCatching { manager.notify(id.hashCode(), NotificationCompat.Builder(applicationContext, "food-timers")
            .setSmallIcon(R.drawable.ic_launcher).setContentTitle("Check ${batch.food}")
            .setContentText("${stage.replace('_', ' ')} reminder. Check the canner; pressure is not measured by Outpost.")
            .setContentIntent(intent).setAutoCancel(true).build()) }
        return Result.success()
    }
    companion object {
        fun schedule(context: Context, batch: CanningBatch) {
            val work = WorkManager.getInstance(context)
            val name = "food-timer-${batch.id}"
            val end = batch.timerEndsAt
            if (end == null) { work.cancelUniqueWork(name); return }
            work.enqueueUniqueWork(name, ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<FoodTimerWorker>()
                .setInitialDelay((end - System.currentTimeMillis()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
                .setInputData(workDataOf("batch" to batch.id, "stage" to batch.stage, "ends" to end)).build())
        }
    }
}
