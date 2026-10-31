package com.conza.outpost.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.conza.outpost.OutpostApp
import com.conza.outpost.catalog.ResourceKind
import com.conza.outpost.data.InstalledResource
import com.conza.outpost.storage.FileFormats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.coroutines.coroutineContext

/**
 * Resumable download of one catalog item. Replaces NOMAD's BullMQ `run_download_job`.
 *
 * - Writes to `<file>.part` and sends `Range:` on retry, so a multi-GB ZIM survives
 *   dropped connections, app restarts, and reboots (WorkManager persists the job).
 * - Runs as a foreground service so Android doesn't kill it mid-transfer.
 * - Validates the file's magic bytes before registering it as installed.
 * - Removes the superseded version of the same resource after a successful update.
 */
class DownloadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    private val app get() = applicationContext as OutpostApp

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val id = inputData.getString(KEY_ID) ?: return@withContext Result.failure()
        val version = inputData.getString(KEY_VERSION) ?: return@withContext Result.failure()
        val title = inputData.getString(KEY_TITLE) ?: id
        val url = inputData.getString(KEY_URL) ?: return@withContext Result.failure()
        val kind = ResourceKind.valueOf(inputData.getString(KEY_KIND) ?: return@withContext Result.failure())
        val fileName = inputData.getString(KEY_FILE) ?: return@withContext Result.failure()
        val expectedBytes = inputData.getLong(KEY_EXPECTED_BYTES, 0L)

        val storage = app.container.storage
        val target = storage.fileFor(kind, fileName)
        val part = storage.partialFileFor(kind, fileName)

        // Refuse to start if it obviously won't fit (10% headroom over the catalog size).
        val remaining = (expectedBytes - part.length()).coerceAtLeast(0)
        if (expectedBytes > 0 && storage.freeBytes() < remaining + remaining / 10) {
            return@withContext Result.failure(workDataOf(KEY_ERROR to "Not enough free space"))
        }

        setForeground(foregroundInfo(title, 0))

        try {
            fetch(url, part, title, expectedBytes)
        } catch (e: IOException) {
            return@withContext if (runAttemptCount < MAX_ATTEMPTS) Result.retry()
            else Result.failure(workDataOf(KEY_ERROR to (e.message ?: "Network error")))
        }

        if (!FileFormats.looksValid(kind, part)) {
            part.delete()
            return@withContext Result.failure(workDataOf(KEY_ERROR to "Downloaded file is not a valid ${kind.extension}"))
        }
        if (target.exists()) target.deleteRecursively()
        if (!part.renameTo(target)) {
            return@withContext Result.failure(workDataOf(KEY_ERROR to "Could not move file into place"))
        }
        // Tool packs are web apps: unpack them into a folder and keep only the folder.
        var installed = target
        if (kind == ResourceKind.TOOL) {
            val dir = File(target.parentFile, target.nameWithoutExtension)
            try {
                FileFormats.extractZip(target, dir)
            } catch (e: Exception) {
                dir.deleteRecursively()
                return@withContext Result.failure(workDataOf(KEY_ERROR to "Could not unpack: ${e.message}"))
            } finally {
                target.delete()
            }
            installed = dir
        }

        val dao = app.container.db.installed()
        val previous = dao.get(id)
        dao.upsert(
            InstalledResource(
                resourceId = id,
                kind = kind.name,
                version = version,
                title = title,
                filePath = installed.absolutePath,
                sizeBytes = if (installed.isDirectory) installed.walkTopDown().filter { it.isFile }.sumOf { it.length() } else installed.length(),
                installedAt = System.currentTimeMillis(),
            )
        )
        // Clean up the older version only after the new one is registered.
        if (previous != null && previous.filePath != installed.absolutePath) {
            File(previous.filePath).deleteRecursively()
        }
        Result.success()
    }

    private suspend fun fetch(url: String, part: File, title: String, expectedBytes: Long) {
        val already = if (part.exists()) part.length() else 0L
        val req = Request.Builder().url(url).apply {
            if (already > 0) header("Range", "bytes=$already-")
        }.build()

        app.container.http.newCall(req).execute().use { resp ->
            if (resp.code == 416) return // Range not satisfiable: we already have it all.
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("Empty response")
            val append = resp.code == 206 && already > 0
            if (!append && already > 0) part.delete() // server ignored Range; start over

            val start = if (append) already else 0L
            val total = when {
                body.contentLength() > 0 -> start + body.contentLength()
                else -> expectedBytes
            }

            FileOutputStream(part, append).use { out ->
                val input = body.byteStream()
                val buf = ByteArray(256 * 1024)
                var written = start
                var lastReport = 0L
                while (true) {
                    coroutineContext.ensureActive() // honors cancel from the UI
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    written += n
                    val now = System.currentTimeMillis()
                    if (now - lastReport > 750) {
                        lastReport = now
                        val pct = if (total > 0) ((written * 100) / total).toInt() else -1
                        setProgress(workDataOf(KEY_BYTES to written, KEY_TOTAL to total))
                        setForeground(foregroundInfo(title, pct))
                    }
                }
                // A stream that ends early (flaky link) is not success; retry resumes from here.
                if (body.contentLength() > 0 && written < total) {
                    throw IOException("Connection closed at $written of $total bytes")
                }
            }
        }
    }

    private fun foregroundInfo(title: String, percent: Int): ForegroundInfo {
        val ctx = applicationContext
        ensureChannel(ctx)
        val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloading $title")
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, percent.coerceAtLeast(0), percent < 0)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel, "Cancel",
                androidx.work.WorkManager.getInstance(ctx).createCancelPendingIntent(id)
            )
            .build()
        val notifId = id.hashCode()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notifId, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notifId, n)
        }
    }

    companion object {
        const val TAG = "content-download"
        const val KEY_ID = "id"
        const val KEY_VERSION = "version"
        const val KEY_TITLE = "title"
        const val KEY_URL = "url"
        const val KEY_KIND = "kind"
        const val KEY_FILE = "file"
        const val KEY_EXPECTED_BYTES = "expected"
        const val KEY_BYTES = "bytes"
        const val KEY_TOTAL = "total"
        const val KEY_ERROR = "error"
        private const val MAX_ATTEMPTS = 8
        private const val CHANNEL_ID = "downloads"

        fun ensureChannel(ctx: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = ctx.getSystemService(NotificationManager::class.java)
                if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                    nm.createNotificationChannel(
                        NotificationChannel(CHANNEL_ID, "Content downloads", NotificationManager.IMPORTANCE_LOW)
                    )
                }
            }
        }
    }
}
