// Diálogos de emparejamiento: uno muestra el código (receptor) y otro lo pide (emisor).
// Los equipos nuevos usan un QR (más el mismo token como texto); los antiguos, un PIN de 6 dígitos.

const PIN_LENGTH = 6;
const TOKEN_LENGTH = 32;

/** El token puede llegar como `sharedrop://pair?t=…` o suelto, con espacios o guiones. */
export function extractToken(text) {
  const i = text.indexOf("t=");
  const raw = i >= 0 ? text.slice(i + 2).split("&")[0] : text;
  const token = raw.replace(/[\s-]/g, "").toLowerCase();
  return new RegExp(`^[0-9a-f]{${TOKEN_LENGTH}}$`).test(token) ? token : null;
}

/**
 * @param {object} deps
 * @param {{ dialog: HTMLDialogElement, title: HTMLElement, hint: HTMLElement, qr: HTMLImageElement,
 *           code: HTMLElement }} deps.show
 * @param {{ dialog: HTMLDialogElement, title: HTMLElement, hint: HTMLElement, form: HTMLFormElement,
 *           input: HTMLInputElement, ok: HTMLButtonElement, cancel: HTMLElement }} deps.ask
 * @param {(code: string|null) => Promise<void>} deps.answerCode  `null` = el usuario canceló.
 */
export function createPairing({ show, ask, answerCode }) {
  let useToken = false;

  ask.input.addEventListener("input", () => {
    if (useToken) {
      ask.ok.disabled = extractToken(ask.input.value) === null;
    } else {
      ask.input.value = ask.input.value.replace(/\D/g, "").slice(0, PIN_LENGTH);
      ask.ok.disabled = ask.input.value.length !== PIN_LENGTH;
    }
  });

  ask.form.addEventListener("submit", () => answerCode(ask.input.value));
  ask.cancel.addEventListener("click", () => {
    ask.dialog.close();
    answerCode(null);
  });
  // Escape también cancela.
  ask.dialog.addEventListener("cancel", () => answerCode(null));

  return {
    /** El otro equipo quiere emparejarse: se muestra el QR (o el PIN) para usarlo allí. `qr` es un SVG. */
    showCode({ peer, code, qr }) {
      show.title.textContent = `Emparejar con ${peer}`;
      show.code.textContent = code;
      show.code.classList.toggle("token", Boolean(qr));
      show.hint.textContent = qr ? "Escanea el QR con el otro equipo" : "Escribe este código en el otro equipo";
      if (qr) {
        show.qr.src = `data:image/svg+xml;charset=utf-8,${encodeURIComponent(qr)}`;
        show.qr.hidden = false;
      } else {
        show.qr.removeAttribute("src");
        show.qr.hidden = true;
      }
      show.dialog.showModal();
    },
    hideCode() {
      if (show.dialog.open) show.dialog.close();
      show.qr.removeAttribute("src");
      show.code.textContent = "";
    },
    /** Hay que emparejar con otro equipo: se pide lo que ese equipo muestra (token del QR o PIN). */
    askCode({ peer, qr }) {
      useToken = Boolean(qr);
      ask.title.textContent = `Emparejar con ${peer}`;
      ask.hint.textContent = useToken
        ? "Pega el código que muestra el otro equipo. Si tienes la app del celular, escanea su QR allí."
        : "Escribe el código de 6 dígitos que muestra el otro equipo";
      ask.input.classList.toggle("token", useToken);
      ask.input.maxLength = useToken ? 256 : PIN_LENGTH;
      ask.input.inputMode = useToken ? "text" : "numeric";
      ask.input.placeholder = useToken ? "Código largo" : "••••••";
      ask.input.setAttribute("aria-label", useToken ? "Código de emparejamiento" : "Código de 6 dígitos");
      ask.input.value = "";
      ask.ok.disabled = true;
      ask.dialog.showModal();
      ask.input.focus();
    },
  };
}
