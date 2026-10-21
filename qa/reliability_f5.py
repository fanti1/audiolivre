"""Reads real book sentences with F5-TTS pt-br, so its reliability can be compared with the app's Piper voice.

Naturalness is only half the question: a narrator that sounds lovely but swallows or repeats words ruins a book.
This records N sentences from Dom Casmurro with each reference voice and leaves them for qa/score_book.py to
transcribe. Runs in the GPU venv.

    %USERPROFILE%\\qa-models\\venv-tts\\Scripts\\python.exe qa/reliability_f5.py --out <dir> --count 24
"""

import argparse
import json
import random
import time
from pathlib import Path

import soundfile as sf
import torch
import torchaudio


def _load(path, *a, **k):
    data, sr = sf.read(str(path), dtype="float32", always_2d=True)
    return torch.from_numpy(data.T), sr


torchaudio.load = _load

from f5_tts.api import F5TTS  # noqa: E402
from huggingface_hub import hf_hub_download  # noqa: E402

SENTENCES = Path.home() / "qa-models" / "book_sentences.jsonl"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    ap.add_argument("--count", type=int, default=24)
    ap.add_argument("--refs", nargs="+", default=["human-male", "human-female"])
    ap.add_argument("--nfe", type=int, default=32)
    args = ap.parse_args()
    out = Path(args.out)
    (out / "book").mkdir(parents=True, exist_ok=True)

    lines = [json.loads(l) for l in SENTENCES.read_text(encoding="utf-8", errors="replace").splitlines()]
    # Full sentences only: headings and one-word lines say nothing about reliability.
    body = [l for l in lines if not l.get("heading") and len(l["tts"]) > 60]
    random.Random(7).shuffle(body)
    picked = body[:args.count]
    (out / "book" / "script.json").write_text(json.dumps(picked, ensure_ascii=False, indent=2), encoding="utf-8")

    ckpt = hf_hub_download("firstpixel/F5-TTS-pt-br", "pt-br/model_last.safetensors")
    vocab = hf_hub_download("SWivid/F5-TTS", "F5TTS_Base/vocab.txt")
    model = F5TTS(model="F5TTS_Base", ckpt_file=ckpt, vocab_file=vocab, device="cuda")

    for ref in args.refs:
        ref_text = (out / f"{ref}.txt").read_text(encoding="utf-8").strip().lower()
        total_audio = total_time = 0.0
        for i, line in enumerate(picked):
            t0 = time.perf_counter()
            wav, sr, _ = model.infer(ref_file=str(out / f"{ref}.wav"), ref_text=ref_text,
                                     gen_text=line["tts"].lower(), nfe_step=args.nfe, speed=1.0, seed=1234)
            total_time += time.perf_counter() - t0
            total_audio += len(wav) / sr
            sf.write(out / "book" / f"f5-{ref}-{i:02d}.wav", wav, sr)
        print(f"f5-{ref}: {len(picked)} sentences, {total_audio:.0f}s audio, GPU RTF {total_time / total_audio:.3f}",
              flush=True)


if __name__ == "__main__":
    main()
