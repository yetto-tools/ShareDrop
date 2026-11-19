package com.erashon.sharedrop

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.app.PendingIntent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** Mantiene el servidor de recepción vivo con la pantalla apagada o la app en segundo plano. */
class TransferService : Service() {
    private var wake: PowerManager.WakeLock? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var watching = false
    private var watchingIncoming = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "ShareDrop", NotificationManager.IMPORTANCE_LOW))
        val open = android.app.PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle("ShareDrop activo")
            .setContentText("Listo para recibir archivos")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)

        wake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sharedrop:service").apply { acquire() }
        val engine = (application as ShareDropApp).engine
        engine.start()
        watchForUsbHint(engine, nm)
        watchIncoming(engine, nm)
        return START_STICKY
    }

    /** Avisa con una notificación cuando llega un archivo (recibiendo, recibido o fallido), con la app en segundo plano. */
    private fun watchIncoming(engine: Engine, nm: NotificationManager) {
        if (watchingIncoming) return
        watchingIncoming = true
        nm.createNotificationChannel(NotificationChannel(INCOMING_CHANNEL, "Archivos recibidos", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        scope.launch {
            val last = HashMap<Long, Pair<Transfer.State, Int>>()
            engine.transfers.collect { list ->
                for (t in list.filter { it.incoming }) {
                    val pct = if (t.total > 0) (t.done * 100 / t.total).toInt() else -1
                    val key = t.state to pct
                    if (last[t.id] == key) continue
                    last[t.id] = key
                    val b = Notification.Builder(this@TransferService, INCOMING_CHANNEL)
                        .setSmallIcon(android.R.drawable.stat_sys_download_done)
                        .setContentIntent(open)
                        .setOnlyAlertOnce(true)
                    when (t.state) {
                        Transfer.State.Active -> b.setContentTitle("Recibiendo de ${t.peer}")
                            .setContentText(t.file)
                            .setOngoing(true)
                            .setProgress(100, pct.coerceAtLeast(0), pct < 0)
                        Transfer.State.Finished -> b.setContentTitle("Archivo recibido")
                            .setContentText("${t.file} · de ${t.peer}")
                            .setAutoCancel(true)
                        Transfer.State.Failed -> b.setContentTitle("Falló la recepción")
                            .setContentText(t.error ?: t.file)
                            .setAutoCancel(true)
                    }
                    nm.notify(INCOMING_ID_BASE + (t.id % 100_000).toInt(), b.build())
                }
            }
        }
    }

    /**
     * Si se conecta un cable USB, no hay ningún equipo a la vista y el USB tethering está apagado,
     * avisa (tras 8 s, para no molestar al simplemente cargar el teléfono) y lleva al ajuste.
     */
    private fun watchForUsbHint(engine: Engine, nm: NotificationManager) {
        if (watching) return
        watching = true
        nm.createNotificationChannel(NotificationChannel(HINT_CHANNEL, "Avisos de conexión", NotificationManager.IMPORTANCE_DEFAULT))
        scope.launch {
            combine(engine.link, engine.devices) { l, d -> l.usbPlugged && !l.usbTether && d.isEmpty() }
                .distinctUntilChanged()
                .collectLatest { needsTether ->
                    if (!needsTether) {
                        nm.cancel(HINT_ID)
                        return@collectLatest
                    }
                    delay(8000)
                    val tether = Intent().setClassName("com.android.settings", "com.android.settings.TetherSettings")
                    val target = if (tether.resolveActivity(packageManager) != null) tether else Intent(Settings.ACTION_WIRELESS_SETTINGS)
                    val pi = PendingIntent.getActivity(this@TransferService, 1, target, PendingIntent.FLAG_IMMUTABLE)
                    nm.notify(
                        HINT_ID,
                        Notification.Builder(this@TransferService, HINT_CHANNEL)
                            .setContentTitle("Cable USB conectado")
                            .setContentText("Activa el anclaje USB para conectarte con la computadora.")
                            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                            .setContentIntent(pi)
                            .setAutoCancel(true)
                            .build(),
                    )
                }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        (application as ShareDropApp).engine.stop()
        wake?.release()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "sharedrop"
        private const val HINT_CHANNEL = "hints"
        private const val HINT_ID = 2
        private const val INCOMING_CHANNEL = "incoming"
        private const val INCOMING_ID_BASE = 100
    }
}
