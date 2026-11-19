// Lógica de presentación sin DOM: decide QUÉ mostrar; los componentes deciden CÓMO.
// Al ser pura se prueba directamente con `node --test` (ver desktop/ui-tests).

import { formatBytes, formatGb } from "./format.js";

/**
 * @typedef {import("./types.js").Transfer} Transfer
 * @typedef {import("./types.js").Device} Device
 * @typedef {import("./types.js").DeviceInfo} DeviceInfo
 */

/** Progreso de 0 a 1 (0 si se desconoce el total). @param {Transfer} t */
export function transferProgress(t) {
  return t.total > 0 ? Math.min(1, t.done / t.total) : 0;
}

/** Un archivo recibido y ya guardado se puede abrir. @param {Transfer} t */
export function isOpenable(t) {
  return Boolean(t.incoming && t.path);
}

/**
 * Etiqueta de dirección: lo recibido es una descarga (↓) y lo enviado, una subida (↑).
 * El verbo sigue al estado: Recibiendo → Recibido; si falla se queda en el sustantivo (Recepción / Envío).
 * @param {Transfer} t
 * @returns {{ key: "in" | "out", label: string, symbol: string }}
 */
export function directionOf(t) {
  const labels = t.incoming
    ? { active: "Recibiendo", finished: "Recibido", failed: "Recepción" }
    : { active: "Enviando", finished: "Enviado", failed: "Envío" };
  return {
    key: t.incoming ? "in" : "out",
    label: labels[t.state] ?? labels.finished,
    symbol: t.incoming ? "↓" : "↑",
  };
}

/**
 * Texto de estado (equipo y tamaño, sin repetir la dirección que ya dice la etiqueta) y su tono.
 * @param {Transfer} t
 * @returns {{ text: string, tone: "active" | "ok" | "error" }}
 */
export function transferStatus(t) {
  const peer = t.peer ? `${t.incoming ? "De" : "A"} ${t.peer}` : "";
  const join = (...parts) => parts.filter(Boolean).join(" · ");
  switch (t.state) {
    case "active":
      return { text: join(peer, `${formatBytes(t.done)} de ${formatBytes(t.total)}`), tone: "active" };
    case "finished":
      return { text: join(peer, formatBytes(t.total)), tone: "ok" };
    default:
      return { text: join(peer, `No se pudo completar: ${t.error ?? "error desconocido"}`), tone: "error" };
  }
}

/** Fabricante + modelo + sistema, p. ej. "Xiaomi 24094RAD4G · Android 16". @param {DeviceInfo} [info] */
export function hardwareLine(info = {}) {
  const model = [info.maker, info.model].filter(Boolean).join(" ");
  return [model, info.os].filter(Boolean).join(" · ");
}

/**
 * Datos de batería y almacenamiento libre, o `null` si el equipo no anuncia nada de eso.
 * @param {DeviceInfo} [info]
 * @returns {{ battery: {level: number, charging: boolean, low: boolean} | null, storage: string | null } | null}
 */
export function deviceStatus(info = {}) {
  const hasBattery = typeof info.battery === "number";
  const hasStorage = typeof info.free === "number" && typeof info.total === "number" && info.total > 0;
  if (!hasBattery && !hasStorage) return null;
  return {
    battery: hasBattery
      ? { level: info.battery, charging: Boolean(info.charging), low: !info.charging && info.battery <= 20 }
      : null,
    storage: hasStorage ? `${formatGb(info.free)} libres de ${formatGb(info.total)}` : null,
  };
}

/** @param {Device} d */
export function pairingLine(d) {
  return `${d.paired ? "Emparejado" : "Sin emparejar"} · ${d.address}`;
}

/** Los teléfonos llevan icono de móvil; el resto, de portátil. @param {Device} d */
export function isPhone(d) {
  return d.platform === "android" || d.platform === "ios";
}

// ---------- Conexión (mismo texto y criterio que la app de Android) ----------

/** @typedef {import("./types.js").LocalLink} LocalLink */

/** @param {string} ip @returns {number} */
function ipToInt(ip) {
  return ip.split(".").reduce((acc, part) => (acc << 8) + Number(part), 0) >>> 0;
}

/** ¿Están `a` y `b` en la misma subred, según la máscara? */
export function sameSubnet(a, mask, b) {
  const m = ipToInt(mask);
  return (ipToInt(a) & m) === (ipToInt(b) & m);
}

