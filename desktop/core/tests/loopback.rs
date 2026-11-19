use sharedrop_core::engine::{FsStorage, Outgoing};
use sharedrop_core::{Device, Engine, Event, Store, TransferInfo, TransferState};
use std::path::PathBuf;
use std::sync::mpsc::{channel, Receiver};
use std::sync::{Arc, Mutex};
use std::time::Duration;

struct Node {
    engine: Arc<Engine>,
    events: Receiver<Event>,
    inbox: PathBuf,
}

fn tmp(tag: &str) -> PathBuf {
    let d = std::env::temp_dir().join(format!("sharedrop-{tag}-{}", uuid_like()));
    std::fs::create_dir_all(&d).unwrap();
    d
}

fn uuid_like() -> String {
    use std::time::{SystemTime, UNIX_EPOCH};
    format!("{}-{}", std::process::id(), SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_nanos())
}

fn node(name: &str) -> Node {
    let root = tmp(name);
    let store = Arc::new(Store::open(root.join("cfg")).unwrap());
    let inbox = root.join("inbox");
    let (tx, rx) = channel();
    let tx = Mutex::new(tx);
    let engine = Engine::new(store, Arc::new(FsStorage::new(inbox.clone())), "test", move |e| {
        let _ = tx.lock().unwrap().send(e);
    });
    engine.start_on(0).unwrap();
    Node { engine, events: rx, inbox }
}

fn wait_for<T>(rx: &Receiver<Event>, mut pick: impl FnMut(&Event) -> Option<T>) -> T {
    loop {
        let e = rx.recv_timeout(Duration::from_secs(20)).expect("evento esperado no llegó");
        if let Some(v) = pick(&e) {
            return v;
        }
    }
}

fn final_transfer(rx: &Receiver<Event>) -> TransferInfo {
    wait_for(rx, |e| match e {
        Event::Transfer(t) if t.state != TransferState::Active => Some(t.clone()),
        _ => None,
    })
}

/// Espera a que un archivo entrante con ese nombre termine y tenga ruta final.
fn file_done(rx: &Receiver<Event>, name: &str) -> TransferInfo {
    wait_for(rx, |e| match e {
        Event::Transfer(t) if t.incoming && t.state == TransferState::Finished && t.path.as_deref().is_some_and(|p| p.ends_with(name)) => {
            Some(t.clone())
        }
        _ => None,
    })
}

/// Espera al resultado final de un envío saliente.
fn outgoing_done(rx: &Receiver<Event>) -> TransferInfo {
    wait_for(rx, |e| match e {
        Event::Transfer(t) if !t.incoming && t.state != TransferState::Active => Some(t.clone()),
        _ => None,
    })
}

fn device_of(n: &Node) -> Device {
    Device {
        id: n.engine.store.device_id(),
        name: n.engine.store.device_name(),
        platform: "test".into(),
        address: "127.0.0.1".into(),
        port: n.engine.local_port(),
        paired: false,
        info: Default::default(),
        link: None,
    }
}

fn sample(n: usize) -> Vec<u8> {
    (0..n).map(|i| (i.wrapping_mul(31) ^ (i >> 7)) as u8).collect()
}

