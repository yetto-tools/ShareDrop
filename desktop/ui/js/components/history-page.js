// Pantalla Historial: transferencias terminadas, guardadas en disco. Cada pestaña (Todo, Recibidos,
// Enviados) muestra como máximo las 20 más recientes.

import { createTransferRow, paintTransferRow } from "./transfer-row.js";
import { entryAsTransfer, visibleHistory, whenLabel } from "../view-models.js";

/** @typedef {import("../types.js").HistoryEntry} HistoryEntry */
/** @typedef {import("../types.js").Transfer} Transfer */

const EMPTY_TEXT = {
  all: "Aún no hay transferencias.",
  in: "Aún no has recibido archivos.",
  out: "Aún no has enviado archivos.",
};

/**
 * @param {object} deps
 * @param {HTMLElement} deps.list     <ul> de filas.
 * @param {HTMLElement} deps.filter   Control segmentado "Todo / Recibidos / Enviados".
 * @param {HTMLElement} deps.empty    Texto que se muestra cuando la pestaña no tiene elementos.
 * @param {(t: Transfer) => void} deps.onOpen  Se tocó un archivo recibido que sigue en su sitio.
 * @param {() => number} [deps.now]   Reloj (inyectable para pruebas).
 */
export function createHistoryPage({ list, filter, empty, onOpen, now = Date.now }) {
  /** @type {HistoryEntry[]} */
  let entries = [];
  /** @type {"all" | "in" | "out"} */
  let current = "all";
  /** @type {Map<string, Transfer>} */
  const byUid = new Map();

  function render() {
    byUid.clear();
    const shown = visibleHistory(entries, current);
    list.replaceChildren(
      ...shown.map((e) => {
        const t = entryAsTransfer(e);
        byUid.set(e.uid, t);
        const li = createTransferRow(e.uid);
        paintTransferRow(li, t, ` · ${whenLabel(e.at, now())}`);
        return li;
      }),
    );
    empty.hidden = shown.length > 0;
    empty.textContent = EMPTY_TEXT[current];
    list.hidden = shown.length === 0;
  }

  /** @param {Event} e */
  function rowOf(e) {
    const li = /** @type {Element} */ (e.target).closest("li.openable");
    return li ? byUid.get(li.getAttribute("data-id") ?? "") ?? null : null;
  }

  list.addEventListener("click", (e) => {
    const t = rowOf(e);
    if (t) onOpen(t);
  });
  list.addEventListener("keydown", (e) => {
    if (e.key !== "Enter" && e.key !== " ") return;
    const t = rowOf(e);
    if (t) {
      e.preventDefault();
      onOpen(t);
    }
  });

  filter.addEventListener("click", (e) => {
    const button = /** @type {Element} */ (e.target).closest("button[data-filter]");
    if (!button) return;
    for (const b of filter.querySelectorAll("button")) b.setAttribute("aria-checked", String(b === button));
    current = /** @type {"all" | "in" | "out"} */ (button.getAttribute("data-filter") ?? "all");
    render();
  });

  return {
    /** @param {HistoryEntry[]} next */
    setEntries(next) {
      entries = next;
      render();
    },
  };
}
