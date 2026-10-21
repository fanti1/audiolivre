"""Records the same passage with F5-TTS pt-br, the heavyweight Brazilian model, for the quality comparison.

F5-TTS imitates whatever voice it is given as reference, so the app's own Faber can be the reference: the
result is the voice he already likes, spoken by a far larger model. Runs in the GPU venv, which has torch+cu126
and f5-tts but not sherpa-onnx, so scoring happens afterwards with qa/score_wavs.py.

    %USERPROFILE%\\qa-models\\venv-tts\\Scripts\\python.exe qa/bakeoff_f5.py --out %USERPROFILE%\\qa-models\\out\\sota
"""

import argparse
import json
import time
from pathlib import Path

import soundfile as sf
import torch
import torchaudio

# torchaudio 2.14 reads files through torchcodec, which only binds FFmpeg 4-7; this machine has FFmpeg 8.
# Reference clips are plain WAV, so soundfile reads them directly.
def _load(path, *a, **k):
    data, sr = sf.read(str(path), dtype="float32", always_2d=True)
    return torch.from_numpy(data.T), sr


torchaudio.load = _load

from f5_tts.api import F5TTS  # noqa: E402
from huggingface_hub import hf_hub_download  # noqa: E402

# Narration, dialogue with a dash, suspense; F5-TTS pt-br wants lower case and numbers already spelled out.
PASSAGE = (
    "naquela noite, a cidade parecia diferente. ele abriu a porta devagar, sem fazer barulho, e ficou ouvindo a "
    "própria respiração. o relógio marcava vinte e três horas e dez minutos. "
    "— você ouviu isso? — perguntou ela, com a voz presa na garganta. "
    "— ouvi. não se mexa. "
    "o corredor estava frio, e o cheiro de terra molhada subia pelo assoalho. ninguém morava ali havia trinta anos."
)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    ap.add_argument("--refs", nargs="+", default=["faber", "miro", "dii"])
    ap.add_argument("--nfe", type=int, default=32, help="flow-matching steps; quality against speed")
    ap.add_argument("--device", default="cuda", help="cuda, or cpu to estimate what a phone would cost")
    ap.add_argument("--cpu-threads", type=int, default=4, help="phone-CPU proxy when --device cpu")
    args = ap.parse_args()
    if args.device == "cpu":
        torch.set_num_threads(args.cpu_threads)
    out = Path(args.out)

    ckpt = hf_hub_download("firstpixel/F5-TTS-pt-br", "pt-br/model_last.safetensors")
    vocab = hf_hub_download("SWivid/F5-TTS", "F5TTS_Base/vocab.txt")
    # The pt-br weights were fine-tuned from the original F5TTS_Base architecture, not the newer v1.
    model = F5TTS(model="F5TTS_Base", ckpt_file=ckpt, vocab_file=vocab, device=args.device)

    rows = []
    for ref in args.refs:
        # Each reference carries its own transcript; the app's own voices share ref.txt.
        clip = out / f"ref-{ref}.wav" if (out / f"ref-{ref}.wav").exists() else out / f"{ref}.wav"
        words = out / f"{ref}.txt" if (out / f"{ref}.txt").exists() else out / "ref.txt"
        ref_text = words.read_text(encoding="utf-8").strip().lower()
        t0 = time.perf_counter()
        wav, sr, _ = model.infer(ref_file=str(clip), ref_text=ref_text, gen_text=PASSAGE,
                                 nfe_step=args.nfe, speed=1.0, remove_silence=False, seed=1234)
        took = time.perf_counter() - t0
        path = out / f"f5-{ref}.wav"
        sf.write(path, wav, sr)
        seconds = len(wav) / sr
        rows.append({"voice": f"f5-{ref}", "seconds": round(seconds, 2), "rtf": round(took / seconds, 3), "device": args.device,
                     "sample_rate": sr, "nfe": args.nfe})
        print(f"f5-{ref:<12} {seconds:5.1f}s  {args.device.upper()} RTF {took / seconds:6.2f}", flush=True)

    (out / f"f5-{args.device}.json").write_text(json.dumps(rows, ensure_ascii=False, indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
