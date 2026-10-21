"""Every Brazilian-trained voice that can run on a phone, reading the same passage, so the user can pick by ear.

The phoneme-based accent measure failed the listening test: it ranked the current Supertonic cast far above the
old one, but the user still hears European Portuguese. So this script stops scoring and just renders candidates:
Piper's six Brazilian voices, Kokoro's three, Kyutai Pocket TTS's Portuguese voice, and the app's current cast
with and without Brazilian respelling. It also measures how fast each engine runs with four threads, which is what
decides whether a voice can read a book in real time on the phone.

    python qa/voice_shortlist.py --out <dir>
"""

import argparse
import json
import subprocess
import sys
import tarfile
import time
import urllib.request
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).parent))
import listen_check as lc  # noqa: E402
import sherpa_onnx  # noqa: E402
from accent_boost import respell  # noqa: E402

MODELS = lc.MODEL_DIR.parent
RELEASE = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/"
THREADS = 4  # what the app gives the engine on the phone

# Narration plus dialogue, loaded with the words where Brazilian and European Portuguese part ways.
PASSAGE = (
    "Naquela noite, a cidade parecia diferente. O tio de Diego tinha partido de tarde, sem dizer nada a ninguém, "
    "e a gente sentia que alguma coisa ia acontecer.\n"
    "— Você não devia ter voltado — disse ela, da porta da cozinha.\n"
    "— Eu vim buscar a verdade — respondeu o rapaz, deixando o leite quente na mesa.\n"
    "Às sete horas da manhã, quando o vento parou, já era tarde demais para voltar atrás."
)

PIPER = [("cadu", "medium"), ("faber", "medium"), ("jeff", "medium"), ("edresson", "low"), ("miro", "high"), ("dii", "high")]
KOKORO = [(42, "dora"), (43, "alex"), (44, "santa")]


def fetch(name):
    target = MODELS / name
    if not target.exists():
        archive = MODELS / f"{name}.tar.bz2"
        urllib.request.urlretrieve(RELEASE + archive.name, archive)
        with tarfile.open(archive) as tar:
            tar.extractall(MODELS)
        archive.unlink()
    return target


def sherpa(model_config):
    cfg = sherpa_onnx.OfflineTtsConfig(model=model_config, silence_scale=1.0)
    return sherpa_onnx.OfflineTts(cfg)


def piper_engine(voice, quality):
    d = fetch(f"vits-piper-pt_BR-{voice}-{quality}")
    vits = sherpa_onnx.OfflineTtsVitsModelConfig(model=str(next(d.glob("*.onnx"))), tokens=str(d / "tokens.txt"),
                                                 data_dir=str(d / "espeak-ng-data"))
    return sherpa(sherpa_onnx.OfflineTtsModelConfig(vits=vits, num_threads=THREADS)), dir_size_mb(d)


def kokoro_engine():
    d = fetch("kokoro-int8-multi-lang-v1_0")
    kokoro = sherpa_onnx.OfflineTtsKokoroModelConfig(model=str(d / "model.int8.onnx"), voices=str(d / "voices.bin"),
                                                     tokens=str(d / "tokens.txt"), data_dir=str(d / "espeak-ng-data"),
                                                     lang="pt-br")
    return sherpa(sherpa_onnx.OfflineTtsModelConfig(kokoro=kokoro, num_threads=THREADS)), dir_size_mb(d)


def supertonic_engine(voice_file):
    d = lc.MODEL_DIR
    st = sherpa_onnx.OfflineTtsSupertonicModelConfig(
        duration_predictor=str(d / "duration_predictor.int8.onnx"), text_encoder=str(d / "text_encoder.int8.onnx"),
        vector_estimator=str(d / "vector_estimator.int8.onnx"), vocoder=str(d / "vocoder.int8.onnx"),
        tts_json=str(d / "tts.json"), unicode_indexer=str(d / "unicode_indexer.bin"), voice_style=str(voice_file))
    return sherpa(sherpa_onnx.OfflineTtsModelConfig(supertonic=st, num_threads=THREADS)), dir_size_mb(d)


