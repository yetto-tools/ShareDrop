// Tipos compartidos (solo JSDoc: no hay código en tiempo de ejecución).
// Reflejan lo que envía el núcleo en Rust (desktop/core) a través de Tauri.

/**
 * Datos opcionales que un equipo anuncia sobre sí mismo (el teléfono los manda todos).
 * @typedef {object} DeviceInfo
 * @property {string} [model]    Código de modelo, p. ej. "24094RAD4G".
 * @property {string} [maker]    Fabricante.
 * @property {string} [os]       Sistema operativo, p. ej. "Android 16".
 * @property {string} [link]     Medio por el que el teléfono dice estar conectado: "usb", "wifi" o "hotspot".
 * @property {number} [battery]  Porcentaje de batería.
 * @property {boolean} [charging]
 * @property {number} [free]     Bytes libres de almacenamiento.
 * @property {number} [total]    Bytes totales de almacenamiento.
 */

/**
 * @typedef {object} Device
 * @property {string} id
 * @property {string} name
 * @property {string} platform   "android", "windows", "linux"…
 * @property {string} address
 * @property {number} port
 * @property {boolean} paired
 * @property {DeviceInfo} info
 * @property {string|null} [link]  "usb-adb" si se llegó por el túnel USB; null si fue por la red.
 */

/** @typedef {"active" | "finished" | "failed"} TransferState */

/**
 * @typedef {object} Transfer
 * @property {number|string} id
 * @property {string} peer
 * @property {string} file
 * @property {number} done       Bytes transferidos.
 * @property {number} total      Bytes totales (≤ 0 si no se sabe).
 * @property {boolean} incoming
 * @property {TransferState} state
 * @property {string|null} [error]
 * @property {string|null} [path]  Ruta final de un archivo recibido, ya guardado.
 */

/**
 * @typedef {object} AppInfo
 * @property {string} name
 * @property {string} platform   Sistema de esta PC.
 * @property {string} version    Versión de la app.
 * @property {string} receive_dir
 * @property {boolean} is_default_dir
 * @property {Device[]} devices
 */

/**
 * Interfaz de red de esta PC con IPv4.
 * @typedef {object} LocalLink
 * @property {string} name
 * @property {string} address
 * @property {string} netmask
 * @property {boolean} virtual_adapter  WSL, Hyper-V, VPN…: no sirve para llegar a un teléfono.
 */


/**
 * Transferencia terminada, guardada en el historial (en disco).
 * @typedef {object} HistoryEntry
 * @property {string} uid
 * @property {string} peer
 * @property {string} file
 * @property {number} size       Bytes (-1 si se desconoce).
 * @property {boolean} incoming
 * @property {boolean} ok
 * @property {string|null} [error]
 * @property {string|null} [path]  Dónde quedó guardado un archivo recibido.
 * @property {number} at         Cuándo terminó, en milisegundos desde 1970.
 */

export {};
