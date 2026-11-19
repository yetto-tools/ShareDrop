// Comprobaciones estáticas de index.html: un id que falta o un comentario sin cerrar hacen que la
// interfaz no arranque ("Falta #… en index.html"), y eso no lo detectan las pruebas de componentes.

import assert from "node:assert/strict";
import { readdirSync, readFileSync, statSync } from "node:fs";
import { join } from "node:path";
import { test } from "node:test";

const ui = new URL("../ui/", import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, "$1");
const html = readFileSync(join(ui, "index.html"), "utf8");

function jsFiles(dir) {
  return readdirSync(dir).flatMap((name) => {
    const p = join(dir, name);
    return statSync(p).isDirectory() ? jsFiles(p) : p.endsWith(".js") ? [p] : [];
  });
}

test("index.html: todos los comentarios están cerrados", () => {
  const opens = (html.match(/<!--/g) ?? []).length;
  const closes = (html.match(/-->/g) ?? []).length;
  assert.equal(opens, closes, `hay ${opens} «<!--» y ${closes} «-->»: un comentario sin cerrar oculta el resto de la página`);
  // Un comentario no puede contener otra apertura.
  for (const c of html.match(/<!--[\s\S]*?-->/g) ?? []) assert.ok(!c.slice(4).includes("<!--"), `comentario anidado: ${c.slice(0, 50)}`);
});

test("index.html: define todos los ids que main.js pide con byId()", () => {
  const defined = new Set([...html.replace(/<!--[\s\S]*?-->/g, "").matchAll(/\bid="([^"]+)"/g)].map((m) => m[1]));
  const wanted = new Set();
  for (const file of jsFiles(ui)) {
    for (const m of readFileSync(file, "utf8").matchAll(/byId\("([^"]+)"\)/g)) wanted.add(m[1]);
  }
  assert.ok(wanted.size > 20, "debería encontrar los ids que usa main.js");
  const missing = [...wanted].filter((id) => !defined.has(id));
  assert.deepEqual(missing, [], `faltan en index.html: ${missing.join(", ")}`);
});

test("index.html: cada pantalla y cada opción del menú existen", () => {
  const body = html.replace(/<!--[\s\S]*?-->/g, "");
  const screens = [...body.matchAll(/<section id="screen-([a-z]+)"/g)].map((m) => m[1]);
  assert.deepEqual(screens.sort(), ["connection", "folder", "general", "history", "home"]);
  for (const m of body.matchAll(/data-go="([a-z]+)"/g)) assert.ok(screens.includes(m[1]), `el menú apunta a una pantalla inexistente: ${m[1]}`);
});

test("index.html: los botones «atrás» están dentro de una pantalla que no es Inicio", () => {
  const body = html.replace(/<!--[\s\S]*?-->/g, "");
  const home = body.match(/<section id="screen-home"[\s\S]*?<\/section>\s*(?=<section id="screen-)/)?.[0] ?? "";
  assert.ok(!home.includes("data-back"), "Inicio no tiene botón de atrás");
  assert.equal((body.match(/data-back/g) ?? []).length, 4, "historial, conexión, carpeta y general");
});
