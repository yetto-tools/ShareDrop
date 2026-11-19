// Pantalla "Conexión": estado (mismo texto que en el teléfono) y acciones para habilitar un medio,
// más el aviso breve de Inicio cuando no hay dispositivos a la vista.

import { chevron, el, onActivate, svg } from "../dom.js";
import { connectionSummary, usbLinkText } from "../view-models.js";

/** @typedef {import("../types.js").Device} Device */
/** @typedef {import("../types.js").LocalLink} LocalLink */

/** Iconos de línea (viewBox 24) de cada tipo de conexión. */
const GLYPHS = {
  wifi: ["M3 9a13 13 0 0 1 18 0", "M6 12.5a8.5 8.5 0 0 1 12 0", "M9 16a4 4 0 0 1 6 0", "M12 19.2h.01"],
  usb: ["M3 12h17", "M9 12l3-6h3", "M11 12l3 5h2"],
  hotspot: ["M12 12h.01", "M8.5 8.5a5 5 0 0 0 0 7", "M15.5 8.5a5 5 0 0 1 0 7", "M5.8 5.8a9 9 0 0 0 0 12.4", "M18.2 5.8a9 9 0 0 1 0 12.4"],
  shield: ["M12 3l8 3v6c0 4.5-3.2 7.6-8 9-4.8-1.4-8-4.5-8-9V6z", "M8.5 12l2.5 2.5 4.5-5"],
};

/**
 * Opciones para conectarse, con el mismo icono y color que en el teléfono. En Windows abren los
 * ajustes del sistema; en otros sistemas, su equivalente.
 * @type {{ kind: "wifi"|"hotspot"|"network"|"firewall", title: string, detail: string, color: string, icon: string[] }[]}
 */
const ACTIONS = [
  { kind: "wifi", title: "Wi-Fi del router", detail: "Conéctate al mismo Wi-Fi (router) que el teléfono", color: "#007aff", icon: GLYPHS.wifi },
  { kind: "network", id: "usb", title: "Cable USB / Ethernet", detail: "Conecta el cable y activa la depuración USB en el teléfono", color: "#ff9500", icon: GLYPHS.usb },
  { kind: "hotspot", title: "Zona Wi-Fi móvil de esta PC", detail: "El teléfono se une a la red de la PC con una clave", color: "#34c759", icon: GLYPHS.hotspot },
  { kind: "firewall", title: "Firewall", detail: "Si el teléfono no ve esta PC, permite ShareDrop en redes privadas", color: "#5856d6", icon: GLYPHS.shield },
];

/**
 * @param {object} deps
 * @param {HTMLElement} deps.status      <ul> con la tarjeta de estado.
 * @param {HTMLElement} deps.actions     <ul> con las acciones.
 * @param {HTMLElement} deps.hint        Aviso de Inicio (fila que lleva a esta pantalla).
 * @param {HTMLElement} deps.hintTitle
 * @param {() => void} deps.onOpenPage   Se tocó el aviso.
 * @param {(kind: string) => Promise<void>} deps.openSettings
 * @param {(message: string) => void} deps.notify
 */
export function createConnectionPage({ status, actions, hint, hintTitle, onOpenPage, openSettings, notify }) {
  /** @type {LocalLink[]} */
  let links = [];
  /** @type {Device[]} */
  let devices = [];
  /** Partes de la fila "Cable USB" que cambian con el estado del enlace. */
  let usbDetail = null;
  let usbBadge = null;

  /** Ficha de color con el icono del medio. @param {{ color: string, icon: string[] }} a */
  function tile(a) {
    const t = el("span", { cls: "tile" }, svg(a.icon));
    t.style.background = a.color;
    return t;
  }

  function render() {
    const summary = connectionSummary(links, devices);

    // Tarjeta de estado
    const colorClass = summary.tone === "ok" ? "ok" : summary.tone === "error" ? "err" : "";
    status.replaceChildren(
      el("li", { cls: "row stack" }, el("div", { cls: "body" },
        el("div", { cls: `title ${colorClass}`, text: summary.title }),
        el("div", { cls: "sub wrap", text: summary.detail }),
      )),
    );

    // Aviso en Inicio: solo cuando no hay nadie a la vista
    hint.hidden = devices.length > 0;
    hintTitle.textContent = summary.anyLink ? "No veo otros equipos" : "Sin conexión";
    hintTitle.classList.toggle("err", !summary.anyLink);
  }

  // Las acciones no cambian: se construyen una vez.
  actions.replaceChildren(
    ...ACTIONS.map((a) => {
      const detail = el("div", { cls: "sub wrap", text: a.detail });
      const badge = el("span", { cls: "active-badge", text: "Activo", attrs: { hidden: "" } });
      const row = el(
        "li",
        { cls: "row media tappable", attrs: { tabindex: "0", role: "button" } },
        tile(a),
        el("div", { cls: "body" }, el("div", { cls: "title", text: a.title }), detail),
        badge,
        chevron(),
      );
      if (a.id === "usb") {
        usbDetail = detail;
        usbBadge = badge;
      }
      onActivate(row, async () => {
        try {
          await openSettings(a.kind);
        } catch (e) {
          notify(String(e));
        }
      });
      return row;
    }),
  );

  onActivate(hint, onOpenPage);

  return {
    /** @param {LocalLink[]} next */
    setLinks(next) {
      links = next;
      render();
    },
    /** Estado del enlace USB por cable (adb). @param {{adb_found: boolean, devices: number, unauthorized: number, linked: boolean}} status */
    setUsb(status) {
      if (!usbDetail || !usbBadge) return;
      const { text, active } = usbLinkText(status);
      usbDetail.textContent = text;
      usbBadge.hidden = !active;
    },
    /** @param {Device[]} next */
    setDevices(next) {
      devices = next;
      render();
    },
  };
}
