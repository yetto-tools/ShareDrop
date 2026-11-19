// Navegación entre pantallas, igual que en la app de Android: Inicio → páginas con "‹" para volver.
// No usa la URL ni el historial del navegador: son solo cuatro pantallas y una app de escritorio.

/** @typedef {"home" | "history" | "connection" | "folder" | "general"} ScreenName */

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
    current = name;
    for (const [key, node] of Object.entries(screens)) node.hidden = key !== name;
    window.scrollTo?.(0, 0);
    onChange(name);
  }

  const back = () => go("home");

  // Escape vuelve a Inicio (como el gesto de atrás), salvo que haya un diálogo o menú abierto.
  document.addEventListener("keydown", (e) => {
    if (e.key === "Escape" && current !== "home" && !blocked()) back();
  });

  go("home");
  return { go, back, get current() { return current; } };
}
