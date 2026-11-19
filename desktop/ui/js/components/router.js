// Navegación entre pantallas, igual que en la app de Android: Inicio → páginas con "‹" para volver.
// No usa la URL ni el historial del navegador: son solo cuatro pantallas y una app de escritorio.

/** @typedef {"home" | "history" | "connection" | "folder" | "general"} ScreenName */

const SLIDE_MS = 250;
const EASE = "cubic-bezier(.25, 1, .5, 1)";

/** El historial viene de la izquierda (como el gesto de deslizar); las demás páginas, de la derecha. */
const sideOf = (/** @type {ScreenName} */ name) => (name === "history" ? -1 : 1);

/**
 * @param {object} deps
 * @param {Record<ScreenName, HTMLElement>} deps.screens  Una sección por pantalla.
 * @param {() => boolean} [deps.blocked]  Si devuelve true (hay un diálogo abierto), Escape no navega.
 * @param {(name: ScreenName) => void} [deps.onChange]
 */
export function createRouter({ screens, blocked = () => false, onChange = () => {} }) {
  /** @type {ScreenName} */
  let current = "home";

  /** @param {ScreenName} name */
  function go(name) {
    if (!screens[name]) throw new Error(`Pantalla desconocida: ${name}`);
    const from = current;
    current = name;
    for (const [key, node] of Object.entries(screens)) node.hidden = key !== name;
    window.scrollTo?.(0, 0);
    if (from !== name) slide(from, name);
    onChange(name);
  }

  /** Desliza la pantalla que entra y la que sale; no hace nada si el sistema pide menos movimiento. */
  function slide(/** @type {ScreenName} */ from, /** @type {ScreenName} */ to) {
    const incoming = screens[to];
    const outgoing = screens[from];
    if (typeof incoming.animate !== "function" || window.matchMedia?.("(prefers-reduced-motion: reduce)").matches) return;
    // Al ir a una página entra desde su lado; al volver a Inicio sale hacia el lado por el que vino.
    const forward = to !== "home";
    const d = sideOf(forward ? to : from) * (forward ? 1 : -1);
    const opts = { duration: SLIDE_MS, easing: EASE, fill: /** @type {FillMode} */ ("both") };
    incoming.animate(
      [{ transform: `translateX(${d * 30}%)`, opacity: 0 }, { transform: "translateX(0)", opacity: 1 }],
      opts,
    ).finished.then(() => incoming.getAnimations().forEach((a) => a.cancel())).catch(() => {});
    // La que sale se muestra un momento encima, sin empujar el contenido.
    outgoing.hidden = false;
    outgoing.style.position = "absolute";
    outgoing.style.inset = "0";
    outgoing.style.pointerEvents = "none";
    outgoing.animate(
      [{ transform: "translateX(0)", opacity: 1 }, { transform: `translateX(${-d * 30}%)`, opacity: 0 }],
      opts,
    ).finished.then(() => {
      outgoing.getAnimations().forEach((a) => a.cancel());
      outgoing.style.cssText = "";
      outgoing.hidden = outgoing !== screens[current];
    }).catch(() => {});
  }

  const back = () => go("home");

  // Escape vuelve a Inicio (como el gesto de atrás), salvo que haya un diálogo o menú abierto.
  document.addEventListener("keydown", (e) => {
    if (e.key === "Escape" && current !== "home" && !blocked()) back();
  });

  go("home");
  return { go, back, get current() { return current; } };
}
