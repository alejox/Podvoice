# Podcam

Usa la camara y el microfono del celular como fuente de video en el PC (estilo DroidCam),
por WiFi o USB, en **Windows, macOS y Linux**, sin instalar drivers y sin cuenta de Apple Developer.

- **Celular:** app Android nativa, o una pagina web (Android o iPhone) sin instalar nada.
- **PC:** `podcam.py` (Python, solo libreria estandar) para la pagina web y para conectar la app por USB.
- **Webcam virtual:** se usa la **Camara Virtual de OBS**, que ya esta firmada en los tres sistemas.

Hay dos formas de usar el celular:

| | App Android nativa (recomendada) | Pagina web (Android o iPhone) |
|---|---|---|
| Instalar en el celular | APK `dist/Podcam-0.1.0-debug.apk` | nada, se abre en el navegador |
| USB | solo el cable (`adb forward`), sin WiFi | necesita WiFi o anclaje USB |
| Pantalla apagada / otra app | si (servicio en primer plano) | no |
| En el PC | OBS lo abre directo (Fuente multimedia) | `podcam.py` + Fuente de navegador |

## App Android nativa

Camara → H.264 por hardware, microfono → AAC 48 kHz, empaquetado en MPEG-TS y servido por TCP
en el puerto **4747** del celular. OBS, VLC y ffmpeg lo leen sin nada extra.

**Instalar:** copia `dist/Podcam-0.1.0-debug.apk` al celular y abrelo (permite *Instalar apps desconocidas*),
o `adb install dist/Podcam-0.1.0-debug.apk`. Acepta los permisos de camara, microfono y notificaciones.
Requiere Android 8.0 o superior. La app se usa en horizontal.

**Conectar por USB (sin WiFi):**
1. Activa *Opciones de desarrollador › Depuracion USB* y conecta el cable (acepta el aviso del celular).
2. Instala [Platform-Tools](https://developer.android.com/tools/releases/platform-tools) (trae `adb`).
3. En la app pulsa **Iniciar**. En el PC: `python3 podcam.py usb`
   (equivale a `adb forward tcp:4747 tcp:4747`; con `--play` lo abre en ffplay).
4. En OBS: **Fuente › Fuente multimedia**, desmarca *Archivo local*, entrada `tcp://127.0.0.1:4747`,
   formato de entrada `mpegts`. Luego **Iniciar camara virtual**.

**Conectar por WiFi:** misma red; en OBS usa la URL `tcp://<IP-del-celular>:4747` que muestra la app
(o `python3 podcam.py wifi <IP> --play`).

En la app eliges camara trasera/frontal, 1080p/720p/480p, 4-16 Mbps y microfono si/no; los cambios se aplican
sin desconectar el PC. Sigue transmitiendo con la pantalla apagada (notificacion con boton *Detener*).

**Compilar:** `cd android && ./gradlew assembleDebug` (necesita el Android SDK con `sdk.dir` en `local.properties`
o `ANDROID_HOME`). Para publicar en Google Play: crea tu propia clave de firma y genera `bundleRelease`.

## Pagina web (sin instalar app)

1. Instala Python 3.8+ y [OBS Studio](https://obsproject.com) en el PC.
2. Ejecuta `python3 podcam.py` (en Windows: `py podcam.py`).
3. Conecta el celular:
   - **WiFi:** abre en el celular la URL `https://<IP-del-PC>:8443` que muestra el script y acepta
     el aviso del certificado (es un certificado local autofirmado; HTTPS es obligatorio para usar la camara).
   - **USB (Android):** activa *Depuracion USB* y ejecuta `adb reverse tcp:8080 tcp:8080`,
     luego abre `http://localhost:8080` en el celular. Ver la nota sobre USB abajo.
4. En el celular pulsa **Iniciar** (elige camara trasera/frontal y resolucion).
5. En OBS: **Fuente › Navegador**, URL `http://localhost:8080/viewer?hud=0`, 1920×1080,
   marca *Controlar audio a traves de OBS*. Luego **Iniciar camara virtual**.
   Zoom, Meet, Resolve, etc. veran "OBS Virtual Camera".

Para solo mirar o grabar, basta abrir `http://localhost:8080/viewer` en el navegador del PC.

## Permisos en macOS (sin pagar Apple Developer)

- `podcam.py` es un script, no una app: no pasa por Gatekeeper ni necesita firma.
- La primera vez macOS puede pedir permiso para aceptar conexiones de red entrantes: **Permitir**.
- OBS pide permiso de *Grabacion de pantalla* y la camara virtual se activa desde OBS
  (la extension de camara de OBS ya va firmada y notarizada por OBS).
- Ninguna parte del proyecto instala extensiones del sistema.

## Nota sobre USB (solo pagina web)

`adb reverse` lleva por el cable la pagina y la senalizacion (TCP). El video WebRTC va por UDP, asi que el
celular y el PC necesitan una red en comun:
- celular y PC en el mismo WiFi (el cable solo da estabilidad a la senalizacion), o
- **Anclaje USB** activado en el celular (crea una red por el cable; funciona sin WiFi).
  En Windows y Linux suele funcionar directo; en macOS depende del modelo de Android.
  En iPhone el equivalente es *Compartir Internet* por cable, que macOS soporta de serie.

## Limitaciones

- Pagina web: un celular y un visor a la vez; mantener la pantalla encendida y el navegador en primer plano.
- En Windows, `openssl` viene con Git for Windows; sin `openssl` no hay HTTPS y solo funciona el modo USB.
- Pagina web: probada de extremo a extremo con Chromium y camara simulada en Linux.
- App nativa: compila y pasa lint; el empaquetado MPEG-TS y el servidor TCP se probaron con ffmpeg
  (video y audio decodifican sin errores, un cliente que entra a mitad arranca en un keyframe).
  Camara, microfono y servicio no se pudieron probar aqui: falta probar en un celular real y con OBS.
- La imagen sale en horizontal segun el sensor; si en algun modelo sale girada, avisa.
