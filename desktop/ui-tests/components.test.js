// Pruebas de componentes con elementos falsos (sin navegador): comprueban su lógica y sus
// dependencias inyectadas, no el aspecto.

import assert from "node:assert/strict";
import { test } from "node:test";
import { createDropZone } from "../ui/js/components/drop-zone.js";
import { createSwipe } from "../ui/js/components/swipe.js";
import { createFileActions } from "../ui/js/components/file-actions.js";
import { createFolderRow } from "../ui/js/components/folder-row.js";
import { infoRows } from "../ui/js/components/device-info.js";

/** Elemento mínimo: guarda los manejadores y permite dispararlos. */
function fakeEl() {
  const handlers = {};
  const classes = new Set();
  return {
    hidden: false,
    textContent: "",
    classList: { add: (c) => classes.add(c), remove: (c) => classes.delete(c), has: (c) => classes.has(c) },
    addEventListener: (type, fn) => (handlers[type] ??= []).push(fn),
    fire: (type, event = {}) => (handlers[type] ?? []).forEach((fn) => fn(event)),
  };
}

function fakeDialog() {
  const d = fakeEl();
  d.open = false;
  d.showModal = () => (d.open = true);
  d.close = () => (d.open = false);
  return d;
}

test("drop-zone: resalta el dispositivo bajo el cursor y envía al soltar", () => {
  const hint = fakeEl();
  const node = fakeEl();
  const sent = [];
  const zone = createDropZone({
    hint,
    deviceAt: (x) => (x < 100 ? { id: "tel", node } : null),
    onDrop: (id, paths) => sent.push([id, paths]),
  });

  zone.handle({ type: "over", position: { x: 10, y: 0 } });
  assert.equal(node.classList.has("over"), true);
  assert.equal(hint.hidden, true, "con un destino válido se oculta el cartel");

  zone.handle({ type: "over", position: { x: 500, y: 0 } });
  assert.equal(node.classList.has("over"), false);
  assert.equal(hint.hidden, false, "fuera de un dispositivo se muestra el cartel");

  zone.handle({ type: "over", position: { x: 10, y: 0 } });
  zone.handle({ type: "drop", position: { x: 10, y: 0 }, paths: ["a.txt", "b.txt"] });
  assert.deepEqual(sent, [["tel", ["a.txt", "b.txt"]]]);
  assert.equal(node.classList.has("over"), false);
  assert.equal(hint.hidden, true);
});

test("drop-zone: soltar fuera de un dispositivo no envía nada", () => {
  const sent = [];
  const zone = createDropZone({ hint: fakeEl(), deviceAt: () => null, onDrop: (...a) => sent.push(a) });
  zone.handle({ type: "drop", position: { x: 1, y: 1 }, paths: ["a.txt"] });
  assert.deepEqual(sent, []);
});

test("drop-zone: salir de la ventana limpia el resaltado", () => {
  const node = fakeEl();
  const hint = fakeEl();
  const zone = createDropZone({ hint, deviceAt: () => ({ id: "x", node }), onDrop() {} });
  zone.handle({ type: "over", position: { x: 0, y: 0 } });
  zone.handle({ type: "leave" });
  assert.equal(node.classList.has("over"), false);
  assert.equal(hint.hidden, true);
});

test("file-actions: abrir y mostrar llaman al backend con la ruta", async () => {
  const dialog = fakeDialog();
  const [openButton, revealButton, cancelButton, title] = [fakeEl(), fakeEl(), fakeEl(), fakeEl()];
  const calls = [];
  const notices = [];
  const actions = createFileActions({
    dialog, title, openButton, revealButton, cancelButton,
    openReceived: async (path, reveal) => (calls.push([path, reveal]), "opened"),
    notify: (m) => notices.push(m),
  });

  actions.show({ file: "a.txt", path: "C:\\r\\a.txt" });
  assert.equal(dialog.open, true);
  assert.equal(title.textContent, "a.txt");

  openButton.fire("click");
  await Promise.resolve();
  revealButton.fire("click");
  await Promise.resolve();
  assert.deepEqual(calls, [["C:\\r\\a.txt", false], ["C:\\r\\a.txt", true]]);
  assert.equal(dialog.open, false);
  assert.deepEqual(notices, []);
});

test("file-actions: avisa si el tipo de archivo no se abre por seguridad", async () => {
  const dialog = fakeDialog();
  const notices = [];
  const openButton = fakeEl();
  const actions = createFileActions({
    dialog, title: fakeEl(), openButton, revealButton: fakeEl(), cancelButton: fakeEl(),
    openReceived: async () => "revealed",
    notify: (m) => notices.push(m),
  });
  actions.show({ file: "setup.exe", path: "C:\\r\\setup.exe" });
  openButton.fire("click");
  await new Promise((r) => setTimeout(r, 0));
  assert.equal(notices.length, 1);
  assert.match(notices[0], /seguridad/);
});

test("file-actions: un error del backend se muestra al usuario", async () => {
  const notices = [];
  const openButton = fakeEl();
  const actions = createFileActions({
    dialog: fakeDialog(), title: fakeEl(), openButton, revealButton: fakeEl(), cancelButton: fakeEl(),
    openReceived: async () => { throw "El archivo ya no está en esa ubicación"; },
    notify: (m) => notices.push(m),
  });
  actions.show({ file: "a.txt", path: "C:\\r\\a.txt" });
  openButton.fire("click");
  await new Promise((r) => setTimeout(r, 0));
  assert.deepEqual(notices, ["El archivo ya no está en esa ubicación"]);
});

