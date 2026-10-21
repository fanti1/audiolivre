"""Bundled previews for the two narrator options in the app's voice screen (Supertonic 3, with character voices)."""

import subprocess
import sys
import tempfile
import wave
from pathlib import Path

import numpy as np

import generate as g

ASSETS = Path(__file__).parent.parent / "app" / "src" / "main" / "assets" / "voice_samples"
SPEED = 0.85
# sid: F1..F5 = 0..4, M1..M5 = 5..9 (see generate.SUPERTONIC_NAMES). Characters stay the same for both narrators.
CASTS = {
    "narrador-masculino": {"N": 5, "M": 7, "W": 1},
    "narrador-feminino": {"N": 0, "M": 7, "W": 1},
}


def save_ogg(samples, sr, name):
    wav_path = Path(tempfile.gettempdir()) / f"{name}.wav"
    pcm = (np.clip(samples, -1.0, 1.0) * 32767).astype(np.int16)
    with wave.open(str(wav_path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(sr)
        w.writeframes(pcm.tobytes())
    out = ASSETS / f"{name}.ogg"
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", str(wav_path), "-ac", "1", "-c:a", "libvorbis",
                    "-q:a", "3", str(out)], check=True)
    wav_path.unlink()
    return out


def main():
    ASSETS.mkdir(parents=True, exist_ok=True)
    engine = g.supertonic_engine()
    for name, sids in CASTS.items():
        audio, synth = g.render_cast(engine, sids, SPEED)
        out = save_ogg(audio, engine.sr, name)
        print(f"{out.name}: {audio.size / engine.sr:.1f}s audio, {synth:.1f}s synth, {out.stat().st_size // 1024} KB")


if __name__ == "__main__":
    sys.exit(main())
