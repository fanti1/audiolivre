"""Compares the voice the app uses today with everything better that exists for Brazilian Portuguese.

Two families are measured side by side:
  * on-device: what can run inside the app in real time (Piper VITS, Kokoro 82M) via sherpa-onnx;
  * heavyweight: the current state of the art (F5-TTS pt-br, Chatterbox pt), which needs a GPU.

For each voice it records the same passage and reports the real-time factor with 4 threads (a phone-CPU proxy),
plus the word error rate Whisper makes on the result. The audio lands in <out>/ for a listening page.

    python qa/bakeoff_sota.py --out %USERPROFILE%\\qa-models\\out\\sota
"""

import argparse
import json
import sys
import time
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).parent))
import listen_check as lc  # noqa: E402

MODELS = Path.home() / "qa-models"
KOKORO = MODELS / "kokoro-int8-multi-lang-v1_0"
BASE_SPEED = 0.70  # VoiceCast.BASE_SPEED: the app's audiobook pace

# Narration, dialogue with a dash, suspense and the words that usually break a pt-BR voice.
PASSAGE = (
    "Naquela noite, a cidade parecia diferente. Ele abriu a porta devagar, sem fazer barulho, e ficou ouvindo a "
    "própria respiração. O relógio marcava vinte e três horas e dez minutos. "
    "— Você ouviu isso? — perguntou ela, com a voz presa na garganta. "
    "— Ouvi. Não se mexa. "
    "O corredor estava frio, e o cheiro de terra molhada subia pelo assoalho. Ninguém morava ali havia trinta anos."
)

# Piper voices installed by the app, as VoiceModels declares them: folder and pace correction.
PIPER = {
    "faber": ("vits-piper-pt_BR-faber-medium", 1.00),
    "miro": ("vits-piper-pt_BR-miro-high", 1.17),
    "dii": ("vits-piper-pt_BR-dii-high", 1.16),
}
# Kokoro's Brazilian speakers (ids from the model metadata).
KOKORO_VOICES = {"pm_santa": 44, "pm_alex": 43, "pf_dora": 42}


def kokoro_tts(threads):
    cfg = lc.sherpa_onnx.OfflineTtsKokoroModelConfig(
        model=str(KOKORO / "model.int8.onnx"),
        voices=str(KOKORO / "voices.bin"),
        tokens=str(KOKORO / "tokens.txt"),
        data_dir=str(KOKORO / "espeak-ng-data"),
        dict_dir=str(KOKORO / "dict"),
        lexicon=",".join(str(KOKORO / f"lexicon-{n}.txt") for n in ("us-en", "zh")),
        lang="pt",
    )
    return lc.sherpa_onnx.OfflineTts(lc.sherpa_onnx.OfflineTtsConfig(
        model=lc.sherpa_onnx.OfflineTtsModelConfig(kokoro=cfg, num_threads=threads), silence_scale=1.0))


def measure(name, audio, sr, synth_s, out_dir, whisper, rows):
    wav = out_dir / f"{name}.wav"
    lc.save_wav(wav, audio, sr)
    seconds = len(audio) / sr
    rtf = synth_s / seconds if seconds else 0
    text, _ = _transcribe(whisper, wav)
    wer = lc.process_words(lc.fold(PASSAGE), lc.fold(text)).wer if text else 1.0
    rows.append({"voice": name, "seconds": round(seconds, 2), "rtf": round(rtf, 3),
                 "wer": round(wer * 100, 1), "sample_rate": sr, "heard": text})
    print(f"{name:<28} {seconds:5.1f}s  RTF {rtf:5.3f}  WER {wer * 100:4.1f}%")


def _transcribe(whisper, wav):
    segments, info = whisper.transcribe(str(wav), language="pt", beam_size=5,
                                        initial_prompt=lc.WORDS_ONLY_PROMPT)
    return " ".join(s.text.strip() for s in segments), info


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    ap.add_argument("--threads", type=int, default=4, help="phone-CPU proxy")
    args = ap.parse_args()
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)

    print("loading Whisper large-v3 ...")
    whisper = lc.WhisperModel("large-v3", device="cuda", compute_type="float16")
    rows = []

    for voice, (folder, pace) in PIPER.items():
        tts = lc.piper_tts(MODELS / folder, args.threads)
        t0 = time.perf_counter()
        audio = lc.synth(tts, PASSAGE, 0, BASE_SPEED * pace, 0)
        measure(f"piper-{voice}", lc.trim_silence(audio, tts.sample_rate), tts.sample_rate,
                time.perf_counter() - t0, out, whisper, rows)

    tts = kokoro_tts(args.threads)
    for voice, sid in KOKORO_VOICES.items():
        t0 = time.perf_counter()
        audio = lc.synth(tts, PASSAGE, sid, 1.0, 0)
        measure(f"kokoro-{voice}", lc.trim_silence(audio, tts.sample_rate), tts.sample_rate,
                time.perf_counter() - t0, out, whisper, rows)

    (out / "ondevice.json").write_text(json.dumps(rows, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"\n{len(rows)} voices -> {out}")


if __name__ == "__main__":
    main()
