# Podcam

Usa la camara y el microfono del celular como fuente de video en el PC (estilo DroidCam),
por WiFi o USB, en **Windows, macOS y Linux**, sin instalar drivers y sin cuenta de Apple Developer.

- **PC:** un script de Python (solo libreria estandar) que sirve dos paginas y coordina la conexion.
- **Celular (Android o iPhone):** se abre una pagina en el navegador; no hay app que instalar.
- **Video/audio:** WebRTC directo celular → PC (H.264/VP8 + Opus, baja latencia, red local).
- **Webcam virtual:** se usa la **Camara Virtual de OBS**, que ya esta firmada en los tres sistemas.

## Uso

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

## Nota sobre USB

`adb reverse` lleva por el cable la pagina y la senalizacion (TCP). El video WebRTC va por UDP, asi que el
celular y el PC necesitan una red en comun:
- celular y PC en el mismo WiFi (el cable solo da estabilidad a la senalizacion), o
- **Anclaje USB** activado en el celular (crea una red por el cable; funciona sin WiFi).
  En Windows y Linux suele funcionar directo; en macOS depende del modelo de Android.
  En iPhone el equivalente es *Compartir Internet* por cable, que macOS soporta de serie.

## Limitaciones

- Un celular y un visor a la vez.
- Mantener la pantalla del celular encendida y el navegador en primer plano (se pide *wake lock*).
- En Windows, `openssl` viene con Git for Windows; sin `openssl` no hay HTTPS y solo funciona el modo USB.
- Probado de extremo a extremo con Chromium y camara simulada en Linux. Falta probarlo con
  celulares reales, con OBS y en Windows/macOS.
