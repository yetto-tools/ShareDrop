#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use serde::Serialize;
use serde_json::json;
use sharedrop_core::engine::{FsStorage, Outgoing};
use sharedrop_core::{Device, Engine, Event, Store};
use std::collections::HashSet;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};
use tauri::menu::{Menu, MenuItem, PredefinedMenuItem};
use tauri::tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent};
use tauri::{AppHandle, Emitter, Manager, State, WindowEvent};

/// Comandos de línea de comandos / enlaces (`sharedrop-desktop --show`, `--quit`, `sharedrop://open`).
/// Una copia nueva con estos argumentos actúa sobre la que ya corre (ver `single_instance`).
#[derive(Debug, PartialEq, Eq)]
enum Command {
    /// Abrir la ventana o traerla al frente (lo predeterminado).
    Show,
    /// Cerrar ShareDrop por completo.
    Quit,
    /// No tocar la ventana (arranque con el sistema).
    Silent,
}

fn command_from(args: &[String]) -> Command {
    let has = |flag: &str| args.iter().any(|a| a == flag);
    if has("--quit") {
        Command::Quit
    } else if has("--show") || args.iter().any(|a| a.starts_with("sharedrop://")) {
        Command::Show
    } else if has("--minimized") {
        Command::Silent
    } else {
        Command::Show
    }
}

fn show_main(app: &AppHandle) {
    if let Some(w) = app.get_webview_window("main") {
        let _ = w.unminimize();
        let _ = w.show();
        let _ = w.set_focus();
    }
}

fn open_folder(path: &std::path::Path) {
    #[cfg(windows)]
    let cmd = "explorer";
    #[cfg(target_os = "macos")]
    let cmd = "open";
    #[cfg(all(unix, not(target_os = "macos")))]
    let cmd = "xdg-open";
    let _ = std::process::Command::new(cmd).arg(path).spawn();
}

/// Icono en la bandeja del sistema (área de notificación en Windows, indicador en Linux).
fn build_tray(app: &tauri::App) -> tauri::Result<()> {
    let open = MenuItem::with_id(app, "open", "Abrir ShareDrop", true, None::<&str>)?;
    let folder = MenuItem::with_id(app, "folder", "Abrir carpeta de recibidos", true, None::<&str>)?;
    let quit = MenuItem::with_id(app, "quit", "Salir", true, None::<&str>)?;
    let menu = Menu::with_items(app, &[&open, &folder, &PredefinedMenuItem::separator(app)?, &quit])?;

    let mut tray = TrayIconBuilder::new()
        .tooltip("ShareDrop")
        .menu(&menu)
        .show_menu_on_left_click(false)
        .on_menu_event(|app, event| match event.id.as_ref() {
            "open" => show_main(app),
            "folder" => open_folder(&app.state::<AppState>().storage.dir()),
            "quit" => app.exit(0),
            _ => {}
        })
        .on_tray_icon_event(|tray, event| {
            if let TrayIconEvent::Click { button: MouseButton::Left, button_state: MouseButtonState::Up, .. } = event {
                show_main(tray.app_handle());
            }
        });
    if let Some(icon) = app.default_window_icon() {
        tray = tray.icon(icon.clone());
    }
    tray.build(app)?;
    Ok(())
}

struct AppState {
    engine: Arc<Engine>,
    storage: Arc<FsStorage>,
    devices: Arc<Mutex<Vec<Device>>>,
    /// Rutas de archivos que ShareDrop recibió: lo único que la interfaz puede pedir abrir.
    received: Arc<Mutex<HashSet<PathBuf>>>,
}

#[derive(Serialize)]
struct Info {
    name: String,
    /// Sistema de esta PC ("windows", "linux", "macos"): para saber si el otro equipo es del mismo tipo.
    platform: &'static str,
    /// Versión de la app, para la barra de estado.
    version: &'static str,
    receive_dir: String,
    is_default_dir: bool,
    devices: Vec<Device>,
}

fn info_of(state: &AppState) -> Info {
    Info {
        name: state.engine.store.device_name(),
        platform: std::env::consts::OS,
        version: env!("CARGO_PKG_VERSION"),
        receive_dir: state.storage.dir().display().to_string(),
        is_default_dir: state.engine.store.receive_dir().is_none(),
        devices: state.devices.lock().unwrap().clone(),
    }
}

#[tauri::command]
fn info(state: State<AppState>) -> Info {
    info_of(&state)
}

/// Cambia la carpeta de recepción (`None` restablece la predeterminada) y devuelve el estado actualizado.
#[tauri::command]
fn set_receive_dir(state: State<AppState>, path: Option<String>) -> Result<Info, String> {
    let dir = match &path {
        Some(p) => PathBuf::from(p),
        None => Store::default_receive_dir(),
    };
    std::fs::create_dir_all(&dir).map_err(|e| format!("No se pudo usar la carpeta: {e}"))?;
    state
        .engine
        .store
        .set_receive_dir(path.as_ref().map(|_| dir.as_path()))
        .map_err(|e| e.to_string())?;
    state.storage.set_dir(dir);
    Ok(info_of(&state))
}

