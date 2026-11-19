package com.erashon.sharedrop.ui

import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import com.erashon.sharedrop.discovery.mediumOf
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.erashon.sharedrop.Engine
import com.erashon.sharedrop.Transfer
import com.erashon.sharedrop.discovery.Device
import com.erashon.sharedrop.discovery.HotspotController
import com.erashon.sharedrop.discovery.LinkState
import com.erashon.sharedrop.storage.Storage

// ---------- Piezas base al estilo iOS ----------

@Composable
fun LargeTitle(title: String, subtitle: String) {
    Column(Modifier.padding(start = 4.dp, bottom = 8.dp)) {
        Text(title, fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp, color = MaterialTheme.colorScheme.onBackground)
        Text(subtitle, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun SectionHeader(text: String) {
    Text(
        text.uppercase(),
        fontSize = 13.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 26.dp, bottom = 7.dp),
    )
}

/** Lista agrupada: una tarjeta redondeada con filas separadas por líneas finas. */
@Composable
fun Grouped(content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Column { content() }
    }
}

@Composable
fun RowDivider(inset: Boolean = true) {
    HorizontalDivider(
        modifier = Modifier.padding(start = if (inset) 68.dp else 16.dp),
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

@Composable
private fun TintedButton(text: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 7.dp),
    ) {
        Text(text, color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    }
}

/** Círculo con el icono del tipo de equipo, dibujado a mano (sin librerías de iconos). */
@Composable
private fun Avatar(phone: Boolean) {
    val tint = MaterialTheme.colorScheme.onPrimaryContainer
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(22.dp)) {
            val s = Stroke(width = 1.7.dp.toPx(), cap = StrokeCap.Round)
            if (phone) {
                drawRoundRect(tint, Offset(size.width * .28f, size.height * .06f), Size(size.width * .44f, size.height * .88f), CornerRadius(3.dp.toPx()), style = s)
                drawLine(tint, Offset(size.width * .43f, size.height * .8f), Offset(size.width * .57f, size.height * .8f), 1.7.dp.toPx(), StrokeCap.Round)
            } else {
                drawRoundRect(tint, Offset(size.width * .12f, size.height * .2f), Size(size.width * .76f, size.height * .5f), CornerRadius(2.dp.toPx()), style = s)
                drawLine(tint, Offset(size.width * .04f, size.height * .84f), Offset(size.width * .96f, size.height * .84f), 1.7.dp.toPx(), StrokeCap.Round)
            }
        }
    }
}

// ---------- Emparejamiento ----------

/** Diálogos de emparejamiento: se muestran donde sea que esté el usuario. */
@Composable
fun PairingDialogs(engine: Engine) {
    val show by engine.codeToShow.collectAsStateWithLifecycle()
    val ask by engine.codeRequest.collectAsStateWithLifecycle()

    show?.let { (peer, code) ->
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            shape = RoundedCornerShape(14.dp),
            title = { Text("Emparejar con $peer", textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) },
            text = {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Escribe este código en el otro equipo", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        code.toCharArray().joinToString(" "),
                        fontSize = 38.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 14.dp),
                    )
                }
            },
        )
    }

    ask?.let { p ->
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { engine.answerCode(null) },
            shape = RoundedCornerShape(14.dp),
            title = { Text("Emparejar con ${p.peerName}", textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) },
            text = {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Escribe el código de 6 dígitos que muestra el otro equipo", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it.filter(Char::isDigit).take(6) },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.headlineSmall.copy(textAlign = TextAlign.Center, letterSpacing = 6.sp),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.padding(top = 14.dp),
                    )
                }
            },
            confirmButton = { TextButton(onClick = { engine.answerCode(text) }, enabled = text.length == 6) { Text("Aceptar", fontWeight = FontWeight.SemiBold) } },
            dismissButton = { TextButton(onClick = { engine.answerCode(null) }) { Text("Cancelar") } },
        )
    }
}

// ---------- Dispositivos ----------

