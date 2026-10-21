"""Listening QA: does the voice say what the page says?

Synthesizes sentences exactly like the app does (Supertonic 3 int8 via sherpa-onnx, same voices, speed and
steps), transcribes the audio with Whisper large-v3 on the GPU and compares, word by word, what the voice was
asked to say with what a listener hears. Sentences where they diverge are the reading errors to fix.

Input: JSON lines with "tts" (text sent to the voice), optional "display" and "role" (NARRATOR/MALE/FEMALE),
as written by StressCorpusTest (JVM) or QaDumpTest (on device).

    python listen_check.py app/build/qa/stress_normalized.jsonl --out qa/out/stress
"""

import argparse
import glob
import json
import os
import random
import re
import site
import time
import unicodedata
import wave
from pathlib import Path

import numpy as np


def add_cuda_dlls():
    # The CUDA runtime comes from pip wheels (nvidia-cublas-cu12, nvidia-cudnn-cu12) on Windows.
    for base in site.getsitepackages():
        for d in glob.glob(os.path.join(base, "nvidia", "*", "bin")):
            os.add_dll_directory(d)
            os.environ["PATH"] = d + os.pathsep + os.environ["PATH"]


add_cuda_dlls()
import sherpa_onnx  # noqa: E402
from faster_whisper import WhisperModel  # noqa: E402
from jiwer import process_words  # noqa: E402
from num2words import num2words  # noqa: E402

MODEL_DIR = Path(os.environ.get("SUPERTONIC_DIR", str(Path.home() / "qa-models" / "sherpa-onnx-supertonic-3-tts-int8-2026-05-11")))
SIDS = {"NARRATOR": 5, "MALE": 7, "FEMALE": 1}
BASE_SPEED = 0.85  # VoiceCast.BASE_SPEED
# Whisper writes numbers as digits ("R$ 12,50", "20h01"), which hides whether the voice said them right; a prompt
# in the words-only style steers it to spell out what it hears.
WORDS_ONLY_PROMPT = ("Transcrição literal, com tudo escrito por extenso como foi falado: vinte e três horas e dez "
                     "minutos, mil e quinhentos reais, trinta e cinco por cento, primeiro de janeiro de dois mil e "
                     "vinte, capítulo quatro, cê pê éfe.")


APP_CAST = Path(__file__).parent.parent / "app" / "src" / "main" / "assets" / "voices" / "cast.bin"


# The sampling temperatures the app ships (VoiceModels.NOISE_SCALE / NOISE_SCALE_W), measured by qa/tune_piper.py.
NOISE_SCALE = 0.25
NOISE_SCALE_W = 0.65


def piper_tts(voice_dir, threads, noise_scale=NOISE_SCALE, noise_scale_w=NOISE_SCALE_W):
    d = Path(voice_dir)
    vits = sherpa_onnx.OfflineTtsVitsModelConfig(model=str(next(d.glob("*.onnx"))), tokens=str(d / "tokens.txt"),
                                                 data_dir=str(d / "espeak-ng-data"),
                                                 noise_scale=noise_scale, noise_scale_w=noise_scale_w)
    return sherpa_onnx.OfflineTts(sherpa_onnx.OfflineTtsConfig(
        model=sherpa_onnx.OfflineTtsModelConfig(vits=vits, num_threads=threads), silence_scale=1.0))


def make_tts(threads, voice_style=None):
    st = sherpa_onnx.OfflineTtsSupertonicModelConfig(
        duration_predictor=str(MODEL_DIR / "duration_predictor.int8.onnx"),
        text_encoder=str(MODEL_DIR / "text_encoder.int8.onnx"),
        vector_estimator=str(MODEL_DIR / "vector_estimator.int8.onnx"),
        vocoder=str(MODEL_DIR / "vocoder.int8.onnx"),
        tts_json=str(MODEL_DIR / "tts.json"),
        unicode_indexer=str(MODEL_DIR / "unicode_indexer.bin"),
        voice_style=str(voice_style or MODEL_DIR / "voice.bin"))
    cfg = sherpa_onnx.OfflineTtsConfig(
        model=sherpa_onnx.OfflineTtsModelConfig(supertonic=st, num_threads=threads), silence_scale=1.0)
    return sherpa_onnx.OfflineTts(cfg)


def synth(tts, text, sid, speed, steps):
    gen = sherpa_onnx.GenerationConfig()
    gen.sid = sid
    gen.speed = speed
    gen.silence_scale = 1.0
    if steps:  # Supertonic only; Piper (VITS) ignores steps and the language tag
        gen.num_steps = steps
        gen.extra = {"lang": "pt"}
    return np.asarray(tts.generate(text, gen).samples, dtype=np.float32)


