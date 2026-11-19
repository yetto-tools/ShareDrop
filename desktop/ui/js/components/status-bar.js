// Barra de estado inferior: estado de la conexión, actividad de transferencias y versión.
// Solo muestra; no tiene acciones. Recibe los textos ya calculados (ver view-models.js).

/**
 * @param {object} deps
 * @param {HTMLElement} deps.dot            Punto de color del estado.
 * @param {HTMLElement} deps.linkText
 * @param {HTMLElement} deps.devices
 * @param {HTMLElement} deps.transfers
 * @param {HTMLElement} deps.version
 * @param {HTMLElement} deps.progress       Hilo de progreso (se escala en horizontal).
 */
export function createStatusBar({ dot, linkText, devices, transfers, version, progress }) {
  return {
    /** @param {{ tone: "ok" | "warn" | "error", text: string, devices: string }} line */
    setConnection(line) {
      dot.className = `dot ${line.tone}`;
      linkText.textContent = line.text;
      devices.textContent = line.devices;
    },
    /** @param {{ text: string, progress: number, active: number }} activity */
    setActivity(activity) {
      transfers.textContent = activity.text;
      const bar = /** @type {HTMLElement} */ (progress.firstElementChild);
      bar.style.transform = `scaleX(${activity.active > 0 ? activity.progress : 0})`;
    },
    /** @param {string} v */
    setVersion(v) {
      version.textContent = `ShareDrop ${v} · pre-alpha`;
    },
  };
}
