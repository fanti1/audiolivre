"""Can the narrator sound *more* Brazilian? Two ways to try, measured against the same bar.

1. Blends of the male voices with the high-accent female ones (accent lives in the style vector, so a blend may
   inherit it while staying a man's voice — the median pitch says whether it did).
2. Brazilian respelling of the text sent to the voice only (the page keeps its own text): Brazilians turn "t"/"d"
   before an [i] sound into "tch"/"dj" — "noite" -> "noitchi", "cidade" -> "cidadji", "dizer" -> "djizer". A
   character-reading model takes those spellings literally, which is how pronunciation dictionaries work.

Each candidate is measured like qa/voice_select.py: accent (43 places over three passages), word errors against
the ORIGINAL sentences (so respelling that hurts intelligibility shows up), naturalness (UTMOS) and pitch.

    python qa/accent_boost.py --out <dir>
"""

import argparse
import json
import re
import sys
import time
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).parent))
import listen_check as lc  # noqa: E402
import sherpa_onnx  # noqa: E402
from pick_characters import median_f0, read16k  # noqa: E402
from voice_select import PASSAGES, PRESETS, make_tts, sentences  # noqa: E402

# Male voices, and blends of a male with a female voice that scored high on accent.
BLENDS = [("M5", "F5", 0.7), ("M5", "F5", 0.5), ("M5", "F3", 0.7), ("M3", "F5", 0.7), ("M5", "F4", 0.7), ("M3", "F3", 0.5)]
VOICES = ["M5", "M3", "M2", "M1"]

VOWEL_I = "iíìîï"


def respell(text: str) -> str:
    """Writes Brazilian affrication the way it is pronounced: t/d before an [i] sound."""
    def sub(pattern, repl, s):
        return re.sub(pattern, repl, s, flags=re.IGNORECASE)

    # "t"/"d" directly before an i: tio, partido, dizer, médico
    t = re.sub(r"([Tt])(?=[" + VOWEL_I + "])", lambda m: "Tch" if m.group(1).isupper() else "tch", text)
    t = re.sub(r"([Dd])(?=[" + VOWEL_I + "])", lambda m: "Dj" if m.group(1).isupper() else "dj", t)
    # final unstressed "-te"/"-de" (+ plural), pronounced [tʃi]/[dʒi]: noite, cidade, antes, verdades
    t = sub(r"te\b", "tchi", t)
    t = sub(r"tes\b", "tchis", t)
    t = sub(r"de\b", "dji", t)
    t = sub(r"des\b", "djis", t)
    return t