#[test]
fn pair_then_transfer_then_reconnect_without_code() {
    let a = node("a");
    let b = node("b");
    let dir = tmp("src");
    let big = dir.join("grande.bin");
    let empty = dir.join("vacío.txt");
    std::fs::write(&big, sample(3 * 1024 * 1024 + 123)).unwrap(); // más de 6 fragmentos
    std::fs::write(&empty, b"").unwrap();

    // 1ª vez: emparejamiento. B muestra el código; A lo "escribe".
    a.engine.send(&device_of(&b), vec![Outgoing::from_path(&big).unwrap(), Outgoing::from_path(&empty).unwrap()]);
    let code = wait_for(&b.events, |e| match e {
        Event::ShowCode { code, .. } => Some(code.clone()),
        _ => None,
    });
    wait_for(&a.events, |e| matches!(e, Event::AskCode { .. }).then_some(()));
    a.engine.answer_code(Some(code));

    let sent = outgoing_done(&a.events);
    assert_eq!(sent.state, TransferState::Finished, "{:?}", sent.error);
    // Cada archivo recibido llega en su propia fila, con su ruta final.
    let big_done = file_done(&b.events, "grande.bin");
    let big_path = big_done.path.expect("ruta del archivo recibido");
    assert!(std::path::Path::new(&big_path).exists());
    assert_eq!(std::path::Path::new(&big_path), b.inbox.join("grande.bin"));
    assert_ne!(big_done.id, file_done(&b.events, "vacío.txt").id, "un archivo = una fila");
    assert_eq!(std::fs::read(b.inbox.join("grande.bin")).unwrap(), sample(3 * 1024 * 1024 + 123));
    assert_eq!(std::fs::read(b.inbox.join("vacío.txt")).unwrap(), b"");
    assert!(a.engine.store.is_paired(&b.engine.store.device_id()));
    assert!(b.engine.store.is_paired(&a.engine.store.device_id()));

    // 2ª vez, y en sentido contrario: ya emparejados, no debe pedir código.
    let reply = dir.join("respuesta.txt");
    std::fs::write(&reply, b"hola").unwrap();
    b.engine.send(&device_of(&a), vec![Outgoing::from_path(&reply).unwrap()]);
    let back = wait_for(&b.events, |e| match e {
        Event::AskCode { .. } => panic!("no debía pedir código al reconectar"),
        Event::Transfer(t) if t.state != TransferState::Active && !t.incoming => Some(t.clone()),
        _ => None,
    });
    assert_eq!(back.state, TransferState::Finished, "{:?}", back.error);
    file_done(&a.events, "respuesta.txt");
    assert_eq!(std::fs::read(a.inbox.join("respuesta.txt")).unwrap(), b"hola");

    // Un archivo con el mismo nombre no se pisa.
    a.engine.send(&device_of(&b), vec![Outgoing::from_path(&reply).unwrap()]);
    assert_eq!(outgoing_done(&a.events).state, TransferState::Finished);
    file_done(&b.events, "respuesta.txt");
    assert_eq!(std::fs::read(b.inbox.join("respuesta.txt")).unwrap(), b"hola");

    a.engine.stop();
    b.engine.stop();
}

#[test]
fn wrong_code_fails_and_does_not_pair() {
    let a = node("a2");
    let b = node("b2");
    let dir = tmp("src2");
    let f = dir.join("x.txt");
    std::fs::write(&f, b"x").unwrap();

    a.engine.send(&device_of(&b), vec![Outgoing::from_path(&f).unwrap()]);
    let code = wait_for(&b.events, |e| match e {
        Event::ShowCode { code, .. } => Some(code.clone()),
        _ => None,
    });
    wait_for(&a.events, |e| matches!(e, Event::AskCode { qr: true, .. }).then_some(()));
    // Un token válido pero distinto del mostrado: debe fallar la autenticación, no el formato.
    let wrong = if code.replace(' ', "").starts_with('0') { "1".repeat(32) } else { "0".repeat(32) };
    a.engine.answer_code(Some(wrong));

    assert_eq!(final_transfer(&a.events).state, TransferState::Failed);
    assert_eq!(final_transfer(&b.events).state, TransferState::Failed);
    assert!(!b.engine.store.is_paired(&a.engine.store.device_id()));
    assert!(!b.inbox.join("x.txt").exists());
    a.engine.stop();
    b.engine.stop();
}

#[test]
fn probe_identifies_the_peer_without_pairing_or_events() {
    let a = node("pa");
    let b = node("pb");
    let addr = std::net::SocketAddr::from(([127, 0, 0, 1], b.engine.local_port()));

    let reply = sharedrop_core::handshake::probe(&addr, &a.engine.store, Duration::from_secs(2)).unwrap();
    assert_eq!(reply.id, b.engine.store.device_id());
    assert_eq!(reply.name, b.engine.store.device_name());
    assert!(!reply.paired, "aún no se emparejaron");

    // Un sondeo no muestra código de emparejamiento ni deja una transferencia (fallida) en la lista.
    assert!(b.events.recv_timeout(Duration::from_millis(600)).is_err(), "un sondeo no debe generar eventos");
    assert!(!b.engine.store.is_paired(&a.engine.store.device_id()));
    assert!(!a.engine.store.is_paired(&b.engine.store.device_id()));
}

