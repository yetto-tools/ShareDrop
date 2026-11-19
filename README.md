# ShareDrop

Compartir archivos en ambos sentidos entre un celular Android y una laptop (Windows o Linux), sin internet y sin nube. Protocolo en [`protocol/PROTOCOL.md`](protocol/PROTOCOL.md).

## Estructura
| Carpeta | Qué es |
|---|---|
| `desktop/core` | Núcleo en Rust (descubrimiento, emparejamiento, cifrado, transferencia). Compartido por todo lo demás |
| `desktop/app` + `desktop/ui` | App de escritorio con **Tauri 2** (Windows, Ubuntu/Debian, Fedora, Rocky 10) |
| `desktop/cli` | Versión en terminal para **Rocky 9** y servidores (no necesita WebKitGTK 4.1) |
| `android` | App Android en Kotlin (referencia actual; se migrará al núcleo en Rust) |
| `windows` | Cliente WPF en C#, solo como referencia del protocolo |

## Estado: Fase 1 (núcleo por TCP)
Funciona por cualquier medio con conectividad IP: misma Wi-Fi, hotspot del celular, USB tethering o Bluetooth tethering. La laptop no cambia de red.
Emparejamiento por **QR** (token de 128 bits) listo en el núcleo, el escritorio y el CLI; falta el escáner en Android.
Pendiente: BLE, Wi-Fi Direct, Bluetooth RFCOMM, varios enlaces a la vez, reanudación, escáner QR en Android, Android en Rust.

## Compilar y probar
```
cd desktop
cargo test -p sharedrop-core          # protocolo (Rust contra Rust)
cargo build -p sharedrop-desktop      # app Tauri (dev)
cargo tauri build                     # instaladores (desde desktop/app): .deb, .rpm, AppImage, NSIS
cargo build -p sharedrop-cli          # versión terminal
```
Android: `cd android && gradlew.bat assembleDebug` y `adb install -r app\build\outputs\apk\debug\app-debug.apk`.
Pruebas de C# (incluyen vectores cruzados con Rust): `cd windows\ShareDrop.Tests && dotnet test`.

## Conectar por cable USB
Hay dos formas, y la app muestra cuál se usa con una etiqueta bajo "Emparejado · dirección":
- **USB tethering** (etiqueta "USB"): el teléfono comparte su conexión y la PC lo ve como una red.
- **Enlace por cable con adb** (etiqueta "USB · cable"): sin red ni tethering, funciona aunque la red aísle a los equipos.
  Requiere `adb` en la PC (se busca en el PATH, en `ADB` y en el SDK de Android) y la **depuración USB** activa y autorizada en el teléfono.
  La app de escritorio abre sola los túneles `adb forward tcp:47811 tcp:47800` y `adb reverse tcp:47810 tcp:47800`.
  Solo se enlaza un teléfono a la vez. Detalles del sondeo en [`protocol/PROTOCOL.md`](protocol/PROTOCOL.md).

Cada dispositivo muestra el icono de su sistema (Android en verde, Windows, Linux) y un botón ⓘ con su ficha de información.
Teléfono↔teléfono y PC↔PC funcionan pero se marcan como **Pre-alpha**.

## Historial
Se abre **deslizando de izquierda a derecha** en la pantalla de inicio (o desde el menú "•••"); de derecha a izquierda vuelve.
Guarda en disco las últimas **20 transferencias recibidas y las últimas 20 enviadas**; cada pestaña (Todo, Recibidos, Enviados)
muestra como máximo 20. Los archivos recibidos se pueden abrir desde el historial. En escritorio el gesto funciona con el ratón
(arrastrando), con el dedo o con dos dedos en el panel táctil. El historial está en `history.json` junto a `store.json`.
Si abres a la vez la app y `sharedrop-cli` en la misma PC, cada una guarda su propia lista: usa solo una a la vez.

## Abrir o cerrar la app de escritorio por comando
Solo hay una instancia: ejecutar el programa otra vez actúa sobre la que ya corre.
```
sharedrop-desktop --show         abre la ventana o la trae al frente (también: sharedrop://open)
sharedrop-desktop --minimized    arranca oculto, solo en la bandeja (lo usa "Iniciar con el sistema")
sharedrop-desktop --quit         cierra ShareDrop por completo
```
`sharedrop://open` se puede lanzar desde un acceso directo, el navegador o `start sharedrop://open`. Lo registra el instalador en Windows; en Linux lo registra la app al arrancar.

## Compilar los instaladores
```
cd desktop\app
cargo tauri build --bundles nsis     # Windows: desktop\target\release\bundle\nsis\ShareDrop_*_x64-setup.exe
cargo tauri build                    # en Linux: .deb, .rpm y AppImage (no hay compilación cruzada)
```
El ejecutable suelto queda en `desktop\target\release\sharedrop-desktop.exe`.
Pruebas de la interfaz: `cd desktop && npm test`.

## Requisitos en Linux para la app Tauri
`webkit2gtk-4.1` y `libsoup3`. Ubuntu/Debian y Fedora los traen. Rocky 10: activar EPEL. **Rocky 9 no tiene `webkit2gtk-4.1`: usa `sharedrop-cli`.**
Firewall: abrir TCP 47800 y UDP 47801 (`ufw allow 47800/tcp && ufw allow 47801/udp` o `firewall-cmd --add-port=47800/tcp --add-port=47801/udp`).

## Usar
1. Abre ShareDrop en los dos equipos. Windows pedirá permiso de firewall: acepta en redes privadas.
2. Los equipos aparecen en la lista. Si no, la red puede aislar clientes: usa el hotspot del celular o USB tethering.
3. Primera vez: el receptor muestra un **QR** (y el mismo código en texto). El emisor lo escanea, o pega el código si no tiene cámara. Los equipos antiguos que no entienden el QR siguen con el código de 6 dígitos. Después se conectan solos.
4. Escritorio → celular: arrastra archivos sobre el dispositivo o pulsa "Enviar…". Celular → escritorio: "Enviar" en la app o "Compartir" desde cualquier app. Se guardan en `Download/ShareDrop` (celular) y `Descargas/ShareDrop` (escritorio).
5. Terminal: `sharedrop-cli receive`, `sharedrop-cli list`, `sharedrop-cli send <ip> archivo…`.

## Limitaciones conocidas
- El QR usa un secreto de 128 bits y no se puede adivinar offline. El código de 6 dígitos que usan los equipos sin QR (hoy, el APK actual) sí es vulnerable a fuerza bruta offline si alguien captura el handshake: empareja en una red de confianza.
- El escritorio no tiene escáner: si el escritorio inicia la conexión, hay que pegar el código largo que muestra el teléfono.
- El cifrado es AES-256-GCM por trama con clave derivada del secreto emparejado (no TLS).
- Probado: Rust↔Rust por loopback y vectores idénticos Rust↔C#. **No probado aún con un celular real**, ni el APK contra el núcleo en Rust.