#[tauri::command]
fn send_files(state: State<AppState>, device_id: String, paths: Vec<String>) -> Result<(), String> {
    let device = state
        .devices
        .lock()
        .unwrap()
        .iter()
        .find(|d| d.id == device_id)
        .cloned()
        .ok_or("El dispositivo ya no está disponible")?;
    let files = paths
        .iter()
        .map(|p| Outgoing::from_path(std::path::Path::new(p)).map_err(|e| format!("{p}: {e}")))
        .collect::<Result<Vec<_>, _>>()?;
    state.engine.send(&device, files);
    Ok(())
}

/// Tipos de archivo que ejecutan código: nunca se abren desde ShareDrop, solo se muestran en su carpeta.
fn is_risky(path: &Path) -> bool {
    const RISKY: &[&str] = &[
        "exe", "com", "scr", "bat", "cmd", "ps1", "psm1", "vbs", "vbe", "js", "jse", "wsf", "wsh", "msi", "msp",
        "lnk", "url", "hta", "cpl", "reg", "jar", "dll", "sh", "appimage", "desktop", "run", "apk", "app", "pkg", "dmg",
    ];
    path.extension()
        .and_then(|e| e.to_str())
        .is_some_and(|e| RISKY.contains(&e.to_ascii_lowercase().as_str()))
}

/// Muestra el archivo seleccionado en el explorador de archivos del sistema.
fn reveal(path: &Path) {
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        // raw_arg: el explorador exige `/select,"ruta"` sin el escapado habitual de argumentos.
        let _ = std::process::Command::new("explorer").raw_arg(format!("/select,\"{}\"", path.display())).spawn();
    }
    #[cfg(target_os = "macos")]
    let _ = std::process::Command::new("open").arg("-R").arg(path).spawn();
    #[cfg(all(unix, not(target_os = "macos")))]
    open_folder(path.parent().unwrap_or(path));
}

/// Abre el archivo con la aplicación predeterminada del sistema.
fn open_with_default(path: &Path) {
    #[cfg(windows)]
    let cmd = "explorer";
    #[cfg(target_os = "macos")]
    let cmd = "open";
    #[cfg(all(unix, not(target_os = "macos")))]
    let cmd = "xdg-open";
    let _ = std::process::Command::new(cmd).arg(path).spawn();
}

/// Abre un archivo recibido (`reveal = false`) o lo muestra en su carpeta (`reveal = true`).
/// Devuelve "opened" o "revealed" (este último también cuando el tipo de archivo es peligroso).
#[tauri::command]
fn open_received(state: State<AppState>, path: String, reveal: bool) -> Result<&'static str, String> {
    let p = PathBuf::from(&path);
    // Solo se abre lo que ShareDrop recibió: en esta sesión o anotado en el historial de sesiones anteriores.
    let known = state.received.lock().unwrap().contains(&p)
        || state.engine.history().iter().any(|h| h.path.as_deref() == Some(path.as_str()));
    if !known {
        return Err("Ese archivo no fue recibido por ShareDrop".into());
    }
    if !p.exists() {
        return Err("El archivo ya no está en esa ubicación (¿lo moviste o lo borraste?)".into());
    }
    if reveal || is_risky(&p) {
        self::reveal(&p);
        Ok("revealed")
    } else {
        open_with_default(&p);
        Ok("opened")
    }
}

/// Interfaces de red de esta PC, para explicar en la pantalla de Conexión por dónde puede verse el teléfono.
#[tauri::command]
fn network_status() -> Vec<sharedrop_core::LocalLink> {
    sharedrop_core::local_links()
}

/// Historial de transferencias terminadas (hasta 20 recibidas y 20 enviadas), la más reciente primero.
#[tauri::command]
fn history(state: State<AppState>) -> Vec<sharedrop_core::HistoryEntry> {
    state.engine.history()
}

/// Estado del enlace USB por adb (¿hay adb?, ¿hay teléfono?, ¿está enlazado?).
#[tauri::command]
fn usb_status(state: State<AppState>) -> sharedrop_core::adb::UsbStatus {
    state.engine.usb_status()
}

