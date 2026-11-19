package com.erashon.sharedrop

import android.content.Context
import android.net.Uri
import com.erashon.sharedrop.core.Channel
import com.erashon.sharedrop.core.Handshake
import com.erashon.sharedrop.core.History
import com.erashon.sharedrop.core.PairingUi
import com.erashon.sharedrop.core.Store
import com.erashon.sharedrop.core.T
import com.erashon.sharedrop.discovery.Device
import com.erashon.sharedrop.discovery.DeviceInfo
import com.erashon.sharedrop.discovery.UsbLink
import com.erashon.sharedrop.core.ProbeDone
import com.erashon.sharedrop.discovery.LanDiscovery
import com.erashon.sharedrop.discovery.LinkMonitor
import com.erashon.sharedrop.discovery.LinkState
import com.erashon.sharedrop.storage.IncomingFile
import com.erashon.sharedrop.storage.Storage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

data class Transfer(
    val id: Long,
    val peer: String,
    val file: String,
    val done: Long,
    val total: Long,
    val incoming: Boolean,
    val state: State,
    val error: String? = null,
    /** Archivo recibido ya guardado (para abrirlo); null mientras se recibe o si es un envío. */
    val uri: String? = null,
) {
    enum class State { Active, Finished, Failed }
}

/** Cadena entrante pendiente: el usuario debe escribir este código. */
data class CodePrompt(val peerName: String)

class Engine(private val ctx: Context) {
    val store = Store(ctx)
    private val linkMonitor = LinkMonitor(ctx)

    /** Medio por el que está conectado el teléfono ahora, para anunciarlo a los demás equipos. */
    private fun linkName(): String? = linkMonitor.state.value.let {
        when {
            it.usbTether -> "usb"
            it.hotspot -> "hotspot"
            it.wifiConnected -> "wifi"
            else -> null
        }
    }

    private val discovery = LanDiscovery(ctx, store) { linkName() }
    val devices: StateFlow<List<Device>> = discovery.devices
    private val usbLink = UsbLink(store) { discovery.inject(it) }
    /** Medios de conexión disponibles ahora (Wi-Fi, USB tethering, hotspot…). */
    val link: StateFlow<LinkState> = linkMonitor.state

    private val _transfers = MutableStateFlow<List<Transfer>>(emptyList())
    val transfers: StateFlow<List<Transfer>> = _transfers

    /** Código que este equipo muestra mientras otro se está emparejando con él. */
    private val _codeToShow = MutableStateFlow<Pair<String, String>?>(null)
    val codeToShow: StateFlow<Pair<String, String>?> = _codeToShow

    /** Este equipo necesita que el usuario escriba el código que muestra el otro. */
    private val _codeRequest = MutableStateFlow<CodePrompt?>(null)
    val codeRequest: StateFlow<CodePrompt?> = _codeRequest
    @Volatile private var codeAnswer: CompletableFuture<String?>? = null

    /** Carpeta de destino elegida (URI de árbol SAF) o null si se usa Download/ShareDrop. */
    private val _receiveTree = MutableStateFlow(store.receiveTree)
    val receiveTree: StateFlow<String?> = _receiveTree

    fun setReceiveTree(uri: Uri?) {
        store.receiveTree = uri?.toString()
        _receiveTree.value = uri?.toString()
    }

    /** Historial de transferencias terminadas (últimas 20 recibidas y 20 enviadas). */
    val history = History(ctx)

    /** Identifica este arranque: los ids de transferencia se reinician en cada uno. */
    private val session = System.currentTimeMillis()

    private val ids = AtomicLong()

    /** Envíos fallidos que se pueden repetir: id → (equipo, archivos que faltaron). */
    private val retries = java.util.concurrent.ConcurrentHashMap<Long, Pair<Device, List<Uri>>>()
    private val pool = Executors.newCachedThreadPool()
    @Volatile private var server: ServerSocket? = null

    private val ui = object : PairingUi {
        override fun showCode(peerName: String, code: String) { _codeToShow.value = peerName to code }
        override fun hideCode() { _codeToShow.value = null }
        override fun askCode(peerName: String): String? {
            val f = CompletableFuture<String?>()
            codeAnswer = f
            _codeRequest.value = CodePrompt(peerName)
            return try {
                f.get(120, TimeUnit.SECONDS)
            } catch (e: Exception) {
                null
            } finally {
                _codeRequest.value = null
                codeAnswer = null
            }
        }
    }

