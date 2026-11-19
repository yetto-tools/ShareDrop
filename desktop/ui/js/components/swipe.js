// Gesto de deslizar en horizontal: con el dedo, con el ratón arrastrando, o con dos dedos en el
// panel táctil. Deslizar de izquierda a derecha abre el historial; de derecha a izquierda vuelve.

/** Distancia mínima (px) para que un arrastre cuente como deslizar. */
const DRAG_THRESHOLD = 90;
/** Desplazamiento horizontal acumulado del panel táctil para que cuente como deslizar. */
const WHEEL_THRESHOLD = 160;

/**
 * @param {object} deps
 * @param {{ addEventListener: Function }} deps.target  Normalmente `document`.
 * @param {() => void} deps.onRight  De izquierda a derecha.
 * @param {() => void} deps.onLeft   De derecha a izquierda.
 * @param {() => boolean} [deps.blocked]  Si devuelve true (hay un diálogo abierto), se ignora el gesto.
 */
export function createSwipe({ target, onRight, onLeft, blocked = () => false }) {
  /** @type {{ x: number, y: number } | null} */
  let start = null;

  /** Los controles de texto y los menús tienen sus propios gestos de arrastre. @param {any} node */
  const ownsDrag = (node) => Boolean(node?.closest?.("input, textarea, dialog, [role='menu']"));

  target.addEventListener("pointerdown", (/** @type {PointerEvent} */ e) => {
    start = e.button === 0 || e.pointerType !== "mouse" ? (ownsDrag(e.target) ? null : { x: e.clientX, y: e.clientY }) : null;
  });
  target.addEventListener("pointercancel", () => (start = null));
  target.addEventListener("pointerup", (/** @type {PointerEvent} */ e) => {
    if (!start) return;
    const dx = e.clientX - start.x;
    const dy = e.clientY - start.y;
    start = null;
    if (blocked()) return;
    // Casi horizontal: un arrastre en diagonal es un desplazamiento, no un deslizar.
    if (Math.abs(dx) >= DRAG_THRESHOLD && Math.abs(dx) > Math.abs(dy) * 2) (dx > 0 ? onRight : onLeft)();
  });

  // Panel táctil: dos dedos hacia la derecha dan deltaX negativo (el contenido "se mueve" a la derecha).
  let acc = 0;
  /** @type {ReturnType<typeof setTimeout> | undefined} */
  let timer;
  target.addEventListener(
    "wheel",
    (/** @type {WheelEvent} */ e) => {
      if (Math.abs(e.deltaX) <= Math.abs(e.deltaY)) return;
      acc += e.deltaX;
      clearTimeout(timer);
      timer = setTimeout(() => (acc = 0), 250);
      if (Math.abs(acc) < WHEEL_THRESHOLD) return;
      const toRight = acc < 0;
      acc = 0;
      if (!blocked()) (toRight ? onRight : onLeft)();
    },
    { passive: true },
  );
}
