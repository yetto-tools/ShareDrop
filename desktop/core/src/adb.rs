//! Enlace USB por adb: con solo el cable y la depuración USB, sin red ni tethering.
//!
//! La PC abre dos túneles con el teléfono:
//! - `adb forward tcp:47811 tcp:47800`: lo que la PC envíe a 127.0.0.1:47811 llega al teléfono.
//! - `adb reverse tcp:47810 tcp:47800`: lo que el teléfono envíe a 127.0.0.1:47810 llega a la PC.
//!
//! Por un túnel no pasa el descubrimiento por broadcast, así que los equipos se encuentran con un
//! sondeo (ver "Sondeo" en PROTOCOL.md).

use serde::Serialize;
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};

/// Puerto de la PC que lleva al teléfono (`adb forward`).
pub const FORWARD_PORT: u16 = 47811;
/// Puerto del teléfono que lleva a la PC (`adb reverse`).
pub const REVERSE_PORT: u16 = 47810;

/// Estado del enlace USB por adb, para explicarlo en la pantalla de Conexión.
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize)]
pub struct UsbStatus {
    /// ¿Hay un `adb` utilizable en esta PC?
    pub adb_found: bool,
    /// Teléfonos con la depuración autorizada.
    pub devices: usize,
    /// Teléfonos conectados pero sin autorizar (falta aceptar el aviso en la pantalla del teléfono).
    pub unauthorized: usize,
    /// El túnel está abierto y el teléfono respondió al sondeo.
    pub linked: bool,
}

/// Busca `adb`: variable ADB, el PATH y las carpetas habituales del SDK de Android.
pub fn find_adb() -> Option<PathBuf> {
    let exe = if cfg!(windows) { "adb.exe" } else { "adb" };
    let mut candidates: Vec<PathBuf> = Vec::new();
    if let Some(p) = std::env::var_os("ADB") {
        candidates.push(PathBuf::from(p));
    }
    if let Some(path) = std::env::var_os("PATH") {
        candidates.extend(std::env::split_paths(&path).map(|d| d.join(exe)));
    }
    for var in ["ANDROID_HOME", "ANDROID_SDK_ROOT"] {
        if let Some(root) = std::env::var_os(var) {
            candidates.push(PathBuf::from(root).join("platform-tools").join(exe));
        }
    }
    if let Some(local) = dirs::data_local_dir() {
        candidates.push(local.join("Android").join("Sdk").join("platform-tools").join(exe));
    }
    candidates.into_iter().find(|p| p.is_file())
}

fn run(adb: &Path, args: &[&str]) -> Option<String> {
    let mut cmd = Command::new(adb);
    cmd.args(args).stdin(Stdio::null()).stderr(Stdio::null());
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        cmd.creation_flags(0x0800_0000); // CREATE_NO_WINDOW: sin parpadeo de consola
    }
    let out = cmd.output().ok()?;
    out.status.success().then(|| String::from_utf8_lossy(&out.stdout).into_owned())
}

/// Teléfonos conectados: (números de serie autorizados, cantidad sin autorizar).
pub fn list_devices(adb: &Path) -> (Vec<String>, usize) {
    let Some(text) = run(adb, &["devices"]) else { return (vec![], 0) };
    parse_devices(&text)
}

/// Interpreta la salida de `adb devices`.
pub fn parse_devices(text: &str) -> (Vec<String>, usize) {
    let mut ok = Vec::new();
    let mut unauthorized = 0;
    for line in text.lines().skip(1) {
        let mut parts = line.split_whitespace();
        match (parts.next(), parts.next()) {
            (Some(serial), Some("device")) => ok.push(serial.to_string()),
            (Some(_), Some("unauthorized")) => unauthorized += 1,
            _ => {}
        }
    }
    (ok, unauthorized)
}

/// Abre los dos túneles con un teléfono. Es idempotente: repetirlo no hace daño.
pub fn setup(adb: &Path, serial: &str) -> bool {
    let forward = run(adb, &["-s", serial, "forward", &format!("tcp:{FORWARD_PORT}"), &format!("tcp:{}", crate::TCP_PORT)]);
    let reverse = run(adb, &["-s", serial, "reverse", &format!("tcp:{REVERSE_PORT}"), &format!("tcp:{}", crate::TCP_PORT)]);
    forward.is_some() && reverse.is_some()
}

#[cfg(test)]
mod tests {
    use super::parse_devices;

    #[test]
    fn parses_adb_devices_output() {
        let out = "List of devices attached\nGYRCGQUOHEAECE69\tdevice\nABC123\tunauthorized\nXYZ\toffline\n\n";
        let (ok, unauthorized) = parse_devices(out);
        assert_eq!(ok, vec!["GYRCGQUOHEAECE69".to_string()]);
        assert_eq!(unauthorized, 1);
    }

    #[test]
    fn empty_list() {
        assert_eq!(parse_devices("List of devices attached\n\n"), (vec![], 0));
        assert_eq!(parse_devices(""), (vec![], 0));
    }
}
