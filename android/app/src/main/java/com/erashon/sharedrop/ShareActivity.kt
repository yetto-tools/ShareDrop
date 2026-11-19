package com.erashon.sharedrop

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.erashon.sharedrop.storage.Storage
import com.erashon.sharedrop.ui.ShareDropTheme
import com.erashon.sharedrop.ui.ShareSheet

/**
 * Aparece como "ShareDrop" en el menú Compartir de cualquier app. Admite archivos (uno o varios)
 * y también texto o enlaces, que se envían como un archivo .txt.
 */
class ShareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val engine = (application as ShareDropApp).engine
        startForegroundService(Intent(this, TransferService::class.java))

        val uris = sharedUris()
        if (uris.isEmpty()) {
            finish()
            return
        }
        val first = Storage.describe(this, uris.first()).name
        val summary = if (uris.size == 1) first else "$first y ${uris.size - 1} más"

        setContent {
            ShareDropTheme {
                ShareSheet(engine, summary, onSend = { device -> engine.send(device, uris) }, onClose = { finish() })
            }
        }
    }

    private fun sharedUris(): List<Uri> {
        val streams: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(parcelable())
            Intent.ACTION_SEND_MULTIPLE -> parcelableList()
            else -> emptyList()
        }
        if (streams.isNotEmpty()) return streams
        // Sin archivo adjunto: puede ser texto o un enlace.
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.takeIf { it.isNotBlank() }
        return if (intent.action == Intent.ACTION_SEND && text != null) listOf(Storage.textAsFile(this, text)) else emptyList()
    }

    @Suppress("DEPRECATION")
    private fun parcelable(): Uri? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else intent.getParcelableExtra(Intent.EXTRA_STREAM)

    @Suppress("DEPRECATION")
    private fun parcelableList(): List<Uri> =
        (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)).orEmpty()
}
