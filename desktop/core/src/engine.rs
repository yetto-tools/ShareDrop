//! Motor: escucha conexiones, envía y recibe archivos.

use crate::channel::Channel;
use crate::discovery::{Device, Discovery};
use crate::handshake::{self, PairingUi};
use crate::store::Store;
use crate::{proto, t, Error, Result, CHUNK, TCP_PORT};
use serde::Serialize;
use serde_json::{json, Value};
use std::io::{self, Read, Write};
use std::net::{Shutdown, SocketAddr, TcpListener, TcpStream, ToSocketAddrs};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::mpsc::{self, Sender};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::{Duration, Instant};

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "lowercase")]
pub enum TransferState {
    Active,
    Finished,
    Failed,
}

#[derive(Clone, Debug, Serialize)]
pub struct TransferInfo {
    pub id: u64,
    pub peer: String,
    pub file: String,
    pub done: u64,
    pub total: i64,
    pub incoming: bool,
    pub state: TransferState,
    pub error: Option<String>,
    /// Dónde quedó guardado un archivo recibido (solo cuando termina; `None` si el destino no es una ruta).
    pub path: Option<String>,
}

/// Eventos hacia la interfaz (Tauri los reenvía al frontend).
#[derive(Clone, Debug)]
pub enum Event {
    Devices(Vec<Device>),
    Transfer(TransferInfo),
    /// `qr` es el URI a dibujar como QR; sin él, `code` es el PIN de 6 dígitos de los equipos antiguos.
    ShowCode { peer: String, code: String, qr: Option<String> },
    HideCode,
    /// `qr`: se espera el token largo (escanearlo o pegarlo) y no el PIN de 6 dígitos.
    AskCode { peer: String, qr: bool },
}

// ---------- Almacenamiento (en Android lo implementa MediaStore) ----------

pub trait IncomingFile: Write + Send {
    /// Cierra el archivo y devuelve su ruta final, si el destino tiene una.
    fn finish(self: Box<Self>) -> io::Result<Option<PathBuf>>;
    fn abort(self: Box<Self>);
}

pub trait Storage: Send + Sync {
    fn create_incoming(&self, name: &str) -> io::Result<Box<dyn IncomingFile>>;
}

/// Guarda en una carpeta, sin pisar archivos existentes (`nombre (1).ext`).
/// La carpeta se puede cambiar en caliente; afecta a los archivos que lleguen después.
pub struct FsStorage {
    dir: std::sync::RwLock<PathBuf>,
}

impl FsStorage {
    pub fn new(dir: PathBuf) -> Self {
        FsStorage { dir: std::sync::RwLock::new(dir) }
    }

    pub fn dir(&self) -> PathBuf {
        self.dir.read().unwrap().clone()
    }

    pub fn set_dir(&self, dir: PathBuf) {
        *self.dir.write().unwrap() = dir;
    }
}

struct FsIncoming {
    file: Option<std::io::BufWriter<std::fs::File>>,
    tmp: PathBuf,
    dest: PathBuf,
}

impl Write for FsIncoming {
    fn write(&mut self, buf: &[u8]) -> io::Result<usize> {
        self.file.as_mut().unwrap().write(buf)
    }
    fn flush(&mut self) -> io::Result<()> {
        self.file.as_mut().unwrap().flush()
    }
}

impl IncomingFile for FsIncoming {
    fn finish(mut self: Box<Self>) -> io::Result<Option<PathBuf>> {
        self.file.take().unwrap().into_inner().map_err(|e| e.into_error())?.sync_all()?;
        std::fs::rename(&self.tmp, &self.dest)?;
        Ok(Some(self.dest.clone()))
    }
    fn abort(mut self: Box<Self>) {
        drop(self.file.take());
        let _ = std::fs::remove_file(&self.tmp);
    }
}

