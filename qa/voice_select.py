"""Picks the app's cast (narrators and character voices) by measurement instead of taste.

Candidates: Supertonic 3's 10 preset voices plus blends of them (a voice style is 50 unit vectors for the text-to-
latent model and 8 for the duration model, so a blend is a per-row interpolation, renormalized). For each voice:

  accent       share of 43 places where Brazilian speech has [tʃ]/[dʒ] ("noite", "cidade", "tio") that a phoneme
               recognizer (wav2vec2 XLSR-53 eSpeak) hears as such, over three passages;
  wer          Whisper large-v3 word error rate on 45 sentences (book narration, dialogue, modern hard cases);
  utmos        predicted naturalness (UTMOS22 strong, the MOS predictor used across TTS papers; 1-5);
  speaker      WavLM speaker embedding, to tell whether two voices sound like different people.

Decision rule, fixed before looking at the results:
  eligible    accent >= 0.6 and wer <= 1.25 x the best wer among voices of that gender with accent >= 0.6
  narrator    per gender, the eligible voice with the highest utmos
  character   per gender, the eligible voice with the highest utmos whose speaker similarity to that gender's
              narrator is below the WavLM verification threshold (0.86), i.e. heard as another person

    python qa/voice_select.py --out <dir>
"""

import argparse
import json
import random
import sys
import time
import wave
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).parent))
import listen_check as lc  # noqa: E402  (also puts the CUDA DLLs on the path)
import sherpa_onnx  # noqa: E402

PRESETS = ["F1", "F2", "F3", "F4", "F5", "M1", "M2", "M3", "M4", "M5"]
BLENDS = [("M3", other, w) for other in ["M5", "M4", "M2", "M1"] for w in (0.7, 0.5)]
SPEAKER_THRESHOLD = 0.86

PASSAGES = [
    ("Naquela noite, a cidade parecia diferente. O tio de Diego tinha partido de tarde, sem dizer nada a ninguém. "
     "Ele pediu um pouco de leite quente e sentou perto da porta, ouvindo o vento. "
     "Dizem que a verdade sempre aparece, mas às vezes demora demais.", 19),
    ("De repente, o telefone tocou de novo. Era o delegado, dizendo que o médico tinha desaparecido durante a noite. "
     "Ninguém sabia o destino dele, e a tia de Tiago estava muito assustada.", 11),
    ("Tudo isso aconteceu na cidade de Belo Horizonte, numa tarde quente de outubro. O artista pediu desculpas, "
     "sentiu vontade de chorar e partiu antes do fim da festa.", 13),
]


def blend_voice_file(source: Path, target: Path):
    raw = source.read_bytes()
    header = np.frombuffer(raw[:48], dtype=np.int64)
    n, s1, d1, _, s2, d2 = header
    floats = np.frombuffer(raw[48:], dtype=np.float32)
    ttl = floats[: n * s1 * d1].reshape(n, s1, d1)
    dp = floats[n * s1 * d1:].reshape(n, s2, d2)
    names = list(PRESETS)
    new_ttl, new_dp = [ttl[i] for i in range(n)], [dp[i] for i in range(n)]

    def mix(a, b, w):
        m = w * a + (1 - w) * b
        return (m / np.linalg.norm(m, axis=-1, keepdims=True)).astype(np.float32)

    for a, b, w in BLENDS:
        ia, ib = PRESETS.index(a), PRESETS.index(b)
        new_ttl.append(mix(ttl[ia], ttl[ib], w))
        new_dp.append(mix(dp[ia], dp[ib], w))
        names.append(f"{a}{int(w * 100)}+{b}")
    count = len(names)
    head = np.array([count, s1, d1, count, s2, d2], dtype=np.int64).tobytes()
    target.write_bytes(head + np.stack(new_ttl).tobytes() + np.stack(new_dp).tobytes())
    return names


