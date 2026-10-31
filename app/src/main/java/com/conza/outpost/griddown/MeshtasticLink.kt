package com.conza.outpost.griddown

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import com.conza.outpost.container
import com.conza.outpost.data.MapMarker
import com.conza.outpost.data.MeshMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import kotlin.random.Random

/**
 * Experimental: talk to a Meshtastic LoRa radio over Bluetooth LE. Sends and receives channel-0
 * text messages; received positions become map pins. Pair the radio in Android's Bluetooth
 * settings first (default PIN 123456 unless you changed it).
 */
@SuppressLint("MissingPermission") // callers check BLUETOOTH_SCAN/CONNECT first (MeshScreen)
class MeshtasticLink(private val context: Context, private val scope: CoroutineScope) {
    companion object {
        val SERVICE: UUID = UUID.fromString("6ba1b218-15a8-461f-9fa8-5dcae273eafd")
        val TO_RADIO: UUID = UUID.fromString("f75c76d2-129e-4dad-a1dd-7866124401e7")
        val FROM_RADIO: UUID = UUID.fromString("2c55e69e-4993-11ed-b878-0242ac120002")
        val FROM_RADIO_LEGACY: UUID = UUID.fromString("8ba2bcc2-ee02-4a55-a531-c525c5e454d5")
        val FROM_NUM: UUID = UUID.fromString("ed9da18c-a800-4f66-a670-aa7547e34453")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    data class Radio(val address: String, val name: String)

    private val _status = MutableStateFlow("Not connected")
    val status: StateFlow<String> = _status
    private val _found = MutableStateFlow<List<Radio>>(emptyList())
    val found: StateFlow<List<Radio>> = _found
    val nodes = MutableStateFlow<Map<Long, String>>(emptyMap())
    private var myNode: Long = 0

    private var gatt: BluetoothGatt? = null
    private var toRadio: BluetoothGattCharacteristic? = null
    private var fromRadio: BluetoothGattCharacteristic? = null
    private val op = Mutex()
    private var pending: CompletableDeferred<Any?>? = null

    private val adapter get() = context.getSystemService(BluetoothManager::class.java)?.adapter

    fun scan() {
        val scanner = adapter?.bluetoothLeScanner ?: run { _status.value = "Bluetooth is off or missing"; return }
        _found.value = emptyList(); _status.value = "Scanning for Meshtastic radios…"
        val cb = object : ScanCallback() {
            override fun onScanResult(type: Int, r: ScanResult) {
                val radio = Radio(r.device.address, r.device.name ?: r.scanRecord?.deviceName ?: r.device.address)
                if (_found.value.none { it.address == radio.address }) _found.value = _found.value + radio
            }
            override fun onScanFailed(code: Int) { _status.value = "Scan failed ($code)" }
        }
        scanner.startScan(listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).build()),
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
        scope.launch { delay(12_000); runCatching { scanner.stopScan(cb) }; if (gatt == null) _status.value = "Found ${_found.value.size} radio(s)" }
    }