/// Abre un ajuste del sistema. Solo acepta nombres conocidos, nunca una orden arbitraria.
#[tauri::command]
fn open_settings(kind: String) -> Result<(), String> {
    #[cfg(windows)]
    {
        let spawn = |cmd: &str, arg: &str| std::process::Command::new(cmd).arg(arg).spawn().map(|_| ());
        match kind.as_str() {
            "wifi" => spawn("explorer", "ms-settings:network-wifi"),
            "hotspot" => spawn("explorer", "ms-settings:network-mobilehotspot"),
            "network" => spawn("explorer", "ms-settings:network"),
            "bluetooth" => spawn("explorer", "ms-settings:bluetooth"),
            "firewall" => spawn("control.exe", "firewall.cpl"),
            other => return Err(format!("Ajuste desconocido: {other}")),
        }
        .map_err(|e| e.to_string())
    }
    #[cfg(target_os = "linux")]
    {
        let panel = match kind.as_str() {
            "wifi" => "wifi",
            "hotspot" | "network" | "firewall" => "network",
            "bluetooth" => "bluetooth",
            other => return Err(format!("Ajuste desconocido: {other}")),
        };
        // Cada escritorio trae su propio panel: se prueba el de GNOME y, si no existe, el de NetworkManager.
        std::process::Command::new("gnome-control-center")
            .arg(panel)
            .spawn()
            .or_else(|_| std::process::Command::new("nm-connection-editor").spawn())
            .map(|_| ())
            .map_err(|_| "No se encontró un panel de ajustes de red".to_string())
    }
    #[cfg(not(any(windows, target_os = "linux")))]
    {
        let _ = kind;
        std::process::Command::new("open")
            .arg("x-apple.systempreferences:com.apple.Network-Settings.extension")
            .spawn()
            .map(|_| ())
            .map_err(|e| e.to_string())
    }
}

#[tauri::command]
fn answer_code(state: State<AppState>, code: Option<String>) {
    state.engine.answer_code(code);
}

fn main() {
    tauri::Builder::default()
        // Debe ir primero: si ya hay una copia corriendo, solo trae su ventana al frente
        // (una segunda copia no podría abrir los puertos 47800/47801).
        .plugin(tauri_plugin_single_instance::init(|app, args, _cwd| match command_from(&args) {
            Command::Show => show_main(app),
            Command::Quit => app.exit(0),
            Command::Silent => {}
        }))
        .plugin(tauri_plugin_deep_link::init())
        .plugin(tauri_plugin_autostart::init(
            tauri_plugin_autostart::MacosLauncher::LaunchAgent,
            Some(vec!["--minimized"]),
        ))
        .plugin(tauri_plugin_dialog::init())
        .on_window_event(|window, event| {
            // Cerrar la ventana la oculta: ShareDrop sigue recibiendo desde la bandeja.
            if let WindowEvent::CloseRequested { api, .. } = event {
                let _ = window.hide();
                api.prevent_close();
            }
        })
        .setup(|app| {
            let handle = app.handle().clone();
            let store = Arc::new(Store::open(Store::default_dir())?);
            let receive_dir = store.receive_dir().unwrap_or_else(Store::default_receive_dir);
            let storage = Arc::new(FsStorage::new(receive_dir));
            let devices: Arc<Mutex<Vec<Device>>> = Arc::default();

            let received: Arc<Mutex<HashSet<PathBuf>>> = Arc::default();
            let shared = devices.clone();
            let shared_received = received.clone();
            let engine = Engine::new(
                store,
                storage.clone(),
                std::env::consts::OS,
                move |event| {
                    let _ = match event {
                        Event::Devices(d) => {
                            *shared.lock().unwrap() = d.clone();
                            handle.emit("devices", d)
                        }
                        Event::Transfer(t) => {
                            if let Some(p) = &t.path {
                                shared_received.lock().unwrap().insert(PathBuf::from(p));
                            }
                            handle.emit("transfer", t)
                        }
                        Event::ShowCode { peer, code, qr } => {
                            let svg = qr.as_deref().and_then(sharedrop_core::qr::svg);
                            handle.emit("show-code", json!({"peer": peer, "code": code, "qr": svg}))
                        }
                        Event::HideCode => handle.emit("hide-code", ()),
                        Event::AskCode { peer, qr } => handle.emit("ask-code", json!({"peer": peer, "qr": qr})),
                    };
                },
            );
            engine.start()?;
            app.manage(AppState { engine, storage, devices, received });
            build_tray(app)?;
            // En desarrollo y en Linux no hay instalador que registre `sharedrop://`: se registra aquí.
            // En Windows instalado lo registra el instalador.
            #[cfg(any(all(debug_assertions, windows), target_os = "linux"))]
            {
                use tauri_plugin_deep_link::DeepLinkExt;
                let _ = app.deep_link().register_all();
            }

            let args: Vec<String> = std::env::args().collect();
            match command_from(&args) {
                Command::Quit => app.handle().exit(0), // `--quit` sin nada corriendo: no hay nada que cerrar
                // Al iniciar con el sistema arranca oculto, solo en la bandeja.
                Command::Silent => {
                    if let Some(w) = app.get_webview_window("main") {
                        let _ = w.hide();
                    }
                }
                Command::Show => {}
            }
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![info, send_files, answer_code, set_receive_dir, open_received, network_status, usb_status, history, open_settings])
        .run(tauri::generate_context!())
        .expect("error al iniciar ShareDrop");
}
