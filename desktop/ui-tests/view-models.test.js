import assert from "node:assert/strict";
import { test } from "node:test";
import {
  deviceStatus,
  directionOf,
  hardwareLine,
  isOpenable,
  isPhone,
  pairingLine,
  transferProgress,
  transferStatus,
} from "../ui/js/view-models.js";

const transfer = (over = {}) => ({
  id: 1, peer: "Redmi", file: "a.txt", done: 0, total: 0, incoming: true, state: "active", error: null, path: null, ...over,
});

test("transferProgress", () => {
  assert.equal(transferProgress(transfer({ done: 50, total: 200 })), 0.25);
  assert.equal(transferProgress(transfer({ done: 999, total: 100 })), 1, "no pasa de 1");
  assert.equal(transferProgress(transfer({ done: 5, total: 0 })), 0, "total desconocido");
  assert.equal(transferProgress(transfer({ done: 5, total: -1 })), 0);
});

test("directionOf distingue descarga (recibido) de subida (enviado) y sigue al estado", () => {
  const dir = (over) => directionOf(transfer(over));
  assert.deepEqual(dir({ incoming: true, state: "active" }), { key: "in", label: "Recibiendo", symbol: "↓" });
  assert.deepEqual(dir({ incoming: true, state: "finished" }), { key: "in", label: "Recibido", symbol: "↓" });
  assert.deepEqual(dir({ incoming: true, state: "failed" }), { key: "in", label: "Recepción", symbol: "↓" });
  assert.deepEqual(dir({ incoming: false, state: "active" }), { key: "out", label: "Enviando", symbol: "↑" });
  assert.deepEqual(dir({ incoming: false, state: "finished" }), { key: "out", label: "Enviado", symbol: "↑" });
  assert.deepEqual(dir({ incoming: false, state: "failed" }), { key: "out", label: "Envío", symbol: "↑" });
});

test("transferStatus dice el equipo (De/A) y el tamaño, sin repetir la dirección", () => {
  assert.deepEqual(transferStatus(transfer({ done: 1024, total: 2048 })), { text: "De Redmi · 1 KB de 2 KB", tone: "active" });
  assert.deepEqual(transferStatus(transfer({ done: 1024, total: 2048, incoming: false })), {
    text: "A Redmi · 1 KB de 2 KB", tone: "active",
  });
  assert.deepEqual(transferStatus(transfer({ state: "finished", total: 2048 })), { text: "De Redmi · 2 KB", tone: "ok" });
  assert.deepEqual(transferStatus(transfer({ state: "finished", total: 2048, incoming: false })), {
    text: "A Redmi · 2 KB", tone: "ok",
  });
  assert.deepEqual(transferStatus(transfer({ state: "failed", error: "Cancelado" })), {
    text: "De Redmi · No se pudo completar: Cancelado", tone: "error",
  });
});

test("transferStatus sin nombre de equipo ni motivo", () => {
  const s = transferStatus(transfer({ peer: "", state: "failed", error: null }));
  assert.equal(s.text, "No se pudo completar: error desconocido");
  assert.equal(transferStatus(transfer({ peer: "", state: "finished", total: 2048 })).text, "2 KB");
});

test("isOpenable solo para archivos recibidos con ruta", () => {
  assert.equal(isOpenable(transfer({ path: "C:\\x\\a.txt" })), true);
  assert.equal(isOpenable(transfer({ path: null })), false, "aún sin guardar");
  assert.equal(isOpenable(transfer({ incoming: false, path: "C:\\x\\a.txt" })), false, "los envíos no se abren");
});

test("hardwareLine combina fabricante, modelo y sistema", () => {
  assert.equal(hardwareLine({ maker: "Xiaomi", model: "24094RAD4G", os: "Android 16" }), "Xiaomi 24094RAD4G · Android 16");
  assert.equal(hardwareLine({ os: "Android 16" }), "Android 16");
  assert.equal(hardwareLine({}), "");
  assert.equal(hardwareLine(undefined), "");
});

test("deviceStatus devuelve null sin datos y los formatea con datos", () => {
  assert.equal(deviceStatus({}), null);
  assert.equal(deviceStatus(undefined), null);

  const s = deviceStatus({ battery: 98, charging: true, free: 176 * 2 ** 30, total: 228 * 2 ** 30 });
  assert.deepEqual(s.battery, { level: 98, charging: true, low: false });
  assert.equal(s.storage, "176 GB libres de 228 GB");
});

test("batería baja solo si no se está cargando", () => {
  assert.equal(deviceStatus({ battery: 15, charging: false }).battery.low, true);
  assert.equal(deviceStatus({ battery: 15, charging: true }).battery.low, false);
  assert.equal(deviceStatus({ battery: 15 }).battery.low, true);
});

test("almacenamiento ignorado si el total es 0", () => {
  assert.equal(deviceStatus({ free: 5, total: 0 }), null);
});

test("pairingLine e isPhone", () => {
  assert.equal(pairingLine({ paired: true, address: "10.0.0.2" }), "Emparejado · 10.0.0.2");
  assert.equal(pairingLine({ paired: false, address: "10.0.0.2" }), "Sin emparejar · 10.0.0.2");
  assert.equal(isPhone({ platform: "android" }), true);
  assert.equal(isPhone({ platform: "windows" }), false);
});
