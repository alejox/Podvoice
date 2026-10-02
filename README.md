# Podvoice Enhance

Script para DaVinci Resolve que limpia y mejora la voz (estilo Adobe Podcast Enhance)
sin salir de Resolve, con interfaz propia para refinar el resultado.

## Que hace
Cadena de procesado (FFmpeg, 100 % local): filtro de graves → reduccion de ruido (FFT) →
gate anti-sala/eco → de-esser → EQ (calidez / claridad / aire) → compresion → normalizado
a LUFS + limitador. Opcionalmente una red neuronal RNNoise (`.rnnn`) para ruido dificil.

Interfaz: presets, sliders de cada etapa, vista previa A/B (12 s original vs mejorado),
mono/estereo y opcion de silenciar la pista original. Recuerda tus ajustes.

## Instalacion
1. Instala [FFmpeg](https://ffmpeg.org) (`brew install ffmpeg` / `winget install ffmpeg` / `apt install ffmpeg`).
2. Ejecuta `./install.sh` (o copia `Podvoice Enhance.py` a la carpeta `Fusion/Scripts/Utility` de Resolve;
   en Windows: `%APPDATA%\Blackmagic Design\DaVinci Resolve\Support\Fusion\Scripts\Utility`).
3. En Resolve: **Workspace › Scripts › Podvoice Enhance**.

## Uso
1. Pon los clips de voz en una pista de audio (ej. A1).
2. Abre el script, elige preset y pista, afina los sliders, escucha el A/B.
3. **Aplicar a la pista**: crea una pista nueva con el audio mejorado alineado a los clips originales
   (los WAV quedan en `~/Podvoice_Output`).

RNNoise: descarga un modelo de <https://github.com/richardpl/arnndn-models> y pon su ruta en el campo "Modelo RNNoise".

## CLI (sin Resolve)
`python3 "Podvoice Enhance.py" in.wav out.wav --preset podcast [--mono] [--rnn-model m.rnnn]`

## Limitaciones
- Usa el archivo fuente del clip y su recorte (in/out); no incluye cambios de velocidad, fades ni ganancia del clip.
- Los tiempos de clips solo-audio con FPS distinto al timeline pueden desfasarse; revisa el resultado.
- Ejecutar desde el menu Scripts funciona en Resolve gratis y Studio. No probado dentro de Resolve real
  (solo el motor de audio y el CLI se probaron).
- Esto es DSP clasico, no el modelo generativo de Adobe: limpia mucho pero no "reconstruye" voces muy dañadas.
