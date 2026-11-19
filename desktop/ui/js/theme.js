// Apariencia: "system" sigue al sistema; "light" y "dark" la fuerzan. Se guarda en este equipo.

const KEY = "sharedrop-theme";
export const THEMES = ["system", "light", "dark"];

/** @param {unknown} v @returns {"system" | "light" | "dark"} */
export function normalizeTheme(v) {
  return v === "light" || v === "dark" ? v : "system";
}

/** @param {Pick<Storage, "getItem">} [storage] */
export function loadTheme(storage = globalThis.localStorage) {
  try {
    return normalizeTheme(storage?.getItem(KEY));
  } catch {
    return "system";
  }
}

/**
 * Aplica la apariencia al documento: sin atributo, manda el sistema.
 * @param {"system" | "light" | "dark"} theme
 * @param {{ documentElement: { dataset: Record<string, string | undefined> } }} [doc]
 */
export function applyTheme(theme, doc = document) {
  if (theme === "system") delete doc.documentElement.dataset.theme;
  else doc.documentElement.dataset.theme = theme;
}

/** @param {"system" | "light" | "dark"} theme @param {Pick<Storage, "setItem">} [storage] */
export function saveTheme(theme, storage = globalThis.localStorage) {
  try {
    storage?.setItem(KEY, theme);
  } catch {
    // sin almacenamiento: la elección vale solo para esta sesión
  }
}
