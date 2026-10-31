package com.conza.outpost.griddown

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.conza.outpost.container
import com.conza.outpost.data.BatterySample
import com.conza.outpost.data.PressureSample
import com.conza.outpost.download.DownloadWorker
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

data class BatteryReading(val percent: Float, val charging: Boolean, val chargeCounterUah: Long?, val capacityUah: Long?)

object PowerMonitor {
    const val WORK = "outpost-sampler"

    /** Reads the sticky battery broadcast. No receiver is left registered. */
    fun read(context: Context): BatteryReading? {
        val i = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (level < 0 || scale <= 0) return null
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val bm = context.getSystemService(BatteryManager::class.java)
        val counter = bm?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)?.takeIf { it > 0 && it != Long.MIN_VALUE }
        val pct = level * 100f / scale
        val capacity = counter?.let { if (pct > 1) (it * 100 / pct).toLong() else null }
        return BatteryReading(pct, charging, counter, capacity)
    }

    suspend fun sample(context: Context) {
        val c = context.container
        val r = read(context) ?: return
        val dao = c.db.gridDown()
        val now = System.currentTimeMillis()
        dao.addBattery(BatterySample(now, r.percent, r.charging, c.settings.survivalMode))
        readPressure(context)?.let { dao.addPressure(PressureSample(now, it)) }
        dao.pruneBattery(now - 14 * 86_400_000L)
        dao.prunePressure(now - 14 * 86_400_000L)
    }

    /** One barometer reading (hPa), or null if the phone has no pressure sensor. Listens for at most 3 s. */
    suspend fun readPressure(context: Context): Float? {
        val sm = context.getSystemService(SensorManager::class.java) ?: return null
        val sensor = sm.getDefaultSensor(Sensor.TYPE_PRESSURE) ?: return null
        return withTimeoutOrNull(3_000) {
            suspendCancellableCoroutine { cont ->
                val l = object : SensorEventListener {
                    override fun onSensorChanged(e: SensorEvent) {
                        sm.unregisterListener(this)
                        if (cont.isActive) cont.resume(e.values[0])
                    }
                    override fun onAccuracyChanged(s: Sensor?, a: Int) {}
                }
                sm.registerListener(l, sensor, SensorManager.SENSOR_DELAY_NORMAL)
                cont.invokeOnCancellation { sm.unregisterListener(l) }
            }
        }
    }

    fun hasBarometer(context: Context) = context.getSystemService(SensorManager::class.java)?.getDefaultSensor(Sensor.TYPE_PRESSURE) != null

    /**
     * The only recurring job Outpost schedules: battery + barometer sample every hour, every
     * 3 hours in Survival mode. No wakelocks, no exact alarms; WorkManager batches it with the
     * system's own maintenance windows.
     */
    fun schedule(context: Context, survival: Boolean) {
        val hours = if (survival) 3L else 1L
        val req = PeriodicWorkRequestBuilder<SamplerWorker>(hours, TimeUnit.HOURS).addTag(WORK).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    /** Survival mode on: pause downloads (they resume from where they stopped), drug indexing, AI. */
    suspend fun enterSurvival(context: Context) {
        val c = context.container
        WorkManager.getInstance(context).cancelAllWorkByTag(DownloadWorker.TAG)
        com.conza.outpost.medical.DrugIngestWorker.cancel(context)
        runCatching { c.llm.unloadAll() }
        schedule(context, true)
    }

    fun exitSurvival(context: Context) = schedule(context, false)

    /** Estimated drain and hours left from the samples of the last two days. */
    suspend fun estimate(context: Context): Estimate? {
        val r = read(context) ?: return null
        val now = System.currentTimeMillis()
        val samples = context.container.db.gridDown().batterySince(now - 48 * 3_600_000L)
            .map { PowerModel.Sample(it.at, it.percent, it.charging) } + PowerModel.Sample(now, r.percent, r.charging)
        val drain = PowerModel.drainPercentPerHour(samples, now)
        return Estimate(r, drain, drain?.let { PowerModel.hoursLeft(r.percent.toDouble(), it) })
    }

    data class Estimate(val reading: BatteryReading, val drainPerHour: Double?, val hoursLeft: Double?) {
        val text: String get() = when {
            reading.charging -> "Charging · %.0f%%".format(reading.percent)
            hoursLeft != null -> "%.0f%% · %s".format(reading.percent, PowerModel.describeHours(hoursLeft))
            else -> "%.0f%% · estimate after a few hours of readings".format(reading.percent)
        }
    }

    /** What the wakelock / alarm audit shows in Power. */
    fun audit(context: Context): List<Pair<String, String>> {
        val pm = context.getSystemService(PowerManager::class.java)
        val ignoring = Build.VERSION.SDK_INT >= 23 && pm?.isIgnoringBatteryOptimizations(context.packageName) == true
        val work = runCatching { WorkManager.getInstance(context).getWorkInfosByTag(WORK).get().filter { !it.state.isFinished } }.getOrDefault(emptyList())
        val downloads = runCatching { WorkManager.getInstance(context).getWorkInfosByTag(DownloadWorker.TAG).get().count { !it.state.isFinished } }.getOrDefault(0)
        return listOf(
            "Wakelocks" to "None held. Outpost never keeps the CPU awake on its own.",
            "Exact alarms" to "None. Outpost doesn't request or schedule alarms.",
            "Recurring jobs" to (if (work.isEmpty()) "None scheduled" else "Battery & barometer sample, every ${if (context.container.settings.survivalMode) 3 else 1} h (WorkManager, batched by Android)"),
            "Downloads" to (if (downloads == 0) "None queued" else "$downloads queued or running (paused by Survival mode)"),
            "Location" to "Only while the Map is open and you've tapped 'my location'.",
            "Battery optimization" to (if (ignoring) "Exempt — Android lets Outpost run more in the background. Not needed; consider turning it back on." else "Optimized (recommended). Android may delay samples to save power."),
            "Network" to "Only for downloads you start. No analytics, no telemetry.",
        )
    }
}

class SamplerWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        runCatching { PowerMonitor.sample(applicationContext) }
        return Result.success()
    }
}