def make_tts(voice_file: Path):
    d = lc.MODEL_DIR
    st = sherpa_onnx.OfflineTtsSupertonicModelConfig(
        duration_predictor=str(d / "duration_predictor.int8.onnx"), text_encoder=str(d / "text_encoder.int8.onnx"),
        vector_estimator=str(d / "vector_estimator.int8.onnx"), vocoder=str(d / "vocoder.int8.onnx"),
        tts_json=str(d / "tts.json"), unicode_indexer=str(d / "unicode_indexer.bin"), voice_style=str(voice_file))
    cfg = sherpa_onnx.OfflineTtsConfig(model=sherpa_onnx.OfflineTtsModelConfig(supertonic=st, num_threads=8), silence_scale=1.0)
    return sherpa_onnx.OfflineTts(cfg)


def sentences():
    random.seed(11)
    stress = [json.loads(l) for l in open("app/build/qa/stress_normalized.jsonl", encoding="utf-8")]
    # Web addresses and acronyms come back from Whisper as symbols ("CPF"), which would read as errors.
    stress = [s["tts"] for s in stress if not any(k in s["display"] for k in ["@", "www", "CPF", "ONU", "IBGE", "UFRJ", "TV", "MASP"])]
    book = [json.loads(l) for l in open(str(Path.home() / "qa-models" / "book_sentences_v1.jsonl"), encoding="utf-8")]
    narration = [b["tts"] for b in book if b["role"] == "NARRATOR" and 60 <= len(b["tts"]) <= 220]
    speech = [b["tts"] for b in book if b["display"].lstrip().startswith(("—", "-")) and 25 <= len(b["tts"]) <= 160]
    return random.sample(stress, 15) + random.sample(narration, 20) + random.sample(speech, 10)


def load_models():
    import torch
    from huggingface_hub import hf_hub_download
    from transformers import AutoFeatureExtractor, Wav2Vec2FeatureExtractor, Wav2Vec2ForCTC, WavLMForXVector
    repo = "facebook/wav2vec2-xlsr-53-espeak-cv-ft"
    phon_fx = Wav2Vec2FeatureExtractor.from_pretrained(repo)
    phon = Wav2Vec2ForCTC.from_pretrained(repo).eval()
    vocab = json.loads(Path(hf_hub_download(repo, "vocab.json")).read_text(encoding="utf-8"))
    id_to_phone = {i: p for p, i in vocab.items()}
    sv_fx = AutoFeatureExtractor.from_pretrained("microsoft/wavlm-base-plus-sv")
    sv = WavLMForXVector.from_pretrained("microsoft/wavlm-base-plus-sv").eval()
    utmos = torch.hub.load("tarepan/SpeechMOS:v1.2.0", "utmos22_strong", trust_repo=True).eval()
    return torch, phon_fx, phon, id_to_phone, sv_fx, sv, utmos


