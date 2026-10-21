"""Writes the app's voice cast from the measured pick (qa/voice_select.py) and refreshes the bundled previews.

The app ships its own small voice-style file, assets/voices/cast.bin, holding exactly its four voices in a fixed
order (sid 0 male narrator, 1 female narrator, 2 male character, 3 female character), so blended voices can be used
and the app never depends on the order of the downloaded model's voice.bin.

    python qa/make_cast.py
"""

import json
import subprocess
import sys
import tempfile
import wave
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).parent))
import listen_check as lc  # noqa: E402
from voice_select import PRESETS, make_tts  # noqa: E402

APP = Path(__file__).parent.parent / "app" / "src" / "main" / "assets"
# The cast, as measured in qa/voice_select.py, qa/pick_characters.py and qa/accent_score.py. A tuple of three is a
# blend: (voice, other voice, weight of the first). The narrator carries 30% of F5, the most Brazilian-sounding
# style, which raised its Brazilian markers from 21 to 24 while staying a man's voice (93 Hz).
CAST = [
    ("male_narrator", ("M5", "F5", 0.7)),
    ("female_narrator", ("F5",)),
    ("male_character", ("M3", "M4", 0.7)),
    ("female_character", ("F1",)),
]
ROLES = [role for role, _ in CAST]
PREVIEW_SPEED = 0.85
PREVIEW = [  # (role, text) — narration, a man's line and a woman's line, as the book reader would voice them
    ("N", "Capítulo quatro. A casa no fim da rua."),
    ("N", "Naquela noite, o senhor Almeida chegou às vinte e três horas, com mil e quinhentos reais no bolso e um "
          "gosto amargo na boca. A porta rangeu. Alguém, ou alguma coisa, respirava no escuro."),
    ("M", "Quem está aí?"),
    ("N", "perguntou ele, com a voz trêmula."),
    ("W", "Você não devia ter voltado."),
    ("N", "respondeu uma mulher, quase num sussurro."),
    ("W", "Eu disse que não gosto de visitas."),
]


def main():
    raw = (lc.MODEL_DIR / "voice.bin").read_bytes()
    n, s1, d1, _, s2, d2 = np.frombuffer(raw[:48], dtype=np.int64)
    floats = np.frombuffer(raw[48:], dtype=np.float32)
    ttl = floats[: n * s1 * d1].reshape(n, s1, d1)
    dp = floats[n * s1 * d1:].reshape(n, s2, d2)

    def style(recipe, table):
        first = table[PRESETS.index(recipe[0])]
        if len(recipe) == 1:
            return first
        mixed = recipe[2] * first + (1 - recipe[2]) * table[PRESETS.index(recipe[1])]
        return (mixed / np.linalg.norm(mixed, axis=-1, keepdims=True)).astype(np.float32)

    described = {role: (recipe[0] if len(recipe) == 1 else f"{recipe[0]} {recipe[2]:.0%} + {recipe[1]}")
                 for role, recipe in CAST}
    print("cast:", described)
    head = np.array([len(CAST), s1, d1, len(CAST), s2, d2], dtype=np.int64).tobytes()
    target = APP / "voices" / "cast.bin"
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(head + np.stack([style(r, ttl) for _, r in CAST]).tobytes()
                       + np.stack([style(r, dp) for _, r in CAST]).tobytes())
    (APP / "voices" / "cast.json").write_text(json.dumps(described, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"{target} ({target.stat().st_size} bytes)")

    tts = make_tts(target)
    sr = tts.sample_rate
    for preview, narrator in [("narrador-masculino", 0), ("narrador-feminino", 1)]:
        sids = {"N": narrator, "M": 2, "W": 3}
        parts = []
        for role, text in PREVIEW:
            parts.append(lc.trim_silence(lc.synth(tts, text, sids[role], PREVIEW_SPEED, 5), sr))
            parts.append(np.zeros(int(sr * 0.35), dtype=np.float32))
        audio = np.concatenate(parts)
        wav = Path(tempfile.gettempdir()) / f"{preview}.wav"
        with wave.open(str(wav), "wb") as w:
            w.setnchannels(1)
            w.setsampwidth(2)
            w.setframerate(sr)
            w.writeframes((np.clip(audio, -1, 1) * 32767).astype(np.int16).tobytes())
        out = APP / "voice_samples" / f"{preview}.ogg"
        subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", str(wav), "-ac", "1", "-c:a", "libvorbis",
                        "-q:a", "3", str(out)], check=True)
        print(f"{out.name}: {len(audio) / sr:.1f}s")


if __name__ == "__main__":
    main()
