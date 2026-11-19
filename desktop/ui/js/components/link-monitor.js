// Mantiene al día el estado de la red de esta PC y del enlace USB por cable (adb), y avisa a quien lo
// necesite (aviso de Inicio, pantalla Conexión, etiquetas de los dispositivos). Consulta cada pocos
// segundos: son llamadas locales y baratas.

const POLL_MS = 4000;

/** @typedef {{ adb_found: boolean, devices: number, unauthorized: number, linked: boolean }} UsbStatus */
/** @typedef {{ links: import("../types.js").LocalLink[], usb: UsbStatus }} LinkState */

/** @type {UsbStatus} */
const NO_USB = { adb_found: false, devices: 0, unauthorized: 0, linked: false };

/**
 * @param {object} deps
 * @param {() => Promise<import("../types.js").LocalLink[]>} deps.networkStatus
 * @param {() => Promise<UsbStatus>} deps.usbStatus
 */
export function createLinkMonitor({ networkStatus, usbStatus }) {
  /** @type {LinkState} */
  let state = { links: [], usb: NO_USB };
  /** @type {Set<(state: LinkState) => void>} */
  const listeners = new Set();

  async function refresh() {
    // Sin información, se muestra como "sin conexión" en lugar de romper la pantalla.
    const [links, usb] = await Promise.all([networkStatus().catch(() => []), usbStatus().catch(() => NO_USB)]);
    state = { links, usb };
    listeners.forEach((fn) => fn(state));
  }

  return {
    /** Empieza a consultar. */
    start() {
      refresh();
      setInterval(refresh, POLL_MS);
    },
    refresh,
    /** @param {(state: LinkState) => void} fn */
    subscribe(fn) {
      listeners.add(fn);
      fn(state);
      return () => listeners.delete(fn);
    },
    get state() {
      return state;
    },
  };
}
