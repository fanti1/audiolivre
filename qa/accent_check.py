"""Is this voice Brazilian? An objective accent check for pt-BR TTS candidates.

Brazilian Portuguese turns "t"/"d" before an "i" sound into affricates ("noite" = noi-tchi, "cidade" = cida-dji,
"tio" = tchio) and keeps final unstressed "e" as [i]; European Portuguese keeps [t]/[d] and reduces that vowel to
[ɨ] or drops it ("noit'"). A multilingual phoneme recognizer (wav2vec2 XLSR-53 fine-tuned on eSpeak IPA) writes
out what it hears, and we count those sounds in a passage full of such words.

    python qa/accent_check.py --out <dir>
"""

import argparse
import json
import os
import tarfile
import time
import urllib.request
import wave
from pathlib import Path

import numpy as np

TEXT = ("Naquela noite, a cidade parecia diferente. O tio de Diego tinha partido de tarde, sem dizer nada a ninguém. "
        "Ele pediu um pouco de leite quente e sentou perto da porta, ouvindo o vento. "
        "Dizem que a verdade sempre aparece, mas às vezes demora demais.")
# Places where Brazilian speech has [tʃ]/[dʒ]: noite, cidade, diferente (2), tio, de (x3), Diego, partido, tarde,
# dizer, pediu, leite, quente, ouvindo, dizem, verdade, demais.
AFFRICATE_SLOTS = 19

MODELS = Path(os.environ.get("QA_MODELS", str(Path.home() / "qa-models")))
SHERPA_RELEASE = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/"


def fetch(name):
    target = MODELS / name
    if not target.exists():
        archive = MODELS / f"{name}.tar.bz2"
        urllib.request.urlretrieve(SHERPA_RELEASE + archive.name, archive)
        with tarfile.open(archive) as tar:
            tar.extractall(MODELS)
        archive.unlink()
    return target


def sherpa_voices():
    import sherpa_onnx
    voices = []

    def engine(model_config):
        cfg = sherpa_onnx.OfflineTtsConfig(model=model_config, silence_scale=1.0)
        return sherpa_onnx.OfflineTts(cfg)

    def gen(tts, sid, speed, extra=None, steps=None):
        g = sherpa_onnx.GenerationConfig()
        g.sid, g.speed, g.silence_scale = sid, speed, 1.0
        if steps:
            g.num_steps = steps
        if extra:
            g.extra = extra
        audio = tts.generate(TEXT, g)
        return np.asarray(audio.samples, dtype=np.float32), audio.sample_rate

    d = MODELS / "sherpa-onnx-supertonic-3-tts-int8-2026-05-11"
    st = engine(sherpa_onnx.OfflineTtsModelConfig(supertonic=sherpa_onnx.OfflineTtsSupertonicModelConfig(
        duration_predictor=str(d / "duration_predictor.int8.onnx"), text_encoder=str(d / "text_encoder.int8.onnx"),
        vector_estimator=str(d / "vector_estimator.int8.onnx"), vocoder=str(d / "vocoder.int8.onnx"),
        tts_json=str(d / "tts.json"), unicode_indexer=str(d / "unicode_indexer.bin"), voice_style=str(d / "voice.bin")),
        num_threads=8))
    for sid, name in enumerate(["F1", "F2", "F3", "F4", "F5", "M1", "M2", "M3", "M4", "M5"]):
        voices.append((f"Supertonic {name}", lambda s=sid: gen(st, s, 0.85, {"lang": "pt"}, 5)))

    k = fetch("kokoro-int8-multi-lang-v1_0")
    kokoro = engine(sherpa_onnx.OfflineTtsModelConfig(kokoro=sherpa_onnx.OfflineTtsKokoroModelConfig(
        model=str(k / "model.int8.onnx"), voices=str(k / "voices.bin"), tokens=str(k / "tokens.txt"),
        data_dir=str(k / "espeak-ng-data"), lang="pt-br"), num_threads=8))
    for sid, name in [(42, "dora"), (43, "alex"), (44, "santa")]:
        voices.append((f"Kokoro {name}", lambda s=sid: gen(kokoro, s, 1.0)))

    p = fetch("vits-piper-pt_BR-cadu-medium")
    piper = engine(sherpa_onnx.OfflineTtsModelConfig(vits=sherpa_onnx.OfflineTtsVitsModelConfig(
        model=str(next(p.glob("*.onnx"))), tokens=str(p / "tokens.txt"), data_dir=str(p / "espeak-ng-data")), num_threads=8))
    voices.append(("Piper cadu", lambda: gen(piper, 0, 1.0)))
    return voices


