import assert from "node:assert/strict";
import { test } from "node:test";
import { entryAsTransfer, visibleHistory, whenLabel, HISTORY_LIMIT, activityOf, connectionSummary, isPreAlpha, statusLine, linkLabel, mediumOf, osKind, osName, sameSubnet, usbLinkText } from "../ui/js/view-models.js";

const link = (name, address, netmask = "255.255.255.0", virtual_adapter = false) => ({ name, address, netmask, virtual_adapter });
const device = (address) => ({ id: "x", name: "Redmi", platform: "android", address, port: 47800, paired: true, info: {} });

test("sameSubnet compara según la máscara", () => {
  assert.equal(sameSubnet("10.98.181.30", "255.255.255.0", "10.98.181.78"), true);
  assert.equal(sameSubnet("192.168.2.228", "255.255.255.0", "10.98.181.78"), false);
  assert.equal(sameSubnet("192.168.2.10", "255.255.0.0", "192.168.9.9"), true);
  assert.equal(sameSubnet("10.0.0.1", "255.255.255.255", "10.0.0.2"), false);
});

test("linkLabel distingue Wi-Fi de Ethernet / USB", () => {
  assert.equal(linkLabel(link("Wi-Fi", "1.1.1.1")), "Wi-Fi");
  assert.equal(linkLabel(link("wlan0", "1.1.1.1")), "Wi-Fi");
  assert.equal(linkLabel(link("Conexión de red inalámbrica", "1.1.1.1")), "Wi-Fi");
  assert.equal(linkLabel(link("Ethernet 3", "1.1.1.1")), "Ethernet / USB");
});

test("connectionSummary: conectado, con los medios por los que se ve el dispositivo", () => {
  const links = [link("Ethernet 2", "192.168.2.228"), link("Ethernet 3", "10.98.181.30")];
  const s = connectionSummary(links, [device("10.98.181.78")]);
  assert.deepEqual(s, { title: "Conectado", detail: "Ethernet / USB", tone: "ok", anyLink: true });
});

test("connectionSummary: no cuenta los adaptadores virtuales", () => {
  const links = [link("vEthernet (WSL)", "172.17.16.1", "255.255.240.0", true)];
  assert.equal(connectionSummary(links, []).title, "Sin conexión");
});

test("connectionSummary: hay red pero nadie responde (red corporativa que aísla equipos)", () => {
  const s = connectionSummary([link("Wi-Fi", "192.168.2.228"), link("Ethernet", "10.0.0.5")], []);
  assert.equal(s.title, "No veo otros equipos");
  assert.equal(s.tone, "warn");
  assert.match(s.detail, /Hay Wi-Fi y Ethernet \/ USB, pero nadie responde/);
});

test("connectionSummary: sin ningún enlace", () => {
  const s = connectionSummary([], []);
  assert.deepEqual(s, {
    title: "Sin conexión",
    detail: "No hay ningún enlace con otro equipo. Activa una de estas opciones.",
    tone: "error",
    anyLink: false,
  });
});

test("osKind y osName por plataforma", () => {
  assert.equal(osKind("android"), "android");
  assert.equal(osKind("Windows"), "windows");
  assert.equal(osKind("linux"), "linux");
  assert.equal(osKind("macos"), "apple");
  assert.equal(osKind("ios"), "apple");
  assert.equal(osKind("raro"), "other");
  assert.equal(osKind(undefined), "other");
  assert.equal(osName("android"), "Android");
  assert.equal(osName("x"), "Desconocido");
});

test("isPreAlpha: teléfono↔PC es lo principal; igual con igual aún está en pruebas", () => {
  const dev = (platform) => ({ platform });
  assert.equal(isPreAlpha("windows", dev("android")), false, "PC ↔ teléfono");
  assert.equal(isPreAlpha("android", dev("windows")), false, "teléfono ↔ PC");
  assert.equal(isPreAlpha("windows", dev("linux")), true, "PC ↔ PC");
  assert.equal(isPreAlpha("windows", dev("windows")), true);
  assert.equal(isPreAlpha("android", dev("android")), true, "teléfono ↔ teléfono");
});

test("mediumOf: túnel USB, anuncio del teléfono, o la red donde se ve", () => {
  const dev = (over) => ({ address: "10.98.181.78", info: {}, link: null, ...over });
  const links = [link("Wi-Fi", "192.168.2.5"), link("Ethernet 3", "10.98.181.30")];
  assert.deepEqual(mediumOf(dev({ link: "usb-adb", address: "127.0.0.1" }), links), { key: "usb", label: "USB · cable" });
  assert.deepEqual(mediumOf(dev({ info: { link: "usb" } }), []), { key: "usb", label: "USB" });
  assert.deepEqual(mediumOf(dev({ info: { link: "wifi" } }), []), { key: "wifi", label: "Wi-Fi" });
  assert.deepEqual(mediumOf(dev({ info: { link: "hotspot" } }), []), { key: "wifi", label: "Zona Wi-Fi" });
  assert.deepEqual(mediumOf(dev({}), links), { key: "net", label: "Red" }, "misma subred que Ethernet 3");
  assert.deepEqual(mediumOf(dev({ address: "192.168.2.77" }), links), { key: "wifi", label: "Wi-Fi" });
  assert.equal(mediumOf(dev({ address: "8.8.8.8" }), links), null);
});

