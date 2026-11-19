// Lista de transferencias de Inicio: una fila por archivo. Las filas se reutilizan (no se reconstruyen)
// en cada actualización, para no perder foco ni parpadear.

import { createTransferRow, paintTransferRow } from "./transfer-row.js";

/** @typedef {import("../types.js").Transfer} Transfer */

/**
 * @param {object} deps
 * @param {HTMLElement} deps.section  Sección completa (se muestra con la primera transferencia).
 * @param {HTMLElement} deps.list     <ul> de filas.
 * @param {HTMLElement} deps.filter   Control segmentado "Todo / Recibidos / Enviados".
 * @param {(t: Transfer) => void} deps.onOpen  Se tocó un archivo recibido.
 */
export function createTransferList({ section, list, filter, onOpen }) {
  /** Indexadas por id en texto: así coinciden con `data-id` del DOM. @type {Map<string, { li: HTMLElement, transfer: Transfer }>} */
  const rows = new Map();

  /** Crea o actualiza la fila de una transferencia. @param {Transfer} t */
  function upsert(t) {
    const key = String(t.id);
    let entry = rows.get(key);
    if (!entry) {
      entry = { li: createTransferRow(key), transfer: t };
      rows.set(key, entry);
      list.prepend(entry.li);
      section.hidden = false;
    }
    entry.transfer = t;
    paintTransferRow(entry.li, t);
  }

  /** @param {Event} e */
  function rowOf(e) {
    const li = /** @type {Element} */ (e.target).closest("li.openable");
    return li ? rows.get(li.getAttribute("data-id") ?? "") ?? null : null;
  }

  list.addEventListener("click", (e) => {
    const entry = rowOf(e);
    if (entry) onOpen(entry.transfer);
  });
  list.addEventListener("keydown", (e) => {
    if (e.key !== "Enter" && e.key !== " ") return;
    const entry = rowOf(e);
    if (entry) {
      e.preventDefault();
      onOpen(entry.transfer);
    }
  });

  // Filtro: solo cambia un atributo; el CSS oculta las filas de la otra dirección.
  filter.addEventListener("click", (e) => {
    const button = /** @type {Element} */ (e.target).closest("button[data-filter]");
    if (!button) return;
    for (const b of filter.querySelectorAll("button")) b.setAttribute("aria-checked", String(b === button));
    list.dataset.filter = button.getAttribute("data-filter") ?? "all";
  });

  return { upsert };
}