/** Icono del sistema operativo del dispositivo en un círculo: Android, Windows, Linux o, si no se sabe, un portátil. */
@Composable
private fun OsAvatar(platform: String) {
    // Cada sistema con su color de marca: Android siempre verde.
    val tint = when (platform.lowercase()) {
        "android" -> Color(0xFF3DDC84)
        "windows" -> Color(0xFF0A84FF)
        "linux" -> Color(0xFFF5C518)
        else -> MaterialTheme.colorScheme.onPrimaryContainer
    }
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(tint.copy(alpha = 0.18f)),
        contentAlignment = Alignment.Center,
    ) {
        // Capa propia: los ojos del robot y del pingüino son huecos (BlendMode.Clear).
        Canvas(Modifier.size(24.dp).graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)) {
            val w = size.width
            val h = size.height
            when (platform.lowercase()) {
                "android" -> {
                    drawArc(tint, 180f, 180f, true, Offset(w * 0.2f, h * 0.14f), Size(w * 0.6f, h * 0.6f))
                    drawRoundRect(tint, Offset(w * 0.2f, h * 0.44f), Size(w * 0.6f, h * 0.4f), CornerRadius(2.dp.toPx()))
                    drawRoundRect(tint, Offset(w * 0.06f, h * 0.44f), Size(w * 0.1f, h * 0.3f), CornerRadius(w * 0.05f))
                    drawRoundRect(tint, Offset(w * 0.84f, h * 0.44f), Size(w * 0.1f, h * 0.3f), CornerRadius(w * 0.05f))
                    drawRoundRect(tint, Offset(w * 0.32f, h * 0.76f), Size(w * 0.12f, h * 0.2f), CornerRadius(w * 0.06f))
                    drawRoundRect(tint, Offset(w * 0.56f, h * 0.76f), Size(w * 0.12f, h * 0.2f), CornerRadius(w * 0.06f))
                    drawLine(tint, Offset(w * 0.32f, h * 0.2f), Offset(w * 0.24f, h * 0.06f), 1.4.dp.toPx(), StrokeCap.Round)
                    drawLine(tint, Offset(w * 0.68f, h * 0.2f), Offset(w * 0.76f, h * 0.06f), 1.4.dp.toPx(), StrokeCap.Round)
                    drawCircle(Color.Black, 1.3.dp.toPx(), Offset(w * 0.38f, h * 0.34f), blendMode = BlendMode.Clear)
                    drawCircle(Color.Black, 1.3.dp.toPx(), Offset(w * 0.62f, h * 0.34f), blendMode = BlendMode.Clear)
                }
                "windows" -> {
                    val g = w * 0.05f
                    val half = w * 0.44f
                    val x0 = w * 0.06f
                    val y0 = h * 0.12f
                    drawRect(tint, Offset(x0, y0), Size(half, half * 0.95f))
                    drawRect(tint, Offset(x0 + half + g * 1.4f, y0), Size(half, half * 0.95f))
                    drawRect(tint, Offset(x0, y0 + half * 0.95f + g * 1.4f), Size(half, half * 0.95f))
                    drawRect(tint, Offset(x0 + half + g * 1.4f, y0 + half * 0.95f + g * 1.4f), Size(half, half * 0.95f))
                }
                "linux" -> {
                    drawOval(tint, Offset(w * 0.22f, h * 0.06f), Size(w * 0.56f, h * 0.88f))
                    drawOval(tint, Offset(w * 0.12f, h * 0.7f), Size(w * 0.3f, h * 0.24f))
                    drawOval(tint, Offset(w * 0.58f, h * 0.7f), Size(w * 0.3f, h * 0.24f))
                    drawCircle(Color.Black, 1.4.dp.toPx(), Offset(w * 0.42f, h * 0.24f), blendMode = BlendMode.Clear)
                    drawCircle(Color.Black, 1.4.dp.toPx(), Offset(w * 0.58f, h * 0.24f), blendMode = BlendMode.Clear)
                }
                else -> {
                    val s2 = Stroke(width = 1.7.dp.toPx(), cap = StrokeCap.Round)
                    drawRoundRect(tint, Offset(w * 0.12f, h * 0.2f), Size(w * 0.76f, h * 0.5f), CornerRadius(2.dp.toPx()), style = s2)
                    drawLine(tint, Offset(w * 0.04f, h * 0.84f), Offset(w * 0.96f, h * 0.84f), 1.7.dp.toPx(), StrokeCap.Round)
                }
            }
        }
    }
}

