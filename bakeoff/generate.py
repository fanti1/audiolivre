"""Generate the pt-BR voice bake-off samples (Fase 9).

Mimics the app pipeline: one generate() call per sentence, silence trimming,
350 ms between sentences and 700 ms between paragraphs, silence_scale = 1.0.
Each engine family gets one speed so its speech lands near TARGET_WPM.
"""

import json
import os
import re
import subprocess
import sys
import tempfile
import time
import wave
from pathlib import Path

import numpy as np
import sherpa_onnx

MODELS = Path(os.environ.get("BAKEOFF_MODELS", Path(tempfile.gettempdir()) / "bakeoff-models"))
OUT = Path(__file__).parent / "out"
NUM_THREADS = 4
TARGET_WPM = 150
SENTENCE_PAUSE_MS = 350
PARAGRAPH_PAUSE_MS = 700
SEGMENT_PAUSE_MS = 250

TEST_TEXT = """Capítulo IV — A casa no fim da rua.
Naquela noite, o Sr. Almeida chegou às 23h com 1.500 reais no bolso e um gosto amargo na boca. A porta rangeu. Alguém — ou alguma coisa — respirava no escuro.
— Quem está aí? — perguntou ele, com a voz trêmula.
— Você não devia ter voltado — respondeu uma mulher, quase num sussurro. — Eu disse que não gosto de visitas.
Ele sentiu o olho esquerdo tremer. Era o jogo mais perigoso da sua vida… e ele estava perdendo!"""

FIXED_TEXT = """Capítulo quatro. A casa no fim da rua.
Naquela noite, o senhor Almeida chegou às vinte e três horas com mil e quinhentos reais no bolso e um gôsto amargo na boca. A porta rangeu. Alguém, ou alguma coisa, respirava no escuro.
— Quem está aí? — perguntou ele, com a voz trêmula.
— Você não devia ter voltado — respondeu uma mulher, quase num sussurro. — Eu disse que não gósto de visitas.
Ele sentiu o ôlho esquerdo tremer. Era o jôgo mais perigoso da sua vida… e ele estava perdendo!"""

# (role, text) per paragraph; roles: N = narrator, M = man, W = woman.
CAST_SCRIPT = [
    [("N", "Capítulo quatro. A casa no fim da rua.")],
    [("N", "Naquela noite, o senhor Almeida chegou às vinte e três horas com mil e quinhentos reais no bolso "
           "e um gôsto amargo na boca. A porta rangeu. Alguém, ou alguma coisa, respirava no escuro.")],
    [("M", "Quem está aí?"), ("N", "perguntou ele, com a voz trêmula.")],
    [("W", "Você não devia ter voltado."), ("N", "respondeu uma mulher, quase num sussurro."),
     ("W", "Eu disse que não gósto de visitas.")],
    [("N", "Ele sentiu o ôlho esquerdo tremer. Era o jôgo mais perigoso da sua vida… e ele estava perdendo!")],
]

PIPER_VOICES = [
    ("faber", "vits-piper-pt_BR-faber-medium", "Faber"),
    ("cadu", "vits-piper-pt_BR-cadu-medium", "Cadu"),
    ("jeff", "vits-piper-pt_BR-jeff-medium", "Jeff"),
    ("edresson", "vits-piper-pt_BR-edresson-low", "Edresson"),
    ("miro", "vits-piper-pt_BR-miro-high", "Miro"),
    ("dii", "vits-piper-pt_BR-dii-high", "Dii"),
]
KOKORO_DIR = "kokoro-int8-multi-lang-v1_0"
KOKORO_VOICES = [(42, "dora", "Dora"), (43, "alex", "Alex"), (44, "santa", "Santa")]
SUPERTONIC_DIR = "sherpa-onnx-supertonic-3-tts-int8-2026-05-11"


def paragraphs(text):
    return [p.strip() for p in text.split("\n") if p.strip()]


def sentences(paragraph):
    return [s.strip() for s in re.split(r"(?<=[.?!…])\s+", paragraph) if s.strip()]


def word_count(text):
    return sum(1 for tok in text.split() if re.search(r"\w", tok))


def trim(samples, sr, threshold=0.01, margin_ms=40):
    loud = np.flatnonzero(np.abs(samples) > threshold)
    if loud.size == 0:
        return samples[:0]
    margin = margin_ms * sr // 1000
    return samples[max(loud[0] - margin, 0): min(loud[-1] + margin + 1, samples.size)]


