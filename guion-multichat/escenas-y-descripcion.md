# AC Multichat 2.3: qué mostrar en pantalla y descripción de YouTube

Duración estimada leyendo a ritmo de teleprompter: **8 a 9 minutos** (~1.300 palabras).

## Qué mostrar en cada bloque

| Bloque | Qué se ve en pantalla |
|---|---|
| **Gancho** (0:00) | Corte rápido: 5 pestañas de chat abiertas → transición → el dock de Multichat con todo junto. Texto grande: "5 CHATS EN 1". |
| **¿Qué es esto?** | Dock con mensajes de varias plataformas (activa *Mensajes de prueba*). Zoom a los logos. Darle a *Iniciar transmisión* y mostrar el aviso **"Multistream detectado"**. |
| **1. Ventana flotante** | Juego en pantalla completa sin bordes + la ventanita encima. Moverla, cambiarle el tamaño. Clic derecho → *Los clics atraviesan la ventana*. Pantalla dividida: tu monitor (con ventana) vs. la vista previa de OBS (sin ventana). Después cambiar *Estilo en la ventana* → *Chat limpio (sin fondos)*. |
| **2. Estilo GTA 6** | Propiedades de la fuente → *Estilo del fondo* → GTA 6 (Vice City). Pasar rápido por Neón, Cyberpunk, Glitch, Cristal. Luego los preajustes de un clic. |
| **3. Destacado con pausa** | Clic a un mensaje del dock → aparece grande. Mostrar la barra con **⏸ Pausar** y **✕ Quitar**. |
| **4. Voz** | Herramientas → Multichat… → pestaña **Voz** → *Leer el chat en voz alta*. Que se escuche leyendo un mensaje de prueba. Mostrar *Multichat (voz)* en el mezclador y el modo *Avisar con un sonido*. |
| **5. Más ordenado** | Propiedades nuevas (Estilo / Texto / Diseño / Fecha y hora / Ventana flotante). Cinta horizontal con fecha y hora. El asistente de inicio. |
| **Lo que ya traía** | Montaje rápido: espectadores por plataforma, un mensaje que desaparece al borrarlo, pestaña Filtros, aviso de *Actualizar ahora*. |
| **Instalación** | Página de Releases en GitHub, resaltar los 3 archivos. Texto en pantalla: **"CIERRA OBS ANTES"**. |
| **Windows** | Grabación real: Extraer todo → escribir la ruta en el Explorador → pegar carpeta → permiso de administrador. Poner la ruta como texto en pantalla. |
| **Mac / Linux** | Ruta y comandos como texto en pantalla (los mismos de la descripción). |
| **Primeros pasos** | Asistente: escribir el canal → aparece la foto de perfil → botón de añadir a la escena. Luego Propiedades → Ventana flotante. |
| **Cierre** | Volver a la ventana flotante sobre un juego + botón de suscribirse. |

**Tip para el teleprompter:** el guion ya viene en frases cortas, una idea por línea. Las líneas `━━━` separan los bloques: te sirven de pausa para respirar o para cortar en la edición.

---

## Descripción para YouTube (copiar y pegar)

```
Todos tus chats de Twitch, YouTube, Kick, TikTok y Starvios juntos en OBS 💬
AC Multichat 2.3 trae ventana flotante para jugar con un solo monitor, estilo GTA 6, destacados con pausa y que te lea el chat en voz alta. Gratis, sin cuentas y sin claves.

⬇️ DESCARGA: https://github.com/alejox/multi-chat/releases/latest

✅ Necesitas OBS 33 o más nuevo. Cierra OBS antes de instalar.

🪟 WINDOWS
1. Descomprime el .zip (clic derecho → Extraer todo)
2. Copia la carpeta "obs-multichat" en:
C:\ProgramData\obs-studio\plugins
(si no existe la carpeta "plugins", créala)
3. Abre OBS

🍎 MAC
1. Descomprime el .zip
2. Copia "obs-multichat.plugin" en:
~/Library/Application Support/obs-studio/plugins/
3. En la Terminal, una sola vez:
xattr -cr ~/Library/Application\ Support/obs-studio/plugins/obs-multichat.plugin
4. Abre OBS

🐧 LINUX (Ubuntu 24.04 o más nuevo)
mkdir -p ~/.config/obs-studio/plugins
tar -xzf obs-multichat-*-linux-x86_64.tar.gz -C ~/.config/obs-studio/plugins
(Para que lea el chat en voz alta: sudo apt install espeak-ng)

⏱️ CAPÍTULOS
0:00 Qué es AC Multichat
0:00 Ventana flotante para un solo monitor
0:00 Estilo GTA 6 y preajustes
0:00 Mensaje destacado con pausa
0:00 Que te lea el chat en voz alta
0:00 Todo más ordenado
0:00 Cómo instalarlo
0:00 Primeros pasos
```

(Cambia los `0:00` por los tiempos reales cuando termines de editar.)
