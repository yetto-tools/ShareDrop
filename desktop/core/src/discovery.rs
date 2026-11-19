//! Descubrimiento por broadcast UDP (Wi-Fi, hotspot, USB tethering, etc.).

use crate::store::Store;
use crate::{TCP_PORT, UDP_PORT};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use socket2::{Domain, Protocol, Socket, Type};
use std::collections::HashMap;
use std::net::{IpAddr, Ipv4Addr, SocketAddr, UdpSocket};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::{Duration, Instant};

/// Datos opcionales que un equipo anuncia sobre sí mismo (el teléfono manda todos).
#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize)]
pub struct DeviceInfo {
    /// Código de modelo, p. ej. "24094RAD4G".
    pub model: Option<String>,
    pub maker: Option<String>,
    /// Sistema operativo, p. ej. "Android 16".
    pub os: Option<String>,
    /// Batería en porcentaje.
    pub battery: Option<u8>,
    pub charging: Option<bool>,
    /// Almacenamiento libre y total, en bytes.
    pub free: Option<u64>,
    pub total: Option<u64>,
    /// Medio por el que el teléfono dice estar conectado: "usb", "wifi" o "hotspot".
    pub link: Option<String>,
}

#[derive(Clone, Debug, Serialize)]
pub struct Device {
    pub id: String,
    pub name: String,
    pub platform: String,
    pub address: String,
    pub port: u16,
    pub paired: bool,
    pub info: DeviceInfo,
    /// Cómo se llegó a este equipo cuando no fue por la red: "usb-adb" (túnel por cable). `None` = red.
    pub link: Option<String>,
}

struct Seen {
    device: Device,
    last: Instant,
}

pub struct Discovery {
    store: Arc<Store>,
    platform: &'static str,
    seen: Arc<Mutex<HashMap<String, Seen>>>,
    running: Arc<AtomicBool>,
    on_change: Arc<dyn Fn(Vec<Device>) + Send + Sync>,
}

impl Discovery {
    pub fn new(
        store: Arc<Store>,
        platform: &'static str,
        on_change: Arc<dyn Fn(Vec<Device>) + Send + Sync>,
    ) -> Self {
        Discovery {
            store,
            platform,
            seen: Arc::default(),
            running: Arc::new(AtomicBool::new(false)),
            on_change,
        }
    }

    pub fn start(&self) -> std::io::Result<()> {
        if self.running.swap(true, Ordering::SeqCst) {
            return Ok(());
        }
        let sock = Socket::new(Domain::IPV4, Type::DGRAM, Some(Protocol::UDP))?;
        sock.set_reuse_address(true)?;
        #[cfg(all(unix, not(target_os = "solaris"), not(target_os = "illumos")))]
        sock.set_reuse_port(true)?;
        sock.set_broadcast(true)?;
        sock.bind(&SocketAddr::from((Ipv4Addr::UNSPECIFIED, UDP_PORT)).into())?;
        let sock: UdpSocket = sock.into();
        sock.set_read_timeout(Some(Duration::from_millis(500)))?;

        let tx = sock.try_clone()?;
        let msg = json!({
            "app": "sharedrop", "v": 1, "id": self.store.device_id(),
            "name": self.store.device_name(), "platform": self.platform, "port": TCP_PORT,
        })
        .to_string()
        .into_bytes();

        let running = self.running.clone();
        let (seen, on_change, store) = (self.seen.clone(), self.on_change.clone(), self.store.clone());
        {
            let running = running.clone();
            let (seen, on_change) = (seen.clone(), on_change.clone());
            thread::spawn(move || {
                while running.load(Ordering::SeqCst) {
                    for addr in broadcast_addresses() {
                        let _ = tx.send_to(&msg, SocketAddr::new(IpAddr::V4(addr), UDP_PORT));
                    }
                    if prune(&seen) {
                        on_change(snapshot(&seen));
                    }
                    thread::sleep(Duration::from_secs(2));
                }
            });
        }
        thread::spawn(move || {
            let mut buf = [0u8; 2048];
            while running.load(Ordering::SeqCst) {
                let Ok((n, from)) = sock.recv_from(&mut buf) else { continue };
                let Ok(j) = serde_json::from_slice::<Value>(&buf[..n]) else { continue };
                if j["app"] != "sharedrop" {
                    continue;
                }
                let (Some(id), Some(name), Some(port)) = (j["id"].as_str(), j["name"].as_str(), j["port"].as_u64()) else {
                    continue;
                };
                if id == store.device_id() {
                    continue;
                }
                let device = Device {
                    id: id.to_string(),
                    name: name.to_string(),
                    platform: j["platform"].as_str().unwrap_or("?").to_string(),
                    address: from.ip().to_string(),
                    port: port as u16,
                    paired: store.is_paired(id),
                    // Opcional: un equipo antiguo no lo manda, y un valor mal formado se ignora.
                    info: serde_json::from_value(j["info"].clone()).unwrap_or_default(),
                    link: None,
                };
                if upsert(&seen, device) {
                    on_change(snapshot(&seen));
                }
            }
        });
        Ok(())
    }

