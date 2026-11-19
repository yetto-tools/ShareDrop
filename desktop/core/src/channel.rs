//! Canal de tramas sobre TCP. En claro hasta `enable_crypto`; después AES-256-GCM.

use crate::{proto, Result};
use aes_gcm::aead::{Aead, KeyInit};
use aes_gcm::{Aes256Gcm, Nonce};
use hmac::{Hmac, Mac};
use sha2::{Digest, Sha256};
use std::io::{BufReader, BufWriter, Read, Write};
use std::net::TcpStream;

pub const MAX_FRAME: usize = 8 * 1024 * 1024;

pub struct Frame {
    pub typ: u8,
    pub payload: Vec<u8>,
}

pub fn hmac(key: &[u8], parts: &[&[u8]]) -> Vec<u8> {
    let mut m = <Hmac<Sha256> as Mac>::new_from_slice(key).expect("HMAC acepta cualquier longitud");
    for p in parts {
        m.update(p);
    }
    m.finalize().into_bytes().to_vec()
}

pub fn pin_secret(code: &str) -> Vec<u8> {
    Sha256::digest(format!("sharedrop-pin:{code}").as_bytes()).to_vec()
}

/// Secreto de emparejamiento a partir del token de alta entropía del QR.
pub fn token_secret(token: &str) -> Vec<u8> {
    Sha256::digest(format!("sharedrop-qr:{token}").as_bytes()).to_vec()
}

/// Token de emparejamiento: 128 bits aleatorios en hexadecimal (32 caracteres).
pub fn new_pair_token() -> String {
    use rand::RngCore;
    let mut b = [0u8; 16];
    rand::rngs::OsRng.fill_bytes(&mut b);
    hex::encode(b)
}

/// Dirección que codifica el QR de emparejamiento.
pub fn pair_uri(token: &str) -> String {
    format!("sharedrop://pair?t={token}")
}

/// Extrae el token de lo que se escanee o pegue: el URI completo o el token suelto,
/// con o sin espacios o guiones de agrupación. `None` si no son 32 dígitos hexadecimales.
pub fn parse_pair_token(input: &str) -> Option<String> {
    let input = input.trim();
    let raw = match input.find("t=") {
        Some(i) => input[i + 2..].split('&').next().unwrap_or(""),
        None => input,
    };
    let token: String = raw.chars().filter(|c| !c.is_whitespace() && *c != '-').collect::<String>().to_ascii_lowercase();
    (token.len() == 32 && token.bytes().all(|b| b.is_ascii_hexdigit())).then_some(token)
}

pub fn ct_eq(a: &[u8], b: &[u8]) -> bool {
    use subtle::ConstantTimeEq;
    a.len() == b.len() && a.ct_eq(b).into()
}

fn nonce(prefix: u32, ctr: u64) -> [u8; 12] {
    let mut n = [0u8; 12];
    n[..4].copy_from_slice(&prefix.to_be_bytes());
    n[4..].copy_from_slice(&ctr.to_be_bytes());
    n
}

/// Cifra `type ‖ payload` con el formato de la trama (contador en claro + ciphertext‖tag).
pub fn seal(cipher: &Aes256Gcm, prefix: u32, ctr: u64, typ: u8, payload: &[u8]) -> Vec<u8> {
    let mut plain = Vec::with_capacity(1 + payload.len());
    plain.push(typ);
    plain.extend_from_slice(payload);
    let ct = cipher
        .encrypt(Nonce::from_slice(&nonce(prefix, ctr)), plain.as_slice())
        .expect("AES-GCM no falla al cifrar");
    let mut body = Vec::with_capacity(8 + ct.len());
    body.extend_from_slice(&ctr.to_be_bytes());
    body.extend_from_slice(&ct);
    body
}

pub struct Channel {
    r: BufReader<TcpStream>,
    w: BufWriter<TcpStream>,
    cipher: Option<Aes256Gcm>,
    send_prefix: u32,
    recv_prefix: u32,
    send_ctr: u64,
    recv_ctr: u64,
    pub remote: String,
}

