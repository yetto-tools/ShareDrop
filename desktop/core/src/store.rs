//! Identidad local y secretos de los equipos emparejados.
//! Mismo formato que la versión de Windows en C# (`store.json`, claves en PascalCase).

use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::Mutex;

#[derive(Serialize, Deserialize)]
#[serde(rename_all = "PascalCase")]
struct Data {
    id: String,
    name: String,
    #[serde(default)]
    peers: HashMap<String, String>,
    /// Carpeta de destino elegida por el usuario; `None` = la predeterminada.
    #[serde(default)]
    receive_dir: Option<String>,
}

pub struct Store {
    path: PathBuf,
    data: Mutex<Data>,
}

impl Store {
    pub fn default_dir() -> PathBuf {
        dirs::config_dir().unwrap_or_else(|| PathBuf::from(".")).join("ShareDrop")
    }

    pub fn open(dir: PathBuf) -> std::io::Result<Self> {
        std::fs::create_dir_all(&dir)?;
        let path = dir.join("store.json");
        let data = std::fs::read_to_string(&path)
            .ok()
            .and_then(|s| serde_json::from_str::<Data>(&s).ok())
            .unwrap_or_else(|| Data {
                id: uuid::Uuid::new_v4().to_string(),
                name: hostname::get().ok().and_then(|h| h.into_string().ok()).unwrap_or_else(|| "Escritorio".into()),
                peers: HashMap::new(),
                receive_dir: None,
            });
        let s = Store { path, data: Mutex::new(data) };
        s.save()?;
        Ok(s)
    }

    /// Carpeta donde se guardan los datos de la app (identidad, emparejamientos, historial).
    pub fn data_dir(&self) -> PathBuf {
        self.path.parent().map(PathBuf::from).unwrap_or_else(|| PathBuf::from("."))
    }

    pub fn device_id(&self) -> String {
        self.data.lock().unwrap().id.clone()
    }

    pub fn device_name(&self) -> String {
        self.data.lock().unwrap().name.clone()
    }

    pub fn secret_for(&self, id: &str) -> Option<Vec<u8>> {
        self.data.lock().unwrap().peers.get(id).and_then(|h| hex::decode(h).ok())
    }

    pub fn is_paired(&self, id: &str) -> bool {
        self.secret_for(id).is_some()
    }

    pub fn save_peer(&self, id: &str, secret: &[u8]) -> std::io::Result<()> {
        self.data.lock().unwrap().peers.insert(id.to_string(), hex::encode(secret));
        self.save()
    }

    /// Carpeta de recepción elegida por el usuario, si la hay.
    pub fn receive_dir(&self) -> Option<PathBuf> {
        self.data.lock().unwrap().receive_dir.as_ref().map(PathBuf::from)
    }

    /// Guarda la carpeta de recepción (`None` restablece la predeterminada).
    pub fn set_receive_dir(&self, dir: Option<&std::path::Path>) -> std::io::Result<()> {
        self.data.lock().unwrap().receive_dir = dir.map(|d| d.to_string_lossy().into_owned());
        self.save()
    }

    /// Carpeta por defecto del sistema: `<Descargas>/ShareDrop`.
    pub fn default_receive_dir() -> PathBuf {
        dirs::download_dir().or_else(dirs::home_dir).unwrap_or_else(|| PathBuf::from(".")).join("ShareDrop")
    }

    fn save(&self) -> std::io::Result<()> {
        let json = serde_json::to_string(&*self.data.lock().unwrap()).expect("serializable");
        std::fs::write(&self.path, json)
    }
}