/** Etiqueta pequeña de una línea: el medio de conexión o "Pre-alpha". */
@Composable
private fun Tag(text: String, color: Color, outlined: Boolean = false) {
    Text(
        text, color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(CircleShape)
            .then(if (outlined) Modifier.border(1.dp, color.copy(alpha = 0.55f), CircleShape) else Modifier.background(color.copy(alpha = 0.16f)))
            .padding(horizontal = 8.dp, vertical = 1.dp),
    )
}

private val Orange = Color(0xFFFF9500)

/** Símbolo "info.circle" de iOS: círculo de línea fina con un punto y un trazo, en el color de acento. */
@Composable
private fun InfoButton(name: String, onClick: () -> Unit) {
    val tint = MaterialTheme.colorScheme.primary
    Box(
        // Área táctil de 44 dp (mínimo de accesibilidad); el dibujo es de 24 dp.
        Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onClick).semantics { contentDescription = "Información de $name" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(24.dp)) {
            val w = size.width
            val line = 1.7.dp.toPx()
            drawCircle(tint, w * 0.45f, Offset(w / 2, w / 2), style = Stroke(width = line, cap = StrokeCap.Round))
            drawLine(tint, Offset(w / 2, w * 0.46f), Offset(w / 2, w * 0.68f), line, StrokeCap.Round)
            drawCircle(tint, line * 0.62f, Offset(w / 2, w * 0.32f))
        }
    }
}

@Composable
fun DeviceList(devices: List<Device>, engine: Engine, onPick: (Device) -> Unit) {
    var detail by remember { mutableStateOf<Device?>(null) }
    if (devices.isEmpty()) {
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 24.dp, vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.5.dp)
                Text("Buscando dispositivos…", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 14.dp))
                Text(
                    "Abre ShareDrop en el otro equipo. Deben estar en la misma red o conectados por USB.",
                    fontSize = 13.sp, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        return
    }
    Grouped {
        devices.forEachIndexed { i, d ->
            if (i > 0) RowDivider()
            val medium = mediumOf(d)
            // Teléfono↔PC es lo principal; teléfono↔teléfono funciona pero aún está en pruebas.
            val preAlpha = d.platform.equals("android", ignoreCase = true)
            val scheme = MaterialTheme.colorScheme
            Row(
                Modifier.fillMaxWidth().clickable { onPick(d) }.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                OsAvatar(d.platform)
                Column(Modifier.weight(1f)) {
                    Text(d.name, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val hw = listOfNotNull(d.info?.optString("maker")?.takeIf { it.isNotEmpty() }, d.info?.optString("model")?.takeIf { it.isNotEmpty() })
                        .joinToString(" ")
                    if (hw.isNotEmpty()) Text(hw, fontSize = 13.sp, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        (if (engine.store.isPaired(d.id)) "Emparejado" else "Sin emparejar") + " · ${d.address}",
                        fontSize = 13.sp, color = scheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    // Debajo de "Emparejado · dirección": por qué medio se conecta y si la pareja aún es Pre-alpha.
                    if (medium != null || preAlpha) {
                        Row(Modifier.padding(top = 3.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            medium?.let { Tag(it.label, if (it.key == "usb") Orange else scheme.primary) }
                            if (preAlpha) Tag("Pre-alpha", Orange, outlined = true)
                        }
                    }
                }
                InfoButton(d.name) { detail = d }
                TintedButton("Enviar") { onPick(d) }
            }
        }
    }
    detail?.let { DeviceInfoSheet(it, engine.store.isPaired(it.id)) { detail = null } }
}

/** Ficha de información de un dispositivo: lo que se sabe de él y por dónde se conecta. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun DeviceInfoSheet(d: Device, paired: Boolean, onDismiss: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val medium = mediumOf(d)
    val info = d.info
    fun gb(bytes: Long) = (bytes / (1L shl 30).toDouble()).let { if (it >= 10) "%.0f GB".format(it) else "%.1f GB".format(it) }
    val rows = buildList {
        add("Sistema" to (info?.optString("os")?.takeIf { it.isNotEmpty() } ?: d.platform.replaceFirstChar { it.uppercase() }))
        val hw = listOfNotNull(info?.optString("maker")?.takeIf { it.isNotEmpty() }, info?.optString("model")?.takeIf { it.isNotEmpty() }).joinToString(" ")
        if (hw.isNotEmpty()) add("Modelo" to hw)
        if (info != null && info.has("battery")) add("Batería" to "${info.optInt("battery")} %" + if (info.optBoolean("charging")) " · cargando" else "")
        if (info != null && info.has("free") && info.has("total")) add("Almacenamiento" to "${gb(info.optLong("free"))} libres de ${gb(info.optLong("total"))}")
        medium?.let { add("Conexión" to it.label) }
        add("Dirección" to "${d.address}:${d.port}")
        add("Estado" to if (paired) "Emparejado" else "Sin emparejar")
    }
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = scheme.background,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
    ) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp).navigationBarsPadding()) {
            Text(d.name, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 10.dp))
            Grouped {
                rows.forEachIndexed { i, (k, v) ->
                    if (i > 0) RowDivider(inset = false)
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(k, fontSize = 15.sp, color = scheme.onSurfaceVariant)
                        Text(v, fontSize = 15.sp, textAlign = TextAlign.End, modifier = Modifier.padding(start = 16.dp).weight(1f, fill = false))
                    }
                }
            }
            if (d.platform.equals("android", ignoreCase = true)) {
                Text(
                    "Pre-alpha: entre dos teléfonos o entre dos PC la conexión funciona, pero aún está en pruebas.",
                    fontSize = 12.sp, color = scheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, top = 8.dp),
                )
            }
        }
    }
}

// ---------- Transferencias ----------

/** Control segmentado estilo iOS: una pastilla gris con la opción elegida resaltada. */
@Composable
internal fun SegmentedControl(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(scheme.surfaceVariant).padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(8.dp))
                    .background(if (on) scheme.surface else Color.Transparent)
                    .clickable { onSelect(i) }
                    .semantics { contentDescription = label }
                    .padding(vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, fontSize = 13.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium, color = scheme.onSurface)
            }
        }
    }
}

