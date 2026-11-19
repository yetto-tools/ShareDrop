// Ayudas mínimas para construir DOM sin `innerHTML` (así no hay inyección de HTML por nombres de
// archivo o de dispositivo, que vienen de la red).

const SVG_NS = "http://www.w3.org/2000/svg";

/**
 * Crea un elemento HTML.
 * @param {string} tag
 * @param {{ cls?: string, text?: string, attrs?: Record<string, string> }} [props]
 * @param {...(Node|null|undefined)} children
 */
export function el(tag, props = {}, ...children) {
  const node = document.createElement(tag);
  if (props.cls) node.className = props.cls;
  if (props.text !== undefined) node.textContent = props.text;
  for (const [k, v] of Object.entries(props.attrs ?? {})) node.setAttribute(k, v);
  node.append(...children.filter(Boolean));
  return node;
}

/**
 * Crea un SVG a partir de trazos (`d` de cada <path>).
 * @param {string[]} paths
 * @param {{ cls?: string, viewBox?: string }} [opts]
 */
export function svg(paths, { cls, viewBox = "0 0 24 24" } = {}) {
  const s = document.createElementNS(SVG_NS, "svg");
  s.setAttribute("viewBox", viewBox);
  s.setAttribute("aria-hidden", "true");
  if (cls) s.setAttribute("class", cls);
  s.append(...paths.map((d) => svgChild("path", { d })));
  return s;
}

/**
 * @param {string} tag  Etiqueta SVG ("path", "circle"…).
 * @param {Record<string, string|number>} attrs
 */
export function svgChild(tag, attrs) {
  const n = document.createElementNS(SVG_NS, tag);
  for (const [k, v] of Object.entries(attrs)) n.setAttribute(k, String(v));
  return n;
}

export const ICONS = Object.freeze({
  phone: ["M7 2h10a1 1 0 0 1 1 1v18a1 1 0 0 1-1 1H7a1 1 0 0 1-1-1V3a1 1 0 0 1 1-1z", "M11 19h2"],
  laptop: ["M4 5h16v11H4z", "M2 19h20"],
  down: ["M12 4v12", "M7 11l5 5 5-5"],
  up: ["M12 20V8", "M7 13l5-5 5 5"],
});

/**
 * Logotipos simplificados de cada sistema (viewBox 24, rellenos). Los ojos del robot y del pingüino
 * son huecos (regla evenodd).
 */
export const OS_ICONS = Object.freeze({
  android:
    "M6.2 9.3a5.8 5.8 0 0 1 11.6 0H6.2zM9.4 7.2a.7.7 0 1 0 0-1.4.7.7 0 0 0 0 1.4zm5.2 0a.7.7 0 1 0 0-1.4.7.7 0 0 0 0 1.4z" +
    "M6.2 10.2h11.6v7.1a1 1 0 0 1-1 1H7.2a1 1 0 0 1-1-1v-7.1zM3.4 10.2a1 1 0 0 1 2 0v4.4a1 1 0 0 1-2 0v-4.4zm15.2 0a1 1 0 0 1 2 0v4.4a1 1 0 0 1-2 0v-4.4z" +
    "M8.3 17.6h2.2v3.1a1.1 1.1 0 0 1-2.2 0v-3.1zm5.2 0h2.2v3.1a1.1 1.1 0 0 1-2.2 0v-3.1z" +
    "M8.1 3.2l-1-1.6.6-.4 1 1.6zM15.9 3.2l1-1.6-.6-.4-1 1.6z",
  windows: "M3 5.6l7.4-1v7H3zM11.5 4.4L21 3v8.6h-9.5zM3 12.5h7.4v7L3 18.5zM11.5 12.5H21V21l-9.5-1.4z",
  linux:
    "M12 2.5c-2.3 0-3.6 2.1-3.6 4.6 0 1.3-.5 2.3-1.4 3.7C5.9 12.5 5 14.4 5 16.6c0 1.6 1 2.7 2.5 2.7.6 0 1.2-.2 1.7-.6.9 1 1.9 1.5 2.8 1.5s1.9-.5 2.8-1.5c.5.4 1.1.6 1.7.6 1.5 0 2.5-1.1 2.5-2.7 0-2.2-.9-4.1-2-5.8-.9-1.4-1.4-2.4-1.4-3.7 0-2.5-1.3-4.6-3.6-4.6z" +
    "M10.3 7.6a.8.8 0 1 0 0-1.6.8.8 0 0 0 0 1.6zm3.4 0a.8.8 0 1 0 0-1.6.8.8 0 0 0 0 1.6z",
});

/** Flecha "›" de las filas que se pueden tocar. */
export function chevron() {
  return svg(["M1 1l6 6-6 6"], { cls: "chevron", viewBox: "0 0 8 14" });
}

/**
 * Hace que un elemento se comporte como botón: clic, Enter y Espacio.
 * @param {HTMLElement} node
 * @param {() => void} handler
 */
export function onActivate(node, handler) {
  node.addEventListener("click", handler);
  node.addEventListener("keydown", (e) => {
    if (e.key === "Enter" || e.key === " ") {
      e.preventDefault();
      handler();
    }
  });
}