    fun answerCode(code: String?) { codeAnswer?.complete(code) }

    @Synchronized
    fun start() {
        if (server != null) return
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(InetSocketAddress(TCP_PORT))
        server = s
        discovery.start()
        linkMonitor.start()
        usbLink.start()
        pool.execute {
            while (!s.isClosed) {
                try {
                    val sock = s.accept()
                    pool.execute { handleIncoming(sock) }
                } catch (e: IOException) {
                    if (s.isClosed) break
                }
            }
        }
    }

    @Synchronized
    fun stop() {
        runCatching { server?.close() }
        server = null
        discovery.stop()
        linkMonitor.stop()
        usbLink.stop()
    }

    // ---------- Envío ----------

    /** Empieza el envío y devuelve el id de la transferencia, para poder seguirla en [transfers]. */
    fun send(device: Device, uris: List<Uri>): Long {
        val tid = ids.incrementAndGet()
        pool.execute { runSend(tid, device, uris) }
        return tid
    }

    private fun track(t: Transfer) {
        _transfers.update { list ->
            if (list.any { it.id == t.id }) list.map { if (it.id == t.id) t else it } else listOf(t) + list
        }
        // Las transferencias terminadas pasan al historial (guardado en el teléfono).
        if (t.state != Transfer.State.Active) history.record(t, session)
    }

    /** ¿Se puede repetir este envío fallido? Solo mientras la app siga abierta. */
    fun canRetry(t: Transfer) = t.state == Transfer.State.Failed && !t.incoming && retries.containsKey(t.id)

    /** Repite un envío fallido desde el archivo que se cortó; la fila fallida se reemplaza por la nueva. */
    fun retry(t: Transfer) {
        val (device, uris) = retries.remove(t.id) ?: return
        _transfers.update { list -> list.filterNot { it.id == t.id } }
        send(device, uris)
    }

    /** Reintentos automáticos tras un corte de red; pasados estos, queda el botón «Reintentar». */
    private val autoRetries = 2

    /** ¿Es un fallo de red que puede resolverse solo? (corte, conexión rechazada, tiempo agotado). */
    private fun isTransient(e: Exception) =
        e is java.net.SocketException || e is java.net.SocketTimeoutException || e is java.io.EOFException