@Composable
fun TransferSection(engine: Engine) {
    val transfers by engine.transfers.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf<Transfer?>(null) }
    // 0 = todo, 1 = recibidos (descargas), 2 = enviados (subidas)
    var filter by rememberSaveable { mutableIntStateOf(0) }
    if (transfers.isEmpty()) return

    SectionHeader("Transferencias")
    SegmentedControl(listOf("Todo", "↓ Recibidos", "↑ Enviados"), filter) { filter = it }
    Spacer(Modifier.height(10.dp))

    val shown = transfers.filter { filter == 0 || (filter == 1) == it.incoming }.take(30)
    if (shown.isEmpty()) {
        Text(
            if (filter == 1) "Aún no has recibido archivos." else "Aún no has enviado archivos.",
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp),
        )
    } else {
        Grouped {
            shown.forEachIndexed { i, t ->
                if (i > 0) RowDivider()
                TransferRow(t, onOpen = { selected = it })
            }
        }
    }
    selected?.let { FileActionsSheet(it, engine) { selected = null } }
}

@Composable
fun TransferRow(t: Transfer, onOpen: ((Transfer) -> Unit)? = null, whenText: String? = null) {
    // Un color por dirección: lo recibido (descarga) es turquesa y lo enviado (subida) es azul.
    val dirColor = if (t.incoming) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
    val failed = t.state == Transfer.State.Failed
    val color = if (failed) MaterialTheme.colorScheme.error else dirColor
    // El texto de la etiqueta necesita más contraste que el anillo: el amarillo usa un tono propio para texto.
    val chipText = when {
        failed -> MaterialTheme.colorScheme.error
        t.incoming -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.primary
    }
    val openable = onOpen != null && t.uri != null
    Row(
        Modifier.fillMaxWidth()
            .then(if (openable) Modifier.clickable { onOpen?.invoke(t) } else Modifier)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(30.dp).clip(CircleShape).background(color.copy(alpha = 0.15f)))
            when {
                t.state != Transfer.State.Active ->
                    CircularProgressIndicator(progress = { 1f }, modifier = Modifier.size(40.dp), color = color, trackColor = Color.Transparent, strokeWidth = 3.dp)
                t.total > 0 ->
                    CircularProgressIndicator(
                        progress = { (t.done.toFloat() / t.total).coerceIn(0f, 1f) },
                        modifier = Modifier.size(40.dp), color = color, trackColor = MaterialTheme.colorScheme.surfaceVariant, strokeWidth = 3.dp,
                    )
                else -> CircularProgressIndicator(Modifier.size(40.dp), color = color, strokeWidth = 3.dp)
            }
            Text(if (t.incoming) "↓" else "↑", color = color, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.weight(1f)) {
            Text(t.file.ifEmpty { "…" }, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Etiqueta de dirección: lleva el verbo y el estado (Recibiendo → Recibido).
                Text(
                    "${if (t.incoming) "↓" else "↑"} ${directionLabel(t)}",
                    color = chipText, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(CircleShape).background(color.copy(alpha = 0.16f)).padding(horizontal = 8.dp, vertical = 1.dp),
                )
                Text(
                    statusText(t), fontSize = 13.sp,
                    color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 6.dp).weight(1f, fill = false),
                )
            }
            // Fecha del historial: en su propia línea, para que no se parta a mitad de "Hoy · 16:50".
            if (whenText != null) {
                Text(whenText, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
            }
        }
        if (openable) Text("›", fontSize = 24.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun directionLabel(t: Transfer): String = when (t.state) {
    Transfer.State.Active -> if (t.incoming) "Recibiendo" else "Enviando"
    Transfer.State.Finished -> if (t.incoming) "Recibido" else "Enviado"
    Transfer.State.Failed -> if (t.incoming) "Recepción" else "Envío"
}

/** Equipo y tamaño, sin repetir la dirección que ya dice la etiqueta. */
private fun statusText(t: Transfer): String {
    val peer = if (t.peer.isNotEmpty()) (if (t.incoming) "De " else "A ") + t.peer else ""
    val detail = when (t.state) {
        Transfer.State.Active -> "${size(t.done)} de ${size(t.total)}"
        Transfer.State.Finished -> size(t.total)
        Transfer.State.Failed -> "No se pudo completar: ${t.error}"
    }
    return listOf(peer, detail).filter { it.isNotEmpty() }.joinToString(" · ")
}

// ---------- Carpeta de destino ----------

@Composable
fun FolderSection(engine: Engine, onChoose: () -> Unit, header: Boolean = true) {
    val tree by engine.receiveTree.collectAsStateWithLifecycle()
    if (header) SectionHeader("Archivos recibidos")
    Grouped {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onChoose).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Guardar en", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Text(Storage.label(tree?.let(Uri::parse)), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("›", fontSize = 24.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (tree != null) {
            RowDivider(inset = false)
            Text(
                "Restablecer carpeta predeterminada",
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth().clickable { engine.setReceiveTree(null) }.padding(horizontal = 16.dp, vertical = 14.dp),
            )
        }
    }
    Spacer(Modifier.height(24.dp))
}

