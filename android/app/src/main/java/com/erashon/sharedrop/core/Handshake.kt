package com.erashon.sharedrop.core

import org.json.JSONObject
import java.io.IOException

/** Interacción con el usuario durante el emparejamiento. */
interface PairingUi {
    /** Responder: muestra [code] al usuario hasta que termine el emparejamiento. */
    fun showCode(peerName: String, code: String)
    fun hideCode()

    /** Iniciador: bloquea hasta que el usuario escribe el código mostrado en el otro equipo, o null si cancela. */
    fun askCode(peerName: String): String?
}

class Peer(val id: String, val name: String)

/** La conexión era un sondeo (quién eres): ya se respondió; no es un fallo ni una transferencia. */
class ProbeDone : Exception("Sondeo")

object Handshake {
    private fun json(vararg kv: Pair<String, Any>) = JSONObject(mapOf(*kv)).toString().toByteArray()

    private fun expect(f: Frame, type: Int): JSONObject {
        if (f.type == T.ERROR) throw IOException(JSONObject(String(f.payload)).optString("message", "Error remoto"))
        if (f.type != type) throw IOException("Se esperaba el tipo $type y llegó ${f.type}")
        return JSONObject(String(f.payload))
    }

    private fun proof(secret: ByteArray, who: String, nc: ByteArray, ns: ByteArray) =
        Crypto.hmac(secret, who.toByteArray(), nc, ns)

    /** Lado cliente (quien se conecta). */
    fun initiate(ch: Channel, store: Store, ui: PairingUi): Peer {
        val nc = Crypto.randomBytes(16)
        ch.send(T.HELLO, json("id" to store.deviceId, "name" to store.deviceName, "nonce" to nc.hex()))
        val h = expect(ch.recv(), T.HELLO)
        val peer = Peer(h.getString("id"), h.getString("name"))
        val ns = h.getString("nonce").unhex()

        val stored = store.secretFor(peer.id)
        val pairing = !(h.getBoolean("paired") && stored != null)
        val secret = if (pairing) {
            val code = ui.askCode(peer.name) ?: run {
                ch.send(T.ERROR, json("message" to "Emparejamiento cancelado"))
                throw IOException("Emparejamiento cancelado")
            }
            Crypto.pinSecret(code.trim())
        } else stored!!

        ch.send(T.AUTH, json("proof" to proof(secret, "C", nc, ns).hex()))
        val ok = expect(ch.recv(), T.AUTH_OK)
        if (!Crypto.constantTimeEquals(ok.getString("proof").unhex(), proof(secret, "S", nc, ns)))
            throw IOException("El otro equipo no pudo autenticarse")
        ch.enableCrypto(Crypto.hmac(secret, "key".toByteArray(), nc, ns), isClient = true)

        if (pairing) {
            val s = expect(ch.recv(), T.PAIR_SECRET)
            store.savePeer(peer.id, s.getString("secret").unhex())
        }
        return peer
    }

    /**
     * Pregunta a `host:port` quién es, sin emparejar ni transferir (ver "Sondeo" en PROTOCOL.md).
     * Devuelve su respuesta HELLO o null si no hay nadie (conexión rechazada, tiempo agotado…).
     */
    fun probe(host: String, port: Int, store: Store): JSONObject? = try {
        java.net.Socket().use { sock ->
            sock.connect(java.net.InetSocketAddress(host, port), 800)
            sock.soTimeout = 1500
            Channel(sock).use { ch ->
                ch.send(
                    T.HELLO,
                    json("id" to store.deviceId, "name" to store.deviceName, "nonce" to Crypto.randomBytes(16).hex(), "probe" to true),
                )
                val f = ch.recv()
                if (f.type == T.HELLO) JSONObject(String(f.payload)) else null
            }
        }
    } catch (e: Exception) {
        null
    }

    /** Lado servidor (quien acepta la conexión). `info` aporta los datos del equipo para los sondeos. */
    fun respond(ch: Channel, store: Store, ui: PairingUi, info: () -> JSONObject = { JSONObject() }): Peer {
        val h = expect(ch.recv(), T.HELLO)
        val peer = Peer(h.getString("id"), h.getString("name"))
        val nc = h.getString("nonce").unhex()
        val ns = Crypto.randomBytes(16)

        // Sondeo: se dice quién somos y se cierra, sin emparejar ni registrar transferencia.
        if (h.optBoolean("probe", false)) {
            ch.send(
                T.HELLO,
                json(
                    "id" to store.deviceId, "name" to store.deviceName, "nonce" to ns.hex(),
                    "paired" to store.isPaired(peer.id), "platform" to "android", "info" to info(),
                ),
            )
            throw ProbeDone()
        }

        val stored = store.secretFor(peer.id)
        val pairing = stored == null
        var code: String? = null
        val secret = if (pairing) {
            code = "%06d".format(java.security.SecureRandom().nextInt(1_000_000))
            ui.showCode(peer.name, code)
            Crypto.pinSecret(code)
        } else stored!!

        try {
            ch.send(T.HELLO, json("id" to store.deviceId, "name" to store.deviceName, "nonce" to ns.hex(), "paired" to !pairing))
            val a = expect(ch.recv(), T.AUTH)
            if (!Crypto.constantTimeEquals(a.getString("proof").unhex(), proof(secret, "C", nc, ns))) {
                ch.send(T.ERROR, json("message" to "Código o secreto incorrecto"))
                throw IOException("Autenticación fallida con ${peer.name}")
            }
            ch.send(T.AUTH_OK, json("proof" to proof(secret, "S", nc, ns).hex()))
            ch.enableCrypto(Crypto.hmac(secret, "key".toByteArray(), nc, ns), isClient = false)

            if (pairing) {
                val fresh = Crypto.randomBytes(32)
                ch.send(T.PAIR_SECRET, json("secret" to fresh.hex()))
                store.savePeer(peer.id, fresh)
            }
        } finally {
            if (pairing) ui.hideCode()
        }
        return peer
    }
}