/** Nombre del medio de una interfaz: "Wi-Fi" o "Ethernet / USB". @param {LocalLink} link */
export function linkLabel(link) {
  return /wi-?fi|wlan|wireless|inal[aá]mbrica/i.test(link.name) ? "Wi-Fi" : "Ethernet / USB";
}

/**
 * Resumen del estado de conexión: lo mismo que muestra la pantalla Conexión del teléfono.
 * @param {LocalLink[]} links
 * @param {Device[]} devices
 * @returns {{ title: string, detail: string, tone: "ok" | "warn" | "error", anyLink: boolean }}
 */
export function connectionSummary(links, devices) {
  const real = links.filter((l) => !l.virtual_adapter);
  const labels = (list) => [...new Set(list.map(linkLabel))];

  if (devices.length > 0) {
    // Medios por los que se ve realmente a algún dispositivo.
    const used = real.filter((l) => devices.some((d) => sameSubnet(l.address, l.netmask, d.address)));
    return { title: "Conectado", detail: labels(used).join(" · ") || "Equipo encontrado", tone: "ok", anyLink: true };
  }
  if (real.length > 0) {
    return {
      title: "No veo otros equipos",
      detail: `Hay ${labels(real).join(" y ")}, pero nadie responde. Una red corporativa puede aislar los equipos: usa USB o la red del teléfono.`,
      tone: "warn",
      anyLink: true,
    };
  }
  return { title: "Sin conexión", detail: "No hay ningún enlace con otro equipo. Activa una de estas opciones.", tone: "error", anyLink: false };
}

// ---------- Sistema operativo y medio de conexión de cada dispositivo ----------

/** Familia del sistema de un dispositivo, para elegir su icono. @param {string} platform */
export function osKind(platform) {
  const p = (platform ?? "").toLowerCase();
  if (p === "android") return "android";
  if (p === "windows") return "windows";
  if (p === "linux") return "linux";
  if (p === "macos" || p === "ios") return "apple";
  return "other";
}

/** Nombre legible del sistema. @param {string} platform */
export function osName(platform) {
  return { android: "Android", windows: "Windows", linux: "Linux", apple: "macOS", other: "Desconocido" }[osKind(platform)];
}

/**
 * Teléfono↔PC es el uso principal. Teléfono↔teléfono y PC↔PC funcionan (el protocolo es simétrico)
 * pero aún están en pruebas: se marcan como "Pre-alpha".
 * @param {string} ownPlatform @param {Device} d
 */
export function isPreAlpha(ownPlatform, d) {
  return isPhone({ platform: ownPlatform }) === isPhone(d);
}

/**
 * Medio por el que se ve al dispositivo.
 * @param {Device} d @param {LocalLink[]} links
 * @returns {{ key: "usb" | "wifi" | "net", label: string } | null}
 */
export function mediumOf(d, links = []) {
  if (d.link === "usb-adb") return { key: "usb", label: "USB · cable" };
  const announced = d.info?.link;
  if (announced === "usb") return { key: "usb", label: "USB" };
  if (announced === "wifi") return { key: "wifi", label: "Wi-Fi" };
  if (announced === "hotspot") return { key: "wifi", label: "Zona Wi-Fi" };
  const via = links.find((l) => !l.virtual_adapter && sameSubnet(l.address, l.netmask, d.address));
  if (!via) return null;
  return linkLabel(via) === "Wi-Fi" ? { key: "wifi", label: "Wi-Fi" } : { key: "net", label: "Red" };
}

/**
 * Estado del enlace USB por cable (adb) para la fila "Cable USB" de Conexión.
 * @param {{adb_found: boolean, devices: number, unauthorized: number, linked: boolean}} s
 */
export function usbLinkText(s) {
  if (s.linked) return { text: "Enlazado por cable (adb): comparte archivos e información sin red", active: true };
  if (s.unauthorized > 0) return { text: "Acepta el aviso «Permitir depuración USB» en el teléfono", active: false };
  if (s.devices > 0) return { text: "Teléfono detectado; abriendo el enlace…", active: false };
  if (!s.adb_found) return { text: "Para usar el cable sin red, instala las herramientas adb y activa la depuración USB", active: false };
  return { text: "Conecta el cable y activa la depuración USB en el teléfono", active: false };
}

