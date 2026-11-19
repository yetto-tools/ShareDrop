// Única frontera con el backend de Tauri: el resto de la interfaz nunca toca `window.__TAURI__`.
// Así los componentes se pueden probar con un `api` falso.

/** Eventos que emite el backend (ver desktop/app/src/main.rs). */
export const EVENTS = Object.freeze({
  DEVICES: "devices",
  TRANSFER: "transfer",
  SHOW_CODE: "show-code",
  HIDE_CODE: "hide-code",
  ASK_CODE: "ask-code",
});

/**
 * @typedef {import("./types.js").AppInfo} AppInfo
 * @typedef {import("./types.js").Device} Device
 * @typedef {import("./types.js").Transfer} Transfer
 */

/**
 * @param {any} tauri  Objeto global `window.__TAURI__` (inyectado por `withGlobalTauri`).
 */
export function createApi(tauri) {
  const { invoke } = tauri.core;

  return {
    /** @returns {Promise<AppInfo>} */
    info: () => invoke("info"),
    /** @param {string} deviceId @param {string[]} paths */
    sendFiles: (deviceId, paths) => invoke("send_files", { deviceId, paths }),
    retryTransfer: (id) => invoke("retry_transfer", { id }),
    /** @param {string|null} code */
    answerCode: (code) => invoke("answer_code", { code }),
    /** @param {string|null} path @returns {Promise<AppInfo>} */
    setReceiveDir: (path) => invoke("set_receive_dir", { path }),
    /** @returns {Promise<"opened"|"revealed">} */
    openReceived: (path, reveal) => invoke("open_received", { path, reveal }),
    /** @returns {Promise<import("./types.js").LocalLink[]>} */
    networkStatus: () => invoke("network_status"),
    /** @returns {Promise<import("./types.js").HistoryEntry[]>} */
    history: () => invoke("history"),
    /** @returns {Promise<{adb_found: boolean, devices: number, unauthorized: number, linked: boolean}>} */
    usbStatus: () => invoke("usb_status"),
    /** @param {"wifi"|"hotspot"|"network"|"bluetooth"|"firewall"} kind */
    openSettings: (kind) => invoke("open_settings", { kind }),

    /** @returns {Promise<string[]|null>} */
    async pickFiles() {
      const r = await tauri.dialog.open({ multiple: true });
      return r ? (Array.isArray(r) ? r : [r]) : null;
    },
    /** @returns {Promise<string|null>} */
    pickFolder: () => tauri.dialog.open({ directory: true, multiple: false }),

    /**
     * Suscribe un manejador a un evento del backend.
     * @param {string} event
     * @param {(payload: any) => void} handler
     */
    on: (event, handler) => tauri.event.listen(event, (e) => handler(e.payload)),
    /** @param {(payload: any) => void} handler */
    onDragDrop: (handler) => tauri.webview.getCurrentWebview().onDragDropEvent((e) => handler(e.payload)),

    autostart: {
      isEnabled: () => tauri.autostart.isEnabled(),
      enable: () => tauri.autostart.enable(),
      disable: () => tauri.autostart.disable(),
    },
  };
}