test("usbLinkText explica cada estado del enlace por cable", () => {
  const st = (over) => ({ adb_found: true, devices: 0, unauthorized: 0, linked: false, ...over });
  assert.equal(usbLinkText(st({ linked: true })).active, true);
  assert.match(usbLinkText(st({ unauthorized: 1 })).text, /Permitir depuración USB/);
  assert.match(usbLinkText(st({ devices: 1 })).text, /abriendo el enlace/);
  assert.match(usbLinkText(st({ adb_found: false })).text, /instala las herramientas adb/);
  assert.match(usbLinkText(st()).text, /Conecta el cable/);
  assert.equal(usbLinkText(st()).active, false);
});

test("activityOf cuenta lo que está en curso y el avance total", () => {
  const t = (over) => ({ id: 1, incoming: true, state: "active", done: 0, total: 0, ...over });
  assert.deepEqual(activityOf([]), { active: 0, incoming: 0, outgoing: 0, progress: 0, text: "Sin transferencias activas" });
  assert.equal(activityOf([t({ state: "finished" }), t({ state: "failed" })]).active, 0, "las terminadas no cuentan");

  const a = activityOf([t({ done: 50, total: 100 }), t({ incoming: false, done: 50, total: 300 })]);
  assert.equal(a.active, 2);
  assert.equal(a.incoming, 1);
  assert.equal(a.outgoing, 1);
  assert.equal(a.progress, 0.25, "100 de 400 bytes");
  assert.equal(a.text, "↓ 1  ↑ 1 en curso · 25 %");

  assert.equal(activityOf([t({ incoming: false, done: 5, total: 0 })]).text, "↑ 1 en curso", "sin total no hay porcentaje");
});

test("statusLine: conectado con el medio, sin dispositivos o sin red", () => {
  const dev = (over) => ({ id: "x", name: "R", platform: "android", address: "127.0.0.1", port: 1, paired: true, info: {}, link: "usb-adb", ...over });
  assert.deepEqual(statusLine([], [dev({})]), { tone: "ok", text: "Conectado · USB · cable", devices: "1 dispositivo" });
  assert.deepEqual(statusLine([], [dev({}), dev({ id: "y", link: null, info: { link: "wifi" } })]), {
    tone: "ok", text: "Conectado · USB · cable · Wi-Fi", devices: "2 dispositivos",
  });
  assert.equal(statusLine([link("Wi-Fi", "192.168.2.5")], []).tone, "warn");
  assert.equal(statusLine([link("Wi-Fi", "192.168.2.5")], []).text, "No veo otros equipos");
  assert.deepEqual(statusLine([], []), { tone: "error", text: "Sin conexión", devices: "0 dispositivos" });
});

const entry = (over) => ({ uid: "1-1", peer: "Redmi", file: "a.txt", size: 10, incoming: true, ok: true, error: null, path: null, at: 1000, ...over });

test("visibleHistory: hasta 20 por pestaña, lo más reciente primero", () => {
  const all = [
    ...Array.from({ length: 25 }, (_, i) => entry({ uid: `r${i}`, incoming: true, at: 1000 + i })),
    ...Array.from({ length: 25 }, (_, i) => entry({ uid: `s${i}`, incoming: false, at: 5000 + i })),
  ];
  assert.equal(HISTORY_LIMIT, 20);
  assert.equal(visibleHistory(all, "in").length, 20);
  assert.equal(visibleHistory(all, "out").length, 20);
  assert.equal(visibleHistory(all, "all").length, 20, "Todo también se queda en 20");
  assert.ok(visibleHistory(all, "in").every((e) => e.incoming));
  assert.ok(visibleHistory(all, "out").every((e) => !e.incoming));
  assert.equal(visibleHistory(all, "in")[0].uid, "r24", "la más reciente primero");
  assert.equal(visibleHistory(all, "all")[0].uid, "s24");
  assert.deepEqual(visibleHistory([], "all"), []);
});

test("whenLabel: siempre fecha y hora", () => {
  const at = (y, m, d, h, min) => new Date(y, m - 1, d, h, min).getTime();
  const now = at(2026, 11, 18, 18, 0);
  assert.equal(whenLabel(at(2026, 11, 18, 16, 31), now), "18/11/2026 · 16:31");
  assert.equal(whenLabel(at(2026, 11, 17, 9, 5), now), "17/11/2026 · 09:05");
  assert.equal(whenLabel(at(2026, 11, 1, 8, 0), now), "01/11/2026 · 08:00");
  assert.equal(whenLabel(at(2025, 12, 31, 23, 0), now), "31/12/2025 · 23:00");
});

test("entryAsTransfer reutiliza la fila de transferencias", () => {
  const ok = entryAsTransfer(entry({ path: "C:\\r\\a.txt" }));
  assert.equal(ok.state, "finished");
  assert.equal(ok.done, 10);
  assert.equal(ok.path, "C:\\r\\a.txt");
  const bad = entryAsTransfer(entry({ ok: false, error: "Cancelado", size: -1 }));
  assert.equal(bad.state, "failed");
  assert.equal(bad.error, "Cancelado");
  assert.equal(bad.done, 0);
});
