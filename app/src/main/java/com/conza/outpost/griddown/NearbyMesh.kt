package com.conza.outpost.griddown

import android.Manifest
import android.content.Context
import android.os.Build
import com.conza.outpost.container
import com.conza.outpost.data.MeshMessage
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Phone-to-phone sharing with Google Nearby Connections: Bluetooth, BLE, and Wi-Fi Direct, no
 * internet or cell service. Both phones open "Share nearby"; each sees the other, the user
 * confirms the 4-character code on both screens, then notes, pins, messages, supplies (family
 * phones only), and content files can be exchanged.
 */
class NearbyMesh(private val context: Context, private val scope: CoroutineScope) {
    data class Peer(val id: String, val name: String, val state: String, val token: String? = null)
    data class Incoming(val endpointId: String, val name: String, val plan: Sync.Plan)

    private val client by lazy { Nearby.getConnectionsClient(context) }
    private val serviceId = context.packageName + ".mesh"
    private val _peers = MutableStateFlow<Map<String, Peer>>(emptyMap())
    val peers: StateFlow<Map<String, Peer>> = _peers
    private val _status = MutableStateFlow("Off")
    val status: StateFlow<String> = _status
    /** A received bundle waiting for the user to accept. */
    val incoming = MutableStateFlow<Incoming?>(null)
    var mergeSupplies = false
    private val pendingFiles = mutableMapOf<Long, Pair<String, String>>() // payload id → (endpoint, file name)
    private val fileHeaders = mutableMapOf<String, String>() // endpoint → next file name

    private fun update(id: String, f: (Peer?) -> Peer?) { _peers.value = _peers.value.toMutableMap().also { m -> f(m[id])?.let { m[id] = it } ?: m.remove(id) } }