impl Storage for FsStorage {
    fn create_incoming(&self, name: &str) -> io::Result<Box<dyn IncomingFile>> {
        let dir = self.dir();
        std::fs::create_dir_all(&dir)?;
        let path = Path::new(name);
        let stem = path.file_stem().and_then(|s| s.to_str()).unwrap_or("archivo");
        let ext = path.extension().and_then(|s| s.to_str()).map(|e| format!(".{e}")).unwrap_or_default();
        let mut dest = dir.join(name);
        let mut i = 1;
        while dest.exists() {
            dest = dir.join(format!("{stem} ({i}){ext}"));
            i += 1;
        }
        let tmp = dest.with_file_name(format!("{}.part", dest.file_name().unwrap().to_string_lossy()));
        let f = std::fs::OpenOptions::new().write(true).create_new(true).open(&tmp)?;
        Ok(Box::new(FsIncoming { file: Some(std::io::BufWriter::with_capacity(1 << 16, f)), tmp, dest }))
    }
}

/// Archivo a enviar; `open` se llama una sola vez al empezar a enviarlo.
pub struct Outgoing {
    pub name: String,
    pub size: u64,
    pub open: Box<dyn FnOnce() -> io::Result<Box<dyn Read + Send>> + Send>,
}

impl Outgoing {
    pub fn from_path(p: &Path) -> io::Result<Self> {
        let size = std::fs::metadata(p)?.len();
        let name = p.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_else(|| "archivo".into());
        let p = p.to_path_buf();
        Ok(Outgoing { name, size, open: Box::new(move || Ok(Box::new(std::fs::File::open(p)?) as Box<dyn Read + Send>)) })
    }
}

// ---------- Motor ----------

type Emit = Arc<dyn Fn(Event) + Send + Sync>;

pub struct Engine {
    pub store: Arc<Store>,
    discovery: Discovery,
    storage: Arc<dyn Storage>,
    emit: Emit,
    ids: AtomicU64,
    running: Arc<AtomicBool>,
    answer: Mutex<Option<Sender<Option<String>>>>,
    local_port: Mutex<u16>,
    usb: Mutex<crate::adb::UsbStatus>,
    history: crate::history::History,
    /// Identifica este arranque: los ids de transferencia se reinician en cada uno.
    session: u64,
}

struct Ui {
    emit: Emit,
    engine: std::sync::Weak<Engine>,
}

impl PairingUi for Ui {
    fn show_code(&self, peer: &str, code: &str, qr: Option<&str>) {
        (self.emit)(Event::ShowCode { peer: peer.into(), code: code.into(), qr: qr.map(Into::into) });
    }
    fn hide_code(&self) {
        (self.emit)(Event::HideCode);
    }
    fn ask_code(&self, peer: &str, qr: bool) -> Option<String> {
        let engine = self.engine.upgrade()?;
        let (tx, rx) = mpsc::channel();
        *engine.answer.lock().unwrap() = Some(tx);
        (self.emit)(Event::AskCode { peer: peer.into(), qr });
        let r = rx.recv_timeout(Duration::from_secs(120)).ok().flatten();
        *engine.answer.lock().unwrap() = None;
        r
    }
}

