"""Second-stage rule for the character voices, run on voice_select.py's output.

voice_select's rule found both narrators but no character voice passing every cut-off (the clarity limit, 1.25x
the best voice, was too tight for a second voice). Declared before computing it, the character rule is:
  eligible   accent >= 0.5 and wer <= 2x the best wer among that gender's voices, and heard as a different person
             from that gender's narrator (WavLM speaker similarity below 0.86)
  character  the eligible voice with the highest utmos
It also reports each voice's median pitch, since characters should not sound like the narrator a tone apart.

Voices of one synthesizer sit closer together in WavLM space than real people do (F3 vs F5: 0.987, F1 vs F5:
0.898), so the 0.86 verification threshold, made for real speakers, left no female character. "A different person"
therefore also accepts similarity below 0.95 when the median pitch is at least 2 semitones from the narrator's.

    python qa/pick_characters.py <voice_select out dir>
"""

import json
import sys
import wave
from pathlib import Path

import numpy as np

SPEAKER_THRESHOLD = 0.86
CLIPS = range(1, 45, 5)


def read16k(path):
    with wave.open(str(path)) as w:
        a = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float32) / 32768
        rate = w.getframerate()
    n = int(len(a) * 16000 / rate)
    return np.interp(np.linspace(0, len(a) - 1, n), np.arange(len(a)), a).astype(np.float32)


def median_f0(a, sr=16000):
    frame, hop, lo, hi = 1024, 256, sr // 400, sr // 60
    f0s = []
    for start in range(0, len(a) - frame, hop):
        x = a[start:start + frame]
        if np.sqrt(np.mean(x * x)) < 0.02:
            continue
        x = x - x.mean()
        ac = np.correlate(x, x, mode="full")[frame - 1:]
        if ac[0] <= 0:
            continue
        ac = ac / ac[0]
        lag = lo + int(np.argmax(ac[lo:hi]))
        if ac[lag] > 0.35:
            f0s.append(sr / lag)
    return float(np.median(f0s)) if f0s else 0.0


def main():
    import torch
    from transformers import AutoFeatureExtractor, WavLMForXVector
    out = Path(sys.argv[1])
    summary = json.loads((out / "selection.json").read_text(encoding="utf-8"))
    voices = summary["voices"]
    fx = AutoFeatureExtractor.from_pretrained("microsoft/wavlm-base-plus-sv")
    sv = WavLMForXVector.from_pretrained("microsoft/wavlm-base-plus-sv").eval()
    torch.set_num_threads(16)
    for v in voices:
        embs, f0 = [], []
        for k in CLIPS:
            a = read16k(out / "wav" / f"{v['voice']}_{k:02d}.wav")
            with torch.no_grad():
                e = sv(**fx(a, sampling_rate=16000, return_tensors="pt")).embeddings[0].numpy()
            embs.append(e / np.linalg.norm(e))
            f0.append(median_f0(a))
        m = np.mean(embs, axis=0)
        v["speaker"] = m / np.linalg.norm(m)
        v["f0"] = round(float(np.median(f0)), 1)

    by_name = {v["voice"]: v for v in voices}
    picks = summary["picks"]
    for gender in ("M", "F"):
        narrator = by_name[picks[gender]["narrator"]]
        pool = [v for v in voices if v["gender"] == gender]
        best = min(v["wer"] for v in pool if v["accent"] >= 0.5)
        print(f"\n{gender} narrator {narrator['voice']} (f0 {narrator['f0']} Hz)")
        eligible = []
        for v in sorted(pool, key=lambda v: -v["utmos"]):
            sim = float(np.dot(v["speaker"], narrator["speaker"]))
            semitones = abs(12 * np.log2(v["f0"] / narrator["f0"])) if v["f0"] and narrator["f0"] else 0.0
            distinct = sim < SPEAKER_THRESHOLD or (sim < 0.95 and semitones >= 2)
            ok = v is not narrator and v["accent"] >= 0.5 and v["wer"] <= 2 * best and distinct
            print(f"  {v['voice']:10s} accent {v['accent']:.2f} wer {v['wer']:.3f} utmos {v['utmos']:.2f} "
                  f"f0 {v['f0']:5.1f} similarity {sim:.3f} {'ELIGIBLE' if ok else ''}")
            if ok:
                eligible.append((v, sim))
        if eligible:
            character, sim = max(eligible, key=lambda e: e[0]["utmos"])
            picks[gender].update({"character": character["voice"], "character_sid": character["sid"],
                                  "similarity": round(sim, 3)})
    summary["picks"] = picks
    summary["character_rule"] = ("accent>=0.5, wer<=2x best of gender, different person (WavLM similarity<0.86, or <0.95 "
                                 "with pitch >=2 semitones from the narrator), max utmos")
    summary["voices"] = [{k: val for k, val in v.items() if k != "speaker"} for v in voices]
    (out / "selection.json").write_text(json.dumps(summary, ensure_ascii=False, indent=1), encoding="utf-8")
    print(json.dumps(picks, ensure_ascii=False, indent=1))


if __name__ == "__main__":
    main()