def dir_size_mb(path: Path) -> int:
    return round(sum(f.stat().st_size for f in path.rglob("*") if f.is_file()) / 1_000_000)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    args = ap.parse_args()
    out = Path(args.out)
    (out / "audio").mkdir(parents=True, exist_ok=True)
    results = []

    def render(label, engine, sid, speed, size_mb, engine_name, steps=None, text=PASSAGE):
        started = time.perf_counter()
        parts = []
        for line in [ln for ln in text.split("\n") if ln.strip()]:
            gen = sherpa_onnx.GenerationConfig()
            gen.sid, gen.speed, gen.silence_scale = sid, speed, 1.0
            if steps:
                gen.num_steps = steps
                gen.extra = {"lang": "pt"}
            audio = engine.generate(line, gen)
            parts.append(lc.trim_silence(np.asarray(audio.samples, dtype=np.float32), audio.sample_rate))
            parts.append(np.zeros(int(audio.sample_rate * 0.45), dtype=np.float32))
        sr = audio.sample_rate
        synth_s = time.perf_counter() - started
        wave = np.concatenate(parts)
        slug = label.lower().replace(" ", "_").replace("(", "").replace(")", "").replace("+", "e").replace(",", "")
        path = out / "audio" / f"{slug}.wav"
        lc.save_wav(path, wave, sr)
        mp3 = path.with_suffix(".mp3")
        subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", str(path), "-ac", "1", "-b:a", "48k", str(mp3)], check=True)
        path.unlink()
        audio_s = len(wave) / sr
        results.append({"voice": label, "engine": engine_name, "mp3": f"audio/{mp3.name}", "size_mb": size_mb,
                        "audio_s": round(audio_s, 1), "rtf": round(synth_s / audio_s, 3)})
        print(f"{label:34s} {engine_name:12s} {size_mb:4d} MB  RTF {synth_s / audio_s:.3f}", flush=True)

    for voice, quality in PIPER:
        engine, size = piper_engine(voice, quality)
        render(f"Piper {voice}", engine, 0, 1.0, size, "Piper pt_BR")

    kokoro, kokoro_size = kokoro_engine()
    for sid, name in KOKORO:
        render(f"Kokoro {name}", kokoro, sid, 1.0, kokoro_size, "Kokoro pt-br")

    cast, cast_size = supertonic_engine(lc.APP_CAST)
    render("Supertonic narrador (atual)", cast, 0, 0.85, cast_size, "Supertonic", steps=5)
    render("Supertonic narradora (atual)", cast, 1, 0.85, cast_size, "Supertonic", steps=5)
    render("Supertonic narrador + reescrita", cast, 0, 0.85, cast_size, "Supertonic", steps=5, text=respell(PASSAGE))

    try:
        from pocket_tts import TTSModel
        model = TTSModel.load_model(language="portuguese")
        state = model.get_state_for_audio_prompt("rafael")
        started = time.perf_counter()
        audio = model.generate_audio(state, PASSAGE.replace("\n", " "))
        wave = audio.numpy().astype(np.float32)
        synth_s = time.perf_counter() - started
        path = out / "audio" / "pocket_tts_rafael.wav"
        lc.save_wav(path, wave, model.sample_rate)
        subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", str(path), "-ac", "1", "-b:a", "48k",
                        str(path.with_suffix(".mp3"))], check=True)
        path.unlink()
        audio_s = len(wave) / model.sample_rate
        results.append({"voice": "Pocket TTS rafael", "engine": "Pocket TTS", "mp3": "audio/pocket_tts_rafael.mp3",
                        "size_mb": 120, "audio_s": round(audio_s, 1), "rtf": round(synth_s / audio_s, 3)})
        print(f"{'Pocket TTS rafael':34s} {'Pocket TTS':12s}  120 MB  RTF {synth_s / audio_s:.3f}", flush=True)
    except Exception as e:
        print(f"Pocket TTS skipped: {type(e).__name__}: {str(e)[:120]}")

    (out / "shortlist.json").write_text(json.dumps(results, ensure_ascii=False, indent=1), encoding="utf-8")


if __name__ == "__main__":
    main()