impl Engine {
    pub fn new(
        store: Arc<Store>,
        storage: Arc<dyn Storage>,
        platform: &'static str,
        emit: impl Fn(Event) + Send + Sync + 'static,
    ) -> Arc<Self> {
        let emit: Emit = Arc::new(emit);
        let e2 = emit.clone();
        let discovery = Discovery::new(store.clone(), platform, Arc::new(move |d| e2(Event::Devices(d))));
        let history = crate::history::History::open(store.data_dir());
        let session = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_millis() as u64)
            .unwrap_or(0);
        Arc::new(Engine {
            history,
            session,
            store,
            discovery,
            storage,
            emit,
            ids: AtomicU64::new(0),
            running: Arc::new(AtomicBool::new(false)),
            answer: Mutex::new(None),
            local_port: Mutex::new(TCP_PORT),
            usb: Mutex::new(Default::default()),
        })
    }

    fn ui(self: &Arc<Self>) -> Ui {
        Ui { emit: self.emit.clone(), engine: Arc::downgrade(self) }
    }

    /// Respuesta del usuario al evento `AskCode` (`None` = cancelar).
    pub fn answer_code(&self, code: Option<String>) {
        if let Some(tx) = self.answer.lock().unwrap().as_ref() {
            let _ = tx.send(code);
        }
    }

    pub fn start(self: &Arc<Self>) -> io::Result<()> {
        self.start_on(TCP_PORT)
    }

    /// Como `start`, pero en otro puerto TCP (pruebas). El descubrimiento anuncia el puerto estándar.
    pub fn start_on(self: &Arc<Self>, port: u16) -> io::Result<()> {
        if self.running.swap(true, Ordering::SeqCst) {
            return Ok(());
        }
        let listener = TcpListener::bind(("0.0.0.0", port))?;
        *self.local_port.lock().unwrap() = listener.local_addr()?.port();
        if port == TCP_PORT {
            self.discovery.start()?;
            self.start_usb_link();
        }
        let me = self.clone();
        thread::spawn(move || {
            for conn in listener.incoming() {
                if !me.running.load(Ordering::SeqCst) {
                    break;
                }
                if let Ok(s) = conn {
                    let me = me.clone();
                    thread::spawn(move || me.handle_incoming(s));
                }
            }
        });
        Ok(())
    }

    /// Estado del enlace USB por adb (para la pantalla de Conexión).
    pub fn usb_status(&self) -> crate::adb::UsbStatus {
        self.usb.lock().unwrap().clone()
    }

    /// Cada pocos segundos busca un teléfono por cable (adb), abre los túneles y lo sondea.
    fn start_usb_link(self: &Arc<Self>) {
        use crate::adb;
        let me = self.clone();
        thread::spawn(move || {
            let mut adb_path = None;
            while me.running.load(Ordering::SeqCst) {
                if adb_path.is_none() {
                    adb_path = adb::find_adb();
                }
                let mut status = adb::UsbStatus { adb_found: adb_path.is_some(), ..Default::default() };
                if let Some(path) = &adb_path {
                    let (serials, unauthorized) = adb::list_devices(path);
                    status.devices = serials.len();
                    status.unauthorized = unauthorized;
                    // Solo se enlaza el primer teléfono: los puertos de los túneles son fijos.
                    if let Some(serial) = serials.first() {
                        if adb::setup(path, serial) {
                            let addr = SocketAddr::from(([127, 0, 0, 1], adb::FORWARD_PORT));
                            if let Ok(r) = crate::handshake::probe(&addr, &me.store, Duration::from_millis(1500)) {
                                status.linked = true;
                                me.discovery.inject(Device {
                                    paired: r.paired || me.store.is_paired(&r.id),
                                    info: serde_json::from_value(r.info).unwrap_or_default(),
                                    id: r.id,
                                    name: r.name,
                                    platform: r.platform,
                                    address: "127.0.0.1".into(),
                                    port: adb::FORWARD_PORT,
                                    link: Some("usb-adb".into()),
                                });
                            }
                        }
                    }
                }
                *me.usb.lock().unwrap() = status;
                thread::sleep(Duration::from_secs(3));
            }
        });
    }

    pub fn local_port(&self) -> u16 {
        *self.local_port.lock().unwrap()
    }

    pub fn stop(&self) {
        self.running.store(false, Ordering::SeqCst);
        self.discovery.stop();
        // Desbloquea accept().
        let _ = TcpStream::connect(("127.0.0.1", self.local_port()));
    }

    fn next_id(&self) -> u64 {
        self.ids.fetch_add(1, Ordering::SeqCst) + 1
    }

    fn emit_transfer(&self, t: &TransferInfo) {
        // Las transferencias terminadas pasan al historial (guardado en disco).
        self.history.record(t, self.session);
        (self.emit)(Event::Transfer(t.clone()));
    }

    /// Historial de transferencias terminadas, la más reciente primero (hasta 20 recibidas y 20 enviadas).
    pub fn history(&self) -> Vec<crate::history::HistoryEntry> {
        self.history.list()
    }

    // ---------- Envío ----------

    pub fn send(self: &Arc<Self>, device: &Device, files: Vec<Outgoing>) {
        let me = self.clone();
        let addr = format!("{}:{}", device.address, device.port);
        let name = device.name.clone();
        thread::spawn(move || me.run_send(&name, &addr, files));
    }

    pub fn send_to_addr(self: &Arc<Self>, name: &str, addr: &str, files: Vec<Outgoing>) {
        let me = self.clone();
        let (name, addr) = (name.to_string(), addr.to_string());
        thread::spawn(move || me.run_send(&name, &addr, files));
    }

    fn run_send(self: &Arc<Self>, peer: &str, addr: &str, files: Vec<Outgoing>) {
        let mut cur = TransferInfo {
            id: self.next_id(),
            peer: peer.into(),
            file: files.first().map(|f| f.name.clone()).unwrap_or_default(),
            done: 0,
            total: 0,
            incoming: false,
            state: TransferState::Active,
            error: None,
            path: None,
        };
        self.emit_transfer(&cur);
        match self.do_send(&mut cur, addr, files) {
            Ok(()) => cur.state = TransferState::Finished,
            Err(e) => {
                cur.state = TransferState::Failed;
                cur.error = Some(e.to_string());
            }
        }
        self.emit_transfer(&cur);
    }

    fn do_send(self: &Arc<Self>, cur: &mut TransferInfo, addr: &str, files: Vec<Outgoing>) -> Result<()> {
        let sa: SocketAddr = addr
            .to_socket_addrs()?
            .next()
            .ok_or_else(|| Error::Protocol("Dirección inválida".into()))?;
        let stream = TcpStream::connect_timeout(&sa, Duration::from_secs(5))
            .map_err(|e| Error::Protocol(format!("No se pudo conectar ({e}). ¿Firewall o red con aislamiento?")))?;
        stream.set_nodelay(true)?;
        let mut ch = Channel::new(stream)?;
        handshake::initiate(&mut ch, &self.store, &self.ui())?;

        let offer = json!({"files": files.iter().map(|f| json!({"name": f.name, "size": f.size})).collect::<Vec<_>>()});
        ch.send(t::OFFER, offer.to_string().as_bytes())?;
        let reply = ch.recv()?;
        if reply.typ != t::ACCEPT {
            return proto(error_message(&reply.payload).unwrap_or_else(|| "Rechazado".into()));
        }

        let mut buf = vec![0u8; CHUNK];
        for f in files {
            cur.file = f.name.clone();
            cur.done = 0;
            cur.total = f.size as i64;
            self.emit_transfer(cur);
            ch.send(t::FILE_START, json!({"name": f.name, "size": f.size}).to_string().as_bytes())?;
            let mut src = (f.open)()?;
            let mut last_ui = Instant::now();
            loop {
                let n = src.read(&mut buf)?;
                if n == 0 {
                    break;
                }
                ch.send(t::DATA, &buf[..n])?;
                cur.done += n as u64;
                if last_ui.elapsed() > Duration::from_millis(150) {
                    last_ui = Instant::now();
                    self.emit_transfer(cur);
                }
            }
            ch.send(t::FILE_END, &[])?;
            cur.total = cur.done as i64;
            self.emit_transfer(cur);
        }
        ch.send(t::DONE, &[])?;
        let ack = ch.recv()?;
        if ack.typ != t::DONE {
            return proto(error_message(&ack.payload).unwrap_or_else(|| "Sin confirmación".into()));
        }
        Ok(())
    }

    // ---------- Recepción ----------

    fn handle_incoming(self: &Arc<Self>, stream: TcpStream) {
        let _ = stream.set_nodelay(true);
        if !self.running.load(Ordering::SeqCst) {
            let _ = stream.shutdown(Shutdown::Both);
            return;
        }
        let remote = stream.peer_addr().map(|a| a.ip().to_string()).unwrap_or_else(|_| "?".into());
        let mut cur = TransferInfo {
            id: self.next_id(),
            peer: remote,
            file: String::new(),
            done: 0,
            total: 0,
            incoming: true,
            state: TransferState::Active,
            error: None,
            path: None,
        };
        let mut file: Option<Box<dyn IncomingFile>> = None;
        let result = self.do_receive(stream, &mut cur, &mut file);
        // Un sondeo (quién eres) no es una transferencia: no deja fila ni error.
        if matches!(result, Err(Error::Probe)) {
            return;
        }
        // Una conexión que se abre y se cierra sin transferir nada (un túnel que comprueba el puerto,
        // un escaneo, el aviso para detener el servidor) tampoco es una transferencia fallida.
        if let Err(Error::Io(e)) = &result {
            if e.kind() == io::ErrorKind::UnexpectedEof && cur.file.is_empty() {
                return;
            }
        }
        match result {
            Ok(()) => cur.state = TransferState::Finished,
            Err(e) => {
                if let Some(f) = file.take() {
                    f.abort();
                }
                cur.state = TransferState::Failed;
                cur.error = Some(e.to_string());
            }
        }
        self.emit_transfer(&cur);
    }

    fn do_receive(
        self: &Arc<Self>,
        stream: TcpStream,
        cur: &mut TransferInfo,
        file: &mut Option<Box<dyn IncomingFile>>,
    ) -> Result<()> {
        let mut ch = Channel::new(stream)?;
        let peer = handshake::respond(&mut ch, &self.store, &self.ui())?;
        cur.peer = peer.name;
        let mut last_ui = Instant::now();
        let mut started = false;
        loop {
            let f = ch.recv()?;
            match f.typ {
                t::OFFER => ch.send(t::ACCEPT, &[])?,
                t::FILE_START => {
                    let j: Value = serde_json::from_slice(&f.payload).map_err(|e| Error::Protocol(e.to_string()))?;
                    let name = sanitize(j["name"].as_str().unwrap_or("archivo"));
                    *file = Some(self.storage.create_incoming(&name)?);
                    // Cada archivo tiene su propia fila (para poder abrirlo por separado).
                    if started {
                        cur.id = self.next_id();
                    }
                    started = true;
                    cur.file = name;
                    cur.done = 0;
                    cur.total = j["size"].as_i64().unwrap_or(-1);
                    cur.path = None;
                    cur.state = TransferState::Active;
                    self.emit_transfer(cur);
                }
                t::DATA => {
                    let out = file.as_mut().ok_or_else(|| Error::Protocol("DATA sin FILE_START".into()))?;
                    out.write_all(&f.payload)?;
                    cur.done += f.payload.len() as u64;
                    if last_ui.elapsed() > Duration::from_millis(150) {
                        last_ui = Instant::now();
                        self.emit_transfer(cur);
                    }
                }
                t::FILE_END => {
                    if let Some(out) = file.take() {
                        cur.path = out.finish()?.map(|p| p.display().to_string());
                    }
                    cur.total = cur.done as i64;
                    cur.state = TransferState::Finished;
                    self.emit_transfer(cur);
                }
                t::DONE => {
                    ch.send(t::DONE, &[])?;
                    return Ok(());
                }
                t::ERROR => return proto(error_message(&f.payload).unwrap_or_else(|| "Error remoto".into())),
                other => return proto(format!("Tipo inesperado {other}")),
            }
        }
    }
}

fn sanitize(name: &str) -> String {
    let base = name.rsplit(['/', '\\']).next().unwrap_or("");
    let clean: String = base
        .chars()
        .map(|c| if c.is_control() || "<>:\"/\\|?*".contains(c) { '_' } else { c })
        .collect();
    let clean = clean.trim().trim_matches('.').to_string();
    if clean.is_empty() { "archivo".into() } else { clean }
}

fn error_message(payload: &[u8]) -> Option<String> {
    serde_json::from_slice::<Value>(payload).ok()?["message"].as_str().filter(|s| !s.is_empty()).map(String::from)
}
