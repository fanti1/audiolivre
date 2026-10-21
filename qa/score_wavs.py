"""Scores every recording in a folder the same way, so voices from different engines can be compared.

    python qa/score_wavs.py %USERPROFILE%\\qa-models\\out\\sota

Reports, per file: Whisper's word error rate against the script (clarity) and UTMOS (predicted naturalness,
the 1-5 scale used in TTS papers). Reference clips (ref-*.wav) are skipped.
"""

import json
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).parent))
import listen_check as lc  # noqa: E402
import torch  # noqa: E402
import torchaudio  # noqa: E402
import wave  # noqa: E402


def read_wav(path):
    # torchaudio 2.x loads through torchcodec, which needs FFmpeg 4-7; everything here is plain 16-bit PCM.
    with wave.open(str(path), "rb") as w:
        sr, n = w.getframerate(), w.getnchannels()
        pcm = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float32) / 32768.0
    return torch.from_numpy(pcm.reshape(-1, n).T.copy()), sr

PASSAGE = (
    "Naquela noite, a cidade parecia diferente. Ele abriu a porta devagar, sem fazer barulho, e ficou ouvindo a "
    "própria respiração. O relógio marcava vinte e três horas e dez minutos. "
    "Você ouviu isso? perguntou ela, com a voz presa na garganta. "
    "Ouvi. Não se mexa. "
    "O corredor estava frio, e o cheiro de terra molhada subia pelo assoalho. Ninguém morava ali havia trinta anos."
)


def main():
    folder = Path(sys.argv[1])
    whisper = lc.WhisperModel("large-v3", device="cuda", compute_type="float16")
    utmos = torch.hub.load("tarepan/SpeechMOS:v1.2.0", "utmos22_strong", trust_repo=True).eval()

    rows = []
    for wav in sorted(folder.glob("*.wav")):
        if wav.name.startswith(("ref-", "short-")):
            continue
        audio, sr = read_wav(wav)
        audio = audio.mean(0, keepdim=True)
        # UTMOS is trained at 16 kHz; every engine here writes a different rate.
        a16 = torchaudio.functional.resample(audio, sr, 16000)
        # Scored in 10-second windows, like the MOS listening tests it was trained on.
        mos = [float(utmos(a16[:, i:i + 160000], 16000)) for i in range(0, a16.shape[1], 160000)
               if a16[:, i:i + 160000].shape[1] > 16000]
        segments, _ = whisper.transcribe(str(wav), language="pt", beam_size=5,
                                         initial_prompt=lc.WORDS_ONLY_PROMPT)
        heard = " ".join(s.text.strip() for s in segments)
        wer = lc.process_words(lc.fold(PASSAGE), lc.fold(heard)).wer
        rows.append({"name": wav.stem, "seconds": round(audio.shape[1] / sr, 1), "sample_rate": sr,
                     "wer": round(wer * 100, 1), "utmos": round(float(np.mean(mos)), 2), "heard": heard})
        print(f"{wav.stem:<20} {rows[-1]['seconds']:5.1f}s  WER {rows[-1]['wer']:4.1f}%  "
              f"UTMOS {rows[-1]['utmos']:.2f}", flush=True)

    rows.sort(key=lambda r: -r["utmos"])
    (folder / "scores.json").write_text(json.dumps(rows, ensure_ascii=False, indent=2), encoding="utf-8")
    print("\nbest naturalness:", ", ".join(f"{r['name']} {r['utmos']}" for r in rows[:3]))


if __name__ == "__main__":
    main()
