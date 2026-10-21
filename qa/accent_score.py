"""Calibrates the accent measure against voices whose origin is known, then scores the candidates on it.

The single marker used so far (Brazilian [tʃ]/[dʒ] in "noite", "cidade") reported 0 for Piper's Brazilian voice,
so it can't be trusted alone. Research on Brazilian vs European Portuguese (arXiv 2605.30457) uses three markers:
palatalization of t/d, the coda /s/ ([s] in São Paulo, [ʃ] in Portugal and Rio) and the coda /r/, plus Portugal's
reduced vowel [ɨ]. This script prints the full phoneme profile of each voice over passages written to expose all
of them, with Piper pt_BR and Piper pt_PT as the two ends of the scale.

    python qa/accent_score.py --out <dir>
"""

import argparse
import json
import sys
import tarfile
import urllib.request
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).parent))
import listen_check as lc  # noqa: E402
import sherpa_onnx  # noqa: E402
from accent_boost import respell  # noqa: E402
from pick_characters import read16k  # noqa: E402
from voice_select import PRESETS  # noqa: E402

MODELS = lc.MODEL_DIR.parent
RELEASE = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/"

PASSAGES = {
    # t/d before [i]: the Brazilian affricates
    "palatal": "Naquela noite, a cidade parecia diferente. O tio de Diego tinha partido de tarde, sem dizer nada. "
               "Ele pediu leite quente e disse que a verdade sempre aparece de repente.",
    # coda /s/, with no "ch" or "x" anywhere to add [ʃ] of its own
    "coda_s": "As casas das meninas estavam prontas, e os livros dos meus amigos ficaram nas mesas da sala. "
              "Depois disso, todos os alunos dos cursos saíram juntos pelas portas.",
    # coda /r/ and final infinitives
    "coda_r": "O ator perdeu o amor pelo mar, mas voltou para cantar no bar durante a tarde. "
              "Quer dormir, quer falar, quer partir: o corpo pede verde, porta, carta e sorte.",
    # unstressed final vowels, where European Portuguese reduces to [ɨ] or drops them
    "vowels": "Ele come pouco de tarde e dorme cedo, porque de noite estuda longe de casa. "
              "A gente sente o peso dessas viagens quase sempre depois que a noite cai.",
}


def fetch(name):
    target = MODELS / name
    if not target.exists():
        archive = MODELS / f"{name}.tar.bz2"
        urllib.request.urlretrieve(RELEASE + archive.name, archive)
        with tarfile.open(archive) as tar:
            tar.extractall(MODELS)
        archive.unlink()
    return target


def piper(dirname):
    d = fetch(dirname)
    vits = sherpa_onnx.OfflineTtsVitsModelConfig(model=str(next(d.glob("*.onnx"))), tokens=str(d / "tokens.txt"),
                                                 data_dir=str(d / "espeak-ng-data"))
    cfg = sherpa_onnx.OfflineTtsConfig(model=sherpa_onnx.OfflineTtsModelConfig(vits=vits, num_threads=8), silence_scale=1.0)
    return sherpa_onnx.OfflineTts(cfg)


def supertonic(voice_file):
    d = lc.MODEL_DIR
    st = sherpa_onnx.OfflineTtsSupertonicModelConfig(
        duration_predictor=str(d / "duration_predictor.int8.onnx"), text_encoder=str(d / "text_encoder.int8.onnx"),
        vector_estimator=str(d / "vector_estimator.int8.onnx"), vocoder=str(d / "vocoder.int8.onnx"),
        tts_json=str(d / "tts.json"), unicode_indexer=str(d / "unicode_indexer.bin"), voice_style=str(voice_file))
    cfg = sherpa_onnx.OfflineTtsConfig(model=sherpa_onnx.OfflineTtsModelConfig(supertonic=st, num_threads=8), silence_scale=1.0)
    return sherpa_onnx.OfflineTts(cfg)