    fun connect(address: String) {
        val device: BluetoothDevice = adapter?.getRemoteDevice(address) ?: return
        _status.value = "Connecting…"
        gatt = if (Build.VERSION.SDK_INT >= 23) device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE) else device.connectGatt(context, false, callback)
    }

    fun disconnect() { runCatching { gatt?.disconnect(); gatt?.close() }; gatt = null; _status.value = "Not connected" }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) { _status.value = "Connected, setting up…"; g.requestMtu(512) }
            else if (newState == BluetoothProfile.STATE_DISCONNECTED) { _status.value = "Disconnected"; pending?.complete(null) }
        }
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) { g.discoverServices() }
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val s = g.getService(SERVICE) ?: run { _status.value = "Not a Meshtastic radio"; return }
            toRadio = s.getCharacteristic(TO_RADIO)
            fromRadio = s.getCharacteristic(FROM_RADIO) ?: s.getCharacteristic(FROM_RADIO_LEGACY)
            val num = s.getCharacteristic(FROM_NUM)
            scope.launch {
                if (num != null) op.withLock {
                    g.setCharacteristicNotification(num, true)
                    num.getDescriptor(CCCD)?.let { d -> await { writeDescriptor(g, d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) } }
                }
                write(MeshProto.wantConfig(Random.nextLong(1, 0x7FFFFFFF)))
                drain()
                _status.value = "Connected to ${g.device.name ?: "radio"}"
            }
        }
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) { @Suppress("DEPRECATION") pending?.complete(ch.value) }
        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray, status: Int) { pending?.complete(value) }
        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) { pending?.complete(status) }
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) { pending?.complete(status) }
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) { if (ch.uuid == FROM_NUM) scope.launch { drain() } }
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) { if (ch.uuid == FROM_NUM) scope.launch { drain() } }
    }

    @Suppress("DEPRECATION")
    private fun writeDescriptor(g: BluetoothGatt, d: BluetoothGattDescriptor, v: ByteArray): Boolean =
        if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(d, v) == 0 else { d.value = v; g.writeDescriptor(d) }

    /** One GATT operation at a time; Android drops overlapping ones. */
    private suspend fun await(start: () -> Boolean): Any? {
        val d = CompletableDeferred<Any?>(); pending = d
        if (!start()) return null
        return withTimeoutOrNull(5_000) { d.await() }
    }

    @Suppress("DEPRECATION")
    private suspend fun write(bytes: ByteArray) = op.withLock {
        val g = gatt ?: return@withLock; val ch = toRadio ?: return@withLock
        await {
            if (Build.VERSION.SDK_INT >= 33) g.writeCharacteristic(ch, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == 0
            else { ch.value = bytes; ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT; g.writeCharacteristic(ch) }
        }
    }

    /** Reads FromRadio until the radio has nothing more queued. */
    private suspend fun drain() {
        repeat(500) {
            val bytes = op.withLock {
                val g = gatt ?: return; val ch = fromRadio ?: return
                await { g.readCharacteristic(ch) } as? ByteArray
            }
            if (bytes == null || bytes.isEmpty()) return
            handle(MeshProto.decodeFromRadio(bytes))
        }
    }

    private suspend fun handle(m: MeshProto.FromRadio) {
        val c = context.container
        when (m) {
            is MeshProto.FromRadio.MyInfo -> myNode = m.nodeNum
            is MeshProto.FromRadio.Node -> nodes.value = nodes.value + (m.num to m.longName.ifBlank { m.shortName.ifBlank { MeshProto.nodeId(m.num) } })
            is MeshProto.FromRadio.Text -> c.db.gridDown().addMesh(MeshMessage(uuid = "mt-${m.from}-${m.id}", transport = "meshtastic",
                from = nodes.value[m.from] ?: MeshProto.nodeId(m.from), text = m.text, outgoing = m.from == myNode))
            is MeshProto.FromRadio.Position -> {
                val name = nodes.value[m.from] ?: MeshProto.nodeId(m.from)
                // One pin per node, moved as new positions arrive.
                val uuid = "mt-node-${m.from}"
                val existing = c.db.markers().all().firstOrNull { it.uuid == uuid }
                c.db.markers().upsert((existing ?: MapMarker(name = name, lat = m.lat, lon = m.lon, color = "#3D7DD8", uuid = uuid))
                    .copy(name = name, lat = m.lat, lon = m.lon, notes = "Meshtastic position · ${java.text.DateFormat.getTimeInstance().format(java.util.Date())}"))
            }
            else -> {}
        }
    }

    suspend fun sendText(text: String) {
        if (gatt == null) { _status.value = "Connect to a radio first"; return }
        val id = Random.nextLong(1, 0x7FFFFFFF)
        write(MeshProto.text(text.take(200), id))
        context.container.db.gridDown().addMesh(MeshMessage(uuid = "mt-$myNode-$id", transport = "meshtastic", from = "Me", text = text, outgoing = true))
    }
}