#[test]
fn a_connection_that_closes_without_sending_anything_leaves_no_trace() {
    let b = node("silent");
    // Se abre y se cierra sin enviar nada: como un túnel que comprueba el puerto.
    drop(std::net::TcpStream::connect(("127.0.0.1", b.engine.local_port())).unwrap());
    assert!(b.events.recv_timeout(Duration::from_millis(600)).is_err(), "no debe generar eventos");
    assert!(b.engine.history().is_empty(), "ni entradas en el historial");
}

#[test]
fn pairs_by_scanning_the_qr_uri() {
    let a = node("a3");
    let b = node("b3");
    let dir = tmp("src3");
    let f = dir.join("qr.txt");
    std::fs::write(&f, b"qr").unwrap();

    a.engine.send(&device_of(&b), vec![Outgoing::from_path(&f).unwrap()]);
    let (code, uri) = wait_for(&b.events, |e| match e {
        Event::ShowCode { code, qr, .. } => Some((code.clone(), qr.clone().expect("debe ofrecer QR"))),
        _ => None,
    });
    assert!(uri.starts_with("sharedrop://pair?t="), "{uri}");
    assert_eq!(code.replace(' ', ""), uri.rsplit('=').next().unwrap(), "el texto es el mismo token que el QR");
    assert!(sharedrop_core::qr::svg(&uri).unwrap().contains("<svg"));
    wait_for(&a.events, |e| matches!(e, Event::AskCode { qr: true, .. }).then_some(()));
    a.engine.answer_code(Some(uri)); // lo que devolvería el escáner

    assert_eq!(outgoing_done(&a.events).state, TransferState::Finished);
    assert!(b.engine.store.is_paired(&a.engine.store.device_id()));
    assert_eq!(std::fs::read(b.inbox.join("qr.txt")).unwrap(), b"qr");
    a.engine.stop();
    b.engine.stop();
}

#[test]
fn malformed_pair_code_fails_without_pairing() {
    let a = node("a4");
    let b = node("b4");
    let dir = tmp("src4");
    let f = dir.join("m.txt");
    std::fs::write(&f, b"m").unwrap();

    a.engine.send(&device_of(&b), vec![Outgoing::from_path(&f).unwrap()]);
    wait_for(&b.events, |e| matches!(e, Event::ShowCode { .. }).then_some(()));
    wait_for(&a.events, |e| matches!(e, Event::AskCode { .. }).then_some(()));
    a.engine.answer_code(Some("123456".into())); // un PIN no sirve como token

    assert_eq!(final_transfer(&a.events).state, TransferState::Failed);
    assert!(!b.engine.store.is_paired(&a.engine.store.device_id()));
    a.engine.stop();
    b.engine.stop();
}

#[test]
fn a_failed_send_can_be_retried_once_and_the_retry_runs_again() {
    let a = node("retry");
    let file = tmp("retry-file").join("dato.txt");
    std::fs::write(&file, b"hola").unwrap();

    // Nadie escucha en ese puerto: el envío falla al conectar.
    a.engine.send_to_addr("nadie", "127.0.0.1:1", vec![Outgoing::from_path(&file).unwrap()]);
    let failed = outgoing_done(&a.events);
    assert_eq!(failed.state, TransferState::Failed);

    a.engine.retry(failed.id).expect("el envío fallido se puede reintentar");
    let again = outgoing_done(&a.events);
    assert_eq!(again.state, TransferState::Failed, "sigue sin haber nadie");
    assert_ne!(again.id, failed.id, "el reintento es una transferencia nueva");

    assert!(a.engine.retry(failed.id).is_err(), "el mismo envío no se reintenta dos veces");
}