def blend(target: Path):
    """A file with three male-female blends: more of the Brazilian-sounding female style, still a man's voice."""
    raw = (lc.MODEL_DIR / "voice.bin").read_bytes()
    n, s1, d1, _, s2, d2 = np.frombuffer(raw[:48], dtype=np.int64)
    floats = np.frombuffer(raw[48:], dtype=np.float32)
    ttl = floats[: n * s1 * d1].reshape(n, s1, d1)
    dp = floats[n * s1 * d1:].reshape(n, s2, d2)

    def mix(a, b, w):
        m = w * a + (1 - w) * b
        return (m / np.linalg.norm(m, axis=-1, keepdims=True)).astype(np.float32)

    recipes = [("M5", "F5", 0.7), ("M5", "F5", 0.55), ("M3", "F5", 0.7)]
    new_ttl = [mix(ttl[PRESETS.index(a)], ttl[PRESETS.index(b)], w) for a, b, w in recipes]
    new_dp = [mix(dp[PRESETS.index(a)], dp[PRESETS.index(b)], w) for a, b, w in recipes]
    head = np.array([len(recipes), s1, d1, len(recipes), s2, d2], dtype=np.int64).tobytes()
    target.write_bytes(head + np.stack(new_ttl).tobytes() + np.stack(new_dp).tobytes())
    return target


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    args = ap.parse_args()
    out = Path(args.out)
    (out / "wav").mkdir(parents=True, exist_ok=True)

    pt_br = piper("vits-piper-pt_BR-cadu-medium")
    pt_pt = piper("vits-piper-pt_PT-tugao-medium")
    app_cast = supertonic(lc.APP_CAST)
    presets = supertonic(lc.MODEL_DIR / "voice.bin")
    blended = supertonic(blend(Path(args.out) / "blends.bin"))
    k = fetch("kokoro-int8-multi-lang-v1_0")
    kokoro_cfg = sherpa_onnx.OfflineTtsKokoroModelConfig(model=str(k / "model.int8.onnx"), voices=str(k / "voices.bin"),
                                                        tokens=str(k / "tokens.txt"), data_dir=str(k / "espeak-ng-data"), lang="pt-br")
    kokoro = sherpa_onnx.OfflineTts(sherpa_onnx.OfflineTtsConfig(
        model=sherpa_onnx.OfflineTtsModelConfig(kokoro=kokoro_cfg, num_threads=8), silence_scale=1.0))

    # (label, tts, sid, respell?) — the two Piper voices anchor the scale.
    voices = [
        ("Piper pt_BR cadu (referência BR)", pt_br, 0, False),
        ("Piper pt_PT tugão (referência PT)", pt_pt, 0, False),
        ("Supertonic M1 (narrador antigo)", presets, PRESETS.index("M1"), False),
        ("Supertonic M5 (narrador novo)", app_cast, 0, False),
        ("Supertonic M5 + reescrita BR", app_cast, 0, True),
        ("Supertonic F5 (narradora nova)", app_cast, 1, False),
        ("Supertonic F5 + reescrita BR", app_cast, 1, True),
        ("Supertonic M3", presets, PRESETS.index("M3"), False),
        ("Supertonic M2 + reescrita BR", presets, PRESETS.index("M2"), True),
        ("Mistura M5 70% + F5", blended, 0, False),
        ("Mistura M5 55% + F5", blended, 1, False),
        ("Mistura M3 70% + F5", blended, 2, False),
        ("Kokoro santa", kokoro, 44, False),
    ]

    from huggingface_hub import hf_hub_download
    from transformers import Wav2Vec2FeatureExtractor, Wav2Vec2ForCTC
    import torch
    repo = "facebook/wav2vec2-xlsr-53-espeak-cv-ft"
    fx = Wav2Vec2FeatureExtractor.from_pretrained(repo)
    phon = Wav2Vec2ForCTC.from_pretrained(repo).eval()
    vocab = json.loads(Path(hf_hub_download(repo, "vocab.json")).read_text(encoding="utf-8"))
    id_to_phone = {i: p for p, i in vocab.items()}
    torch.set_num_threads(16)

    def phones_of(a16):
        with torch.no_grad():
            ids = phon(fx(a16, sampling_rate=16000, return_tensors="pt").input_values).logits.argmax(-1)[0].tolist()
        kept, prev = [], None
        for i in ids:
            if i != prev and id_to_phone.get(i) not in ("<pad>", "<s>", "</s>", "<unk>", None):
                kept.append(id_to_phone[i])
            prev = i
        return kept

    markers = ["tʃ", "dʒ", "ʃ", "ɨ", "ʁ", "x", "h", "ɾ", "r", "i", "s"]
    results = []
    for label, tts, sid, use_respell in voices:
        counts = dict.fromkeys(markers, 0)
        total = 0
        slug = label.split(" (")[0].lower().replace(" ", "_").replace("+", "e")
        for key, text in PASSAGES.items():
            path = out / "wav" / f"{slug}_{key}.wav"
            if not path.exists():
                speed = 0.85 if tts is not pt_br and tts is not pt_pt else 1.0
                lc.save_wav(path, lc.render(tts, respell(text) if use_respell else text, sid, speed, 5, 180, tts.sample_rate),
                            tts.sample_rate)
            phones = phones_of(read16k(path))
            total += len(phones)
            for p in phones:
                if p in counts:
                    counts[p] += 1
        rate = {m: round(100 * c / max(total, 1), 2) for m, c in counts.items()}
        results.append({"voice": label, "counts": counts, "per100": rate, "phones": total})
        print(f"{label:38s} " + "  ".join(f"{m} {counts[m]:3d}" for m in ["tʃ", "dʒ", "ʃ", "ɨ", "ʁ", "x", "h"]), flush=True)
    (out / "profiles.json").write_text(json.dumps(results, ensure_ascii=False, indent=1), encoding="utf-8")


if __name__ == "__main__":
    main()
