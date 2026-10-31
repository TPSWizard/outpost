package com.conza.outpost.bench

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import com.conza.outpost.container
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import kotlin.math.roundToInt

@Serializable
data class BenchResult(
    val device: String,
    val android: String,
    val cores: Int,
    val ramGb: Double,
    val cpuSingleMBs: Double,
    val cpuMultiMBs: Double,
    val memoryGBs: Double,
    val storageWriteMBs: Double,
    val storageReadMBs: Double,
    val llmTokensPerSec: Double?,
    val llmModel: String?,
    val score: Int,
    val at: Long = System.currentTimeMillis(),
) {
    fun toJson() = Json.encodeToString(serializer(), this)
    companion object { fun fromJson(s: String?) = s?.let { runCatching { Json.decodeFromString(serializer(), it) }.getOrNull() } }

    /** What this device is good for, in plain words. */
    val verdict: String get() = when {
        llmTokensPerSec != null && llmTokensPerSec >= 12 -> "Fast enough for 3–4B models."
        llmTokensPerSec != null && llmTokensPerSec >= 6 -> "Good for 1–2B models."
        llmTokensPerSec != null -> "Best with the smallest models (0.5B), or a remote server."
        ramGb >= 8 && cpuMultiMBs > 1500 -> "Likely fine for 3–4B models. Install one and re-run to measure."
        ramGb >= 4 -> "Likely fine for 1–2B models. Install one and re-run to measure."
        else -> "Limited memory: use a 0.5B model or a remote server."
    }
}

/** NOMAD's System Benchmark, adapted to a phone: CPU, memory, storage, and on-device AI speed. */
object Benchmark {
    suspend fun run(context: Context, progress: (String) -> Unit): BenchResult = withContext(Dispatchers.Default) {
        val c = context.container
        val cores = Runtime.getRuntime().availableProcessors()
        val am = context.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val ram = mi.totalMem / 1e9

        progress("CPU, single core…")
        val single = hashRate(seconds = 2.0)
        progress("CPU, all $cores cores…")
        val multi = coroutineScope { (1..cores).map { async(Dispatchers.Default) { hashRate(2.0) } }.awaitAll().sum() }
        progress("Memory bandwidth…")
        val mem = memoryBandwidth()
        progress("Storage write…")
        val (w, r) = storageSpeed(File(c.storage.root, ".bench.tmp"), progress)

        var tps: Double? = null
        var model: String? = null
        val models = runCatching { c.assistant.installedChatModels() }.getOrDefault(emptyList())
        val pick = models.firstOrNull { it.resourceId == c.settings.chatModelId } ?: models.minByOrNull { it.sizeBytes }
        if (pick != null && c.llm.available) {
            progress("AI: loading ${pick.title}…")
            runCatching {
                c.llm.ensureChatModel(File(pick.filePath), 2048)
                progress("AI: generating…")
                val stats = c.llm.generate(
                    listOf("user" to "Write three sentences about rivers."), 64, 0.0f,
                ) { true }
                tps = stats.tokensPerSecond
                model = pick.title
            }
        }

        val score = (single / 10 + multi / 40 + mem * 20 + w / 10 + r / 20 + (tps ?: 0.0) * 25).roundToInt()
        BenchResult(
            device = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
            android = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            cores = cores, ramGb = (ram * 10).roundToInt() / 10.0,
            cpuSingleMBs = single, cpuMultiMBs = multi, memoryGBs = mem,
            storageWriteMBs = w, storageReadMBs = r,
            llmTokensPerSec = tps, llmModel = model, score = score,
        ).also { c.settings.lastBenchmark = it.toJson() }
    }

    /** SHA-256 throughput in MB/s over ~[seconds]. */
    private fun hashRate(seconds: Double): Double {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(1 shl 20) { it.toByte() }
        val end = System.nanoTime() + (seconds * 1e9).toLong()
        var bytes = 0L
        val start = System.nanoTime()
        while (System.nanoTime() < end) { md.update(buf); bytes += buf.size }
        md.digest()
        return bytes / 1e6 / ((System.nanoTime() - start) / 1e9)
    }

    private fun memoryBandwidth(): Double {
        val n = 16 shl 20
        val a = ByteArray(n); val b = ByteArray(n)
        val start = System.nanoTime()
        var copied = 0L
        while (System.nanoTime() - start < 1_500_000_000L) { System.arraycopy(a, 0, b, 0, n); copied += n }
        return copied / 1e9 / ((System.nanoTime() - start) / 1e9)
    }

    private fun storageSpeed(f: File, progress: (String) -> Unit): Pair<Double, Double> {
        val size = 128 shl 20
        val buf = ByteArray(4 shl 20) { (it * 31).toByte() }
        return try {
            var t = System.nanoTime()
            FileOutputStream(f).use { out ->
                var left = size
                while (left > 0) { out.write(buf, 0, minOf(buf.size, left)); left -= buf.size }
                out.fd.sync()
            }
            val write = size / 1e6 / ((System.nanoTime() - t) / 1e9)
            progress("Storage read…")
            t = System.nanoTime()
            RandomAccessFile(f, "r").use { raf -> while (raf.read(buf) > 0) Unit }
            val read = size / 1e6 / ((System.nanoTime() - t) / 1e9)
            write to read
        } finally {
            f.delete()
        }
    }
}
