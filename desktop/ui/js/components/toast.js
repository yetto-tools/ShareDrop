// Aviso breve y no bloqueante (reemplaza a `alert`).

const VISIBLE_MS = 3200;

/** @param {HTMLElement} node  Elemento con role="status" (anuncia el texto a lectores de pantalla). */
export function createToast(node) {
  /** @type {ReturnType<typeof setTimeout> | undefined} */
  let timer;

  return {
    /** @param {string} message */
    show(message) {
      node.textContent = message;
      node.classList.add("show");
      clearTimeout(timer);
      timer = setTimeout(() => node.classList.remove("show"), VISIBLE_MS);
    },
  };
}
