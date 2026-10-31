package com.conza.outpost.download

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.conza.outpost.catalog.CatalogItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit

/** Live state of an in-flight download, keyed by resource id. */
data class DownloadState(
    val resourceId: String,
    val state: WorkInfo.State,
    val bytes: Long,
    val total: Long,
    val error: String?,
) {
    val fraction: Float? get() = if (total > 0) (bytes.toFloat() / total).coerceIn(0f, 1f) else null
    val active: Boolean get() = state == WorkInfo.State.RUNNING || state == WorkInfo.State.ENQUEUED || state == WorkInfo.State.BLOCKED
}

class ContentDownloader(context: Context) {
    private val wm = WorkManager.getInstance(context)

    fun enqueue(item: CatalogItem, wifiOnly: Boolean) {
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(
                workDataOf(
                    DownloadWorker.KEY_ID to item.id,
                    DownloadWorker.KEY_VERSION to item.version,
                    DownloadWorker.KEY_TITLE to item.title,
                    DownloadWorker.KEY_URL to item.url,
                    DownloadWorker.KEY_KIND to item.kind.name,
                    DownloadWorker.KEY_FILE to item.fileName,
                    DownloadWorker.KEY_EXPECTED_BYTES to item.sizeMb * 1024 * 1024,
                )
            )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                    .setRequiresStorageNotLow(true)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(DownloadWorker.TAG)
            .addTag(tagFor(item.id))
            .build()
        // KEEP: tapping "Download" twice never starts two transfers into the same .part file.
        wm.enqueueUniqueWork(uniqueName(item.id), ExistingWorkPolicy.KEEP, request)
    }

    fun cancel(resourceId: String) {
        wm.cancelUniqueWork(uniqueName(resourceId))
    }

    /** Most recent work state per resource id. */
    fun observe(): Flow<Map<String, DownloadState>> =
        wm.getWorkInfosByTagFlow(DownloadWorker.TAG).map { infos ->
            infos.mapNotNull { info ->
                val id = info.tags.firstOrNull { it.startsWith(ID_TAG_PREFIX) }?.removePrefix(ID_TAG_PREFIX)
                    ?: return@mapNotNull null
                id to DownloadState(
                    resourceId = id,
                    state = info.state,
                    bytes = info.progress.getLong(DownloadWorker.KEY_BYTES, 0L),
                    total = info.progress.getLong(DownloadWorker.KEY_TOTAL, 0L),
                    error = info.outputData.getString(DownloadWorker.KEY_ERROR),
                )
            }
                // Prefer an active job over stale finished records for the same id.
                .groupBy({ it.first }, { it.second })
                .mapValues { (_, states) -> states.firstOrNull { it.active } ?: states.last() }
        }

    private fun uniqueName(id: String) = "download:$id"
    private fun tagFor(id: String) = ID_TAG_PREFIX + id

    companion object {
        private const val ID_TAG_PREFIX = "rid:"
    }
}
