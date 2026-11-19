import test from "node:test";
import assert from "node:assert/strict";
import { isBrowserShortcut } from "../ui/js/native-feel.js";

const key = (k, mods = {}) => ({ key: k, ctrlKey: false, metaKey: false, shiftKey: false, ...mods });

test("isBrowserShortcut: recarga, imprimir y herramientas sí; copiar y pegar no", () => {
  assert.ok(isBrowserShortcut(key("F5")));
  assert.ok(isBrowserShortcut(key("F12")));
  assert.ok(isBrowserShortcut(key("r", { ctrlKey: true })));
  assert.ok(isBrowserShortcut(key("I", { ctrlKey: true, shiftKey: true })));
  assert.ok(isBrowserShortcut(key("p", { metaKey: true })));
  assert.ok(!isBrowserShortcut(key("c", { ctrlKey: true })));
  assert.ok(!isBrowserShortcut(key("v", { ctrlKey: true })));
  assert.ok(!isBrowserShortcut(key("a", { ctrlKey: true })));
  assert.ok(!isBrowserShortcut(key("r")));
});
