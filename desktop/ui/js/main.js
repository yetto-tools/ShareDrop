// Punto de entrada: crea los componentes, los conecta con el backend y arranca.
// Aquí vive todo el "cableado"; los componentes no se conocen entre sí.

import { createApi, EVENTS } from "./api.js";
import { initAutostartSwitch } from "./components/autostart-switch.js";
import { createConnectionPage } from "./components/connection-page.js";
import { createDeviceInfo } from "./components/device-info.js";
import { createDeviceList } from "./components/device-list.js";
import { createDropZone } from "./components/drop-zone.js";
import { createFileActions } from "./components/file-actions.js";
import { createFolderRow } from "./components/folder-row.js";
import { createHistoryPage } from "./components/history-page.js";
import { createLinkMonitor } from "./components/link-monitor.js";
import { createMoreMenu } from "./components/more-menu.js";
import { createPairing } from "./components/pairing.js";
import { createRouter } from "./components/router.js";
import { createStatusBar } from "./components/status-bar.js";
import { createSwipe } from "./components/swipe.js";
import { createToast } from "./components/toast.js";
import { applyTheme, loadTheme, normalizeTheme as loadThemeValue, saveTheme } from "./theme.js";
import { createTransferList } from "./components/transfer-list.js";
import { lockBrowserUi } from "./native-feel.js";
import { activityOf, statusLine } from "./view-models.js";

/** Obtiene un elemento por id o falla con un mensaje claro (mejor que un error críptico más adelante). */
function byId(id) {
  const node = document.getElementById(id);
  if (!node) throw new Error(`Falta #${id} en index.html`);
  return node;
}

