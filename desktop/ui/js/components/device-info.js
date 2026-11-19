// Ficha de información de un dispositivo: lo que se sabe de él y por dónde se conecta.

import { el } from "../dom.js";
import { deviceStatus, hardwareLine, isPreAlpha, mediumOf, osName } from "../view-models.js";

/** @typedef {import("../types.js").Device} Device */
/** @typedef {import("../types.js").LocalLink} LocalLink */

/**
 * Filas de la ficha. Pura (sin DOM) para poder probarla: cada fila es [etiqueta, valor].
 * @param {Device} d
 * @param {{ ownPlatform: string, links: LocalLink[] }} ctx
 * @returns {[string, string][]}
 */
export function infoRows(d, ctx) {
  /** @type {[string, string][]} */
  const rows = [["Sistema", d.info?.os || osName(d.platform)]];
  const hw = hardwareLine({ maker: d.info?.maker, model: d.info?.model });
  if (hw) rows.push(["Modelo", hw]);

  const status = deviceStatus(d.info);
  if (status?.battery) rows.push(["Batería", `${status.battery.level} %${status.battery.charging ? " · cargando" : ""}`]);
  if (status?.storage) rows.push(["Almacenamiento", status.storage]);

  const medium = mediumOf(d, ctx.links);
  if (medium) rows.push(["Conexión", medium.label]);
  rows.push(["Dirección", `${d.address}:${d.port}`]);
  rows.push(["Estado", d.paired ? "Emparejado" : "Sin emparejar"]);
  return rows;
}

/**
 * @param {object} deps
 * @param {HTMLDialogElement} deps.dialog
 * @param {HTMLElement} deps.title
 * @param {HTMLElement} deps.list      <ul> donde se escriben las filas.
 * @param {HTMLElement} deps.note      Aviso de Pre-alpha (se oculta si no aplica).
 * @param {HTMLElement} deps.closeButton
 * @param {() => { ownPlatform: string, links: LocalLink[] }} deps.context
 */
export function createDeviceInfo({ dialog, title, list, note, closeButton, context }) {
  closeButton.addEventListener("click", () => dialog.close());
  dialog.addEventListener("click", (e) => e.target === dialog && dialog.close());

  return {
    /** @param {Device} d */
    show(d) {
      const ctx = context();
      title.textContent = d.name;
      list.replaceChildren(
        ...infoRows(d, ctx).map(([label, value]) =>
          el("li", { cls: "kv" }, el("span", { cls: "k", text: label }), el("span", { cls: "v", text: value })),
        ),
      );
      note.hidden = !(ctx.ownPlatform && isPreAlpha(ctx.ownPlatform, d));
      dialog.showModal();
    },
  };
}