impl Channel {
    pub fn new(s: TcpStream) -> std::io::Result<Self> {
        let remote = s.peer_addr().map(|a| a.ip().to_string()).unwrap_or_else(|_| "?".into());
        Ok(Channel {
            r: BufReader::with_capacity(1 << 16, s.try_clone()?),
            w: BufWriter::with_capacity(1 << 16, s),
            cipher: None,
            send_prefix: 0,
            recv_prefix: 0,
            send_ctr: 0,
            recv_ctr: 0,
            remote,
        })
    }

    pub fn enable_crypto(&mut self, session_key: &[u8], is_client: bool) {
        self.cipher = Some(Aes256Gcm::new_from_slice(session_key).expect("clave de 32 bytes"));
        self.send_prefix = if is_client { 1 } else { 2 };
        self.recv_prefix = if is_client { 2 } else { 1 };
    }

    pub fn send(&mut self, typ: u8, payload: &[u8]) -> Result<()> {
        match &self.cipher {
            None => {
                self.w.write_all(&((1 + payload.len()) as u32).to_be_bytes())?;
                self.w.write_all(&[typ])?;
                self.w.write_all(payload)?;
            }
            Some(c) => {
                let ctr = self.send_ctr;
                self.send_ctr += 1;
                let body = seal(c, self.send_prefix, ctr, typ, payload);
                self.w.write_all(&(body.len() as u32).to_be_bytes())?;
                self.w.write_all(&body)?;
            }
        }
        self.w.flush()?;
        Ok(())
    }

    pub fn recv(&mut self) -> Result<Frame> {
        let mut hdr = [0u8; 4];
        self.r.read_exact(&mut hdr)?;
        let len = u32::from_be_bytes(hdr) as usize;
        if len < 1 || len > MAX_FRAME {
            return proto(format!("Trama inválida ({len})"));
        }
        let mut body = vec![0u8; len];
        self.r.read_exact(&mut body)?;
        let Some(c) = &self.cipher else {
            return Ok(Frame { typ: body[0], payload: body[1..].to_vec() });
        };
        if len < 8 + 16 + 1 {
            return proto("Trama cifrada demasiado corta");
        }
        let ctr = u64::from_be_bytes(body[..8].try_into().unwrap());
        if ctr != self.recv_ctr {
            return proto("Contador fuera de orden");
        }
        self.recv_ctr += 1;
        let plain = c
            .decrypt(Nonce::from_slice(&nonce(self.recv_prefix, ctr)), &body[8..])
            .map_err(|_| crate::Error::Protocol("Trama alterada o clave incorrecta".into()))?;
        Ok(Frame { typ: plain[0], payload: plain[1..].to_vec() })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const TOKEN: &str = "00112233445566778899aabbccddeeff";

    #[test]
    fn parses_uri_grouped_and_bare_tokens() {
        assert_eq!(parse_pair_token(&pair_uri(TOKEN)).as_deref(), Some(TOKEN));
        assert_eq!(parse_pair_token("  0011 2233 4455 6677 8899 AABB CCDD EEFF
").as_deref(), Some(TOKEN));
        assert_eq!(parse_pair_token(TOKEN).as_deref(), Some(TOKEN));
        assert_eq!(parse_pair_token(&format!("sharedrop://pair?t={TOKEN}&x=1")).as_deref(), Some(TOKEN));
    }

    #[test]
    fn rejects_pins_and_malformed_tokens() {
        assert_eq!(parse_pair_token("123456"), None);
        assert_eq!(parse_pair_token(""), None);
        assert_eq!(parse_pair_token("sharedrop://pair?t="), None);
        assert_eq!(parse_pair_token(&TOKEN.replace('0', "g")), None);
        assert_eq!(parse_pair_token(&format!("{TOKEN}00")), None);
    }

    #[test]
    fn new_tokens_are_valid_and_distinct() {
        let (a, b) = (new_pair_token(), new_pair_token());
        assert_ne!(a, b);
        assert_eq!(parse_pair_token(&a).as_deref(), Some(a.as_str()));
        assert_ne!(token_secret(&a), token_secret(&b));
    }
}
