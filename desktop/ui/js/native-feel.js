// Que la app no parezca un navegador: sin menú del clic derecho (Recargar, Inspeccionar…) ni atajos de
// recarga, impresión o herramientas. Los campos de texto conservan el clic derecho (copiar y pegar).

/** @param {EventTarget | null} target */
function isEditable(target) {
  return target instanceof Element && target.closest("input, textarea, [contenteditable='true']") !== null;
}

/** @param {KeyboardEvent} e */
export function isBrowserShortcut(e) {
  const key = e.key.toLowerCase();
  const mod = e.ctrlKey || e.metaKey;
  if (key === "f5" || key === "f12") return true;
  if (mod && ["r", "p", "u", "s", "g", "f"].includes(key)) return true; // recargar, imprimir, código, guardar, buscar
  if (mod && e.shiftKey && ["i", "j", "c", "r"].includes(key)) return true; // herramientas y recarga forzada
  return false;
}

/** @param {Document} [doc] */
export function lockBrowserUi(doc = document) {
  doc.addEventListener("contextmenu", (e) => {
    if (!isEditable(e.target)) e.preventDefault();
  });
  doc.addEventListener("keydown", (e) => {
    if (isBrowserShortcut(e)) e.preventDefault();
  });
}
