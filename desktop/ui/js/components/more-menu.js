// Menú "•••" (pull-down): botón de cristal que abre una lista de opciones. Accesible con teclado:
// Enter/Espacio abren, ↑↓ mueven, Escape cierra y devuelve el foco al botón.

/**
 * @param {object} deps
 * @param {HTMLButtonElement} deps.button
 * @param {HTMLElement} deps.menu           Contenedor con role="menu" y botones role="menuitem".
 * @param {(target: string) => void} deps.onSelect  Recibe el valor de `data-go` del elemento elegido.
 */
export function createMoreMenu({ button, menu, onSelect }) {
  const items = () => /** @type {HTMLElement[]} */ ([...menu.querySelectorAll('[role="menuitem"]')]);

  function setOpen(open, { focusFirst = false } = {}) {
    menu.hidden = !open;
    button.setAttribute("aria-expanded", String(open));
    if (open && focusFirst) items()[0]?.focus();
    if (!open) button.focus({ preventScroll: true });
  }

  button.addEventListener("click", () => {
    const willOpen = menu.hidden;
    setOpen(willOpen, { focusFirst: false });
    if (willOpen) items()[0]?.focus({ preventScroll: true });
  });

  menu.addEventListener("click", (e) => {
    const item = /** @type {Element} */ (e.target).closest('[role="menuitem"]');
    const target = item?.getAttribute("data-go");
    if (!target) return;
    menu.hidden = true;
    button.setAttribute("aria-expanded", "false");
    onSelect(target);
  });

  menu.addEventListener("keydown", (e) => {
    const list = items();
    const i = list.indexOf(/** @type {HTMLElement} */ (document.activeElement));
    if (e.key === "ArrowDown") {
      e.preventDefault();
      list[(i + 1) % list.length]?.focus();
    } else if (e.key === "ArrowUp") {
      e.preventDefault();
      list[(i - 1 + list.length) % list.length]?.focus();
    } else if (e.key === "Escape") {
      e.stopPropagation(); // que no vuelva además de pantalla
      setOpen(false);
    } else if (e.key === "Tab") {
      setOpen(false);
    }
  });

  // Tocar fuera del menú lo cierra (sin robar el foco).
  document.addEventListener("click", (e) => {
    if (menu.hidden) return;
    const t = /** @type {Node} */ (e.target);
    if (!menu.contains(t) && !button.contains(t)) {
      menu.hidden = true;
      button.setAttribute("aria-expanded", "false");
    }
  });

  return { isOpen: () => !menu.hidden };
}
