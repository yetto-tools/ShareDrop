package com.erashon.sharedrop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.erashon.sharedrop.Engine
import com.erashon.sharedrop.discovery.Device
import com.erashon.sharedrop.discovery.LinkState

/**
 * Pantallas de la app. Inicio es la principal; las opciones secundarias se abren desde el menú "•••"
 * de la barra superior (patrón More / pull-down de iOS) y tienen su propia página con botón de atrás.
 */
enum class Screen(val title: String) {
    Home("ShareDrop"),
    History("Historial"),
    Connection("Conexión"),
    Folder("Archivos recibidos"),
    Settings("Ajustes"),
    Permissions("Permisos"),
    Appearance("Apariencia");

    /** Pantalla a la que lleva "‹". Inicio no tiene. Ajustes agrupa las páginas de configuración, como en iOS. */
    val parent: Screen? get() = when (this) {
        Home -> null
        History, Settings -> Home
        else -> Settings
    }

    /** Nivel de navegación: define si un cambio entra (más profundo) o vuelve. */
    val depth: Int get() = when (this) {
        Home -> 0
        History, Settings -> 1
        else -> 2
    }
}

// ---------- Cristal: solo en los controles flotantes de la barra superior ----------

/** Botón circular de cristal (44 dp, el mínimo táctil). Monocromo: el color se reserva para acciones primarias. */
@Composable
private fun GlassButton(description: String, onClick: () -> Unit, content: @Composable () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier
            .size(44.dp)
            .shadow(6.dp, CircleShape, clip = false, ambientColor = Color.Black.copy(alpha = 0.25f), spotColor = Color.Black.copy(alpha = 0.25f))
            .clip(CircleShape)
            .background(scheme.surface.copy(alpha = 0.82f))
            .border(0.8.dp, scheme.onSurface.copy(alpha = 0.14f), CircleShape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** Barra superior: "‹" (si hay pantalla anterior) a la izquierda y las acciones a la derecha. */
@Composable
fun NavBar(onBack: (() -> Unit)?, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) GlassButton("Atrás", onBack) { ChevronBack() }
        Box(Modifier.weight(1f))
        trailing()
    }
}

/** Título grande bajo la barra, como en iOS. */
@Composable
fun PageHeader(screen: Screen, subtitle: String? = null, onBack: (() -> Unit)?, trailing: @Composable () -> Unit = {}) {
    NavBar(onBack, trailing)
    LargeTitle(screen.title, subtitle ?: "")
}

// ---------- Menú "•••" ----------

/** Menú desplegable con las opciones secundarias. Tres elementos: el mínimo que justifica un menú. */
@Composable
fun MoreMenu(onNavigate: (Screen) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    Box {
        GlassButton("Más opciones", { open = true }) { Dots() }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            offset = DpOffset(0.dp, 8.dp),
            shape = RoundedCornerShape(22.dp),
            containerColor = scheme.surface.copy(alpha = 0.985f), // casi opaco: sin desenfoque real, el texto de detrás se transparentaría
            tonalElevation = 0.dp,
            shadowElevation = 16.dp,
            border = androidx.compose.foundation.BorderStroke(0.8.dp, scheme.onSurface.copy(alpha = 0.12f)),
        ) {
            MenuRow("Historial", MenuIcon.Clock) { open = false; onNavigate(Screen.History) }
            MenuRow("Ajustes", MenuIcon.Sliders) { open = false; onNavigate(Screen.Settings) }
        }
    }
}

private enum class MenuIcon { Clock, Sliders }

@Composable
private fun MenuRow(label: String, icon: MenuIcon, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label, fontSize = 17.sp, modifier = Modifier.padding(end = 24.dp)) },
        trailingIcon = { Symbol(icon) },
        onClick = onClick,
        modifier = Modifier.height(48.dp),
    )
}

// ---------- Símbolos de línea, dibujados a mano (sin librería de iconos) ----------

private fun DrawScope.lineStroke(width: Float) = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round)

@Composable
private fun Dots() {
    val tint = MaterialTheme.colorScheme.onSurface
    Canvas(Modifier.size(22.dp)) {
        val r = 2.1.dp.toPx()
        for (i in -1..1) drawCircle(tint, r, Offset(size.width / 2 + i * 6.dp.toPx(), size.height / 2))
    }
}

@Composable
private fun ChevronBack() {
    val tint = MaterialTheme.colorScheme.onSurface
    Canvas(Modifier.size(22.dp)) {
        val p = Path().apply {
            moveTo(size.width * 0.62f, size.height * 0.16f)
            lineTo(size.width * 0.28f, size.height * 0.5f)
            lineTo(size.width * 0.62f, size.height * 0.84f)
        }
        drawPath(p, tint, style = lineStroke(2.4.dp.toPx()))
    }
}

