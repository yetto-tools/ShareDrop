// Arrastrar archivos desde el sistema y soltarlos sobre un dispositivo.
// Tauri entrega los eventos de arrastre con rutas reales y posición en píxeles físicos.

const OVER_CLASS = "over";

/**
 * @param {object} deps
 * @param {HTMLElement} deps.hint  Cartel "Suelta los archivos sobre un dispositivo".
 * @param {(x: number, y: number) => ({ id: string, node: Element } | null)} deps.deviceAt
 * @param {(deviceId: string, paths: string[]) => void} deps.onDrop
 */
export function createDropZone({ hint, deviceAt, onDrop }) {
  /** @type {Element | null} */
  let highlighted = null;

  /** @param {Element | null} node */
  function highlight(node) {
    if (node === highlighted) return;
    highlighted?.classList.remove(OVER_CLASS);
    node?.classList.add(OVER_CLASS);
    highlighted = node;
  }

  return {
    /**
     * Manejador de los eventos de arrastre de Tauri.
     * @param {{ type: "enter"|"over"|"drop"|"leave", position?: {x: number, y: number}, paths?: string[] }} payload
     */
    handle(payload) {
      if (payload.type === "leave") {
        highlight(null);
        hint.hidden = true;
        return;
      }
      const target = payload.position ? deviceAt(payload.position.x, payload.position.y) : null;

      if (payload.type === "drop") {
        highlight(null);
        hint.hidden = true;
        if (target && payload.paths?.length) onDrop(target.id, payload.paths);
        return;
      }
      highlight(target?.node ?? null);
      hint.hidden = Boolean(target);
    },
  };
}