def pocket_voices(out):
    """Pocket TTS clones the voice (and, we hope, the accent) of its audio prompt. Besides its own Portuguese
    voice it is prompted here with synthetic Brazilian voices made above, so no real person is cloned."""
    from pocket_tts import TTSModel
    voices = []
    prompts = [("rafael", "rafael"), ("kokoro dora", str(out / "kokoro_dora.wav")), ("supertonic M3", str(out / "supertonic_m3.wav"))]
    for language in ["portuguese", "portuguese_24l"]:
        model = TTSModel.load_model(language=language)
        for label, prompt in prompts:
            try:
                state = model.get_state_for_audio_prompt(prompt)
            except Exception as e:  # voice cloning weights may be gated on Hugging Face
                print(f"Pocket TTS {language} {label}: skipped ({type(e).__name__}: {str(e)[:120]})")
                continue

            def gen(m=model, s=state):
                audio = m.generate_audio(s, TEXT)
                return audio.numpy().astype(np.float32), m.sample_rate
            voices.append((f"Pocket TTS {language} {label}", gen))
    return voices


def to_16k(audio, sr):
    if sr == 16000:
        return audio
    n = int(len(audio) * 16000 / sr)
    return np.interp(np.linspace(0, len(audio) - 1, n), np.arange(len(audio)), audio).astype(np.float32)


def save_wav(path, audio, sr):
    pcm = (np.clip(audio, -1, 1) * 32767).astype(np.int16)
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(sr)
        w.writeframes(pcm.tobytes())


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    args = ap.parse_args()
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)

    import torch
    from huggingface_hub import hf_hub_download
    from transformers import Wav2Vec2FeatureExtractor, Wav2Vec2ForCTC
    repo = "facebook/wav2vec2-xlsr-53-espeak-cv-ft"
    features = Wav2Vec2FeatureExtractor.from_pretrained(repo)
    recognizer = Wav2Vec2ForCTC.from_pretrained(repo).eval()
    # The repo's tokenizer needs espeak/phonemizer only to encode text; decoding CTC output is just the vocabulary.
    vocab = json.loads(Path(hf_hub_download(repo, "vocab.json")).read_text(encoding="utf-8"))
    id_to_phone = {i: p for p, i in vocab.items()}

    def decode(ids):
        phones, previous = [], None
        for i in ids:
            if i != previous and id_to_phone.get(i) not in ("<pad>", "<s>", "</s>", "<unk>", None):
                phones.append(id_to_phone[i])
            previous = i
        return " ".join(phones)

    results = []

    def run(voices):
        for label, generate in voices:
            measure(label, generate)

    def measure(label, generate):
        t0 = time.perf_counter()
        audio, sr = generate()
        synth_s = time.perf_counter() - t0
        slug = label.lower().replace(" ", "_").replace("(", "").replace(")", "")
        save_wav(out / f"{slug}.wav", audio, sr)
        inputs = features(to_16k(audio, sr), sampling_rate=16000, return_tensors="pt")
        with torch.no_grad():
            ids = recognizer(inputs.input_values).logits.argmax(-1)[0].tolist()
        phones = decode(ids)
        affricates = phones.count("tʃ") + phones.count("dʒ")
        reduced = phones.count("ɨ")
        results.append({"voice": label, "wav": f"{slug}.wav", "affricates": affricates, "reduced_e": reduced,
                        "brazilian_score": round(min(affricates / AFFRICATE_SLOTS, 1.0), 2),
                        "audio_s": round(len(audio) / sr, 1), "synth_s": round(synth_s, 1), "phones": phones})
        print(f"{label:38s} tʃ/dʒ {affricates:2d}/{AFFRICATE_SLOTS}  ɨ {reduced:2d}  "
              f"audio {len(audio) / sr:5.1f}s  synth {synth_s:4.1f}s", flush=True)

    run(sherpa_voices())
    run(pocket_voices(out))
    (out / "accent.json").write_text(json.dumps(results, ensure_ascii=False, indent=1), encoding="utf-8")


if __name__ == "__main__":
    main()