def save_wav(path, audio, sr):
    pcm = (np.clip(audio, -1, 1) * 32767).astype(np.int16)
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(sr)
        w.writeframes(pcm.tobytes())


CONJUNCTIONS = [" e ", " mas ", " porém ", " que ", " quando ", " porque ", " onde ", " como ", " ou ", " se "]


def chunk_text(text, max_chars):
    """Port of SpeechChunker.split (the app's): cut long sentences where a reader would breathe."""
    if max_chars <= 0:
        return [text]
    pieces, rest = [], text.strip()
    while len(rest) > max_chars:
        lo = int(max_chars * 0.35)
        window = rest[: max_chars + 1]

        def last_after(chars):
            for i in range(len(window) - 1, lo - 1, -1):
                if window[i] in chars and i + 1 < len(window) and window[i + 1].isspace():
                    return i + 1
            return None

        cut = last_after(";:") or last_after(",")
        if cut is None:
            found = [window.rfind(c) for c in CONJUNCTIONS]
            found = [p for p in found if p >= lo]
            cut = max(found) if found else None
        if cut is None:
            sp = window.rfind(" ")
            cut = sp if sp >= lo else max_chars
        pieces.append(rest[:cut].strip())
        rest = rest[cut:].strip()
    if rest:
        pieces.append(rest)
    return pieces


def trim_silence(audio, sr, threshold=0.01, margin_ms=40):
    idx = np.flatnonzero(np.abs(audio) > threshold)
    if idx.size == 0:
        return audio[:0]
    margin = margin_ms * sr // 1000
    return audio[max(idx[0] - margin, 0): min(idx[-1] + margin + 1, len(audio))]


def render(tts, text, sid, speed, steps, max_chars, sr, clause_pause_ms=180):
    parts = []
    for n, piece in enumerate(chunk_text(text, max_chars)):
        if n:
            parts.append(np.zeros(int(sr * clause_pause_ms / 1000), dtype=np.float32))
        parts.append(trim_silence(synth(tts, piece, sid, speed, steps), sr))
    return np.concatenate(parts) if parts else np.zeros(1, dtype=np.float32)


NUMBER = re.compile(r"\d+(?:[.,]\d+)*")
ARCHAIC = [(re.compile(p), r) for p, r in [
    (r"ph", "f"), (r"th", "t"), (r"y", "i"), (r"catorze", "quatorze"), (r"cincoenta", "cinquenta"),
]]


def spell_digits(text):
    def repl(m):
        s = m.group(0)
        if re.fullmatch(r"\d{1,3}(?:\.\d{3})+", s):
            s = s.replace(".", "")
        s = s.replace(",", ".")
        try:
            return " " + num2words(float(s) if "." in s else int(s), lang="pt_BR") + " "
        except Exception:
            return " " + s + " "
    return NUMBER.sub(repl, text)


