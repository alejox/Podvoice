#!/usr/bin/env python3
"""
Podvoice Enhance - limpieza de voz estilo "Adobe Podcast Enhance" dentro de DaVinci Resolve.

Uso dentro de DaVinci Resolve:
    Workspace > Scripts > Podvoice Enhance
    (copiar este archivo a la carpeta Scripts/Utility, ver README.md)

Uso por linea de comandos (sin Resolve):
    python3 "Podvoice Enhance.py" entrada.wav salida.wav --preset podcast

Requiere FFmpeg instalado. Todo el procesado es local.
"""

import json
import os
import shutil
import subprocess
import sys
import tempfile
import time
from pathlib import Path

# --------------------------------------------------------------------------- #
# Parametros y presets
# --------------------------------------------------------------------------- #

# id: (etiqueta, minimo, maximo, unidad)
SLIDERS = [
    ("hp",       "Corte de graves (rumble)", 20, 200, "Hz"),
    ("denoise",  "Reduccion de ruido",        0, 100, "%"),
    ("room",     "Reducir sala / eco (gate)", 0, 100, "%"),
    ("deess",    "De-esser (sibilancias)",    0, 100, "%"),
    ("warmth",   "Calidez (120 Hz)",         -6,   6, "dB"),
    ("presence", "Claridad (3.5 kHz)",       -6,   6, "dB"),
    ("air",      "Aire (10 kHz)",            -6,   6, "dB"),
    ("comp",     "Compresion",                0, 100, "%"),
    ("lufs",     "Volumen final",           -24, -12, "LUFS"),
]

PRESETS = {
    "Podcast (estilo Adobe Enhance)": dict(hp=80, denoise=60, room=35, deess=40, warmth=2, presence=3, air=1, comp=50, lufs=-16),
    "Limpieza fuerte (cuarto ruidoso)": dict(hp=100, denoise=85, room=60, deess=50, warmth=1, presence=3, air=0, comp=60, lufs=-16),
    "Suave (solo retoques)":           dict(hp=70, denoise=30, room=10, deess=25, warmth=0, presence=1, air=0, comp=25, lufs=-16),
    "Entrevista / llamada":            dict(hp=120, denoise=70, room=40, deess=45, warmth=-1, presence=4, air=-2, comp=65, lufs=-16),
    "Solo volumen":                    dict(hp=20, denoise=0, room=0, deess=0, warmth=0, presence=0, air=0, comp=0, lufs=-16),
}
DEFAULT_PRESET = next(iter(PRESETS))

SETTINGS_FILE = Path.home() / ".podvoice_enhance.json"
OUTPUT_DIR = Path.home() / "Podvoice_Output"


def find_ffmpeg(custom=""):
    """Resolve en macOS casi no hereda el PATH, asi que se buscan rutas comunes."""
    cands = [custom] if custom else []
    cands += [shutil.which("ffmpeg") or "", "/opt/homebrew/bin/ffmpeg", "/usr/local/bin/ffmpeg",
              "/usr/bin/ffmpeg", r"C:\ffmpeg\bin\ffmpeg.exe", r"C:\Program Files\ffmpeg\bin\ffmpeg.exe"]
    for c in cands:
        if c and Path(c).is_file():
            return c
    return None


# --------------------------------------------------------------------------- #
# Motor de audio (cadena de filtros FFmpeg)
# --------------------------------------------------------------------------- #

def _ff_path(p):
    """Escapa una ruta para usarla dentro de un filtro de ffmpeg."""
    return str(p).replace("\\", "/").replace(":", r"\:").replace("'", r"\'")


def build_filters(p, rnn_model=""):
    """Convierte los parametros en una cadena -af de FFmpeg."""
    f = []
    f.append(f"highpass=f={int(p['hp'])}:poles=2")

    if rnn_model:  # red neuronal (RNNoise) opcional, mucho mejor con ruido no estacionario
        f.append(f"arnndn=m='{_ff_path(rnn_model)}':mix=1")

    if p["denoise"] > 0:
        nr = 4 + p["denoise"] * 0.36          # 4..40 dB
        f.append(f"afftdn=nr={nr:.1f}:nf=-45:tn=1:nt=w")

    if p["room"] > 0:
        rng_db = -(4 + p["room"] * 0.26)       # hasta -30 dB en pausas
        rng = 10 ** (rng_db / 20)
        f.append(f"agate=threshold=0.01:ratio=3:attack=8:release=250:range={rng:.4f}:knee=3")

    if p["deess"] > 0:
        f.append(f"deesser=i={p['deess'] / 100 * 0.8:.2f}:m=0.5:f=0.5:s=o")

    for gain, freq, width in ((p["warmth"], 120, 1.0), (p["presence"], 3500, 1.2), (p["air"], 10000, 1.0)):
        if abs(gain) >= 0.1:
            f.append(f"equalizer=f={freq}:t=q:w={width}:g={gain}")

    if p["comp"] > 0:
        ratio = 1.5 + p["comp"] / 100 * 3.5    # 1.5..5
        f.append(f"acompressor=threshold=0.1:ratio={ratio:.2f}:attack=15:release=180:makeup=2")

    f.append(f"loudnorm=I={p['lufs']}:TP=-1.5:LRA=9")
    f.append("aresample=48000")
    f.append("alimiter=limit=0.89")
    return ",".join(f)


