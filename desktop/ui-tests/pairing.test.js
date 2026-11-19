// Diálogos de emparejamiento con elementos falsos: QR + token en equipos nuevos, PIN en los antiguos.

import assert from "node:assert/strict";
import { test } from "node:test";
import { createPairing, extractToken } from "../ui/js/components/pairing.js";

const TOKEN = "00112233445566778899aabbccddeeff";

function fakeEl() {
  const handlers = {};
  const classes = new Set();
  return {
    hidden: false,
    textContent: "",
    value: "",
    disabled: false,
    attrs: {},
    classList: {
      add: (c) => classes.add(c),
      remove: (c) => classes.delete(c),
      has: (c) => classes.has(c),
      toggle: (c, on) => (on ? classes.add(c) : classes.delete(c)),
    },
    addEventListener: (type, fn) => (handlers[type] ??= []).push(fn),
    fire: (type, event = {}) => (handlers[type] ?? []).forEach((fn) => fn(event)),
    focus() {},
    setAttribute(k, v) { this.attrs[k] = v; },
    removeAttribute(k) { delete this[k]; },
  };
}

function fakeDialog() {
  const d = fakeEl();
  d.open = false;
  d.showModal = () => (d.open = true);
  d.close = () => (d.open = false);
  return d;
}

function setup() {
  const show = { dialog: fakeDialog(), title: fakeEl(), hint: fakeEl(), qr: fakeEl(), code: fakeEl() };
  const ask = { dialog: fakeDialog(), title: fakeEl(), hint: fakeEl(), form: fakeEl(), input: fakeEl(), ok: fakeEl(), cancel: fakeEl() };
  const answers = [];
  const pairing = createPairing({ show, ask, answerCode: (c) => answers.push(c) });
  return { show, ask, answers, pairing };
}

test("extractToken: acepta URI, token suelto y agrupado; rechaza PIN y basura", () => {
  assert.equal(extractToken(`sharedrop://pair?t=${TOKEN}`), TOKEN);
  assert.equal(extractToken("0011 2233 4455 6677 8899 AABB CCDD EEFF "), TOKEN);
  assert.equal(extractToken(TOKEN), TOKEN);
  assert.equal(extractToken("123456"), null);
  assert.equal(extractToken(""), null);
  assert.equal(extractToken(TOKEN + "00"), null);
});

test("showCode con QR: muestra la imagen, el token y oculta el PIN; hideCode la limpia", () => {
  const { show, pairing } = setup();
  pairing.showCode({ peer: "Pixel", code: "0011 2233", qr: "<svg xmlns='http://www.w3.org/2000/svg'/>" });
  assert.equal(show.dialog.open, true);
  assert.equal(show.qr.hidden, false);
  assert.ok(show.qr.src.startsWith("data:image/svg+xml;charset=utf-8,%3Csvg"), show.qr.src);
  assert.equal(show.code.classList.has("token"), true);
  pairing.hideCode();
  assert.equal(show.dialog.open, false);
  assert.equal(show.code.textContent, "");
});

test("showCode sin QR (equipo antiguo): solo el PIN", () => {
  const { show, pairing } = setup();
  pairing.showCode({ peer: "Viejo", code: "123456", qr: null });
  assert.equal(show.qr.hidden, true);
  assert.equal(show.code.textContent, "123456");
  assert.equal(show.code.classList.has("token"), false);
});

test("askCode con token: solo habilita Aceptar con un token válido y lo envía tal cual", () => {
  const { ask, answers, pairing } = setup();
  pairing.askCode({ peer: "Laptop", qr: true });
  assert.equal(ask.ok.disabled, true);
  ask.input.value = "123456";
  ask.input.fire("input");
  assert.equal(ask.ok.disabled, true, "un PIN no vale como token");
  ask.input.value = `sharedrop://pair?t=${TOKEN}`;
  ask.input.fire("input");
  assert.equal(ask.ok.disabled, false);
  ask.form.fire("submit");
  assert.deepEqual(answers, [`sharedrop://pair?t=${TOKEN}`]);
});

test("askCode con PIN: solo dígitos y 6 de largo", () => {
  const { ask, pairing } = setup();
  pairing.askCode({ peer: "Viejo", qr: false });
  assert.equal(ask.input.maxLength, 6);
  ask.input.value = "12a34b5678";
  ask.input.fire("input");
  assert.equal(ask.input.value, "123456");
  assert.equal(ask.ok.disabled, false);
});

test("cancelar responde null", () => {
  const { ask, answers, pairing } = setup();
  pairing.askCode({ peer: "X", qr: true });
  ask.cancel.fire("click");
  assert.deepEqual(answers, [null]);
  assert.equal(ask.dialog.open, false);
});
