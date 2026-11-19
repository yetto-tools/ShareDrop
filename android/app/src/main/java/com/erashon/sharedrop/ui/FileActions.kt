package com.erashon.sharedrop.ui

import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.erashon.sharedrop.Engine
import com.erashon.sharedrop.Transfer
import com.erashon.sharedrop.storage.Opener

/** Hoja de acciones (estilo iOS) de un archivo recibido: abrirlo o ver su carpeta. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileActionsSheet(t: Transfer, engine: Engine, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    fun report(msg: String?) {
        if (msg != null) Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.background,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
    ) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp).navigationBarsPadding()) {
            Text(
                t.file, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            )
            Grouped {
                ActionRow("Abrir archivo") {
                    report(t.uri?.let { Opener.openFile(ctx, Uri.parse(it), t.file) })
                    onDismiss()
                }
                RowDivider(inset = false)
                ActionRow("Mostrar en carpeta") {
                    report(Opener.openFolder(ctx, engine.receiveTree.value?.let(Uri::parse)))
                    onDismiss()
                }
            }
            Spacer(Modifier.height(8.dp))
            Grouped { ActionRow("Cancelar", bold = true, onClick = onDismiss) }
        }
    }
}

@Composable
private fun ActionRow(text: String, bold: Boolean = false, onClick: () -> Unit) {
    Text(
        text,
        color = MaterialTheme.colorScheme.primary,
        fontSize = 18.sp,
        fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 16.dp),
    )
}