def blend_file(source: Path, target: Path):
    raw = source.read_bytes()
    n, s1, d1, _, s2, d2 = np.frombuffer(raw[:48], dtype=np.int64)
    floats = np.frombuffer(raw[48:], dtype=np.float32)
    ttl = floats[: n * s1 * d1].reshape(n, s1, d1)
    dp = floats[n * s1 * d1:].reshape(n, s2, d2)
    names, new_ttl, new_dp = [], [], []
    for name in VOICES:
        i = PRESETS.index(name)
        names.append(name)
        new_ttl.append(ttl[i])
        new_dp.append(dp[i])

    def mix(a, b, w):
        m = w * a + (1 - w) * b
        return (m / np.linalg.norm(m, axis=-1, keepdims=True)).astype(np.float32)

    for a, b, w in BLENDS:
        ia, ib = PRESETS.index(a), PRESETS.index(b)
        names.append(f"{a}{int(w * 100)}+{b}")
        new_ttl.append(mix(ttl[ia], ttl[ib], w))
        new_dp.append(mix(dp[ia], dp[ib], w))
    count = len(names)
    head = np.array([count, s1, d1, count, s2, d2], dtype=np.int64).tobytes()
    target.write_bytes(head + np.stack(new_ttl).tobytes() + np.stack(new_dp).tobytes())
    return names


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    args = ap.parse_args()
    out = Path(args.out)
    wavs = out / "wav"
    wavs.mkdir(parents=True, exist_ok=True)

    voice_file = out / "voices.bin"
    names = blend_file(lc.MODEL_DIR / "voice.bin", voice_file)
    tts = make_tts(voice_file)
    sr = tts.sample_rate
    texts = sentences()
    variants = [("plain", lambda s: s), ("respelled", respell)]

    t0 = time.perf_counter()
    for sid, name in enumerate(names):
        for tag, transform in variants:
            for k, text in enumerate(texts):
                f = wavs / f"{name}_{tag}_{k:02d}.wav"
                if not f.exists():
                    lc.save_wav(f, lc.render(tts, transform(text), sid, 0.85, 5, 180, sr), sr)
            for p, (text, _) in enumerate(PASSAGES):
                f = wavs / f"{name}_{tag}_p{p}.wav"
                if not f.exists():
                    lc.save_wav(f, lc.render(tts, transform(text), sid, 0.85, 5, 180, sr), sr)
    print(f"synthesis done in {time.perf_counter() - t0:.0f}s", flush=True)
    del tts

    from faster_whisper import WhisperModel
    from jiwer import process_words
    asr = WhisperModel("large-v3", device="cuda", compute_type="float16")
    results = []
    for name in names:
        for tag, _ in variants:
            errors = words = 0
            for k, text in enumerate(texts):
                segs, _ = asr.transcribe(str(wavs / f"{name}_{tag}_{k:02d}.wav"), language="pt", beam_size=5,
                                         condition_on_previous_text=False, temperature=0.0, initial_prompt=lc.WORDS_ONLY_PROMPT)
                # The reference is always the page's own sentence, never the respelling.
                ref, hyp = lc.fold(text), lc.fold(" ".join(s.text.strip() for s in segs))
                m = process_words(ref or "vazio", hyp or "vazio")
                errors += m.substitutions + m.deletions + m.insertions
                words += len(ref.split())
            results.append({"voice": name, "variant": tag, "wer": round(errors / max(words, 1), 4)})
    print(f"whisper done in {time.perf_counter() - t0:.0f}s", flush=True)
    del asr

    import torch
    from huggingface_hub import hf_hub_download
    from transformers import Wav2Vec2FeatureExtractor, Wav2Vec2ForCTC
    repo = "facebook/wav2vec2-xlsr-53-espeak-cv-ft"
    fx = Wav2Vec2FeatureExtractor.from_pretrained(repo)
    phon = Wav2Vec2ForCTC.from_pretrained(repo).eval()
    vocab = json.loads(Path(hf_hub_download(repo, "vocab.json")).read_text(encoding="utf-8"))
    id_to_phone = {i: p for p, i in vocab.items()}
    utmos = torch.hub.load("tarepan/SpeechMOS:v1.2.0", "utmos22_strong", trust_repo=True).eval()
    torch.set_num_threads(16)

    def phones_of(a16):
        with torch.no_grad():
            ids = phon(fx(a16, sampling_rate=16000, return_tensors="pt").input_values).logits.argmax(-1)[0].tolist()
        kept, prev = [], None
        for i in ids:
            if i != prev and id_to_phone.get(i) not in ("<pad>", "<s>", "</s>", "<unk>", None):
                kept.append(id_to_phone[i])
            prev = i
        return " ".join(kept)

    slots = sum(n for _, n in PASSAGES)
    for r in results:
        affricates = 0
        for p in range(len(PASSAGES)):
            heard = phones_of(read16k(wavs / f"{r['voice']}_{r['variant']}_p{p}.wav"))
            affricates += heard.count("tʃ") + heard.count("dʒ")
        mos, f0 = [], []
        for k in range(0, len(texts), 4):
            a16 = read16k(wavs / f"{r['voice']}_{r['variant']}_{k:02d}.wav")
            with torch.no_grad():
                mos.append(float(utmos(torch.from_numpy(a16).unsqueeze(0), 16000)))
            f0.append(median_f0(a16))
        r.update({"accent": round(affricates / slots, 3), "utmos": round(float(np.mean(mos)), 3),
                  "f0": round(float(np.median(f0)), 1)})
        print(f"{r['voice']:10s} {r['variant']:10s} accent {r['accent']:.2f}  wer {r['wer']:.3f}  "
              f"utmos {r['utmos']:.2f}  f0 {r['f0']:.0f} Hz", flush=True)
    (out / "boost.json").write_text(json.dumps(results, ensure_ascii=False, indent=1), encoding="utf-8")


if __name__ == "__main__":
    main()
