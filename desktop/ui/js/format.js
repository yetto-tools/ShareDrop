// Formato de cantidades. Funciones puras, sin DOM: se prueban con `node --test`.

const KB = 2 ** 10;
const MB = 2 ** 20;
const GB = 2 ** 30;

/**
 * Tamaño legible de un archivo o una transferencia.
 * @param {number} bytes  Negativo o no numérico = tamaño desconocido.
 */
export function formatBytes(bytes) {
  if (!Number.isFinite(bytes) || bytes < 0) return "?";
  if (bytes >= GB) return `${(bytes / GB).toFixed(2)} GB`;
  if (bytes >= MB) return `${(bytes / MB).toFixed(1)} MB`;
  if (bytes >= KB) return `${Math.round(bytes / KB)} KB`;
  return `${bytes} B`;
}

/**
 * Almacenamiento en GB: sin decimales a partir de 10 GB, con uno por debajo.
 * @param {number} bytes
 */
export function formatGb(bytes) {
  const v = bytes / GB;
  return `${v >= 10 ? Math.round(v) : v.toFixed(1)} GB`;
}
