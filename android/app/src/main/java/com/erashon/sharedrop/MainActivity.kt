package com.erashon.sharedrop

import android.Manifest
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.erashon.sharedrop.discovery.Device
import com.erashon.sharedrop.discovery.mediumOf
import com.erashon.sharedrop.storage.Storage
import com.erashon.sharedrop.ui.ConnectionPage
import com.erashon.sharedrop.ui.FolderPage
import com.erashon.sharedrop.ui.HistoryPage
import com.erashon.sharedrop.ui.HomeScreen
import com.erashon.sharedrop.ui.PairingDialogs
import com.erashon.sharedrop.ui.PermissionsPage
import com.erashon.sharedrop.ui.Screen
import com.erashon.sharedrop.ui.AppearancePage
import com.erashon.sharedrop.ui.SettingsPage
import com.erashon.sharedrop.ui.ShareDropTheme

class MainActivity : ComponentActivity() {
    // Se refresca en onResume: el usuario concede el permiso en Ajustes y vuelve a la app.
    private var allFiles by mutableStateOf(false)

    override fun onResume() {
        super.onResume()
        allFiles = Storage.hasAllFilesAccess()
        Storage.ensureDefaultFolder()
    }

    private fun tryStart(i: Intent): Boolean = try { startActivity(i); true } catch (e: Exception) { false }

    private fun openWifiPanel() {
        if (!tryStart(Intent(Settings.Panel.ACTION_WIFI))) tryStart(Intent(Settings.ACTION_WIFI_SETTINGS))
    }

    /** Android no deja que una app active el USB tethering sola: se lleva al usuario a ese ajuste. */
    private fun openTetherSettings() {
        val tether = Intent().setClassName("com.android.settings", "com.android.settings.TetherSettings")
        if (!tryStart(tether)) tryStart(Intent(Settings.ACTION_WIRELESS_SETTINGS))
    }

    private fun requestAllFiles() {
        val pkg = Uri.parse("package:$packageName")
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, pkg))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val engine = (application as ShareDropApp).engine
        startForegroundService(Intent(this, TransferService::class.java))

        setContent {
            val themeMode by engine.store.theme.collectAsStateWithLifecycle()
            ShareDropTheme(themeMode) {
                val notif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
                LaunchedEffect(Unit) {
                    if (Build.VERSION.SDK_INT >= 33) notif.launch(Manifest.permission.POST_NOTIFICATIONS)
                }

                var target by remember { mutableStateOf<Device?>(null) }
                val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
                    val d = target
                    if (d != null && uris.isNotEmpty()) engine.send(d, uris)
                }
                val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
                    if (uri != null) {
                        // Permiso persistente: sobrevive a reinicios del teléfono.
                        contentResolver.takePersistableUriPermission(
                            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                        )
                        engine.store.receiveTree?.let { old ->
                            runCatching {
                                contentResolver.releasePersistableUriPermission(
                                    Uri.parse(old), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                                )
                            }
                        }
                        engine.setReceiveTree(uri)
                    }
                }
                val devices by engine.devices.collectAsStateWithLifecycle()
                val link by engine.link.collectAsStateWithLifecycle()
                val hotspotApp = (application as ShareDropApp).hotspot
                val hotspot by hotspotApp.state.collectAsStateWithLifecycle()
                // Crear la red del teléfono exige permiso: se pide al usuario y, si lo concede, se crea.
                val hotspotPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                    if (granted) hotspotApp.start()
                }
                val needed = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION

                // Navegación tipo Ajustes de iOS: Inicio → Ajustes → subpáginas.
                var screen by rememberSaveable { mutableStateOf(Screen.Home) }
                BackHandler(enabled = screen != Screen.Home) { screen = screen.parent ?: Screen.Home }
                val goBack = { screen = screen.parent ?: Screen.Home }

                val startHotspot = {
                    if (checkSelfPermission(needed) == android.content.pm.PackageManager.PERMISSION_GRANTED) hotspotApp.start()
                    else hotspotPermission.launch(needed)
                }

                // Deslizar de izquierda a derecha abre el historial; de derecha a izquierda vuelve.
                val swipePx = with(LocalDensity.current) { 80.dp.toPx() }
                Column(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .pointerInput(screen) {
                            var total = 0f
                            detectHorizontalDragGestures(
                                onDragStart = { total = 0f },
                                onDragCancel = { total = 0f },
                                onDragEnd = {
                                    if (total > swipePx && screen == Screen.Home) screen = Screen.History
                                    else if (total < -swipePx && screen != Screen.Home) screen = screen.parent ?: Screen.Home
                                },
                                onHorizontalDrag = { _, dx -> total += dx },
                            )
                        }
                        .statusBarsPadding()
                        .navigationBarsPadding()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                ) {
                    AnimatedContent(
                        targetState = screen,
                        transitionSpec = {
                            // El historial entra por la izquierda (como el gesto); las demás páginas, por la derecha.
                            val forward = targetState.depth > initialState.depth
                            val d = (if ((if (forward) targetState else initialState) == Screen.History) -1 else 1) * (if (forward) 1 else -1)
                            val spec = tween<androidx.compose.ui.unit.IntOffset>(250)
                            (slideInHorizontally(spec) { d * it / 3 } + fadeIn(tween(250)))
                                .togetherWith(slideOutHorizontally(spec) { -d * it / 3 } + fadeOut(tween(250)))
                        },
                        label = "pantalla",
                        modifier = Modifier.fillMaxWidth(),
                    ) { shown ->
                    // Cada pantalla emite varios elementos pensados para una Column: sin ella se apilarían.
                    Column(Modifier.fillMaxWidth()) {
                    when (shown) {
                        Screen.Home -> HomeScreen(
                            engine = engine, devices = devices, link = link,
                            onPick = { d ->
                                target = d
                                filePicker.launch(arrayOf("*/*"))
                            },
                            onNavigate = { screen = it },
                        )
                        Screen.History -> HistoryPage(engine, onBack = goBack)
                        Screen.Connection -> ConnectionPage(
                            link = link, hotspot = hotspot, hasPeers = devices.isNotEmpty(), onBack = goBack,
                            onWifi = { openWifiPanel() }, onTether = { openTetherSettings() },
                            onStartHotspot = startHotspot, onStopHotspot = { hotspotApp.stop() },
                            usbShared = devices.any { mediumOf(it)?.key == "usb" },
                        )
                        Screen.Folder -> FolderPage(engine, onBack = goBack) { folderPicker.launch(null) }
                        Screen.Permissions -> PermissionsPage(allFiles, onBack = goBack) { requestAllFiles() }
                        Screen.Settings -> SettingsPage(onBack = goBack, onNavigate = { screen = it })
                        Screen.Appearance -> AppearancePage(themeMode, onBack = goBack) { engine.store.setTheme(it) }
                    }
                    }
                    }
                }
                PairingDialogs(engine)
            }
        }
    }
}