    pub fn stop(&self) {
        self.running.store(false, Ordering::SeqCst);
    }

    /// Añade un equipo encontrado por otro camino (el túnel USB por adb).
    pub fn inject(&self, device: Device) {
        if upsert(&self.seen, device) {
            (self.on_change)(snapshot(&self.seen));
        }
    }
}

/// Inserta o actualiza un equipo; devuelve si algo cambió. Un equipo alcanzado por el túnel USB
/// (reciente) no lo pisa el anuncio por red del mismo equipo, para que su dirección no alterne.
fn upsert(seen: &Mutex<HashMap<String, Seen>>, device: Device) -> bool {
    let mut s = seen.lock().unwrap();
    if let Some(old) = s.get(&device.id) {
        if old.device.link.is_some() && device.link.is_none() && old.last.elapsed() < Duration::from_secs(6) {
            return false;
        }
    }
    let changed = s.get(&device.id).map_or(true, |o| {
        o.device.address != device.address
            || o.device.port != device.port
            || o.device.name != device.name
            || o.device.paired != device.paired
            || o.device.info != device.info
            || o.device.link != device.link
    });
    s.insert(device.id.clone(), Seen { device, last: Instant::now() });
    changed
}

fn snapshot(seen: &Mutex<HashMap<String, Seen>>) -> Vec<Device> {
    let mut v: Vec<Device> = seen.lock().unwrap().values().map(|s| s.device.clone()).collect();
    v.sort_by(|a, b| a.name.cmp(&b.name));
    v
}

fn prune(seen: &Mutex<HashMap<String, Seen>>) -> bool {
    let mut s = seen.lock().unwrap();
    let before = s.len();
    s.retain(|_, v| v.last.elapsed() < Duration::from_secs(8));
    s.len() != before
}

fn broadcast_addresses() -> Vec<Ipv4Addr> {
    let mut out = vec![Ipv4Addr::BROADCAST];
    if let Ok(ifs) = if_addrs::get_if_addrs() {
        for i in ifs {
            if let if_addrs::IfAddr::V4(v4) = i.addr {
                if !v4.ip.is_loopback() {
                    if let Some(b) = v4.broadcast {
                        if !out.contains(&b) {
                            out.push(b);
                        }
                    }
                }
            }
        }
    }
    out
}

/// Una interfaz de red local con IPv4 (para mostrar por qué medios puede verse este equipo).
#[derive(Clone, Debug, Serialize)]
pub struct LocalLink {
    pub name: String,
    pub address: String,
    pub netmask: String,
    /// Adaptadores virtuales (WSL, Hyper-V, VPN, Docker…): no sirven para llegar a un teléfono.
    pub virtual_adapter: bool,
}

/// Interfaces de red con IPv4 (sin loopback ni direcciones locales de enlace).
pub fn local_links() -> Vec<LocalLink> {
    if_addrs::get_if_addrs()
        .unwrap_or_default()
        .into_iter()
        .filter_map(|i| match i.addr {
            if_addrs::IfAddr::V4(v4) if !v4.ip.is_loopback() && !v4.ip.is_link_local() => Some(LocalLink {
                virtual_adapter: is_virtual(&i.name),
                name: i.name,
                address: v4.ip.to_string(),
                netmask: v4.netmask.to_string(),
            }),
            _ => None,
        })
        .collect()
}

fn is_virtual(name: &str) -> bool {
    const MARKERS: &[&str] = &[
        "vethernet", "wsl", "default switch", "vmware", "virtualbox", "hyper-v", "docker", "zerotier",
        "tailscale", "loopback", "virbr", "veth", "br-", "tun", "tap",
    ];
    let n = name.to_lowercase();
    MARKERS.iter().any(|m| n.contains(m))
}

#[cfg(test)]
mod tests {
    use super::is_virtual;

    #[test]
    fn detects_virtual_adapters() {
        for n in ["vEthernet (WSL (Hyper-V firewall))", "vEthernet (Default Switch)", "ZeroTier One [b9a1]", "docker0", "VMware Network Adapter VMnet8"] {
            assert!(is_virtual(n), "{n}");
        }
        for n in ["Ethernet 2", "Ethernet 3", "Wi-Fi", "wlan0", "enp3s0"] {
            assert!(!is_virtual(n), "{n}");
        }
    }
}
