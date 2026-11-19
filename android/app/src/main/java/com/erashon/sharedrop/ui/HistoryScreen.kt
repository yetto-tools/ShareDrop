package com.erashon.sharedrop.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.erashon.sharedrop.Engine
import com.erashon.sharedrop.Transfer
import com.erashon.sharedrop.core.HistoryEntry
import com.erashon.sharedrop.core.HistoryFilter
import com.erashon.sharedrop.core.HistoryLogic

/** Una entrada del historial como transferencia terminada, para reutilizar la fila y las acciones de abrir. */
private fun HistoryEntry.asTransfer() = Transfer(
    id = uid.hashCode().toLong(),
    peer = peer,
    file = file,
    done = size.coerceAtLeast(0),
    total = size,
    incoming = incoming,
    state = if (ok) Transfer.State.Finished else Transfer.State.Failed,
    error = error,
    uri = uri,
)

/** Historial: transferencias terminadas, guardadas en el teléfono. Cada pestaña muestra las 20 más recientes. */
@Composable
fun HistoryPage(engine: Engine, onBack: () -> Unit) {
    val entries by engine.history.entries.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<Transfer?>(null) }
    val filter = HistoryFilter.entries[tab]
    val shown = HistoryLogic.visible(entries, filter)
    val now = System.currentTimeMillis()

    PageHeader(Screen.History, "Las últimas ${HistoryLogic.KEEP} de cada tipo", onBack = onBack)
    SegmentedControl(listOf("Todo", "↓ Recibidos", "↑ Enviados"), tab) { tab = it }
    Spacer(Modifier.height(10.dp))

    if (shown.isEmpty()) {
        Text(
            when (filter) {
                HistoryFilter.All -> "Aún no hay transferencias."
                HistoryFilter.Received -> "Aún no has recibido archivos."
                HistoryFilter.Sent -> "Aún no has enviado archivos."
            },
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp),
        )
    } else {
        Grouped {
            shown.forEachIndexed { i, e ->
                if (i > 0) RowDivider()
                TransferRow(e.asTransfer(), onOpen = { selected = it }, whenText = HistoryLogic.whenLabel(e.at, now))
            }
        }
    }
    selected?.let { FileActionsSheet(it, engine) { selected = null } }
}
