import test from "node:test";
import assert from "node:assert/strict";
import { applyTheme, loadTheme, normalizeTheme, saveTheme } from "../ui/js/theme.js";

const fakeDoc = () => ({ documentElement: { dataset: {} } });

test("normalizeTheme: lo desconocido es Sistema", () => {
  assert.equal(normalizeTheme("dark"), "dark");
  assert.equal(normalizeTheme("light"), "light");
  assert.equal(normalizeTheme("otro"), "system");
  assert.equal(normalizeTheme(null), "system");
});

test("applyTheme: Sistema quita el atributo; Claro y Oscuro lo fijan", () => {
  const doc = fakeDoc();
  applyTheme("dark", doc);
  assert.equal(doc.documentElement.dataset.theme, "dark");
  applyTheme("light", doc);
  assert.equal(doc.documentElement.dataset.theme, "light");
  applyTheme("system", doc);
  assert.equal(doc.documentElement.dataset.theme, undefined);
});

test("guardar y cargar, y no falla sin almacenamiento", () => {
  const mem = new Map();
  const storage = { getItem: (k) => mem.get(k) ?? null, setItem: (k, v) => mem.set(k, v) };
  assert.equal(loadTheme(storage), "system");
  saveTheme("dark", storage);
  assert.equal(loadTheme(storage), "dark");
  const broken = { getItem() { throw new Error("x"); }, setItem() { throw new Error("x"); } };
  assert.equal(loadTheme(broken), "system");
  assert.doesNotThrow(() => saveTheme("light", broken));
});