def read_wav(path):
    with wave.open(str(path)) as w:
        return np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float32) / 32768, w.getframerate()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    args = ap.parse_args()
    out = Path(args.out)
    wavs = out / "wav"
    wavs.mkdir(parents=True, exist_ok=True)

    # Stage 1: synthesize everything (sherpa-onnx on the CPU, nothing else competing for it).
    voice_file = out / "voice_candidates.bin"
    names = blend_voice_file(lc.MODEL_DIR / "voice.bin", voice_file)
    tts = make_tts(voice_file)
    sr = tts.sample_rate
    texts = sentences()
    t0 = time.perf_counter()
    for sid, name in enumerate(names):
        jobs = [(f"{name}_{k:02d}.wav", t) for k, t in enumerate(texts)] + [(f"{name}_p{p}.wav", t) for p, (t, _) in enumerate(PASSAGES)]
        for file, text in jobs:
            if not (wavs / file).exists():
                lc.save_wav(wavs / file, lc.render(tts, text, sid, 0.85, 5, 180, sr), sr)
    print(f"synthesis done in {time.perf_counter() - t0:.0f}s", flush=True)
    del tts

    # Stage 2: intelligibility with Whisper on the GPU.
    from faster_whisper import WhisperModel
    from jiwer import process_words
    asr = WhisperModel("large-v3", device="cuda", compute_type="float16")
    results = []
    for sid, name in enumerate(names):
        errors = words = 0
        for k, text in enumerate(texts):
            segs, _ = asr.transcribe(str(wavs / f"{name}_{k:02d}.wav"), language="pt", beam_size=5,
                                     condition_on_previous_text=False, temperature=0.0, initial_prompt=lc.WORDS_ONLY_PROMPT)
            ref, hyp = lc.fold(text), lc.fold(" ".join(seg.text.strip() for seg in segs))
            m = process_words(ref or "vazio", hyp or "vazio")
            errors += m.substitutions + m.deletions + m.insertions
            words += len(ref.split())
        results.append({"voice": name, "sid": sid, "gender": "M" if name.startswith("M") else "F",
                        "wer": round(errors / max(words, 1), 4)})
    print(f"whisper done in {time.perf_counter() - t0:.0f}s", flush=True)
    del asr

    # Stage 3: accent, naturalness and speaker identity with the CPU models, on a spread of the clips.
    torch, phon_fx, phon, id_to_phone, sv_fx, sv, utmos = load_models()
    torch.set_num_threads(16)

    def to16k(a, rate):
        n = int(len(a) * 16000 / rate)
        return np.interp(np.linspace(0, len(a) - 1, n), np.arange(len(a)), a).astype(np.float32)

    def phones_of(a16):
        with torch.no_grad():
            ids = phon(phon_fx(a16, sampling_rate=16000, return_tensors="pt").input_values).logits.argmax(-1)[0].tolist()
        out_, prev = [], None
        for i in ids:
            if i != prev and id_to_phone.get(i) not in ("<pad>", "<s>", "</s>", "<unk>", None):
                out_.append(id_to_phone[i])
            prev = i
        return " ".join(out_)

    mos_clips = list(range(0, len(texts), 3))       # 15 clips across stress, narration and dialogue
    speaker_clips = list(range(1, len(texts), 5))   # 9 clips
    slots = sum(n for _, n in PASSAGES)
    for r in results:
        name = r["voice"]
        affricates = 0
        for p in range(len(PASSAGES)):
            heard = phones_of(to16k(*read_wav(wavs / f"{name}_p{p}.wav")))
            affricates += heard.count("tʃ") + heard.count("dʒ")
        mos, embeddings = [], []
        with torch.no_grad():
            for k in mos_clips:
                a16 = torch.from_numpy(to16k(*read_wav(wavs / f"{name}_{k:02d}.wav")))
                mos.append(float(utmos(a16.unsqueeze(0), 16000)))
            for k in speaker_clips:
                a16 = to16k(*read_wav(wavs / f"{name}_{k:02d}.wav"))
                emb = sv(**sv_fx(a16, sampling_rate=16000, return_tensors="pt")).embeddings[0].numpy()
                embeddings.append(emb / np.linalg.norm(emb))
        speaker = np.mean(embeddings, axis=0)
        r.update({"accent": round(affricates / slots, 3), "utmos": round(float(np.mean(mos)), 3),
                  "speaker": (speaker / np.linalg.norm(speaker)).tolist()})
        print(f"{name:10s} accent {r['accent']:.2f}  wer {r['wer']:.3f}  utmos {r['utmos']:.2f}", flush=True)

    def similarity(a, b):
        return float(np.dot(a["speaker"], b["speaker"]))

    picks = {}
    for gender in ("M", "F"):
        pool = [r for r in results if r["gender"] == gender and r["accent"] >= 0.6]
        if not pool:
            continue
        best_wer = min(r["wer"] for r in pool)
        eligible = [r for r in pool if r["wer"] <= 1.25 * best_wer]
        narrator = max(eligible, key=lambda r: r["utmos"])
        others = [r for r in eligible if r is not narrator and similarity(r, narrator) < SPEAKER_THRESHOLD]
        character = max(others, key=lambda r: r["utmos"]) if others else None
        picks[gender] = {"narrator": narrator["voice"], "narrator_sid": narrator["sid"],
                         "character": character and character["voice"], "character_sid": character and character["sid"],
                         "similarity": character and round(similarity(character, narrator), 3)}
    summary = {"voices": [{k: v for k, v in r.items() if k != "speaker"} for r in results], "picks": picks,
               "names": names, "sentences": len(texts)}
    (out / "selection.json").write_text(json.dumps(summary, ensure_ascii=False, indent=1), encoding="utf-8")
    print(json.dumps(picks, ensure_ascii=False, indent=1))


if __name__ == "__main__":
    main()
