// Fila de una transferencia (anillo, etiqueta de dirección, equipo y tamaño). Se comparte entre la
// lista de Inicio (en curso) y el historial (terminadas), para que se vean idénticas.

import { chevron, el, ICONS, svg, svgChild } from "../dom.js";
import { directionOf, isOpenable, transferProgress, transferStatus } from "../view-models.js";

/** @typedef {import("../types.js").Transfer} Transfer */

/** Circunferencia del anillo de progreso (radio 15). */
export const RING_LENGTH = 2 * Math.PI * 15;

function ringSvg() {
  const s = svg([], { viewBox: "0 0 34 34" });
  for (const cls of ["track", "prog"]) {
    s.append(svgChild("circle", { cx: 17, cy: 17, r: 15, class: cls }));
  }
  s.querySelector(".prog")?.setAttribute("stroke-dasharray", String(RING_LENGTH));
  return s;
}

/** Crea una fila vacía; se rellena con [paintTransferRow]. @param {string} id */
export function createTransferRow(id) {
  const ring = el("div", { cls: "ring" }, ringSvg(), svg(ICONS.down, { cls: "dir" }));
  const sub = el("div", { cls: "sub" }, el("span", { cls: "chip" }), el("span", { cls: "text" }));
  const body = el("div", { cls: "body" }, el("div", { cls: "title" }), sub);
  return el("li", { cls: "row", attrs: { "data-id": id } }, ring, body);
}

/**
 * Pinta (o actualiza) una fila con los datos de una transferencia.
 * @param {HTMLElement} li
 * @param {Transfer} t
 * @param {string} [suffix]  Texto que se añade al final de la línea de estado (p. ej. la fecha).
 */
export function paintTransferRow(li, t, suffix = "") {
  const ring = /** @type {HTMLElement} */ (li.querySelector(".ring"));
  const title = /** @type {HTMLElement} */ (li.querySelector(".title"));
  const sub = /** @type {HTMLElement} */ (li.querySelector(".sub"));
  const chip = /** @type {HTMLElement} */ (li.querySelector(".chip"));
  const text = /** @type {HTMLElement} */ (li.querySelector(".text"));
  const dir = directionOf(t);

  li.dataset.dir = dir.key;
  title.textContent = t.file || "…";
  chip.className = `chip ${dir.key}${t.state === "failed" ? " fail" : ""}`;
  chip.textContent = `${dir.symbol} ${dir.label}`;
  ring.className = `ring ${dir.key}${t.state === "finished" ? " done" : t.state === "failed" ? " fail" : ""}`;
  ring.querySelector(".dir")?.replaceChildren(...(t.incoming ? ICONS.down : ICONS.up).map((d) => svgChild("path", { d })));
  /** @type {SVGElement} */ (ring.querySelector(".prog")).style.strokeDashoffset = String(RING_LENGTH * (1 - transferProgress(t)));

  const status = transferStatus(t);
  text.textContent = status.text + suffix;
  sub.className = `sub${status.tone === "error" ? " err" : ""}`;

  // Un archivo recibido y ya guardado se puede abrir.
  if (isOpenable(t) && !li.classList.contains("openable")) {
    li.classList.add("openable", "tappable");
    li.setAttribute("tabindex", "0");
    li.setAttribute("role", "button");
    li.setAttribute("aria-label", `Abrir ${t.file}`);
    li.append(chevron());
  }
}
