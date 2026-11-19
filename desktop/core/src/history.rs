//! Historial de transferencias terminadas, guardado en disco.
//!
//! Se conservan las últimas [`KEEP`] recibidas y las últimas [`KEEP`] enviadas; así cada pestaña de
//! la interfaz (Todo, Recibidos, Enviados) puede mostrar hasta 20 elementos.

use crate::engine::{TransferInfo, TransferState};
use serde::{Deserialize, Serialize};
use std::path::PathBuf;
use std::sync::Mutex;
use std::time::{SystemTime, UNIX_EPOCH};

/// Cuántas transferencias se conservan de cada tipo (recibidas / enviadas).
pub const KEEP: usize = 20;

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq)]
pub struct HistoryEntry {
    /// Identificador único: sesión + id de la transferencia. Los ids se reinician en cada arranque.
    pub uid: String,
    pub peer: String,
    pub file: String,
    /// Tamaño en bytes (-1 si se desconoce).
    pub size: i64,
    pub incoming: bool,
    pub ok: bool,
    pub error: Option<String>,
    /// Dónde quedó guardado un archivo recibido.
    pub path: Option<String>,
    /// Momento en que terminó, en milisegundos desde 1970.
    pub at: u64,
}

pub struct History {
    path: PathBuf,
    items: Mutex<Vec<HistoryEntry>>,
}

fn now_ms() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_millis() as u64).unwrap_or(0)
}

impl History {
    pub fn open(dir: PathBuf) -> Self {
        let path = dir.join("history.json");
        let items = std::fs::read_to_string(&path)
            .ok()
            .and_then(|s| serde_json::from_str::<Vec<HistoryEntry>>(&s).ok())
            .unwrap_or_default();
        let h = History { path, items: Mutex::new(items) };
        h.trim();
        h
    }

    /// Anota una transferencia terminada (con éxito o con error). Las que siguen en curso se ignoran.
    /// Si ya estaba anotada (misma sesión e id) se actualiza en lugar de duplicarla.
    pub fn record(&self, t: &TransferInfo, session: u64) {
        if t.state == TransferState::Active {
            return;
        }
        let entry = HistoryEntry {
            uid: format!("{session}-{}", t.id),
            peer: t.peer.clone(),
            file: t.file.clone(),
            size: t.total,
            incoming: t.incoming,
            ok: t.state == TransferState::Finished,
            error: t.error.clone(),
            path: t.path.clone(),
            at: now_ms(),
        };
        {
            let mut items = self.items.lock().unwrap();
            match items.iter().position(|e| e.uid == entry.uid) {
                Some(i) => items[i] = HistoryEntry { at: items[i].at, ..entry },
                None => items.push(entry),
            }
        }
        self.trim();
        self.save();
    }

    /// Entradas más recientes primero.
    pub fn list(&self) -> Vec<HistoryEntry> {
        let mut v = self.items.lock().unwrap().clone();
        v.sort_by(|a, b| b.at.cmp(&a.at));
        v
    }

    /// Conserva solo las últimas `KEEP` de cada dirección.
    fn trim(&self) {
        let mut items = self.items.lock().unwrap();
        items.sort_by_key(|e| e.at);
        for incoming in [true, false] {
            let mut count = items.iter().filter(|e| e.incoming == incoming).count();
            let mut i = 0;
            while count > KEEP && i < items.len() {
                if items[i].incoming == incoming {
                    items.remove(i);
                    count -= 1;
                } else {
                    i += 1;
                }
            }
        }
    }

    fn save(&self) {
        let json = serde_json::to_string(&*self.items.lock().unwrap()).unwrap_or_else(|_| "[]".into());
        let _ = std::fs::write(&self.path, json);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn info(id: u64, incoming: bool, state: TransferState) -> TransferInfo {
        TransferInfo {
            id,
            peer: "Redmi".into(),
            file: format!("f{id}.txt"),
            done: 10,
            total: 10,
            incoming,
            state,
            error: None,
            path: None,
        }
    }

    fn temp() -> PathBuf {
        // Las pruebas corren en paralelo: cada una necesita su propia carpeta.
        static NEXT: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);
        let n = NEXT.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
        let d = std::env::temp_dir().join(format!("sharedrop-hist-{}-{}-{}", std::process::id(), now_ms(), n));
        std::fs::create_dir_all(&d).unwrap();
        d
    }

    #[test]
    fn ignores_active_transfers_and_dedupes_by_id() {
        let h = History::open(temp());
        h.record(&info(1, true, TransferState::Active), 7);
        assert!(h.list().is_empty());
        h.record(&info(1, true, TransferState::Finished), 7);
        h.record(&info(1, true, TransferState::Finished), 7); // el mismo archivo emite dos veces
        assert_eq!(h.list().len(), 1);
        // Otra sesión con el mismo id es otra transferencia.
        h.record(&info(1, true, TransferState::Finished), 8);
        assert_eq!(h.list().len(), 2);
    }

    #[test]
    fn keeps_the_last_twenty_of_each_direction() {
        let h = History::open(temp());
        for id in 1..=30 {
            h.record(&info(id, true, TransferState::Finished), 1);
            h.record(&info(1000 + id, false, TransferState::Finished), 1);
            std::thread::sleep(std::time::Duration::from_millis(2));
        }
        let all = h.list();
        assert_eq!(all.iter().filter(|e| e.incoming).count(), KEEP);
        assert_eq!(all.iter().filter(|e| !e.incoming).count(), KEEP);
        assert_eq!(all.len(), 2 * KEEP);
        // Se quedan las más recientes y se van las más antiguas.
        assert!(all.iter().any(|e| e.file == "f30.txt"));
        assert!(!all.iter().any(|e| e.file == "f1.txt"));
        assert!(all.windows(2).all(|w| w[0].at >= w[1].at), "más reciente primero");
    }

    #[test]
    fn survives_a_restart() {
        let dir = temp();
        let h = History::open(dir.clone());
        h.record(&info(5, false, TransferState::Failed), 1);
        let again = History::open(dir);
        let list = again.list();
        assert_eq!(list.len(), 1);
        assert!(!list[0].ok);
    }
}
