"""Records the comparison passage and book sentences with Chatterbox pt, the other top open model.

Chatterbox (Resemble AI, MIT) won a blind preference test against ElevenLabs; this is its Portuguese
fine-tune. Like F5 it clones a reference clip and needs a GPU, so it is measured the same way.

    %USERPROFILE%\\qa-models\\venv-tts\\Scripts\\python.exe qa/bakeoff_chatterbox.py --out <dir> [--book 24]
"""

import argparse
import json
import time
from pathlib import Path

import soundfile as sf
import torch
from chatterbox.tts import ChatterboxTTS
from huggingface_hub import snapshot_download

REPO = "FearL0rd/Chatterbox-TTS-Portuguese"
PASSAGE = (
    "Naquela noite, a cidade parecia diferente. Ele abriu a porta devagar, sem fazer barulho, e ficou ouvindo a "
    "própria respiração. O relógio marcava vinte e três horas e dez minutos. "
    "— Você ouviu isso? — perguntou ela, com a voz presa na garganta. "
    "— Ouvi. Não se mexa. "
    "O corredor estava frio, e o cheiro de terra molhada subia pelo assoalho. Ninguém morava ali havia trinta anos."
)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    ap.add_argument("--refs", nargs="+", default=["short-male", "short-female"])
    ap.add_argument("--book", type=int, default=0, help="also read N sentences from the book script")
    args = ap.parse_args()
    out = Path(args.out)

    model = ChatterboxTTS.from_local(snapshot_download(REPO), device="cuda")
    rows = []
    for ref in args.refs:
        clip = str(out / f"{ref}.wav")
        t0 = time.perf_counter()
        wav = model.generate(PASSAGE, audio_prompt_path=clip, exaggeration=0.5, cfg_weight=0.5)
        took = time.perf_counter() - t0
        audio = wav.squeeze(0).cpu().numpy()
        sf.write(out / f"cb-{ref}.wav", audio, model.sr)
        seconds = len(audio) / model.sr
        rows.append({"voice": f"cb-{ref}", "seconds": round(seconds, 2), "rtf": round(took / seconds, 3)})
        print(f"cb-{ref:<14} {seconds:5.1f}s  GPU RTF {took / seconds:5.3f}", flush=True)

        if args.book:
            script = json.loads((out / "book" / "script.json").read_text(encoding="utf-8"))
            for i, line in enumerate(script[:args.book]):
                w = model.generate(line["tts"], audio_prompt_path=clip, exaggeration=0.5, cfg_weight=0.5)
                sf.write(out / "book" / f"cb-{ref}-{i:02d}.wav", w.squeeze(0).cpu().numpy(), model.sr)
            print(f"cb-{ref}: {args.book} book sentences written", flush=True)

    (out / "chatterbox.json").write_text(json.dumps(rows, ensure_ascii=False, indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
