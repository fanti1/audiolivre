"""Two leftovers from the old engine that nobody re-measured after Piper took over.

1. The app cuts any sentence over 180 characters into pieces and synthesizes each one on its own, joined by a
   180 ms silence. That number was chosen for Supertonic, which read long inputs badly. Piper may not need it
   at all — and every cut restarts the intonation in the middle of a sentence.
2. `silence_scale` stretches the pauses the phonemizer puts at commas and full stops inside a sentence. The
   app pins it at 1.0; a narrator breathes a little longer than that.

Measured on long book sentences only, since short ones are never cut.

    python qa/tune_delivery.py --out %USERPROFILE%\\qa-models\\out\\delivery --count 16
"""

import argparse
import json
import random
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
VOICE = MODELS / "vits-piper-pt_BR-faber-medium"
SENTENCES = MODELS / "book_sentences.jsonl"
BASE_SPEED = 0.70
MIN_CHARS = 200  # long enough that the app's 180-char rule actually cuts it
BAD = 0.20


def tts_with(silence_scale, threads=8):
    vits = sherpa_onnx.OfflineTtsVitsModelConfig(
        model=str(next(VOICE.glob("*.onnx"))), tokens=str(VOICE / "tokens.txt"),
        data_dir=str(VOICE / "espeak-ng-data"),
        noise_scale=lc.NOISE_SCALE, noise_scale_w=lc.NOISE_SCALE_W)
    return sherpa_onnx.OfflineTts(sherpa_onnx.OfflineTtsConfig(
        model=sherpa_onnx.OfflineTtsModelConfig(vits=vits, num_threads=threads), silence_scale=silence_scale))


def read_wav(path):
    with wave.open(str(path), "rb") as w:
        sr = w.getframerate()
        pcm = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float32) / 32768.0
    return torch.from_numpy(pcm.reshape(1, -1).copy()), sr


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    ap.add_argument("--count", type=int, default=16)
    ap.add_argument("--max-chars", type=int, nargs="+", default=[180, 300, 500, 0])
    ap.add_argument("--silence", type=float, nargs="+", default=[1.0])
    args = ap.parse_args()
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)

    lines = [json.loads(l) for l in SENTENCES.read_text(encoding="utf-8", errors="replace").splitlines()]
    body = [l for l in lines if not l.get("heading") and len(l["tts"]) >= MIN_CHARS]
    random.Random(11).shuffle(body)
    picked = body[:args.count]
    print(f"{len(picked)} long sentences, {int(np.mean([len(l['tts']) for l in picked]))} chars on average")

    whisper = lc.WhisperModel("large-v3", device="cuda", compute_type="float16")
    utmos = torch.hub.load("tarepan/SpeechMOS:v1.2.0", "utmos22_strong", trust_repo=True).eval()

    rows = []
    for silence in args.silence:
        tts = tts_with(silence)
        for max_chars in args.max_chars:
            wers, moss, levels = [], [], []
            for i, line in enumerate(picked):
                path = out / f"s{silence}-c{max_chars}-{i:02d}.wav"
                audio = lc.render(tts, line["tts"], 0, BASE_SPEED, 0, max_chars, tts.sample_rate)
                lc.save_wav(path, audio, tts.sample_rate)
                levels.append(float(np.sqrt(np.mean(audio ** 2))))
                a, sr = read_wav(path)
                moss.append(float(utmos(torchaudio.functional.resample(a, sr, 16000), 16000)))
                segments, _ = whisper.transcribe(str(path), language="pt", beam_size=5,
                                                 initial_prompt=lc.WORDS_ONLY_PROMPT)
                heard = " ".join(s.text.strip() for s in segments)
                wers.append(lc.process_words(lc.fold(line["tts"]), lc.fold(heard)).wer)
            # How much the loudness wanders from sentence to sentence: a narrator's does not.
            spread = float(np.std(levels) / np.mean(levels))
            label = "inteira" if max_chars == 0 else f"{max_chars} chars"
            mark = "  <- hoje" if (max_chars == 180 and silence == 1.0) else ""
            rows.append({"max_chars": max_chars, "silence_scale": silence,
                         "utmos": round(float(np.mean(moss)), 3), "wer": round(float(np.mean(wers)) * 100, 1),
                         "broken": sum(1 for w in wers if w > BAD), "level_spread": round(spread, 3)})
            print(f"silence {silence:<4} corte {label:<10} UTMOS {rows[-1]['utmos']:.3f}  "
                  f"WER {rows[-1]['wer']:4.1f}%  quebradas {rows[-1]['broken']}/{len(wers)}  "
                  f"volume ±{spread * 100:.0f}%{mark}", flush=True)

    rows.sort(key=lambda r: -r["utmos"])
    (out / "delivery.json").write_text(json.dumps(rows, ensure_ascii=False, indent=2), encoding="utf-8")
    best = rows[0]
    print(f"\nmelhor: corte {best['max_chars']}, silence_scale {best['silence_scale']} "
          f"-> UTMOS {best['utmos']}, WER {best['wer']}%")


if __name__ == "__main__":
    main()
