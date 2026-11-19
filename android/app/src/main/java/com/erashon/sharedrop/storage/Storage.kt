package com.erashon.sharedrop.storage

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.OutputStream

class SourceFile(val uri: Uri, val name: String, val size: Long)

/**
 * Un archivo que se está recibiendo. Va a la carpeta elegida por el usuario ([tree]) o, si no hay
 * (o ya no se puede escribir en ella), a Download/ShareDrop.
 */
class IncomingFile(private val ctx: Context, name: String, tree: Uri? = null) {
    private val uri: Uri
    private val viaTree: Boolean
    val out: OutputStream

    init {
        val custom = tree?.let {
            runCatching {
                val parent = DocumentsContract.buildDocumentUriUsingTree(it, DocumentsContract.getTreeDocumentId(it))
                DocumentsContract.createDocument(ctx.contentResolver, parent, mimeOf(name), name)
            }.getOrNull()
        }
        if (custom != null) {
            uri = custom
            viaTree = true
        } else {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$DEFAULT_FOLDER")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("No se pudo crear el archivo $name")
            viaTree = false
        }
        out = ctx.contentResolver.openOutputStream(uri)!!.buffered(1 shl 16)
    }

    /** Cierra el archivo y devuelve su URI para poder abrirlo después. */
    fun finish(): Uri {
        out.close()
        if (!viaTree) {
            val v = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
            ctx.contentResolver.update(uri, v, null, null)
        }
        return uri
    }

    fun abort() {
        runCatching { out.close() }
        runCatching {
            if (viaTree) DocumentsContract.deleteDocument(ctx.contentResolver, uri)
            else ctx.contentResolver.delete(uri, null, null)
        }
    }

    private fun mimeOf(name: String): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"
}

object Storage {
    const val DEFAULT_LABEL = "Descargas/ShareDrop"

    /** ¿Tiene la app "Acceso a todos los archivos"? */
    fun hasAllFilesAccess(): Boolean = Environment.isExternalStorageManager()

    /**
     * Con el permiso concedido crea Download/ShareDrop para que se vea desde el principio.
     * Sin él, la carpeta aparece al recibir el primer archivo (MediaStore la crea).
     */
    fun ensureDefaultFolder() {
        if (!hasAllFilesAccess()) return
        runCatching {
            java.io.File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), DEFAULT_FOLDER).mkdirs()
        }
    }

    /** Guarda un texto o enlace compartido como un archivo .txt en la caché, listo para enviarlo. */
    fun textAsFile(ctx: Context, text: String): Uri {
        val dir = java.io.File(ctx.cacheDir, "shared-text").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() } // solo se conserva el último
        val isLink = text.trim().let { it.startsWith("http://") || it.startsWith("https://") }
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
        val f = java.io.File(dir, (if (isLink) "Enlace-" else "Texto-") + stamp + ".txt")
        f.writeText(text)
        return Uri.fromFile(f)
    }

    fun describe(ctx: Context, uri: Uri): SourceFile {
        if (uri.scheme == "file") {
            val f = java.io.File(requireNotNull(uri.path))
            return SourceFile(uri, f.name, f.length())
        }
        var name = uri.lastPathSegment ?: "archivo"
        var size = -1L
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = c.getString(it) }
                c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let { size = c.getLong(it) }
            }
        }
        return SourceFile(uri, name, size)
    }

    /** Texto legible de una carpeta elegida, p. ej. "Almacenamiento interno/Documents/Fotos". */
    fun label(tree: Uri?): String {
        if (tree == null) return DEFAULT_LABEL
        val id = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: return tree.toString()
        val volume = id.substringBefore(':')
        val path = id.substringAfter(':', "")
        val root = if (volume == "primary") "Almacenamiento interno" else volume
        return if (path.isEmpty()) root else "$root/$path"
    }
}

const val DEFAULT_FOLDER = "ShareDrop"
