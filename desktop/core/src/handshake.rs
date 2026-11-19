//! Handshake de emparejamiento y autenticación mutua (ver PROTOCOL.md).

use crate::channel::{ct_eq, hmac, new_pair_token, pair_uri, parse_pair_token, pin_secret, token_secret, Channel, Frame};
use crate::store::Store;
use crate::{proto, t, Error, Result};
use rand::{Rng, RngCore};
use serde_json::{json, Value};

/// Interacción con el usuario durante el emparejamiento.
pub trait PairingUi: Send + Sync {
    /// Responder: muestra `code` hasta que `hide_code` indique que terminó. Con `qr` (el URI a codificar)
    /// el código es el token largo en grupos de 4; sin él, es el PIN de 6 dígitos de los equipos antiguos.
    fn show_code(&self, peer_name: &str, code: &str, qr: Option<&str>);
    fn hide_code(&self);
    /// Iniciador: bloquea hasta que el usuario escanea o pega el código del otro equipo; `None` si cancela.
    /// `qr` indica que se espera el token largo (URI o texto) y no el PIN de 6 dígitos.
    fn ask_code(&self, peer_name: &str, qr: bool) -> Option<String>;
}

pub struct Peer {
    pub id: String,
    pub name: String,
}

fn random16() -> [u8; 16] {
    let mut b = [0u8; 16];
    rand::rngs::OsRng.fill_bytes(&mut b);
    b
}

fn expect(f: Frame, typ: u8) -> Result<Value> {
    let v: Value = serde_json::from_slice(&f.payload).unwrap_or(Value::Null);
    if f.typ == t::ERROR {
        return proto(v["message"].as_str().unwrap_or("Error remoto").to_string());
    }
    if f.typ != typ {
        return proto(format!("Se esperaba el tipo {typ} y llegó {}", f.typ));
    }
    Ok(v)
}

fn field<'a>(v: &'a Value, k: &str) -> Result<&'a str> {
    v[k].as_str().ok_or_else(|| Error::Protocol(format!("Falta el campo {k}")))
}

fn unhex(v: &Value, k: &str) -> Result<Vec<u8>> {
    hex::decode(field(v, k)?).map_err(|_| Error::Protocol(format!("Campo {k} no es hexadecimal")))
}

fn proof(secret: &[u8], who: &str, nc: &[u8], ns: &[u8]) -> Vec<u8> {
    hmac(secret, &[who.as_bytes(), nc, ns])
}

fn session_key(secret: &[u8], nc: &[u8], ns: &[u8]) -> Vec<u8> {
    hmac(secret, &[b"key", nc, ns])
}

fn jbytes(v: Value) -> Vec<u8> {
    v.to_string().into_bytes()
}

/// Lado cliente (quien se conecta).
pub fn initiate(ch: &mut Channel, store: &Store, ui: &dyn PairingUi) -> Result<Peer> {
    let nc = random16();
    ch.send(t::HELLO, &jbytes(json!({"id": store.device_id(), "name": store.device_name(), "nonce": hex::encode(nc), "qr": true})))?;
    let h = expect(ch.recv()?, t::HELLO)?;
    let peer = Peer { id: field(&h, "id")?.to_string(), name: field(&h, "name")?.to_string() };
    let ns = unhex(&h, "nonce")?;

    let stored = store.secret_for(&peer.id);
    let peer_knows_us = h["paired"].as_bool().unwrap_or(false);
    let pairing = !(peer_knows_us && stored.is_some());
    let qr = pairing && h["pair"].as_str() == Some("qr");
    let secret = if pairing {
        match ui.ask_code(&peer.name, qr) {
            Some(code) if qr => match parse_pair_token(&code) {
                Some(token) => token_secret(&token),
                None => {
                    let _ = ch.send(t::ERROR, &jbytes(json!({"message": "Código de emparejamiento no válido"})));
                    return proto("El código de emparejamiento no es válido");
                }
            },
            Some(code) => pin_secret(code.trim()),
            None => {
                let _ = ch.send(t::ERROR, &jbytes(json!({"message": "Emparejamiento cancelado"})));
                return Err(Error::Cancelled);
            }
        }
    } else {
        stored.unwrap()
    };

    ch.send(t::AUTH, &jbytes(json!({"proof": hex::encode(proof(&secret, "C", &nc, &ns))})))?;
    let ok = expect(ch.recv()?, t::AUTH_OK)?;
    if !ct_eq(&unhex(&ok, "proof")?, &proof(&secret, "S", &nc, &ns)) {
        return proto("El otro equipo no pudo autenticarse");
    }
    ch.enable_crypto(&session_key(&secret, &nc, &ns), true);

    if pairing {
        let s = expect(ch.recv()?, t::PAIR_SECRET)?;
        store.save_peer(&peer.id, &unhex(&s, "secret")?)?;
    }
    Ok(peer)
}