// ---------- Conexión ----------

/** Medios por los que se pueden conectar los dos equipos, cada uno con su icono y su color. */
private enum class Media(val color: Color) {
    Router(Color(0xFF007AFF)),
    Usb(Color(0xFFFF9500)),
    SystemHotspot(Color(0xFF34C759)),
    PhoneNetwork(Color(0xFF5856D6)),
}

/** Aviso que se repite en las dos apps: sin una red en común no hay conexión. */
const val SAME_NETWORK_NOTE =
    "Los dos equipos deben estar conectados a la misma red (el mismo router), o unidos por cable USB o por la red del teléfono."

/** Estado de los medios de conexión y acciones para habilitarlos. */
@Composable
fun ConnectionSection(
    link: LinkState,
    hotspot: HotspotController.State,
    hasPeers: Boolean,
    onWifi: () -> Unit,
    onTether: () -> Unit,
    onStartHotspot: () -> Unit,
    onStopHotspot: () -> Unit,
    header: Boolean = true,
    usbShared: Boolean = false,
) {
    if (header) SectionHeader("Conexión")

    // ----- Estado general -----
    Grouped {
        val media = buildList {
            if (link.wifiConnected) add("Wi-Fi")
            if (link.usbTether) add("USB")
            if (link.hotspot || hotspot.active) add("Red del teléfono")
            if (link.bluetoothTether) add("Bluetooth")
            if (link.wifiDirect) add("Wi-Fi Direct")
        }
        val (title, detail, color) = when {
            hasPeers -> Triple("Conectado", media.joinToString(" · ").ifEmpty { "Equipo encontrado" }, MaterialTheme.colorScheme.tertiary)
            link.anyLink -> Triple(
                "No veo otros equipos",
                "Hay " + media.joinToString(" y ") + ", pero nadie responde. Una red corporativa puede aislar los equipos: usa USB o la red del teléfono.",
                MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> Triple("Sin conexión", "No hay ningún enlace con otro equipo. Activa una de estas opciones.", MaterialTheme.colorScheme.error)
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = color)
            Text(detail, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    // ----- Los cuatro medios -----
    Spacer(Modifier.height(14.dp))
    Grouped {
        MediaRow(
            Media.Router, "Wi-Fi del router",
            when {
                !link.wifiOn -> "Wi-Fi apagado. Toca para activarlo"
                link.wifiConnected -> "Conectado. Comprueba que la otra computadora use este mismo Wi-Fi"
                else -> "Conéctate al mismo Wi-Fi (router) que la otra computadora"
            },
            active = link.wifiConnected, onClick = onWifi,
        )
        RowDivider()
        MediaRow(
            Media.Usb, "Cable USB",
            when {
                usbShared -> "Compartiendo archivos e información por USB"
                link.usbTether -> "Anclaje USB activo"
                link.usbPlugged -> "Cable detectado. Toca para activar el anclaje USB"
                else -> "Conecta el cable y activa el anclaje USB"
            },
            active = link.usbTether || usbShared, onClick = onTether,
        )
        RowDivider()
        MediaRow(
            Media.SystemHotspot, "Zona Wi-Fi de Android",
            "Comparte la red del teléfono con el anclaje del sistema",
            active = link.hotspot, onClick = onTether,
        )
        RowDivider()
        MediaRow(
            Media.PhoneNetwork,
            when {
                hotspot.active -> "Red del teléfono activa"
                hotspot.starting -> "Creando red…"
                else -> "Crear red Wi-Fi del teléfono"
            },
            hotspot.error ?: "Sin internet ni router. La otra computadora se une con una clave",
            active = hotspot.active, error = hotspot.error != null,
            onClick = { if (!hotspot.active && !hotspot.starting) onStartHotspot() },
        ) {
            if (hotspot.active) {
                Text("Red: ${hotspot.ssid ?: "?"}", fontSize = 15.sp, modifier = Modifier.padding(top = 4.dp))
                Text("Clave: ${hotspot.password ?: "?"}", fontSize = 15.sp)
            }
        }
        if (hotspot.active) {
            RowDivider(inset = false)
            ActionLine("Detener red del teléfono", null, onStopHotspot, destructive = true)
        }
    }

    // Nota al pie, como en los Ajustes de iOS: lo que hay que tener en cuenta para que funcione.
    Text(
        SAME_NETWORK_NOTE,
        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
    )
}

@Composable
private fun MediaRow(
    media: Media,
    title: String,
    detail: String,
    active: Boolean,
    error: Boolean = false,
    onClick: () -> Unit,
    extra: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(30.dp).clip(RoundedCornerShape(7.dp)).background(media.color), contentAlignment = Alignment.Center) {
            MediaGlyph(media)
        }
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Text(detail, fontSize = 13.sp, color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            extra?.invoke()
        }
        if (active) Text("Activo", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.tertiary)
        else Text("›", fontSize = 24.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Icono de línea blanco de cada medio, dibujado a mano (sin librería de iconos). */
@Composable
private fun MediaGlyph(media: Media) {
    Canvas(Modifier.size(18.dp)) {
        val w = size.width
        val h = size.height
        val white = Color.White
        val line = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (media) {
            Media.Router -> {
                // Router: caja con dos antenas y dos luces.
                drawRoundRect(white, Offset(w * 0.06f, h * 0.58f), Size(w * 0.88f, h * 0.32f), CornerRadius(2.dp.toPx()), style = line)
                drawLine(white, Offset(w * 0.28f, h * 0.58f), Offset(w * 0.2f, h * 0.18f), line.width, StrokeCap.Round)
                drawLine(white, Offset(w * 0.72f, h * 0.58f), Offset(w * 0.8f, h * 0.18f), line.width, StrokeCap.Round)
                drawCircle(white, 1.1.dp.toPx(), Offset(w * 0.3f, h * 0.74f))
                drawCircle(white, 1.1.dp.toPx(), Offset(w * 0.5f, h * 0.74f))
            }
            Media.Usb -> {
                // Símbolo USB: tronco con círculo a la izquierda, flecha a la derecha y dos ramas.
                drawLine(white, Offset(w * 0.2f, h * 0.5f), Offset(w * 0.86f, h * 0.5f), line.width, StrokeCap.Round)
                drawCircle(white, w * 0.1f, Offset(w * 0.12f, h * 0.5f))
                val arrow = Path().apply {
                    moveTo(w * 0.98f, h * 0.5f); lineTo(w * 0.8f, h * 0.38f); lineTo(w * 0.8f, h * 0.62f); close()
                }
                drawPath(arrow, white)
                drawLine(white, Offset(w * 0.4f, h * 0.5f), Offset(w * 0.56f, h * 0.22f), line.width, StrokeCap.Round)
                drawLine(white, Offset(w * 0.56f, h * 0.22f), Offset(w * 0.7f, h * 0.22f), line.width, StrokeCap.Round)
                drawCircle(white, w * 0.07f, Offset(w * 0.72f, h * 0.22f))
                drawLine(white, Offset(w * 0.52f, h * 0.5f), Offset(w * 0.64f, h * 0.76f), line.width, StrokeCap.Round)
                drawRect(white, Offset(w * 0.64f, h * 0.7f), Size(w * 0.14f, w * 0.14f))
            }
            Media.SystemHotspot -> {
                // Zona Wi-Fi: punto central con ondas a ambos lados.
                val c = Offset(w / 2, h / 2)
                drawCircle(white, 1.9.dp.toPx(), c)
                for (i in 1..2) {
                    val r = w * (0.2f + 0.14f * i)
                    drawArc(white, -40f, 80f, false, Offset(c.x - r, c.y - r), Size(r * 2, r * 2), style = line)
                    drawArc(white, 140f, 80f, false, Offset(c.x - r, c.y - r), Size(r * 2, r * 2), style = line)
                }
            }
            Media.PhoneNetwork -> {
                // Teléfono con ondas que salen de una esquina: la red la crea el propio teléfono.
                drawRoundRect(white, Offset(w * 0.1f, h * 0.3f), Size(w * 0.4f, h * 0.66f), CornerRadius(2.5.dp.toPx()), style = line)
                drawCircle(white, 0.9.dp.toPx(), Offset(w * 0.3f, h * 0.84f))
                val o = Offset(w * 0.56f, h * 0.44f)
                for (i in 1..2) {
                    val r = w * (0.16f * i + 0.06f)
                    drawArc(white, -85f, 80f, false, Offset(o.x - r, o.y - r), Size(r * 2, r * 2), style = line)
                }
            }
        }
    }
}

@Composable
internal fun ActionLine(title: String, detail: String?, onClick: () -> Unit, destructive: Boolean = false, error: Boolean = false) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(
            title, fontSize = 16.sp,
            color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        )
        if (detail != null) {
            Text(detail, fontSize = 13.sp, color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---------- Permisos ----------

@Composable
fun PermissionSection(allFilesGranted: Boolean, onGrant: () -> Unit, header: Boolean = true) {
    if (header) SectionHeader("Permisos")
    Grouped {
        Row(
            Modifier.fillMaxWidth().then(if (allFilesGranted) Modifier else Modifier.clickable(onClick = onGrant))
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text("Acceso a todos los archivos", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Text(
                    if (allFilesGranted) "Concedido" else "Para crear la carpeta ShareDrop y guardar en cualquier ruta",
                    fontSize = 13.sp,
                    color = if (allFilesGranted) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!allFilesGranted) TintedButton("Conceder", onGrant)
        }
    }
    Spacer(Modifier.height(24.dp))
}

private fun size(b: Long): String = when {
    b < 0 -> "?"
    b >= 1L shl 30 -> "%.2f GB".format(b / (1L shl 30).toDouble())
    b >= 1L shl 20 -> "%.1f MB".format(b / (1L shl 20).toDouble())
    b >= 1L shl 10 -> "%.0f KB".format(b / 1024.0)
    else -> "$b B"
}
