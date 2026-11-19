//! ShareDrop en terminal.
//!   sharedrop-cli receive            escucha y guarda en la carpeta de recepción
//!   sharedrop-cli set-dir [ruta]     cambia la carpeta de recepción (sin ruta: la predeterminada)
//!   sharedrop-cli list               muestra los equipos cercanos
//!   sharedrop-cli send <ip> <archivos…>   envía a un equipo (ver `list` para la IP)

use sharedrop_core::engine::{FsStorage, Outgoing};
use sharedrop_core::{Engine, Event, Store, TransferState};
use std::io::{BufRead, Write};
use std::path::{Path, PathBuf};
use std::sync::mpsc::{channel, Sender};
use std::sync::{Arc, Mutex};
use std::time::Duration;

enum Msg {
    Print(String),
    Finished(bool),
}

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let code = match args.first().map(String::as_str) {
        Some("receive") => run(Mode::Receive),
        Some("list") => run(Mode::List),
        Some("set-dir") => set_dir(args.get(1)),
        Some("send") if args.len() >= 3 => run(Mode::Send(args[1].clone(), args[2..].iter().map(PathBuf::from).collect())),
        _ => {
            eprintln!("Uso:\n  sharedrop-cli receive\n  sharedrop-cli list\n  sharedrop-cli send <ip> <archivos…>");
            2
        }
    };
    std::process::exit(code);
}

enum Mode {
    Receive,
    List,
    Send(String, Vec<PathBuf>),
}

fn receive_dir(store: &Store) -> PathBuf {
    store.receive_dir().unwrap_or_else(Store::default_receive_dir)
}

fn set_dir(path: Option<&String>) -> i32 {
    let store = match Store::open(Store::default_dir()) {
        Ok(s) => s,
        Err(e) => {
            eprintln!("No se pudo abrir la configuración: {e}");
            return 1;
        }
    };
    let dir = path.map(PathBuf::from);
    if let Some(d) = &dir {
        if let Err(e) = std::fs::create_dir_all(d) {
            eprintln!("No se pudo usar la carpeta: {e}");
            return 1;
        }
    }
    if let Err(e) = store.set_receive_dir(dir.as_deref()) {
        eprintln!("No se pudo guardar: {e}");
        return 1;
    }
    println!("Carpeta de recepción: {}", receive_dir(&store).display());
    0
}

fn run(mode: Mode) -> i32 {
    let store = match Store::open(Store::default_dir()) {
        Ok(s) => Arc::new(s),
        Err(e) => {
            eprintln!("No se pudo abrir la configuración: {e}");
            return 1;
        }
    };
    let (tx, rx) = channel::<Msg>();
    let engine_slot: Arc<Mutex<Option<Arc<Engine>>>> = Arc::default();
    let is_send = matches!(mode, Mode::Send(..));

    let emit = {
        let (tx, slot) = (Mutex::new(tx.clone()), engine_slot.clone());
        move |e: Event| {
            let tx: Sender<Msg> = tx.lock().unwrap().clone();
            match e {
                Event::Devices(d) => {
                    let _ = tx.send(Msg::Print(format!("-- Equipos cercanos: {}", d.len())));
                    for x in d {
                        let _ = tx.send(Msg::Print(format!(
                            "   {}  {}  [{}]{}",
                            x.address, x.name, x.platform, if x.paired { " emparejado" } else { "" }
                        )));
                    }
                }
                Event::Transfer(t) if t.state == TransferState::Active => {}
                Event::Transfer(t) => {
                    let dir = if t.incoming { "recibido de" } else { "enviado a" };
                    let line = match t.state {
                        TransferState::Finished => format!("OK  {dir} {}: {}", t.peer, t.file),
                        _ => format!("ERROR  {dir} {}: {}", t.peer, t.error.unwrap_or_default()),
                    };
                    let _ = tx.send(Msg::Print(line));
                    if is_send {
                        let _ = tx.send(Msg::Finished(t.state == TransferState::Finished));
                    }
                }
                Event::ShowCode { peer, code, qr } => {
                    let msg = match qr.as_deref().and_then(sharedrop_core::qr::terminal) {
                        Some(art) => format!("== Emparejando con {peer}. Escanea este QR en el otro equipo:
{art}
O escribe este código allí: {code}"),
                        None => format!("== Emparejando con {peer}. Escribe este código allí: {code}"),
                    };
                    let _ = tx.send(Msg::Print(msg));
                }
                Event::HideCode => {}
                Event::AskCode { peer, qr } => {
                    if qr {
                        print!("Código largo (o enlace sharedrop://) que muestra {peer} (vacío = cancelar): ");
                    } else {
                        print!("Código de 6 dígitos que muestra {peer} (vacío = cancelar): ");
                    }
                    let _ = std::io::stdout().flush();
                    let mut line = String::new();
                    let _ = std::io::stdin().lock().read_line(&mut line);
                    let line = line.trim().to_string();
                    if let Some(engine) = slot.lock().unwrap().as_ref() {
                        engine.answer_code(if line.is_empty() { None } else { Some(line) });
                    }
                }
            }
        }
    };

    let dir = receive_dir(&store);
    let engine = Engine::new(store, Arc::new(FsStorage::new(dir.clone())), std::env::consts::OS, emit);
    *engine_slot.lock().unwrap() = Some(engine.clone());

    match mode {
        Mode::Receive | Mode::List => {
            if let Err(e) = engine.start() {
                eprintln!("No se pudo escuchar en el puerto {}: {e} (¿ya hay otro ShareDrop abierto?)", sharedrop_core::TCP_PORT);
                return 1;
            }
            println!("ShareDrop escuchando. Recibe en {}. Ctrl+C para salir.", dir.display());
            let list_only = matches!(mode, Mode::List);
            let deadline = std::time::Instant::now() + Duration::from_secs(5);
            loop {
                match rx.recv_timeout(Duration::from_millis(250)) {
                    Ok(Msg::Print(s)) => println!("{s}"),
                    Ok(Msg::Finished(_)) | Err(_) => {}
                }
                if list_only && std::time::Instant::now() > deadline {
                    return 0;
                }
            }
        }
        Mode::Send(addr, paths) => {
            let files: Result<Vec<_>, _> = paths.iter().map(|p| Outgoing::from_path(Path::new(p))).collect();
            let files = match files {
                Ok(f) => f,
                Err(e) => {
                    eprintln!("No se pudo leer un archivo: {e}");
                    return 1;
                }
            };
            let addr = if addr.contains(':') { addr } else { format!("{addr}:{}", sharedrop_core::TCP_PORT) };
            engine.send_to_addr(&addr, &addr, files);
            loop {
                match rx.recv() {
                    Ok(Msg::Print(s)) => println!("{s}"),
                    Ok(Msg::Finished(ok)) => return if ok { 0 } else { 1 },
                    Err(_) => return 1,
                }
            }
        }
    }
}
