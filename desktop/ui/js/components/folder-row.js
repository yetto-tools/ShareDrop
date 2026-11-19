// Fila "Guardar en": muestra la carpeta de recepción y permite cambiarla o restablecerla.

import { onActivate } from "../dom.js";

/** @typedef {import("../types.js").AppInfo} AppInfo */

/**
 * @param {object} deps
 * @param {HTMLElement} deps.row        Fila que abre el selector de carpeta.
 * @param {HTMLElement} deps.pathLabel  Donde se escribe la ruta actual.
 * @param {HTMLElement} deps.resetRow   Fila "Restablecer carpeta predeterminada".
 * @param {() => Promise<string|null>} deps.pickFolder
 * @param {(path: string|null) => Promise<AppInfo>} deps.setReceiveDir
 * @param {(message: string) => void} deps.onError
 */
export function createFolderRow({ row, pathLabel, resetRow, pickFolder, setReceiveDir, onError }) {
  /** @param {Pick<AppInfo, "receive_dir" | "is_default_dir">} info */
  function render(info) {
    pathLabel.textContent = info.receive_dir;
    resetRow.hidden = info.is_default_dir;
  }

  /** @param {string|null} path */
  async function change(path) {
    try {
      render(await setReceiveDir(path));
    } catch (e) {
      onError(String(e));
    }
  }

  onActivate(row, async () => {
    const dir = await pickFolder();
    if (dir) await change(dir);
  });
  onActivate(resetRow, () => change(null));

  return { render };
}
