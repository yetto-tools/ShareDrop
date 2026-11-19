# ShareDrop protocolo v1 (Fase 1)

Puertos: **TCP 47800** (sesión), **UDP 47801** (descubrimiento por broadcast).

## Descubrimiento
Cada equipo envía cada 2 s un datagrama UDP JSON a `255.255.255.255` y al broadcast de cada interfaz:
`{"app":"sharedrop","v":1,"id":"<uuid>","name":"<nombre>","platform":"android|windows","port":47800}`
La dirección del emisor del datagrama es la dirección IP del equipo. Un equipo no visto en 8 s se descarta.

## Tramas
`[u32 BE longitud][cuerpo]`, longitud máxima 8 MiB.
- Antes de `AUTH_OK`: cuerpo = `[u8 tipo][payload]` en claro.
- Después: cuerpo = `[u64 BE contador][AES-256-GCM(tipo ‖ payload)]` con nonce `[u32 BE prefijo][u64 BE contador]` (prefijo 1 = cliente, 2 = servidor) y tag de 16 bytes. El contador debe ser estrictamente creciente por dirección.

Tipos: 1 HELLO, 2 AUTH, 3 AUTH_OK, 4 PAIR_SECRET, 5 OFFER, 6 ACCEPT, 7 FILE_START, 8 DATA, 9 FILE_END, 10 DONE, 12 ERROR.
Los payloads de control son JSON UTF-8; DATA es binario.

## Handshake
1. C→S `HELLO {id, name, nonce(hex16), qr: true}`; `qr` indica que el cliente entiende el emparejamiento por token (puede faltar en clientes antiguos).
2. S→C `HELLO {id, name, nonce, paired, pair}`; `paired` = el servidor conoce el id del cliente. `pair` es `"qr"` o `"pin"` y solo importa en emparejamiento.
3. Secreto: si `paired` y el cliente tiene el secreto guardado, es ese. Si no, es el modo **emparejamiento**:
   - **`qr`** (cuando el cliente envió `qr: true`): el servidor genera un token de 128 bits (32 dígitos hex) y lo muestra como QR con el URI `sharedrop://pair?t=<token>` y como texto en grupos de 4. El cliente lo escanea o pega (se acepta el URI o el token suelto, sin espacios ni guiones, en cualquier caja) y el secreto es `SHA256("sharedrop-qr:" + token_en_minúsculas)`.
   - **`pin`** (clientes antiguos): el servidor genera un código de 6 dígitos, el usuario lo escribe en el cliente y el secreto es `SHA256("sharedrop-pin:" + código)`.
4. C→S `AUTH {proof = HMAC(secreto, "C" ‖ nonceC ‖ nonceS)}`
5. S→C `AUTH_OK {proof = HMAC(secreto, "S" ‖ nonceC ‖ nonceS)}` (autenticación mutua). Clave de sesión = `HMAC(secreto, "key" ‖ nonceC ‖ nonceS)`; desde aquí todo va cifrado.
6. Solo en emparejamiento: S→C `PAIR_SECRET {secret(hex32)}`; ambos lo guardan para el id del otro.

## Sondeo (probe)
Un cliente puede preguntar quién hay al otro lado sin emparejar ni transferir: envía `HELLO {id, name, nonce, probe: true}`.
El servidor responde `HELLO {id, name, nonce, paired, platform, info}` y cierra la conexión, **sin** mostrar código de emparejamiento
ni registrar una transferencia fallida. Sirve cuando no hay descubrimiento por broadcast, como en el enlace USB por adb.

## Enlace USB por adb (sin red)
Con solo el cable y la depuración USB, el escritorio abre dos túneles (`adb forward tcp:47811 tcp:47800` y
`adb reverse tcp:47810 tcp:47800`). El escritorio sondea `127.0.0.1:47811` (el teléfono) y el teléfono sondea
`127.0.0.1:47810` (la PC). Funciona aunque la red aísle a los equipos.

## Transferencia
C→S `OFFER {files:[{name,size}]}` → S→C `ACCEPT`.
Por cada archivo: `FILE_START {name,size}`, N × `DATA` (binario, ≤ 512 KiB), `FILE_END`.
Al final C→S `DONE`, S→C `DONE`. Cualquier fallo: `ERROR {message}`.

## Limitaciones conocidas
- El modo `pin` (solo para clientes que no envían `qr: true`) es vulnerable a fuerza bruta offline por quien capture el handshake; el modo `qr` no, porque el token tiene 128 bits y no viaja por la red. Un cliente puede omitir `qr` para forzar `pin`, pero entonces el atacante solo tiene un intento en línea por cada código mostrado. Se retirará `pin` cuando la app Android entienda `qr`.
- Quien se empareja desde un teléfono escanea el QR del escritorio. El escritorio no escanea: si es él quien inicia, pega el token que muestra el teléfono.