// ---------- Barra de estado ----------

/**
 * Actividad de transferencias: cuántas hay en curso (recibiendo / enviando) y el avance total.
 * @param {Transfer[]} transfers
 * @returns {{ active: number, incoming: number, outgoing: number, progress: number, text: string }}
 */
export function activityOf(transfers) {
  const active = transfers.filter((t) => t.state === "active");
  const incoming = active.filter((t) => t.incoming).length;
  const outgoing = active.length - incoming;
  let total = 0;
  let done = 0;
  for (const t of active) {
    if (t.total > 0) {
      total += t.total;
      done += Math.min(t.done, t.total);
    }
  }
  const progress = total > 0 ? Math.min(1, done / total) : 0;
  if (active.length === 0) return { active: 0, incoming: 0, outgoing: 0, progress: 0, text: "Sin transferencias activas" };

  const parts = [incoming ? `↓ ${incoming}` : "", outgoing ? `↑ ${outgoing}` : ""].filter(Boolean).join("  ");
  return { active: active.length, incoming, outgoing, progress, text: `${parts} en curso${total > 0 ? ` · ${Math.round(progress * 100)} %` : ""}` };
}

/**
 * Texto y color del estado de conexión para la barra inferior.
 * @param {LocalLink[]} links @param {Device[]} devices
 * @returns {{ tone: "ok" | "warn" | "error", text: string, devices: string }}
 */
export function statusLine(links, devices) {
  const summary = connectionSummary(links, devices);
  const count = `${devices.length} ${devices.length === 1 ? "dispositivo" : "dispositivos"}`;
  if (devices.length > 0) {
    // Medios por los que se ve a los dispositivos (USB · cable, Wi-Fi…), sin repetir.
    const labels = [...new Set(devices.map((d) => mediumOf(d, links)?.label).filter(Boolean))];
    return { tone: "ok", text: ["Conectado", ...labels].join(" · "), devices: count };
  }
  return { tone: summary.tone, text: summary.title, devices: count };
}

// ---------- Historial ----------

/** Cuántas transferencias muestra cada pestaña del historial. */
export const HISTORY_LIMIT = 20;

/** @typedef {import("./types.js").HistoryEntry} HistoryEntry */

/**
 * Entradas de una pestaña del historial: las `HISTORY_LIMIT` más recientes, la más reciente primero.
 * @param {HistoryEntry[]} entries
 * @param {"all" | "in" | "out"} filter
 */
export function visibleHistory(entries, filter) {
  const sorted = [...entries].sort((a, b) => b.at - a.at);
  const picked = filter === "in" ? sorted.filter((e) => e.incoming) : filter === "out" ? sorted.filter((e) => !e.incoming) : sorted;
  return picked.slice(0, HISTORY_LIMIT);
}

const pad = (n) => String(n).padStart(2, "0");

/**
 * Cuándo terminó una transferencia: "Hoy · 16:31", "Ayer · 09:05" o "18/11/2026 · 16:31".
 * @param {number} at   Milisegundos desde 1970.
 * @param {number} now  Ahora, en las mismas unidades.
 */
export function whenLabel(at, now) {
  const d = new Date(at);
  const time = `${pad(d.getHours())}:${pad(d.getMinutes())}`;
  const dayStart = (ms) => new Date(new Date(ms).getFullYear(), new Date(ms).getMonth(), new Date(ms).getDate()).getTime();
  const days = Math.round((dayStart(now) - dayStart(at)) / 86_400_000);
  if (days === 0) return `Hoy · ${time}`;
  if (days === 1) return `Ayer · ${time}`;
  return `${pad(d.getDate())}/${pad(d.getMonth() + 1)}/${d.getFullYear()} · ${time}`;
}

/**
 * Una entrada del historial como transferencia terminada, para reutilizar la fila y las acciones de abrir.
 * @param {HistoryEntry} e
 * @returns {Transfer}
 */
export function entryAsTransfer(e) {
  return {
    id: e.uid,
    peer: e.peer,
    file: e.file,
    done: e.size > 0 ? e.size : 0,
    total: e.size,
    incoming: e.incoming,
    state: e.ok ? "finished" : "failed",
    error: e.error ?? null,
    path: e.path ?? null,
  };
}
