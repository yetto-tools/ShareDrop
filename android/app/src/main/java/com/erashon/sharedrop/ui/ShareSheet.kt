package com.erashon.sharedrop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.erashon.sharedrop.Engine
import com.erashon.sharedrop.Transfer
import com.erashon.sharedrop.discovery.Device
import kotlinx.coroutines.delay

/**
 * Hoja inferior del menú "Compartir" (estilo iOS): elige equipo, envía y muestra el progreso.
 * Se queda abierta hasta terminar, porque Android solo permite leer los archivos compartidos
 * mientras esta pantalla siga viva.
 */
@Composable
fun ShareSheet(engine: Engine, summary: String, onSend: (Device) -> Long, onClose: () -> Unit) {
    var sentId by remember { mutableStateOf<Long?>(null) }
    val devices by engine.devices.collectAsStateWithLifecycle()
    val transfers by engine.transfers.collectAsStateWithLifecycle()
    val transfer = sentId?.let { id -> transfers.firstOrNull { it.id == id } }

    // Al completarse, se cierra sola tras un instante para que se vea el "Completado".
    LaunchedEffect(transfer?.state) {
        if (transfer?.state == Transfer.State.Finished) {
            delay(1200)
            onClose()
        }
    }

    val sending = sentId != null && transfer?.state != Transfer.State.Failed
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.4f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { if (!sending) onClose() },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
            color = MaterialTheme.colorScheme.background,
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                // Que tocar la hoja no cuente como tocar el fondo.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        ) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp)) {
                Box(
                    Modifier.align(Alignment.CenterHorizontally).width(36.dp).height(5.dp)
                        .clip(CircleShape).background(MaterialTheme.colorScheme.outlineVariant),
                )
                Text(
                    if (sentId == null) "Enviar con ShareDrop" else "Enviando",
                    fontSize = 20.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 14.dp),
                )
                Text(summary, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 14.dp))

                if (transfer == null) {
                    if (sentId == null) {
                        DeviceList(devices, engine) { d -> sentId = onSend(d) }
                    }
                } else {
                    Grouped { TransferRow(transfer) }
                }

                if (!sending) {
                    TextButton(onClick = onClose, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp)) {
                        Text(if (transfer?.state == Transfer.State.Failed) "Cerrar" else "Cancelar", fontSize = 16.sp, textAlign = TextAlign.Center)
                    }
                }
            }
        }
        PairingDialogs(engine)
    }
}
