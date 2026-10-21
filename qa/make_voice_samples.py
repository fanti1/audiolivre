"""Records the previews the voice screen plays: one short passage per Brazilian voice, at the app's reading pace.

    python qa/make_voice_samples.py
"""

import subprocess
import sys
import tempfile
import wave
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).parent))
import listen_check as lc  # noqa: E402

ASSETS = Path(__file__).parent.parent / "app" / "src" / "main" / "assets" / "voice_samples"
MODELS = lc.MODEL_DIR.parent
BASE_SPEED = 0.70  # VoiceCast.BASE_SPEED: the audiobook pace, ~140 words per minute
# VoiceModels.NOISE_SCALE / NOISE_SCALE_W, so a preview sounds exactly like the book will.
NOISE_SCALE = 0.25
NOISE_SCALE_W = 0.65
SAMPLE = ("Naquela noite, a cidade parecia diferente. Ele abriu a porta devagar, sem fazer barulho, "
          "e ficou ouvindo a própria respiração.")
# (folder, pace) — the pace matches VoiceModels, so a preview sounds exactly like the book will.
VOICES = {
    "faber": ("vits-piper-pt_BR-faber-medium", 1.00),
    "cadu": ("vits-piper-pt_BR-cadu-medium", 1.23),
    "jeff": ("vits-piper-pt_BR-jeff-medium", 1.23),
    "miro": ("vits-piper-pt_BR-miro-high", 1.17),
    "dii": ("vits-piper-pt_BR-dii-high", 1.16),
    "edresson": ("vits-piper-pt_BR-edresson-low", 1.40),
}


def main():
    ASSETS.mkdir(parents=True, exist_ok=True)
    for old in ASSETS.glob("narrador-*.ogg"):
        old.unlink()
    for voice_id, (folder, pace) in VOICES.items():
        tts = lc.piper_tts(MODELS / folder, threads=8, noise_scale=NOISE_SCALE, noise_scale_w=NOISE_SCALE_W)
        audio = lc.trim_silence(lc.synth(tts, SAMPLE, 0, BASE_SPEED * pace, 0), tts.sample_rate)
        wav = Path(tempfile.gettempdir()) / f"{voice_id}.wav"
        with wave.open(str(wav), "wb") as w:
            w.setnchannels(1)
            w.setsampwidth(2)
            w.setframerate(tts.sample_rate)
            w.writeframes((np.clip(audio, -1, 1) * 32767).astype(np.int16).tobytes())
        out = ASSETS / f"{voice_id}.ogg"
        subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", str(wav), "-ac", "1", "-c:a", "libvorbis",
                        "-q:a", "2", str(out)], check=True)
        wav.unlink()
        print(f"{out.name}: {len(audio) / tts.sample_rate:.1f}s, {out.stat().st_size // 1024} KB")


if __name__ == "__main__":
    main()
