// Hoja de acciones de un archivo recibido: abrirlo o mostrarlo en su carpeta.

/** @typedef {import("../types.js").Transfer} Transfer */

const RISKY_NOTICE = "Por seguridad, este tipo de archivo no se abre desde ShareDrop. Se mostró en su carpeta.";

/**
 * @param {object} deps
 * @param {HTMLDialogElement} deps.dialog
 * @param {HTMLElement} deps.title
 * @param {HTMLElement} deps.openButton
 * @param {HTMLElement} deps.revealButton
 * @param {HTMLElement} deps.cancelButton
 * @param {(path: string, reveal: boolean) => Promise<"opened"|"revealed">} deps.openReceived
 * @param {(message: string) => void} deps.notify
 */
export function createFileActions({ dialog, title, openButton, revealButton, cancelButton, openReceived, notify }) {
  /** @type {Transfer | null} */
  let current = null;

  /** @param {boolean} reveal */
  async function run(reveal) {
    const path = current?.path;
    dialog.close();
    if (!path) return;
    try {
      const how = await openReceived(path, reveal);
      // Pidió abrir, pero el tipo de archivo es ejecutable: el backend solo lo mostró.
      if (!reveal && how === "revealed") notify(RISKY_NOTICE);
    } catch (e) {
      notify(String(e));
    }
  }

  openButton.addEventListener("click", () => run(false));
  revealButton.addEventListener("click", () => run(true));
  cancelButton.addEventListener("click", () => dialog.close());
  // Tocar fuera de la hoja (sobre el fondo) la cierra.
  dialog.addEventListener("click", (e) => e.target === dialog && dialog.close());

  return {
    /** @param {Transfer} transfer */
    show(transfer) {
      current = transfer;
      title.textContent = transfer.file;
      dialog.showModal();
    },
  };
}