def run_ffmpeg(ffmpeg, src, dst, params, start=0.0, duration=None, mono=False, rnn_model=""):
    cmd = [ffmpeg, "-y", "-hide_banner", "-loglevel", "error"]
    if start:
        cmd += ["-ss", f"{start:.4f}"]
    if duration:
        cmd += ["-t", f"{duration:.4f}"]
    cmd += ["-i", str(src), "-vn", "-af", build_filters(params, rnn_model)]
    cmd += ["-ac", "1" if mono else "2", "-ar", "48000", "-c:a", "pcm_s24le", str(dst)]
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode != 0:
        raise RuntimeError(r.stderr.strip() or "ffmpeg fallo")
    return dst


def load_settings():
    try:
        return json.loads(SETTINGS_FILE.read_text())
    except Exception:
        return {}


def save_settings(d):
    try:
        SETTINGS_FILE.write_text(json.dumps(d, indent=2))
    except Exception:
        pass


# --------------------------------------------------------------------------- #
# Integracion con DaVinci Resolve
# --------------------------------------------------------------------------- #

def _num(v, default):
    try:
        f = float(v)
        return f if f > 0 else default
    except (TypeError, ValueError):
        return default


def get_track_clips(resolve, track_index):
    """Devuelve [(item, ruta_archivo, inicio_s, duracion_s)] de una pista de audio."""
    project = resolve.GetProjectManager().GetCurrentProject()
    tl = project.GetCurrentTimeline()
    if tl is None:
        raise RuntimeError("No hay un timeline abierto.")
    if track_index > tl.GetTrackCount("audio"):
        raise RuntimeError(f"La pista de audio A{track_index} no existe.")
    tl_fps = _num(tl.GetSetting("timelineFrameRate"), 24.0)
    out = []
    for item in tl.GetItemListInTrack("audio", track_index) or []:
        mpi = item.GetMediaPoolItem()
        if mpi is None:      # clips generados (Fusion, compound...) no tienen archivo
            continue
        path = mpi.GetClipProperty("File Path")
        if not path or not Path(path).is_file():
            continue
        is_audio_only = "Audio" in str(mpi.GetClipProperty("Type")) and "Video" not in str(mpi.GetClipProperty("Type"))
        fps = tl_fps if is_audio_only else _num(mpi.GetClipProperty("FPS"), tl_fps)
        start_s = item.GetSourceStartFrame() / fps
        dur_s = item.GetDuration() / tl_fps
        out.append((item, path, start_s, dur_s))
    return project, tl, tl_fps, out


def apply_to_timeline(resolve, track_index, params, ffmpeg, mono, mute_original, rnn_model, status):
    project, tl, tl_fps, clips = get_track_clips(resolve, track_index)
    if not clips:
        raise RuntimeError(f"No hay clips con archivo en A{track_index}.")
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    stamp = time.strftime("%Y%m%d_%H%M%S")
    pool = project.GetMediaPool()

    new_track = tl.GetTrackCount("audio") + 1
    tl.AddTrack("audio", "mono" if mono else "stereo")

    placed = 0
    for i, (item, src, start_s, dur_s) in enumerate(clips, 1):
        status(f"Procesando clip {i}/{len(clips)}...")
        dst = OUTPUT_DIR / f"{Path(src).stem}_enhanced_{stamp}_{i}.wav"
        run_ffmpeg(ffmpeg, src, dst, params, start_s, dur_s, mono, rnn_model)
        imported = pool.ImportMedia([str(dst)])
        if not imported:
            raise RuntimeError(f"Resolve no pudo importar {dst.name}")
        ok = pool.AppendToTimeline([{
            "mediaPoolItem": imported[0],
            "mediaType": 2,
            "trackIndex": new_track,
            "recordFrame": item.GetStart(),
            "startFrame": 0,
            "endFrame": max(0, int(round(dur_s * tl_fps)) - 1),
        }])
        placed += 1 if ok else 0

    if mute_original and placed:
        tl.SetTrackEnable("audio", track_index, False)
    return placed, new_track


# --------------------------------------------------------------------------- #
# Interfaz (UIManager de Fusion/Resolve)
# --------------------------------------------------------------------------- #

