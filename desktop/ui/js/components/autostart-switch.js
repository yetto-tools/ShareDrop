// Interruptor "Iniciar con el sistema".

/**
 * @param {object} deps
 * @param {HTMLButtonElement} deps.button  Elemento con role="switch".
 * @param {HTMLElement} deps.row           Fila contenedora (se oculta si la plataforma no lo soporta).
 * @param {{ isEnabled(): Promise<boolean>, enable(): Promise<void>, disable(): Promise<void> }} deps.autostart
 */
export async function initAutostartSwitch({ button, row, autostart }) {
  /** @param {boolean} on */
  const paint = (on) => button.setAttribute("aria-checked", String(on));

  try {
    paint(await autostart.isEnabled());
  } catch {
    row.hidden = true; // plataforma sin soporte
    return;
  }

  button.addEventListener("click", async () => {
    const on = button.getAttribute("aria-checked") !== "true";
    paint(on); // respuesta inmediata; se revierte si el sistema lo rechaza
    try {
      await (on ? autostart.enable() : autostart.disable());
    } catch {
      paint(!on);
    }
  });
}