def silence(ms, sr):
    return np.zeros(ms * sr // 1000, dtype=np.float32)


def dir_size_mb(path):
    return round(sum(f.stat().st_size for f in Path(path).rglob("*") if f.is_file()) / 1e6, 1)


def median_f0(samples, sr):
    """Median pitch over voiced frames (simple autocorrelation) — evidence for gender labels."""
    frame, hop = int(0.04 * sr), int(0.02 * sr)
    lo, hi = int(sr / 400), int(sr / 70)
    f0s = []
    for start in range(0, samples.size - frame, hop):
        x = samples[start:start + frame]
        if np.sqrt(np.mean(x * x)) < 0.02:
            continue
        x = x - x.mean()
        ac = np.correlate(x, x, mode="full")[frame - 1:]
        if ac[0] <= 0:
            continue
        ac = ac / ac[0]
        lag = lo + int(np.argmax(ac[lo:hi]))
        if ac[lag] > 0.35:
            f0s.append(sr / lag)
    return round(float(np.median(f0s)), 1) if f0s else None


class Engine:
    def __init__(self, family, model_config, model_dir, silence_scale=1.0):
        self.family = family
        cfg = sherpa_onnx.OfflineTtsConfig(model=model_config, silence_scale=silence_scale)
        if not cfg.validate():
            raise ValueError(f"invalid config for {family}")
        self.tts = sherpa_onnx.OfflineTts(cfg)
        self.sr = self.tts.sample_rate
        self.model_mb = dir_size_mb(model_dir)

    def synth(self, text, sid, speed):
        gen = sherpa_onnx.GenerationConfig()
        gen.sid = sid
        gen.speed = speed
        gen.silence_scale = 1.0
        if self.family == "Supertonic":
            gen.num_steps = 5
            gen.extra = {"lang": "pt"}
        audio = self.tts.generate(text, gen)
        return np.asarray(audio.samples, dtype=np.float32)


def piper_engine(dirname):
    d = MODELS / dirname
    onnx = next(d.glob("*.onnx"))
    vits = sherpa_onnx.OfflineTtsVitsModelConfig(
        model=str(onnx), tokens=str(d / "tokens.txt"), data_dir=str(d / "espeak-ng-data"))
    return Engine("Piper", sherpa_onnx.OfflineTtsModelConfig(vits=vits, num_threads=NUM_THREADS), d)


def kokoro_engine():
    d = MODELS / KOKORO_DIR
    kokoro = sherpa_onnx.OfflineTtsKokoroModelConfig(
        model=str(d / "model.int8.onnx"), voices=str(d / "voices.bin"), tokens=str(d / "tokens.txt"),
        data_dir=str(d / "espeak-ng-data"), lang="pt-br")
    return Engine("Kokoro", sherpa_onnx.OfflineTtsModelConfig(kokoro=kokoro, num_threads=NUM_THREADS), d)


def supertonic_engine():
    d = MODELS / SUPERTONIC_DIR
    st = sherpa_onnx.OfflineTtsSupertonicModelConfig(
        duration_predictor=str(d / "duration_predictor.int8.onnx"),
        text_encoder=str(d / "text_encoder.int8.onnx"),
        vector_estimator=str(d / "vector_estimator.int8.onnx"),
        vocoder=str(d / "vocoder.int8.onnx"),
        tts_json=str(d / "tts.json"),
        unicode_indexer=str(d / "unicode_indexer.bin"),
        voice_style=str(d / "voice.bin"))
    return Engine("Supertonic", sherpa_onnx.OfflineTtsModelConfig(supertonic=st, num_threads=NUM_THREADS), d)


def render_text(engine, text, sid, speed):
    """Returns (audio, speech_seconds, synth_seconds)."""
    parts, speech, synth = [], 0.0, 0.0
    paras = paragraphs(text)
    for pi, para in enumerate(paras):
        sents = sentences(para)
        for si, sent in enumerate(sents):
            t0 = time.perf_counter()
            raw = engine.synth(sent, sid, speed)
            synth += time.perf_counter() - t0
            clip = trim(raw, engine.sr)
            speech += clip.size / engine.sr
            parts.append(clip)
            if si < len(sents) - 1:
                parts.append(silence(SENTENCE_PAUSE_MS, engine.sr))
        if pi < len(paras) - 1:
            parts.append(silence(PARAGRAPH_PAUSE_MS, engine.sr))
    return np.concatenate(parts), speech, synth


def render_cast(engine, sids, speed):
    parts, synth = [], 0.0
    for pi, para in enumerate(CAST_SCRIPT):
        for gi, (role, text) in enumerate(para):
            sents = sentences(text)
            for si, sent in enumerate(sents):
                t0 = time.perf_counter()
                raw = engine.synth(sent, sids[role], speed)
                synth += time.perf_counter() - t0
                parts.append(trim(raw, engine.sr))
                if si < len(sents) - 1:
                    parts.append(silence(SENTENCE_PAUSE_MS, engine.sr))
            if gi < len(para) - 1:
                parts.append(silence(SEGMENT_PAUSE_MS, engine.sr))
        if pi < len(CAST_SCRIPT) - 1:
            parts.append(silence(PARAGRAPH_PAUSE_MS, engine.sr))
    return np.concatenate(parts), synth


def calibrate_speed(engine, sid):
    _, speech, _ = render_text(engine, TEST_TEXT, sid, 1.0)
    wpm_at_1 = word_count(TEST_TEXT) / (speech / 60)
    # sherpa-onnx: larger speed = faster speech, duration ~ 1/speed.
    speed = round(min(max(TARGET_WPM / wpm_at_1, 0.7), 1.2), 2)
    print(f"[{engine.family}] {wpm_at_1:.0f} wpm at speed 1.0 -> speed {speed}", flush=True)
    return speed, round(wpm_at_1)


def save_mp3(samples, sr, name):
    OUT.mkdir(parents=True, exist_ok=True)
    wav_path = Path(tempfile.gettempdir()) / f"bakeoff-{name}.wav"
    pcm = (np.clip(samples, -1.0, 1.0) * 32767).astype(np.int16)
    with wave.open(str(wav_path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(sr)
        w.writeframes(pcm.tobytes())
    mp3 = OUT / f"{name}.mp3"
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", str(wav_path), "-ac", "1", "-b:a", "48k", str(mp3)],
                   check=True)
    wav_path.unlink()
    return mp3.name


def entry(file, group, engine, voice, label, gender, speed, samples, sr, synth, model_mb, notes=""):
    duration = samples.size / sr
    clipped = int(np.sum(np.abs(samples) >= 0.999))
    return {
        "file": file, "group": group, "engine": engine, "voice": voice, "label": label, "gender": gender,
        "speed": speed, "durationSec": round(duration, 2), "synthSec": round(synth, 2),
        "rtf": round(synth / duration, 3) if duration else None, "modelSizeMb": model_mb,
        "medianF0Hz": median_f0(samples, sr), "clippedSamples": clipped, "sampleRate": sr, "notes": notes,
    }


def gender_from_f0(f0):
    if f0 is None:
        return "?"
    return "F" if f0 >= 160 else "M"


# sherpa's export sorts voice files by name: F1..F5 then M1..M5. Measured F0 agrees (sid 4 is a low female).
SUPERTONIC_NAMES = ["F1", "F2", "F3", "F4", "F5", "M1", "M2", "M3", "M4", "M5"]


def main():
    manifest, calibration = [], {}

    # Piper
    piper_speed = None
    for i, (vid, dirname, name) in enumerate(PIPER_VOICES):
        eng = piper_engine(dirname)
        if piper_speed is None:
            piper_speed, calibration["Piper"] = calibrate_speed(eng, 0)
        audio, _, synth = render_text(eng, TEST_TEXT, 0, piper_speed)
        f0 = median_f0(audio, eng.sr)
        g = gender_from_f0(f0)
        label = f"{name} — {'feminina' if g == 'F' else 'masculina'}"
        file = save_mp3(audio, eng.sr, f"piper-{vid}")
        notes = {
            "faber": "voz atual do app",
            # sherpa logs "Skip unknown phonemes U+0303" for this model: nasal tildes (ão, ã, õ) are dropped.
            "edresson": "modelo 'low': perde o til nasal (ão, ã, õ) — pronúncia pt-BR comprometida",
        }.get(vid, "")
        manifest.append(entry(file, "Piper", "Piper VITS", vid, label, g, piper_speed, audio, eng.sr, synth,
                              eng.model_mb, notes))
        if vid == "faber":
            fixed, _, fsynth = render_text(eng, FIXED_TEXT, 0, piper_speed)
            manifest.append(entry(save_mp3(fixed, eng.sr, "fix-piper-faber"), "Correções", "Piper VITS", vid,
                                  "Faber — com correções", g, piper_speed, fixed, eng.sr, fsynth, eng.model_mb,
                                  "mesmo texto normalizado + reescritas (gôsto/gósto/ôlho/jôgo)"))
        print(f"piper {vid} ok f0={f0}", flush=True)
        del eng

    # Kokoro
    eng = kokoro_engine()
    kokoro_speed, calibration["Kokoro"] = calibrate_speed(eng, 42)
    for sid, vid, name in KOKORO_VOICES:
        audio, _, synth = render_text(eng, TEST_TEXT, sid, kokoro_speed)
        f0 = median_f0(audio, eng.sr)
        g = gender_from_f0(f0)
        label = f"{name} — {'feminina' if g == 'F' else 'masculina'}"
        manifest.append(entry(save_mp3(audio, eng.sr, f"kokoro-{vid}"), "Kokoro", "Kokoro v1.0 int8",
                              f"{vid} (sid {sid})", label, g, kokoro_speed, audio, eng.sr, synth, eng.model_mb))
        print(f"kokoro {vid} ok f0={f0}", flush=True)
    fixed, _, fsynth = render_text(eng, FIXED_TEXT, 42, kokoro_speed)
    manifest.append(entry(save_mp3(fixed, eng.sr, "fix-kokoro-dora"), "Correções", "Kokoro v1.0 int8", "dora (sid 42)",
                          "Dora — com correções", "F", kokoro_speed, fixed, eng.sr, fsynth, eng.model_mb,
                          "mesmo texto normalizado + reescritas"))
    cast, csynth = render_cast(eng, {"N": 44, "M": 43, "W": 42}, kokoro_speed)
    manifest.append(entry(save_mp3(cast, eng.sr, "cast-kokoro"), "Personagens", "Kokoro v1.0 int8",
                          "narrador santa(44), homem alex(43), mulher dora(42)", "Kokoro — narrador + 2 personagens",
                          "multi", kokoro_speed, cast, eng.sr, csynth, eng.model_mb))
    del eng

    # Supertonic
    eng = supertonic_engine()
    st_speed, calibration["Supertonic"] = calibrate_speed(eng, 0)
    for sid, name in enumerate(SUPERTONIC_NAMES):
        audio, _, synth = render_text(eng, TEST_TEXT, sid, st_speed)
        f0 = median_f0(audio, eng.sr)
        g = name[0]
        label = f"{name} — {'feminina' if g == 'F' else 'masculina'}"
        manifest.append(entry(save_mp3(audio, eng.sr, f"supertonic-{name.lower()}"), "Supertonic",
                              "Supertonic 3 int8", f"{name} (sid {sid})", label, g, st_speed, audio, eng.sr, synth,
                              eng.model_mb, "nome da voz inferido pela ordem de exportação + tom medido (F0)"))
        print(f"supertonic {name} (sid {sid}) ok f0={f0}", flush=True)
    fixed, _, fsynth = render_text(eng, FIXED_TEXT, 0, st_speed)
    manifest.append(entry(save_mp3(fixed, eng.sr, "fix-supertonic-f1"), "Correções", "Supertonic 3 int8",
                          "F1 (sid 0)", "F1 — com correções", "F", st_speed, fixed, eng.sr, fsynth, eng.model_mb,
                          "mesmo texto normalizado + reescritas"))
    sids = {"N": 5, "M": 7, "W": 1}
    cast, csynth = render_cast(eng, sids, st_speed)
    manifest.append(entry(save_mp3(cast, eng.sr, "cast-supertonic"), "Personagens", "Supertonic 3 int8",
                          "narrador M1 (sid 5), homem M3 (sid 7), mulher F2 (sid 1)",
                          "Supertonic — narrador + 2 personagens", "multi", st_speed, cast, eng.sr, csynth,
                          eng.model_mb))

    (OUT / "manifest.json").write_text(
        json.dumps({"calibrationWpmAtSpeed1": calibration, "targetWpm": TARGET_WPM, "samples": manifest},
                   ensure_ascii=False, indent=2), encoding="utf-8")
    print("done", len(manifest), "samples", flush=True)


if __name__ == "__main__":
    sys.exit(main())