def run_ui(resolve, fusion):
    ui = fusion.UIManager
    disp = bmd.UIDispatcher(ui)  # noqa: F821  (bmd lo inyecta Resolve)

    saved = load_settings()
    ffmpeg_path = saved.get("ffmpeg", "")
    values = dict(PRESETS[saved.get("preset", DEFAULT_PRESET)] if saved.get("preset") in PRESETS else PRESETS[DEFAULT_PRESET])
    values.update({k: v for k, v in saved.get("values", {}).items() if k in values})

    rows = []
    for sid, label, lo, hi, unit in SLIDERS:
        rows.append(ui.HGroup({"Weight": 0}, [
            ui.Label({"Text": label, "MinimumSize": [210, 24], "Weight": 0}),
            ui.Slider({"ID": sid, "Minimum": lo, "Maximum": hi, "Value": int(values[sid]),
                       "Orientation": "Horizontal", "Weight": 1}),
            ui.Label({"ID": sid + "_v", "Text": f"{int(values[sid])} {unit}", "MinimumSize": [70, 24],
                      "Alignment": {"AlignRight": True}, "Weight": 0}),
        ]))

    win = disp.AddWindow({
        "ID": "PodvoiceWin", "WindowTitle": "Podvoice Enhance", "Geometry": [200, 120, 560, 640],
    }, ui.VGroup([
        ui.Label({"Text": "<h2>Podvoice Enhance</h2>Limpia y mejora la voz sin salir de DaVinci",
                  "Weight": 0, "Alignment": {"AlignHCenter": True}}),
        ui.HGroup({"Weight": 0}, [
            ui.Label({"Text": "Preset", "Weight": 0, "MinimumSize": [60, 24]}),
            ui.ComboBox({"ID": "Preset", "Weight": 1}),
        ]),
        ui.HGroup({"Weight": 0}, [
            ui.Label({"Text": "Pista de audio (A#)", "Weight": 0}),
            ui.SpinBox({"ID": "Track", "Minimum": 1, "Maximum": 64, "Value": int(saved.get("track", 1)), "Weight": 0}),
            ui.CheckBox({"ID": "Mono", "Text": "Mono", "Checked": bool(saved.get("mono", False)), "Weight": 0}),
            ui.CheckBox({"ID": "Mute", "Text": "Silenciar pista original", "Checked": bool(saved.get("mute", True)), "Weight": 1}),
        ]),
        *rows,
        ui.HGroup({"Weight": 0}, [
            ui.Label({"Text": "FFmpeg", "Weight": 0, "MinimumSize": [60, 24]}),
            ui.LineEdit({"ID": "Ffmpeg", "Text": ffmpeg_path, "PlaceholderText": "auto (dejar vacio)", "Weight": 1}),
        ]),
        ui.HGroup({"Weight": 0}, [
            ui.Label({"Text": "Modelo RNNoise", "Weight": 0, "MinimumSize": [60, 24]}),
            ui.LineEdit({"ID": "Rnn", "Text": saved.get("rnn", ""), "PlaceholderText": "opcional: archivo .rnnn (IA)", "Weight": 1}),
        ]),
        ui.HGroup({"Weight": 0}, [
            ui.Button({"ID": "PrevOrig", "Text": "Escuchar original (12 s)"}),
            ui.Button({"ID": "PrevEnh", "Text": "Escuchar mejorado (12 s)"}),
        ]),
        ui.Button({"ID": "Apply", "Text": "Aplicar a la pista", "MinimumSize": [100, 36], "Weight": 0}),
        ui.Label({"ID": "Status", "Text": "Listo.", "Weight": 0, "WordWrap": True}),
    ]))
    itm = win.GetItems()
    for name in PRESETS:
        itm["Preset"].AddItem(name)
    itm["Preset"].CurrentIndex = list(PRESETS).index(saved["preset"]) if saved.get("preset") in PRESETS else 0

    units = {s[0]: s[4] for s in SLIDERS}

    def status(msg):
        itm["Status"].Text = msg

    def read_params():
        return {sid: itm[sid].Value for sid, *_ in SLIDERS}

    def persist():
        save_settings({"preset": list(PRESETS)[itm["Preset"].CurrentIndex], "values": read_params(),
                       "track": itm["Track"].Value, "mono": itm["Mono"].Checked, "mute": itm["Mute"].Checked,
                       "ffmpeg": itm["Ffmpeg"].Text, "rnn": itm["Rnn"].Text})

    def get_ffmpeg():
        ff = find_ffmpeg(itm["Ffmpeg"].Text.strip())
        if not ff:
            raise RuntimeError("No se encontro FFmpeg. Instalalo o escribe su ruta en el campo FFmpeg.")
        return ff

    def rnn():
        m = itm["Rnn"].Text.strip()
        if m and not Path(m).is_file():
            raise RuntimeError("El modelo RNNoise no existe en esa ruta.")
        return m

    def make_slider_handler(sid):
        def h(ev):
            itm[sid + "_v"].Text = f"{int(itm[sid].Value)} {units[sid]}"
        return h

    for sid, *_ in SLIDERS:
        win.On[sid].ValueChanged = make_slider_handler(sid)

    def on_preset(ev):
        for sid, v in PRESETS[list(PRESETS)[itm["Preset"].CurrentIndex]].items():
            itm[sid].Value = int(v)
            itm[sid + "_v"].Text = f"{int(v)} {units[sid]}"
    win.On.Preset.CurrentIndexChanged = on_preset

    def preview(enhanced):
        try:
            ff = get_ffmpeg()
            _, _, _, clips = get_track_clips(resolve, int(itm["Track"].Value))
            if not clips:
                raise RuntimeError("No hay clips con archivo en esa pista.")
            _, src, start_s, dur_s = clips[0]
            dur = min(12.0, dur_s)
            tmp = Path(tempfile.gettempdir()) / f"podvoice_prev_{'enh' if enhanced else 'orig'}.wav"
            status("Generando vista previa...")
            params = read_params() if enhanced else PRESETS["Solo volumen"]
            run_ffmpeg(ff, src, tmp, params if enhanced else dict(params, lufs=-16), start_s, dur,
                       itm["Mono"].Checked, rnn() if enhanced else "")
            ffplay = shutil.which("ffplay") or str(Path(ff).with_name("ffplay"))
            if Path(ffplay).exists() or shutil.which("ffplay"):
                subprocess.Popen([ffplay, "-nodisp", "-autoexit", "-loglevel", "quiet", str(tmp)])
            else:
                status(f"Vista previa lista (no hay ffplay): {tmp}")
                return
            status("Reproduciendo " + ("mejorado" if enhanced else "original") + "...")
        except Exception as e:
            status(f"Error: {e}")

    win.On.PrevOrig.Clicked = lambda ev: preview(False)
    win.On.PrevEnh.Clicked = lambda ev: preview(True)

    def on_apply(ev):
        try:
            persist()
            placed, trk = apply_to_timeline(
                resolve, int(itm["Track"].Value), read_params(), get_ffmpeg(),
                itm["Mono"].Checked, itm["Mute"].Checked, rnn(), status)
            status(f"Listo: {placed} clip(s) mejorado(s) en la pista A{trk}.")
        except Exception as e:
            status(f"Error: {e}")
    win.On.Apply.Clicked = on_apply

    def on_close(ev):
        persist()
        disp.ExitLoop()
    win.On.PodvoiceWin.Close = on_close

    win.Show()
    disp.RunLoop()
    win.Hide()