@Composable
private fun Symbol(icon: MenuIcon) {
    val tint = MaterialTheme.colorScheme.onSurface
    Canvas(Modifier.size(22.dp)) {
        val s = lineStroke(1.7.dp.toPx())
        val w = size.width
        val h = size.height
        when (icon) {
            MenuIcon.Clock -> {
                // Reloj: círculo con las dos manecillas.
                drawCircle(tint, w * 0.42f, Offset(w / 2, h / 2), style = s)
                drawLine(tint, Offset(w / 2, h * 0.28f), Offset(w / 2, h / 2), 1.7.dp.toPx(), StrokeCap.Round)
                drawLine(tint, Offset(w / 2, h / 2), Offset(w * 0.66f, h * 0.6f), 1.7.dp.toPx(), StrokeCap.Round)
            }
            MenuIcon.Sliders -> {
                // Dos controles deslizantes, como el símbolo de ajustes.
                drawLine(tint, Offset(w * 0.10f, h * 0.32f), Offset(w * 0.90f, h * 0.32f), 1.7.dp.toPx(), StrokeCap.Round)
                drawLine(tint, Offset(w * 0.10f, h * 0.68f), Offset(w * 0.90f, h * 0.68f), 1.7.dp.toPx(), StrokeCap.Round)
                drawCircle(tint, w * 0.11f, Offset(w * 0.66f, h * 0.32f), style = s)
                drawCircle(tint, w * 0.11f, Offset(w * 0.34f, h * 0.68f), style = s)
            }
        }
    }
}

// ---------- Inicio: solo lo de uso diario ----------

@Composable
fun HomeScreen(
    engine: Engine,
    devices: List<Device>,
    link: LinkState,
    onPick: (Device) -> Unit,
    onNavigate: (Screen) -> Unit,
) {
    PageHeader(Screen.Home, engine.store.deviceName, onBack = null) { MoreMenu(onNavigate) }
    SectionHeader("Dispositivos cercanos")
    DeviceList(devices, engine, onPick)

    // Sin nadie a la vista, un aviso breve lleva directo a las opciones de conexión.
    if (devices.isEmpty()) {
        Spacer(Modifier.height(10.dp))
        Grouped {
            Row(
                Modifier.fillMaxWidth().clickable { onNavigate(Screen.Connection) }.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (link.anyLink) "No veo otros equipos" else "Sin conexión",
                        fontSize = 16.sp, fontWeight = FontWeight.Medium,
                        color = if (link.anyLink) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                    )
                    Text("Toca para ver cómo conectarte", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                }
                Text("›", fontSize = 24.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    TransferSection(engine)
}

// ---------- Páginas de opciones ----------

@Composable
fun ConnectionPage(
    link: LinkState,
    hotspot: com.erashon.sharedrop.discovery.HotspotController.State,
    hasPeers: Boolean,
    onBack: () -> Unit,
    onWifi: () -> Unit,
    onTether: () -> Unit,
    onStartHotspot: () -> Unit,
    onStopHotspot: () -> Unit,
    usbShared: Boolean = false,
) {
    PageHeader(Screen.Connection, onBack = onBack)
    ConnectionSection(link, hotspot, hasPeers, onWifi, onTether, onStartHotspot, onStopHotspot, header = false, usbShared = usbShared)
}

@Composable
fun FolderPage(engine: Engine, onBack: () -> Unit, onChoose: () -> Unit) {
    PageHeader(Screen.Folder, onBack = onBack)
    FolderSection(engine, onChoose, header = false)
}

/** Ajustes: lista agrupada que lleva a cada página de configuración, como Ajustes de iOS. */
@Composable
fun SettingsPage(onBack: () -> Unit, onNavigate: (Screen) -> Unit) {
    PageHeader(Screen.Settings, onBack = onBack)
    Grouped {
        val rows = listOf(Screen.Connection, Screen.Folder, Screen.Appearance, Screen.Permissions)
        rows.forEachIndexed { i, target ->
            if (i > 0) RowDivider(inset = false)
            Row(
                Modifier.fillMaxWidth().clickable { onNavigate(target) }.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(target.title, fontSize = 16.sp, modifier = Modifier.weight(1f))
                Text("›", fontSize = 24.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun AppearancePage(current: String, onBack: () -> Unit, onSelect: (String) -> Unit) {
    PageHeader(Screen.Appearance, onBack = onBack)
    val modes = listOf("system" to "Sistema", "light" to "Claro", "dark" to "Oscuro")
    SegmentedControl(modes.map { it.second }, modes.indexOfFirst { it.first == current }.coerceAtLeast(0)) { onSelect(modes[it].first) }
    Spacer(Modifier.height(10.dp))
    Text(
        "Sistema sigue el modo claro u oscuro del teléfono.",
        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp),
    )
}

@Composable
fun PermissionsPage(allFiles: Boolean, onBack: () -> Unit, onGrant: () -> Unit) {
    PageHeader(Screen.Permissions, onBack = onBack)
    PermissionSection(allFiles, onGrant, header = false)
}
