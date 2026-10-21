"""Head-to-head on real book sentences: how often does each narrator garble a line?

Records the same sentences with the app's Piper voices, transcribes every take (Piper and the F5 takes left by
qa/reliability_f5.py) and reports the average word error rate plus how many sentences came out badly wrong —
a swallowed or repeated line is what he calls an "erro grotesco".

    python qa/score_book.py %USERPROFILE%\\qa-models\\out\\sota
"""

import json
import sys
import wave
from collections import defaultdict
from pathlib import Path

import numpy as np
import torch
import torchaudio

sys.path.insert(0, str(Path(__file__).parent))
import listen_check as lc  # noqa: E402

MODELS = Path.home() / "qa-models"
BASE_SPEED = 0.70
PIPER = {"faber": ("vits-piper-pt_BR-faber-medium", 1.00), "dii": ("vits-piper-pt_BR-dii-high", 1.16)}
BAD = 0.20  # a sentence this wrong is audibly broken, not just a transcription quibble


def read_wav(path):
    with wave.open(str(path), "rb") as w:
        sr, n = w.getframerate(), w.getnchannels()
        pcm = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float32) / 32768.0
    return torch.from_numpy(pcm.reshape(-1, n).T.copy()), sr


def main():
    folder = Path(sys.argv[1])
    book = folder / "book"
    script = json.loads((book / "script.json").read_text(encoding="utf-8"))

    for voice, (folder_name, pace) in PIPER.items():
        tts = lc.piper_tts(MODELS / folder_name, 8)
        for i, line in enumerate(script):
            path = book / f"piper-{voice}-{i:02d}.wav"
            if path.exists():
                continue
            audio = lc.trim_silence(lc.synth(tts, line["tts"], 0, BASE_SPEED * pace, 0), tts.sample_rate)
            lc.save_wav(path, audio, tts.sample_rate)

    whisper = lc.WhisperModel("large-v3", device="cuda", compute_type="float16")
    utmos = torch.hub.load("tarepan/SpeechMOS:v1.2.0", "utmos22_strong", trust_repo=True).eval()
    groups = defaultdict(list)
    for wav in sorted(book.glob("*.wav")):
        name, idx = wav.stem.rsplit("-", 1)
        segments, _ = whisper.transcribe(str(wav), language="pt", beam_size=5,
                                         initial_prompt=lc.WORDS_ONLY_PROMPT)
        heard = " ".join(s.text.strip() for s in segments)
        wer = lc.process_words(lc.fold(script[int(idx)]["tts"]), lc.fold(heard)).wer
        audio, sr = read_wav(wav)
        a16 = torchaudio.functional.resample(audio.mean(0, keepdim=True), sr, 16000)
        groups[name].append({"i": int(idx), "wer": wer, "utmos": float(utmos(a16, 16000)), "heard": heard})

    summary = []
    for name, items in sorted(groups.items()):
        wers = [x["wer"] for x in items]
        broken = [x for x in items if x["wer"] > BAD]
        summary.append({"voice": name, "sentences": len(items), "wer": round(float(np.mean(wers)) * 100, 1),
                        "utmos": round(float(np.mean([x["utmos"] for x in items])), 2),
                        "broken": len(broken), "broken_idx": sorted(x["i"] for x in broken),
                        "worst": sorted(broken, key=lambda x: -x["wer"])[:3]})
        print(f"{name:<22} WER {summary[-1]['wer']:5.1f}%   UTMOS {summary[-1]['utmos']:.2f}   "
              f"broken {len(broken)}/{len(items)}", flush=True)

    (book / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