def fold(text):
    """Folds spelling that sounds the same (accents, archaic doubled letters, digits vs words) so only
    audible differences count."""
    t = spell_digits(text.lower())
    t = unicodedata.normalize("NFKD", t)
    t = "".join(c for c in t if not unicodedata.combining(c))
    t = re.sub(r"[^a-z0-9\s]", " ", t)
    for pattern, replacement in ARCHAIC:
        t = pattern.sub(replacement, t)
    t = re.sub(r"([a-z])\1+", r"\1", t)  # "elle" / "ele", "succedeu" / "sucedeu"
    return " ".join(t.split())


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("input")
    ap.add_argument("--out", required=True)
    ap.add_argument("--speed", type=float, default=1.0, help="the app's speed multiplier (1.0 = 0.85 model speed)")
    ap.add_argument("--steps", type=int, default=5)
    ap.add_argument("--base-speed", type=float, default=BASE_SPEED, help="engine speed at the app's 1.0x")
    ap.add_argument("--narrator", default="MALE", choices=["MALE", "FEMALE"])
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--sample", type=int, default=0, help="random sample size (0 = all, in order)")
    ap.add_argument("--min-chars", type=int, default=0)
    ap.add_argument("--threads", type=int, default=8)
    ap.add_argument("--whisper", default="large-v3")
    ap.add_argument("--chunk", type=int, default=0, help="max chars per synthesized piece (0 = whole sentence)")
    ap.add_argument("--asr-only", default="", help="re-judge the <out>/results.jsonl items using the WAVs in this subfolder")
    ap.add_argument("--cast", action="store_true", help="use the app's own voices (assets/voices/cast.bin)")
    ap.add_argument("--piper", default="", help="a Piper voice folder to use instead of Supertonic")
    args = ap.parse_args()
    if args.asr_only:
        out = Path(args.out)
        items = [json.loads(line) for line in open(out / "results.jsonl", encoding="utf-8")]
        for it in items:
            it["wav"] = str(out / args.asr_only / Path(it["wav"]).name)
            with wave.open(it["wav"]) as w:
                it["audio_s"] = w.getnframes() / w.getframerate()
        return judge(items, out, args, suffix="_" + args.asr_only)

    items = [json.loads(line) for line in open(args.input, encoding="utf-8") if line.strip()]
    items = [it for it in items if len(it["tts"]) >= args.min_chars and re.search(r"\w", it["tts"])]
    if args.sample:
        random.seed(7)
        items = random.sample(items, min(args.sample, len(items)))
    if args.limit:
        items = items[: args.limit]

    out = Path(args.out)
    (out / "wav").mkdir(parents=True, exist_ok=True)
    tts = piper_tts(args.piper, args.threads) if args.piper else make_tts(args.threads, APP_CAST if args.cast else None)
    sr = tts.sample_rate
    if args.cast:  # VoiceCast order: male narrator, female narrator, male character, female character
        sids = {"NARRATOR": 0 if args.narrator == "MALE" else 1, "MALE": 2, "FEMALE": 3}
    else:
        sids = dict(SIDS)
        sids["NARRATOR"] = 5 if args.narrator == "MALE" else 0

    synth_s = audio_s = 0.0
    for n, it in enumerate(items):
        t0 = time.perf_counter()
        audio = render(tts, it["tts"], sids[it.get("role", "NARRATOR")], args.base_speed * args.speed,
                       0 if args.piper else args.steps, args.chunk, sr)
        it["synth_s"] = time.perf_counter() - t0
        it["audio_s"] = len(audio) / sr
        synth_s += it["synth_s"]
        audio_s += it["audio_s"]
        it["wav"] = str(out / "wav" / f"{n:04d}.wav")
        save_wav(it["wav"], audio, sr)
    print(f"synthesized {len(items)} items: {audio_s:.0f}s of audio in {synth_s:.0f}s (RTF {synth_s / max(audio_s, 1e-9):.3f})")
    judge(items, out, args)


def judge(items, out, args, suffix=""):
    asr = WhisperModel(args.whisper, device="cuda", compute_type="float16")
    for it in items:
        segments, _ = asr.transcribe(it["wav"], language="pt", beam_size=5, condition_on_previous_text=False,
                                     vad_filter=False, temperature=0.0, initial_prompt=WORDS_ONLY_PROMPT)
        it["heard"] = " ".join(s.text.strip() for s in segments)
        ref, hyp = fold(it["tts"]), fold(it["heard"])
        m = process_words(ref or "vazio", hyp or "vazio")
        it["wer"] = round(m.wer, 3)
        it["deleted"] = m.deletions
        it["inserted"] = m.insertions
        it["words"] = len(ref.split())
        it["words_per_min"] = round(it["words"] / max(it["audio_s"], 0.1) * 60)

    with open(out / f"results{suffix}.jsonl", "w", encoding="utf-8") as f:
        for it in items:
            f.write(json.dumps(it, ensure_ascii=False) + "\n")

    total_words = sum(it["words"] for it in items)
    weighted = sum(it["wer"] * it["words"] for it in items) / max(total_words, 1)
    print(f"WER {weighted:.3f} over {total_words} words; sentences with WER > 0.2: "
          f"{sum(it['wer'] > 0.2 for it in items)}/{len(items)}; inserted words {sum(it['inserted'] for it in items)}, "
          f"deleted words {sum(it['deleted'] for it in items)}; sentences with 2+ inserted or deleted: "
          f"{sum(it['inserted'] + it['deleted'] >= 2 for it in items)}")
    for lo, hi in [(0, 80), (80, 160), (160, 240), (240, 400), (400, 10_000)]:
        bucket = [it for it in items if lo <= len(it["tts"]) < hi]
        if bucket:
            w = sum(it["wer"] * it["words"] for it in bucket) / max(sum(it["words"] for it in bucket), 1)
            wpm = np.median([it["words_per_min"] for it in bucket])
            print(f"  {lo:>4}-{hi:<5} chars: {len(bucket):4d} sentences, WER {w:.3f}, median {wpm:.0f} words/min")
    print("\nworst:")
    for it in sorted(items, key=lambda x: -x["wer"])[:25]:
        print(f"[{it['wer']:.2f}] {it['tts'][:150]}\n        heard: {it['heard'][:150]}")


if __name__ == "__main__":
    main()