/// Lado servidor (quien acepta la conexión).
pub fn respond(ch: &mut Channel, store: &Store, ui: &dyn PairingUi) -> Result<Peer> {
    let h = expect(ch.recv()?, t::HELLO)?;
    let peer = Peer { id: field(&h, "id")?.to_string(), name: field(&h, "name")?.to_string() };
    let nc = unhex(&h, "nonce")?;
    let ns = random16();

    // Sondeo: se dice quién somos y se cierra, sin emparejar ni registrar transferencia.
    if h["probe"].as_bool().unwrap_or(false) {
        ch.send(
            t::HELLO,
            &jbytes(json!({
                "id": store.device_id(), "name": store.device_name(), "nonce": hex::encode(ns),
                "paired": store.is_paired(&peer.id), "platform": std::env::consts::OS, "info": {},
            })),
        )?;
        return Err(Error::Probe);
    }

    let stored = store.secret_for(&peer.id);
    let pairing = stored.is_none();
    // El cliente avisa con `qr` si entiende el token largo; si no (equipos antiguos), PIN de 6 dígitos.
    let qr = pairing && h["qr"].as_bool().unwrap_or(false);
    let secret = match stored {
        Some(s) => s,
        None if qr => {
            let token = new_pair_token();
            let grouped: Vec<&str> = (0..token.len()).step_by(4).map(|i| &token[i..i + 4]).collect();
            ui.show_code(&peer.name, &grouped.join(" "), Some(&pair_uri(&token)));
            token_secret(&token)
        }
        None => {
            let code = format!("{:06}", rand::rngs::OsRng.gen_range(0..1_000_000));
            ui.show_code(&peer.name, &code, None);
            pin_secret(&code)
        }
    };

    let result = (|| -> Result<()> {
        ch.send(
            t::HELLO,
            &jbytes(json!({"id": store.device_id(), "name": store.device_name(), "nonce": hex::encode(ns), "paired": !pairing, "pair": if qr { "qr" } else { "pin" }})),
        )?;
        let a = expect(ch.recv()?, t::AUTH)?;
        if !ct_eq(&unhex(&a, "proof")?, &proof(&secret, "C", &nc, &ns)) {
            let _ = ch.send(t::ERROR, &jbytes(json!({"message": "Código o secreto incorrecto"})));
            return proto(format!("Autenticación fallida con {}", peer.name));
        }
        ch.send(t::AUTH_OK, &jbytes(json!({"proof": hex::encode(proof(&secret, "S", &nc, &ns))})))?;
        ch.enable_crypto(&session_key(&secret, &nc, &ns), false);

        if pairing {
            let mut fresh = [0u8; 32];
            rand::rngs::OsRng.fill_bytes(&mut fresh);
            ch.send(t::PAIR_SECRET, &jbytes(json!({"secret": hex::encode(fresh)})))?;
            store.save_peer(&peer.id, &fresh)?;
        }
        Ok(())
    })();
    if pairing {
        ui.hide_code();
    }
    result.map(|_| peer)
}

/// Respuesta a un sondeo: quién es el equipo del otro lado.
#[derive(Debug, Clone)]
pub struct ProbeReply {
    pub id: String,
    pub name: String,
    pub paired: bool,
    pub platform: String,
    pub info: Value,
}

/// Pregunta a `addr` quién es, sin emparejar ni transferir nada (ver "Sondeo" en PROTOCOL.md).
pub fn probe(addr: &std::net::SocketAddr, store: &Store, timeout: std::time::Duration) -> Result<ProbeReply> {
    let stream = std::net::TcpStream::connect_timeout(addr, timeout)?;
    stream.set_read_timeout(Some(timeout))?;
    stream.set_write_timeout(Some(timeout))?;
    let mut ch = Channel::new(stream)?;
    ch.send(
        t::HELLO,
        &jbytes(json!({"id": store.device_id(), "name": store.device_name(), "nonce": hex::encode(random16()), "probe": true})),
    )?;
    let h = expect(ch.recv()?, t::HELLO)?;
    Ok(ProbeReply {
        id: field(&h, "id")?.to_string(),
        name: field(&h, "name")?.to_string(),
        paired: h["paired"].as_bool().unwrap_or(false),
        platform: h["platform"].as_str().unwrap_or("?").to_string(),
        info: h["info"].clone(),
    })
}