    companion object {
        fun permissions(): Array<String> = when {
            Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.NEARBY_WIFI_DEVICES, Manifest.permission.ACCESS_FINE_LOCATION)
            Build.VERSION.SDK_INT >= 31 -> arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION)
            else -> arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        const val MSG = "MSG:"
        const val SYNC = "SYNC:"
        const val FILE = "FILE:"
    }

    fun start(myName: String) {
        _status.value = "Looking for phones…"
        client.startAdvertising(myName, serviceId, lifecycle, AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build())
            .addOnFailureListener { _status.value = "Couldn't advertise: ${it.message}" }
        client.startDiscovery(serviceId, discovery, DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build())
            .addOnFailureListener { _status.value = "Couldn't search: ${it.message}. Google Play services is required." }
    }

    fun stop() {
        runCatching { client.stopAdvertising(); client.stopDiscovery(); client.stopAllEndpoints() }
        _peers.value = emptyMap(); _status.value = "Off"
    }

    fun connect(id: String, myName: String) {
        update(id) { it?.copy(state = "Connecting…") }
        client.requestConnection(myName, id, lifecycle).addOnFailureListener { e -> update(id) { it?.copy(state = "Failed: ${e.message}") } }
    }

    fun accept(id: String) = client.acceptConnection(id, payloads)
    fun reject(id: String) = client.rejectConnection(id)

    private val discovery = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(id: String, info: DiscoveredEndpointInfo) { update(id) { Peer(id, info.endpointName, "Found") } }
        override fun onEndpointLost(id: String) { update(id) { p -> p?.takeIf { it.state == "Connected" } } }
    }

    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(id: String, info: ConnectionInfo) {
            update(id) { Peer(id, info.endpointName, "Confirm code", info.authenticationDigits) }
        }
        override fun onConnectionResult(id: String, r: ConnectionResolution) {
            update(id) { it?.copy(state = if (r.status.statusCode == ConnectionsStatusCodes.STATUS_OK) "Connected" else "Not connected", token = null) }
        }
        override fun onDisconnected(id: String) { update(id) { it?.copy(state = "Disconnected") } }
    }

    private val payloads = object : PayloadCallback() {
        override fun onPayloadReceived(id: String, p: Payload) {
            when (p.type) {
                Payload.Type.BYTES -> handleBytes(id, String(p.asBytes() ?: return, Charsets.UTF_8))
                Payload.Type.FILE -> pendingFiles[p.id] = id to (fileHeaders.remove(id) ?: "shared-${p.id}")
                else -> {}
            }
            if (p.type == Payload.Type.FILE) received[p.id] = p
        }
        override fun onPayloadTransferUpdate(id: String, u: PayloadTransferUpdate) {
            if (u.status == PayloadTransferUpdate.Status.SUCCESS) received.remove(u.payloadId)?.let { p ->
                val (endpoint, name) = pendingFiles.remove(u.payloadId) ?: (id to "shared")
                runCatching { saveFile(p, endpoint, name) }.onFailure { _status.value = "Couldn't save $name: ${it.message}" }
            }
        }
    }
    private val received = mutableMapOf<Long, Payload>()

    private fun name(id: String) = _peers.value[id]?.name ?: "Nearby phone"

    private fun handleBytes(id: String, s: String) {
        when {
            s.startsWith(MSG) -> scope.launch {
                val m = runCatching { Sync.decode(s.removePrefix(MSG)).messages }.getOrNull().orEmpty()
                m.forEach { context.container.db.gridDown().addMesh(MeshMessage(uuid = it.uuid, at = it.at, transport = "nearby", from = it.from, text = it.text, outgoing = false)) }
            }
            s.startsWith(SYNC) -> scope.launch {
                val b = runCatching { Sync.decode(s.removePrefix(SYNC)) }.getOrNull() ?: return@launch
                val c = context.container
                val plan = Sync.plan(b, c.db.markers().all(), c.db.inventory().all(), c.db.notes().all(),
                    c.db.gridDown().meshAll().map { it.uuid }.toSet(), mergeSupplies)
                incoming.value = Incoming(id, b.device, plan)
            }
            s.startsWith(FILE) -> fileHeaders[id] = File(s.removePrefix(FILE)).name // never trust a path from another phone
        }
    }

    /** Files arrive into the books folder (PDFs) or a "shared" folder for anything else. */
    private fun saveFile(p: Payload, endpoint: String, name: String) {
        val pfd = p.asFile()?.asParcelFileDescriptor() ?: return
        if (name == "sync.json") { // a large sync bundle, not a file to keep
            handleBytes(endpoint, android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes().toString(Charsets.UTF_8) }); return
        }
        val storage = context.container.storage
        val dir = when {
            name.endsWith(".pdf", true) -> storage.namedDir("books")
            name.endsWith(".pmtiles", true) -> storage.dir(com.conza.outpost.catalog.ResourceKind.MAP)
            name.endsWith(".zim", true) -> storage.dir(com.conza.outpost.catalog.ResourceKind.ZIM)
            else -> storage.namedDir("shared")
        }
        var dest = File(dir, name); var n = 1
        while (dest.exists()) dest = File(dir, "${name.substringBeforeLast('.')}-${n++}.${name.substringAfterLast('.', "bin")}")
        android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input -> dest.outputStream().use { input.copyTo(it) } }
        _status.value = "Received ${dest.name}"
        if (name.endsWith(".pdf", true)) context.container.syncPdfIndex()
    }

    suspend fun applyIncoming() {
        val inc = incoming.value ?: return
        val c = context.container
        inc.plan.pins.forEach { c.db.markers().upsert(it) }
        (inc.plan.newItems + inc.plan.updatedItems).forEach { c.db.inventory().upsert(it) }
        inc.plan.notes.forEach { n -> val id = c.db.notes().upsert(n); runCatching { c.index.indexNote(n.copy(id = id)) } }
        inc.plan.messages.forEach { c.db.gridDown().addMesh(it) }
        incoming.value = null
        _status.value = "Merged from ${inc.name}: ${inc.plan.summary}"
    }

    private fun connected() = _peers.value.values.filter { it.state == "Connected" }.map { it.id }

    suspend fun sendSync(myName: String, includeSupplies: Boolean) {
        val c = context.container
        val b = Sync.bundle(myName, c.db.markers().all(), if (includeSupplies) c.db.inventory().all() else emptyList(), c.db.notes().all(), c.db.gridDown().meshAll())
        val bytes = (SYNC + Sync.encode(b)).toByteArray()
        val ids = connected()
        if (ids.isEmpty()) { _status.value = "Connect to a phone first"; return }
        if (bytes.size > ConnectionsClientMaxBytes) {
            // Large bundles go as a stream payload.
            val f = File(context.cacheDir, "sync.json").apply { writeBytes(bytes) }
            client.sendPayload(ids, Payload.fromBytes((FILE + "sync.json").toByteArray()))
            client.sendPayload(ids, Payload.fromFile(f))
        } else client.sendPayload(ids, Payload.fromBytes(bytes))
        _status.value = "Sent ${b.pins.size} pins, ${b.notes.size} notes, ${b.items.size} supplies"
    }

    suspend fun sendMessage(myName: String, text: String) {
        val m = MeshMessage(transport = "nearby", from = myName, text = text, outgoing = true)
        context.container.db.gridDown().addMesh(m)
        val b = SyncBundle(device = myName, sentAt = m.at, messages = listOf(SyncMessage(m.uuid, m.at, myName, text)))
        connected().takeIf { it.isNotEmpty() }?.let { client.sendPayload(it, Payload.fromBytes((MSG + Sync.encode(b)).toByteArray())) }
    }

    fun sendFile(file: File) {
        val ids = connected().takeIf { it.isNotEmpty() } ?: run { _status.value = "Connect to a phone first"; return }
        val payload = runCatching { Payload.fromFile(file) }.getOrElse { _status.value = "Can't read ${file.name}"; return }
        client.sendPayload(ids, Payload.fromBytes((FILE + file.name).toByteArray()))
        client.sendPayload(ids, payload)
        _status.value = "Sending ${file.name} (${file.length() shr 20} MB)…"
    }

    private val ConnectionsClientMaxBytes = 30_000
}
