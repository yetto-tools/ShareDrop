//! Núcleo de ShareDrop. Implementa `protocol/PROTOCOL.md` (v1) y es compatible con la app Android.

pub mod adb;
pub mod channel;
pub mod discovery;
pub mod engine;
pub mod handshake;
pub mod history;
pub mod qr;
pub mod store;

pub use channel::{Channel, Frame};
pub use discovery::{local_links, Device, DeviceInfo, LocalLink};
pub use engine::{Engine, Event, TransferInfo, TransferState};
pub use handshake::{Peer, PairingUi};
pub use history::{History, HistoryEntry};
pub use store::Store;

pub const TCP_PORT: u16 = 47800;
pub const UDP_PORT: u16 = 47801;
pub const CHUNK: usize = 512 * 1024;

/// Tipos de trama.
pub mod t {
    pub const HELLO: u8 = 1;
    pub const AUTH: u8 = 2;
    pub const AUTH_OK: u8 = 3;
    pub const PAIR_SECRET: u8 = 4;
    pub const OFFER: u8 = 5;
    pub const ACCEPT: u8 = 6;
    pub const FILE_START: u8 = 7;
    pub const DATA: u8 = 8;
    pub const FILE_END: u8 = 9;
    pub const DONE: u8 = 10;
    pub const ERROR: u8 = 12;
}

pub type Result<T> = std::result::Result<T, Error>;

#[derive(Debug)]
pub enum Error {
    Io(std::io::Error),
    Protocol(String),
    Cancelled,
    /// La conexión era un sondeo: ya se respondió y no hay nada más que hacer (no es un fallo).
    Probe,
}

impl std::fmt::Display for Error {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Error::Io(e) => write!(f, "{e}"),
            Error::Protocol(m) => write!(f, "{m}"),
            Error::Cancelled => write!(f, "Cancelado"),
            Error::Probe => write!(f, "Sondeo"),
        }
    }
}

impl std::error::Error for Error {}

impl From<std::io::Error> for Error {
    fn from(e: std::io::Error) -> Self {
        Error::Io(e)
    }
}

pub(crate) fn proto<T>(msg: impl Into<String>) -> Result<T> {
    Err(Error::Protocol(msg.into()))
}