async function main() {
  lockBrowserUi();
  const api = createApi(window.__TAURI__);
  const toast = createToast(byId("toast"));

  // --- Navegación: Inicio y tres páginas, como en el teléfono ---
  const moreMenuNode = byId("more-menu");
  const router = createRouter({
    screens: {
      home: byId("screen-home"),
      history: byId("screen-history"),
      settings: byId("screen-settings"),
      connection: byId("screen-connection"),
      folder: byId("screen-folder"),
      general: byId("screen-general"),
    },
    // Con un diálogo o el menú abierto, Escape cierra eso y no cambia de pantalla.
    blocked: () => document.querySelector("dialog[open]") !== null || !moreMenuNode.hidden,
    // El historial se lee del disco cada vez que se abre.
    onChange: (name) => name === "history" && refreshHistory(),
  });
  createMoreMenu({ button: byId("more-button"), menu: moreMenuNode, onSelect: router.go });
  for (const back of document.querySelectorAll("[data-back]")) back.addEventListener("click", router.back);
  // Filas de Ajustes: abren su página (clic, Enter o espacio).
  for (const row of document.querySelectorAll("[data-open]")) {
    const open = () => router.go(/** @type {any} */ (row.getAttribute("data-open")));
    row.addEventListener("click", open);
    row.addEventListener("keydown", (e) => {
      if (e.key === "Enter" || e.key === " ") {
        e.preventDefault();
        open();
      }
    });
  }

  // --- Historial: de izquierda a derecha lo abre, de derecha a izquierda vuelve ---
  const history = createHistoryPage({
    list: byId("history-list"),
    filter: byId("history-filter"),
    empty: byId("history-empty"),
    onOpen: (t) => fileActions.show(t),
  });
  async function refreshHistory() {
    try {
      history.setEntries(await api.history());
    } catch (e) {
      toast.show(`No se pudo leer el historial: ${e}`);
    }
  }
  const dialogOpen = () => document.querySelector("dialog[open]") !== null || !moreMenuNode.hidden;
  createSwipe({
    target: document,
    blocked: dialogOpen,
    onRight: () => router.current === "home" && router.go("history"),
    onLeft: () => router.current !== "home" && router.back(),
  });

  // --- Archivos recibidos: abrir archivo o carpeta ---
  const fileActions = createFileActions({
    dialog: byId("actions"),
    title: byId("actions-title"),
    openButton: byId("act-open"),
    revealButton: byId("act-reveal"),
    cancelButton: byId("act-cancel"),
    openReceived: api.openReceived,
    notify: toast.show,
  });

  // --- Barra de estado: se recalcula con cada cambio de dispositivos, red o transferencias ---
  const statusBar = createStatusBar({
    dot: byId("sb-dot"),
    linkText: byId("sb-link-text"),
    devices: byId("sb-devices"),
    transfers: byId("sb-transfers"),
    version: byId("sb-version"),
    progress: document.querySelector(".sb-progress"),
  });
  let knownDevices = [];
  let knownLinks = [];
  /** @type {Map<string, import("./types.js").Transfer>} */
  const knownTransfers = new Map();
  const refreshStatus = () => {
    statusBar.setConnection(statusLine(knownLinks, knownDevices));
    statusBar.setActivity(activityOf([...knownTransfers.values()]));
  };

  const transfers = createTransferList({
    section: byId("transfers-section"),
    list: byId("transfers"),
    filter: byId("transfer-filter"),
    onOpen: fileActions.show,
    // Reintentar repite el envío desde el archivo que falló; la fila fallida se reemplaza por la nueva.
    onRetry: async (t) => {
      try {
        await api.retryTransfer(Number(t.id));
        transfers.remove(t.id);
      } catch (e) {
        toast.show(String(e));
      }
    },
  });

  // --- Dispositivos y envío ---
  const devices = createDeviceList({
    list: byId("devices"),
    emptyState: byId("empty"),
    onSend: async (deviceId) => {
      const paths = await api.pickFiles();
      if (paths) sendFiles(deviceId, paths);
    },
    onInfo: (device) => deviceInfo.show(device),
  });

  const deviceInfo = createDeviceInfo({
    dialog: byId("device-info"),
    title: byId("device-info-title"),
    list: byId("device-info-list"),
    note: byId("device-info-note"),
    closeButton: byId("device-info-close"),
    context: () => devices.context,
  });

  /** Si el envío ni siquiera arranca, se muestra como una transferencia fallida. */
  async function sendFiles(deviceId, paths) {
    try {
      await api.sendFiles(deviceId, paths);
    } catch (e) {
      transfers.upsert({ id: `local-${Date.now()}`, peer: "", file: "", done: 0, total: 0, incoming: false, state: "failed", error: String(e) });
    }
  }

  // --- Conexión: estado de la red y aviso de Inicio ---
  const connection = createConnectionPage({
    status: byId("connection-status"),
    actions: byId("connection-actions"),
    hint: byId("connection-hint"),
    hintTitle: byId("connection-hint-title"),
    onOpenPage: () => router.go("connection"),
    openSettings: api.openSettings,
    notify: toast.show,
  });
  const monitor = createLinkMonitor({ networkStatus: api.networkStatus, usbStatus: api.usbStatus });
  monitor.subscribe(({ links, usb }) => {
    knownLinks = links;
    refreshStatus();
    connection.setLinks(links);
    connection.setUsb(usb);
    devices.setContext({ links });
  });

  const showDevices = (list) => {
    knownDevices = list;
    refreshStatus();
    devices.render(list);
    connection.setDevices(list);
  };

  // --- Carpeta de destino ---
  const folder = createFolderRow({
    row: byId("folder-row"),
    pathLabel: byId("folder-path"),
    resetRow: byId("folder-reset"),
    pickFolder: api.pickFolder,
    setReceiveDir: api.setReceiveDir,
    onError: toast.show,
  });

  // --- Emparejamiento ---
  const pairing = createPairing({
    show: { dialog: byId("show-code"), title: byId("show-code-title"), hint: byId("show-code-hint"), qr: byId("code-qr"), code: byId("code") },
    ask: {
      dialog: byId("ask-code"),
      title: byId("ask-title"),
      form: byId("ask-form"),
      hint: byId("ask-hint"),
      input: byId("code-input"),
      ok: byId("ask-ok"),
      cancel: byId("ask-cancel"),
    },
    answerCode: api.answerCode,
  });

  const dropZone = createDropZone({
    hint: byId("drop-hint"),
    deviceAt: devices.deviceAt,
    onDrop: sendFiles,
  });

  // Eventos del backend → componentes.
  await Promise.all([
    api.on(EVENTS.DEVICES, showDevices),
    api.on(EVENTS.TRANSFER, (t) => {
      knownTransfers.set(String(t.id), t);
      transfers.upsert(t);
      refreshStatus();
      if (t.state !== "active" && router.current === "history") refreshHistory();
    }),
    api.on(EVENTS.SHOW_CODE, pairing.showCode),
    api.on(EVENTS.HIDE_CODE, pairing.hideCode),
    api.on(EVENTS.ASK_CODE, pairing.askCode),
    api.onDragDrop(dropZone.handle),
  ]);

  // Estado inicial (los eventos solo informan de cambios).
  const info = await api.info();
  byId("me").textContent = info.name;
  statusBar.setVersion(info.version);
  devices.setContext({ ownPlatform: info.platform });
  folder.render(info);
  showDevices(info.devices);
  monitor.start();
  // Apariencia: Sistema, Claro u Oscuro (se recuerda en este equipo).
  const themeFilter = byId("theme-filter");
  const paintTheme = (/** @type {string} */ t) => {
    for (const b of themeFilter.querySelectorAll("button")) b.setAttribute("aria-checked", String(b.getAttribute("data-theme") === t));
  };
  const savedTheme = loadTheme();
  applyTheme(savedTheme);
  paintTheme(savedTheme);
  themeFilter.addEventListener("click", (e) => {
    const b = /** @type {Element} */ (e.target).closest("button[data-theme]");
    if (!b) return;
    const t = loadThemeValue(b.getAttribute("data-theme"));
    applyTheme(t);
    saveTheme(t);
    paintTheme(t);
  });
  await initAutostartSwitch({ button: byId("autostart"), row: byId("autostart").closest(".row"), autostart: api.autostart });
}

main().catch((e) => {
  console.error(e);
  const node = document.getElementById("toast");
  if (node) {
    node.textContent = `No se pudo iniciar la interfaz: ${e}`;
    node.classList.add("show");
  }
});
