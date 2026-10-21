"""Tunes the knobs Piper's own model exposes, to get the most out of the voice the app already ships.

A VITS voice has two sampling temperatures the app never touched: `noise_scale` (how much the timbre varies)
and `noise_scale_w` (how much the phoneme durations vary — the difference between a metronome and a person).
This sweeps them over real book sentences and reports naturalness and word errors for each setting.

    python qa/tune_piper.py --out %USERPROFILE%\\qa-models\\out\\tune --count 12
"""

import argparse
import json
import sys
import wave
from pathlib import Path

import numpy as np
import torch
import torchaudio

sys.path.insert(0, str(Path(__file__).parent))
import listen_check as lc  # noqa: E402
import sherpa_onnx  # noqa: E402

MODELS = Path.home() / "qa-models"
VOICE = MODELS / "vits-piper-pt_BR-faber-medium"  # overridden by --voice
SENTENCES = MODELS / "book_sentences.jsonl"
BASE_SPEED = 0.70  # VoiceCast.BASE_SPEED
DEFAULTS = (0.667, 0.8)  # what Piper ships, and what the app uses today
BAD = 0.20


def tts_with(noise, noise_w, threads=8):
    vits = sherpa_onnx.OfflineTtsVitsModelConfig(
        model=str(next(VOICE.glob("*.onnx"))), tokens=str(VOICE / "tokens.txt"),
        data_dir=str(VOICE / "espeak-ng-data"), noise_scale=noise, noise_scale_w=noise_w)
    return sherpa_onnx.OfflineTts(sherpa_onnx.OfflineTtsConfig(
        model=sherpa_onnx.OfflineTtsModelConfig(vits=vits, num_threads=threads), silence_scale=1.0))


def read_wav(path):
    with wave.open(str(path), "rb") as w:
        sr = w.getframerate()
        pcm = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float32) / 32768.0
    return torch.from_numpy(pcm.reshape(1, -1).copy()), sr


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    ap.add_argument("--count", type=int, default=12)
    ap.add_argument("--voice", default=None, help="folder name of another Piper voice")
    ap.add_argument("--noise", type=float, nargs="+", default=[0.4, 0.55, 0.667, 0.8])
    ap.add_argument("--noise-w", type=float, nargs="+", default=[0.5, 0.65, 0.8, 0.95])
    args = ap.parse_args()
    global VOICE
    if args.voice:
        VOICE = MODELS / args.voice
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)

    lines = [json.loads(l) for l in SENTENCES.read_text(encoding="utf-8", errors="replace").splitlines()]
    body = [l for l in lines if not l.get("heading") and len(l["tts"]) > 60]
    import random
    random.Random(7).shuffle(body)
    picked = body[:args.count]

    grid = [(n, w) for n in args.noise for w in args.noise_w]
    whisper = lc.WhisperModel("large-v3", device="cuda", compute_type="float16")
    utmos = torch.hub.load("tarepan/SpeechMOS:v1.2.0", "utmos22_strong", trust_repo=True).eval()

    rows = []
    for noise, noise_w in grid:
        tts = tts_with(noise, noise_w)
        wers, moss = [], []
        for i, line in enumerate(picked):
            path = out / f"n{noise}-w{noise_w}-{i:02d}.wav"
            audio = lc.trim_silence(lc.synth(tts, line["tts"], 0, BASE_SPEED, 0), tts.sample_rate)
            lc.save_wav(path, audio, tts.sample_rate)
            a, sr = read_wav(path)
            moss.append(float(utmos(torchaudio.functional.resample(a, sr, 16000), 16000)))
            segments, _ = whisper.transcribe(str(path), language="pt", beam_size=5,
                                             initial_prompt=lc.WORDS_ONLY_PROMPT)
            heard = " ".join(s.text.strip() for s in segments)
            wers.append(lc.process_words(lc.fold(line["tts"]), lc.fold(heard)).wer)
        mark = "  <- hoje" if (noise, noise_w) == DEFAULTS else ""
        rows.append({"noise_scale": noise, "noise_scale_w": noise_w,
                     "utmos": round(float(np.mean(moss)), 3), "wer": round(float(np.mean(wers)) * 100, 1),
                     "broken": sum(1 for w in wers if w > BAD), "count": len(wers)})
        print(f"noise {noise:<5} noise_w {noise_w:<5} UTMOS {rows[-1]['utmos']:.3f}  "
              f"WER {rows[-1]['wer']:4.1f}%  quebradas {rows[-1]['broken']}/{len(wers)}{mark}", flush=True)

    rows.sort(key=lambda r: -r["utmos"])
    (out / "tune.json").write_text(json.dumps(rows, ensure_ascii=False, indent=2), encoding="utf-8")
    best = rows[0]
    print(f"\nmelhor naturalidade: noise_scale {best['noise_scale']}, noise_scale_w {best['noise_scale_w']} "
          f"-> UTMOS {best['utmos']}, WER {best['wer']}%")


if __name__ == "__main__":
    main()