test("folder-row: elegir carpeta, restablecer y errores", async () => {
  const row = fakeEl();
  const resetRow = fakeEl();
  const pathLabel = fakeEl();
  const errors = [];
  let picked = "D:\\Recibidos";
  const folder = createFolderRow({
    row, resetRow, pathLabel,
    pickFolder: async () => picked,
    setReceiveDir: async (p) => {
      if (p === "FALLA") throw "sin permiso";
      return { receive_dir: p ?? "C:\\Descargas\\ShareDrop", is_default_dir: p === null };
    },
    onError: (m) => errors.push(m),
  });

  folder.render({ receive_dir: "C:\\Descargas\\ShareDrop", is_default_dir: true });
  assert.equal(resetRow.hidden, true, "con la carpeta predeterminada no hay nada que restablecer");

  row.fire("click");
  await new Promise((r) => setTimeout(r, 0));
  assert.equal(pathLabel.textContent, "D:\\Recibidos");
  assert.equal(resetRow.hidden, false);

  resetRow.fire("click");
  await new Promise((r) => setTimeout(r, 0));
  assert.equal(pathLabel.textContent, "C:\\Descargas\\ShareDrop");
  assert.equal(resetRow.hidden, true);

  picked = "FALLA";
  row.fire("click");
  await new Promise((r) => setTimeout(r, 0));
  assert.deepEqual(errors, ["sin permiso"]);

  picked = null; // el usuario cierra el selector sin elegir
  row.fire("click");
  await new Promise((r) => setTimeout(r, 0));
  assert.deepEqual(errors, ["sin permiso"], "cancelar el selector no es un error");
});

test("device-info: filas de la ficha de un teléfono por cable", () => {
  const d = {
    name: "Redmi Note 14 5G", platform: "android", address: "127.0.0.1", port: 47811, paired: true, link: "usb-adb",
    info: { maker: "Xiaomi", model: "24094RAD4G", os: "Android 16", battery: 98, charging: true, free: 176 * 2 ** 30, total: 228 * 2 ** 30 },
  };
  assert.deepEqual(infoRows(d, { ownPlatform: "windows", links: [] }), [
    ["Sistema", "Android 16"],
    ["Modelo", "Xiaomi 24094RAD4G"],
    ["Batería", "98 % · cargando"],
    ["Almacenamiento", "176 GB libres de 228 GB"],
    ["Conexión", "USB · cable"],
    ["Dirección", "127.0.0.1:47811"],
    ["Estado", "Emparejado"],
  ]);
});

test("device-info: una PC sin datos extra solo muestra lo que se sabe", () => {
  const d = { name: "LAP", platform: "windows", address: "10.0.0.5", port: 47800, paired: false, info: {}, link: null };
  const rows = infoRows(d, { ownPlatform: "android", links: [] });
  assert.deepEqual(rows.map((r) => r[0]), ["Sistema", "Dirección", "Estado"]);
  assert.equal(rows[0][1], "Windows");
});

/** Destino falso con registro de manejadores, para simular punteros y rueda. */
function fakeTarget() {
  const handlers = {};
  return {
    addEventListener: (type, fn) => (handlers[type] ??= []).push(fn),
    fire: (type, event) => (handlers[type] ?? []).forEach((fn) => fn(event)),
  };
}
const ptr = (x, y, over = {}) => ({ clientX: x, clientY: y, button: 0, pointerType: "mouse", target: { closest: () => null }, ...over });

test("swipe: de izquierda a derecha, de derecha a izquierda, y lo que no cuenta", () => {
  const target = fakeTarget();
  const calls = [];
  createSwipe({ target, onRight: () => calls.push("right"), onLeft: () => calls.push("left") });
  const drag = (x0, y0, x1, y1, over) => { target.fire("pointerdown", ptr(x0, y0, over)); target.fire("pointerup", ptr(x1, y1, over)); };

  drag(50, 300, 200, 310);    // →
  drag(300, 300, 120, 290);   // ←
  drag(50, 300, 100, 300);    // muy corto: es un toque o un arrastre pequeño
  drag(50, 300, 200, 500);    // en diagonal: es un desplazamiento
  drag(50, 300, 200, 300, { button: 2 }); // botón derecho del ratón
  assert.deepEqual(calls, ["right", "left"]);
});

test("swipe: se ignora con un diálogo abierto o si el arrastre empieza en un campo de texto", () => {
  const target = fakeTarget();
  const calls = [];
  let blocked = false;
  createSwipe({ target, onRight: () => calls.push("right"), onLeft: () => calls.push("left"), blocked: () => blocked });

  blocked = true;
  target.fire("pointerdown", ptr(10, 10));
  target.fire("pointerup", ptr(200, 10));
  blocked = false;
  target.fire("pointerdown", ptr(10, 10, { target: { closest: () => ({}) } })); // empieza en un <input>
  target.fire("pointerup", ptr(200, 10));
  assert.deepEqual(calls, []);
});

test("swipe: dos dedos en el panel táctil (rueda horizontal)", () => {
  const target = fakeTarget();
  const calls = [];
  createSwipe({ target, onRight: () => calls.push("right"), onLeft: () => calls.push("left") });
  const wheel = (deltaX, deltaY = 0) => target.fire("wheel", { deltaX, deltaY });

  wheel(-60); wheel(-60); wheel(-60);   // acumula -180 → hacia la derecha
  wheel(0, 100);                        // desplazamiento vertical: no cuenta
  wheel(100); wheel(100);               // acumula +200 → hacia la izquierda
  assert.deepEqual(calls, ["right", "left"]);
});
