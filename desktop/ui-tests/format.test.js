import assert from "node:assert/strict";
import { test } from "node:test";
import { formatBytes, formatGb } from "../ui/js/format.js";

test("formatBytes elige la unidad adecuada", () => {
  assert.equal(formatBytes(0), "0 B");
  assert.equal(formatBytes(1023), "1023 B");
  assert.equal(formatBytes(2048), "2 KB");
  assert.equal(formatBytes(31_457_280), "30.0 MB");
  assert.equal(formatBytes(3 * 2 ** 30), "3.00 GB");
});

test("formatBytes devuelve ? para tamaños desconocidos", () => {
  assert.equal(formatBytes(-1), "?");
  assert.equal(formatBytes(NaN), "?");
  assert.equal(formatBytes(undefined), "?");
});

test("formatGb usa un decimal por debajo de 10 GB y ninguno desde 10 GB", () => {
  assert.equal(formatGb(2.5 * 2 ** 30), "2.5 GB");
  assert.equal(formatGb(176 * 2 ** 30), "176 GB");
  assert.equal(formatGb(9.99 * 2 ** 30), "10.0 GB");
});
