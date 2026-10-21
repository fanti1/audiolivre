"""Records every example of the onomatopoeia catalogue on the app's voice and checks none comes out spelled.

Speech recognition is useless for judging a 0.3-second sound (it heard "a gente se vê no próximo vídeo" for
"Xiu"), but it is reliable at one thing: a word the voice spells comes back as a run of capitals or letter
names ("TRRR", "V-R-R-R", "esse agá"). That is what this checks; the rest is for the ear, on the page.

    ./gradlew testDebugUnitTest --tests "*OnomatopoeiaExamplesTest*"
    python qa/onomatopoeia_page.py <page.html>
"""

import base64
import json
import re
import subprocess
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import listen_check as lc  # noqa: E402

ROOT = Path(__file__).parent.parent
EXAMPLES = ROOT / "app" / "build" / "qa" / "onomatopoeia.jsonl"
VOICE = Path.home() / "qa-models" / "vits-piper-pt_BR-faber-medium"
BASE_SPEED = 0.70
SCREAM_SPEED = 0.45  # Prosody.SCREAM_SPEED

LETTER_NAMES = {"esse", "agá", "zê", "érre", "erre", "efe", "tê", "cê", "pê", "gê", "bê", "dê", "ene", "eme",
                "éle", "xis", "cá", "quê", "vê", "jota"}
SPELLED = re.compile(r"\b[B-DF-HJ-NP-TV-Z]{2,}\b|\b(?:[A-Za-z]-){2,}[A-Za-z]\b")


def spelled(heard):
    # One letter name alone is usually an ordinary word ("cá", "pê"); spelling produces a run of them.
    names = [w for w in (x.strip(".,!?;:").lower() for x in heard.split()) if w in LETTER_NAMES]
    return bool(SPELLED.search(heard)) or len(names) >= 2


def ogg_uri(audio, sr):
    with tempfile.TemporaryDirectory() as tmp:
        wav, ogg = Path(tmp) / "a.wav", Path(tmp) / "a.ogg"
        lc.save_wav(wav, audio, sr)
        subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", str(wav), "-ac", "1", "-c:a", "libvorbis",
                        "-q:a", "4", str(ogg)], check=True)
        return "data:audio/ogg;base64," + base64.b64encode(ogg.read_bytes()).decode()


def main():
    rows = [json.loads(l) for l in EXAMPLES.read_text(encoding="utf-8").splitlines() if l.strip()]
    tts = lc.piper_tts(VOICE, 8)
    whisper = lc.WhisperModel("large-v3", device="cuda", compute_type="float16")
    groups, flagged = {}, []
    with tempfile.TemporaryDirectory() as tmp:
        probe = Path(tmp) / "p.wav"
        for row in rows:
            speed = BASE_SPEED * (SCREAM_SPEED if row["drawnOut"] else 1.0)
            audio = lc.trim_silence(lc.synth(tts, row["spoken"], 0, speed, 0), tts.sample_rate)
            lc.save_wav(probe, audio, tts.sample_rate)
            segments, _ = whisper.transcribe(str(probe), language="pt", beam_size=5)
            heard = " ".join(s.text.strip() for s in segments)
            bad = spelled(heard)
            if bad:
                flagged.append((row["written"], row["spoken"], heard))
            groups.setdefault(row["group"], []).append({
                "written": row["written"], "spoken": row["spoken"], "secs": round(len(audio) / tts.sample_rate, 1),
                "src": ogg_uri(audio, tts.sample_rate), "spelled": bad,
            })
    print(f"{len(rows)} sons gravados, {len(flagged)} soletrados")
    for written, spoken, heard in flagged:
        print(f"  SOLETRADO: {written} -> {spoken} -> ouvido: {heard}")

    if len(sys.argv) > 1:
        data = [{"title": g, "items": items} for g, items in groups.items()]
        template = (ROOT / "qa" / "onomatopoeia_page.html").read_text(encoding="utf-8")
        Path(sys.argv[1]).write_text(template.replace("/*DATA*/null", json.dumps(data, ensure_ascii=False)),
                                     encoding="utf-8")
        print(f"{sys.argv[1]}: {Path(sys.argv[1]).stat().st_size // 1024} KB")


if __name__ == "__main__":
    main()