    private fun runSend(tid: Long, device: Device, uris: List<Uri>) {
        var pending = uris
        var auto = 0
        var current = Transfer(tid, device.name, "", 0, 0, false, Transfer.State.Active)
        while (true) {
            val files = pending.map { Storage.describe(ctx, it) }
            var sentCount = 0
            current = current.copy(file = files.firstOrNull()?.name ?: "", done = 0, total = 0, state = Transfer.State.Active, error = null)
            track(current)
            try {
                val sock = Socket()
                sock.connect(InetSocketAddress(device.address, device.port), 5000)
                sock.tcpNoDelay = true
                sock.sendBufferSize = 1 shl 20
                Channel(sock).use { ch ->
                    Handshake.initiate(ch, store, ui)
                    val offer = JSONObject().put(
                        "files",
                        JSONArray().apply { files.forEach { put(JSONObject().put("name", it.name).put("size", it.size)) } },
                    )
                    ch.send(T.OFFER, offer.toString().toByteArray())
                    val reply = ch.recv()
                    if (reply.type != T.ACCEPT) throw IOException(errorMessage(reply.payload) ?: "Rechazado")

                    val buf = ByteArray(CHUNK)
                    for ((i, f) in files.withIndex()) {
                        sentCount = i
                        current = current.copy(file = f.name, done = 0, total = f.size)
                        track(current)
                        ch.send(T.FILE_START, JSONObject().put("name", f.name).put("size", f.size).toString().toByteArray())
                        var sent = 0L
                        var lastUi = 0L
                        ctx.contentResolver.openInputStream(f.uri)!!.use { input ->
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                ch.send(T.DATA, buf, n)
                                sent += n
                                val now = System.nanoTime()
                                if (now - lastUi > 150_000_000L) {
                                    lastUi = now
                                    track(current.copy(done = sent))
                                }
                            }
                        }
                        ch.send(T.FILE_END)
                        current = current.copy(done = sent, total = sent)
                        track(current)
                    }
                    ch.send(T.DONE)
                    val ack = ch.recv()
                    if (ack.type != T.DONE) throw IOException(errorMessage(ack.payload) ?: "Sin confirmación")
                }
                track(current.copy(state = Transfer.State.Finished))
                return
            } catch (e: Exception) {
                // Lo que faltó, desde el archivo que se cortó: para repetirlo solo o con el botón.
                val rest = pending.drop(sentCount)
                if (auto < autoRetries && isTransient(e)) {
                    auto++
                    pending = rest
                    Thread.sleep(2000L * auto)
                    continue
                }
                retries[tid] = device to rest
                track(current.copy(state = Transfer.State.Failed, error = e.message ?: e.javaClass.simpleName))
                return
            }
        }
    }

    // ---------- Recepción ----------

    private fun handleIncoming(sock: Socket) {
        sock.tcpNoDelay = true
        sock.receiveBufferSize = 1 shl 20
        val tid = ids.incrementAndGet()
        var current = Transfer(tid, sock.inetAddress.hostAddress ?: "?", "", 0, 0, true, Transfer.State.Active)
        var file: IncomingFile? = null
        try {
            Channel(sock).use { ch ->
                val peer = Handshake.respond(ch, store, ui) { DeviceInfo.collect(ctx, linkName()) }
                current = current.copy(peer = peer.name)
                var received = 0L
                var lastUi = 0L
                var any = false
                while (true) {
                    val f = ch.recv()
                    when (f.type) {
                        T.OFFER -> ch.send(T.ACCEPT)
                        T.FILE_START -> {
                            val j = JSONObject(String(f.payload))
                            val name = j.getString("name").substringAfterLast('/').substringAfterLast('\\').ifBlank { "archivo" }
                            file = IncomingFile(ctx, name, store.receiveTree?.let(Uri::parse))
                            received = 0
                            // Cada archivo tiene su propia fila (para poder abrirlo por separado).
                            if (any) current = current.copy(id = ids.incrementAndGet())
                            current = current.copy(
                                file = name, done = 0, total = j.optLong("size", -1),
                                uri = null, state = Transfer.State.Active,
                            )
                            track(current)
                            any = true
                        }
                        T.DATA -> {
                            val out = file?.out ?: throw IOException("DATA sin FILE_START")
                            out.write(f.payload)
                            received += f.payload.size
                            val now = System.nanoTime()
                            if (now - lastUi > 150_000_000L) {
                                lastUi = now
                                track(current.copy(done = received))
                            }
                        }
                        T.FILE_END -> {
                            val saved = file?.finish()
                            file = null
                            current = current.copy(
                                done = received, total = received,
                                uri = saved?.toString(), state = Transfer.State.Finished,
                            )
                            track(current)
                        }
                        T.DONE -> {
                            ch.send(T.DONE)
                            track(current.copy(state = Transfer.State.Finished))
                            return
                        }
                        T.ERROR -> throw IOException(errorMessage(f.payload) ?: "Error remoto")
                        else -> throw IOException("Tipo inesperado ${f.type}")
                    }
                }
            }
        } catch (e: ProbeDone) {
            // Un sondeo (quién eres) no es una transferencia: no deja fila ni error.
        } catch (e: Exception) {
            // Una conexión que se abre y se cierra sin transferir nada (un túnel que comprueba el puerto,
            // un escaneo) no es una transferencia fallida.
            if (e is java.io.EOFException && current.file.isEmpty()) return
            file?.abort()
            track(current.copy(state = Transfer.State.Failed, error = e.message ?: e.javaClass.simpleName))
        }
    }

    private fun errorMessage(payload: ByteArray) =
        runCatching { JSONObject(String(payload)).optString("message") }.getOrNull()?.takeIf { it.isNotEmpty() }

    companion object {
        const val TCP_PORT = 47800
        const val CHUNK = 512 * 1024
    }
}
