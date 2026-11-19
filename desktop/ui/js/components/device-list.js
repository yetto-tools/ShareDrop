// Lista de dispositivos cercanos. Usa delegación de eventos: un solo manejador para todas las filas.

import { el, ICONS, OS_ICONS, svg } from "../dom.js";
import { deviceStatus, hardwareLine, isPreAlpha, mediumOf, osKind, pairingLine } from "../view-models.js";

/** @typedef {import("../types.js").Device} Device */
/** @typedef {import("../types.js").LocalLink} LocalLink */

/**
 * @param {object} deps
 * @param {HTMLElement} deps.list        <ul> que contiene las filas.
 * @param {HTMLElement} deps.emptyState  Tarjeta de "Buscando dispositivos…".
 * @param {(deviceId: string) => void} deps.onSend  Se pidió enviar archivos a este dispositivo.
 * @param {(device: Device) => void} deps.onInfo    Se pidió la ficha de información del dispositivo.
 */
export function createDeviceList({ list, emptyState, onSend, onInfo }) {
  /** @type {Device[]} */
  let devices = [];
  /** Plataforma de esta PC y sus interfaces de red: sirven para decidir etiquetas y medio. */
  let context = { ownPlatform: "", links: /** @type {LocalLink[]} */ ([]) };

  function paint() {
    list.replaceChildren(...devices.map(row));
    emptyState.hidden = devices.length > 0;
  }

  /** @param {Device} d */
  function row(d) {
    const body = el("div", { cls: "body" }, el("div", { cls: "title", text: d.name }));

    const hardware = hardwareLine(d.info);
    if (hardware) body.append(el("div", { cls: "sub", text: hardware }));
    const status = statusLine(d);
    if (status) body.append(status);
    body.append(el("div", { cls: "sub", text: pairingLine(d) }));
    // Debajo de "Emparejado · dirección": por qué medio se conecta y si la pareja aún es Pre-alpha.
    const tags = tagsLine(d);
    if (tags) body.append(tags);

    // Símbolo "info.circle" de iOS: círculo de línea fina con un punto y un trazo, sin fondo.
    const info = el(
      "button",
      { cls: "info", attrs: { type: "button", "aria-label": `Información de ${d.name}`, "data-info": d.id } },
      svg(["M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18z", "M12 11v5.2", "M12 7.8h.01"]),
    );
    const send = el("button", { text: "Enviar", attrs: { tabindex: "-1" } });
    return el(
      "li",
      { cls: "row device tappable", attrs: { "data-id": d.id, tabindex: "0", role: "button", "aria-label": `Enviar archivos a ${d.name}` } },
      el("div", { cls: `avatar ${osKind(d.platform)}` }, osIcon(d)),
      body,
      info,
      send,
    );
  }

  /** Icono del sistema del dispositivo (Android, Windows, Linux); si no se conoce, un portátil. @param {Device} d */
  function osIcon(d) {
    const kind = osKind(d.platform);
    return kind in OS_ICONS
      ? svg([OS_ICONS[/** @type {keyof typeof OS_ICONS} */ (kind)]], { cls: "os" })
      : svg(ICONS.laptop);
  }

  /** Línea de etiquetas: medio de conexión (USB, Wi-Fi…) y "Pre-alpha" si procede; null si no hay ninguna. @param {Device} d */
  function tagsLine(d) {
    const tags = [];
    const medium = mediumOf(d, context.links);
    if (medium) tags.push(el("span", { cls: `chip ${medium.key}`, text: medium.label }));
    if (context.ownPlatform && isPreAlpha(context.ownPlatform, d)) {
      tags.push(el("span", { cls: "chip pre", text: "Pre-alpha", attrs: { title: "Entre dos equipos del mismo tipo aún está en pruebas" } }));
    }
    return tags.length ? el("div", { cls: "sub tags" }, ...tags) : null;
  }

  /** Línea con batería y almacenamiento libre, si el dispositivo los anuncia. @param {Device} d */
  function statusLine(d) {
    const status = deviceStatus(d.info);
    if (!status) return null;
    const parts = [];
    if (status.battery) {
      const { level, charging, low } = status.battery;
      const fill = el("i");
      fill.style.transform = `scaleX(${Math.max(0.04, level / 100)})`;
      const glyph = el("span", { cls: `batt${charging ? " charging" : low ? " low" : ""}` }, fill);
      parts.push(el("span", { cls: "battery" }, glyph, document.createTextNode(`${level} %${charging ? " · cargando" : ""}`)));
    }
    if (status.storage) parts.push(document.createTextNode(status.storage));

    const line = el("div", { cls: "sub status" });
    parts.forEach((p, i) => {
      if (i) line.append(document.createTextNode(" · "));
      line.append(p);
    });
    return line;
  }

  /** Id del dispositivo de una fila (o null) a partir de un elemento dentro de ella. @param {Element|null} node */
  function idOf(node) {
    return node?.closest?.(".device")?.getAttribute("data-id") ?? null;
  }

  list.addEventListener("click", (e) => {
    const target = /** @type {Element} */ (e.target);
    const id = idOf(target);
    if (!id) return;
    // El botón "i" abre la ficha y no inicia un envío.
    if (target.closest("button.info")) {
      const d = devices.find((x) => x.id === id);
      if (d) onInfo(d);
      return;
    }
    onSend(id);
  });
  list.addEventListener("keydown", (e) => {
    if (e.key !== "Enter" && e.key !== " ") return;
    const target = /** @type {Element} */ (e.target);
    if (target.closest("button.info")) return; // el botón maneja su propio Enter
    const id = idOf(target);
    if (id) {
      e.preventDefault();
      onSend(id);
    }
  });

  return {
    /** @param {Device[]} next */
    render(next) {
      devices = next;
      paint();
    },
    /** Datos que cambian las etiquetas sin cambiar los dispositivos. @param {Partial<typeof context>} next */
    setContext(next) {
      context = { ...context, ...next };
      paint();
    },
    get context() {
      return context;
    },
    /**
     * Fila de dispositivo bajo unas coordenadas en píxeles físicos de la ventana (para soltar archivos).
     * @returns {{ id: string, node: Element } | null}
     */
    deviceAt(x, y) {
      const ratio = window.devicePixelRatio || 1;
      const node = document.elementFromPoint(x / ratio, y / ratio)?.closest(".device");
      const id = idOf(node ?? null);
      return node && id ? { id, node } : null;
    },
  };
}