# --------------------------------------------------------------------------- #
# Punto de entrada
# --------------------------------------------------------------------------- #

def cli(argv):
    import argparse
    ap = argparse.ArgumentParser(description="Podvoice Enhance (CLI)")
    ap.add_argument("input")
    ap.add_argument("output")
    ap.add_argument("--preset", default=DEFAULT_PRESET.split(" ")[0].lower(),
                    help="podcast | limpieza | suave | entrevista | solo")
    ap.add_argument("--mono", action="store_true")
    ap.add_argument("--rnn-model", default="")
    ap.add_argument("--print-filters", action="store_true")
    a = ap.parse_args(argv)
    name = next((n for n in PRESETS if n.lower().startswith(a.preset.lower())), None)
    if not name:
        ap.error("preset desconocido: " + a.preset)
    if a.print_filters:
        print(build_filters(PRESETS[name], a.rnn_model))
    ff = find_ffmpeg()
    if not ff:
        sys.exit("FFmpeg no encontrado")
    run_ffmpeg(ff, a.input, a.output, PRESETS[name], mono=a.mono, rnn_model=a.rnn_model)
    print("OK ->", a.output)


if __name__ == "__main__":
    _resolve = globals().get("resolve")
    _fusion = globals().get("fusion")
    if _resolve is None:
        try:  # script lanzado desde fuera (Resolve Studio con scripting externo habilitado)
            import DaVinciResolveScript as dvr  # type: ignore
            _resolve = dvr.scriptapp("Resolve")
            _fusion = _resolve.Fusion() if _resolve else None
        except Exception:
            _resolve = None
    if _resolve is not None and _fusion is not None and len(sys.argv) < 2:
        run_ui(_resolve, _fusion)
    elif len(sys.argv) >= 3:
        cli(sys.argv[1:])
    else:
        sys.exit("Ejecutalo desde Workspace > Scripts en DaVinci Resolve, o usa: "
                 "python3 'Podvoice Enhance.py' in.wav out.wav --preset podcast")
