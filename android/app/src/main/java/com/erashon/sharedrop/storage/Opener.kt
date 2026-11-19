package com.erashon.sharedrop.storage

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap

/** Abre archivos recibidos y su carpeta con las apps del sistema. */
object Opener {
    /** Tipos que ejecutan código o instalan algo: nunca se abren desde ShareDrop. */
    private val RISKY = setOf(
        "apk", "apks", "xapk", "exe", "com", "scr", "bat", "cmd", "ps1", "vbs", "js", "jar", "msi", "dll", "sh",
        "lnk", "url", "hta", "reg", "appimage", "desktop", "pkg", "dmg", "app",
    )

    fun isRisky(name: String) = name.substringAfterLast('.', "").lowercase() in RISKY

    /** Devuelve un mensaje de error o aviso para mostrar al usuario, o null si todo salió bien. */
    fun openFile(ctx: Context, uri: Uri, name: String): String? {
        if (isRisky(name)) return "Por seguridad, este tipo de archivo no se abre desde ShareDrop."
        val ext = name.substringAfterLast('.', "").lowercase()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: ctx.contentResolver.getType(uri) ?: "*/*"
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            ctx.startActivity(intent)
            null
        } catch (e: ActivityNotFoundException) {
            "No hay ninguna app para abrir este tipo de archivo."
        } catch (e: SecurityException) {
            "No se pudo abrir el archivo."
        }
    }

    /** Abre el administrador de archivos en la carpeta de recibidos (la elegida o Download/ShareDrop). */
    fun openFolder(ctx: Context, tree: Uri?): String? {
        val dir = if (tree != null) {
            DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        } else {
            DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Download/$DEFAULT_FOLDER")
        }
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
        return try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(dir, DocumentsContract.Document.MIME_TYPE_DIR).addFlags(flags))
            null
        } catch (e: Exception) {
            // Algunos administradores de archivos no entienden esa carpeta: se abre la lista de descargas.
            try {
                ctx.startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                null
            } catch (e2: Exception) {
                "No se pudo abrir el administrador de archivos."
            }
        }
    }
}
