"""Before/after recordings of the sounds and rhythm of horror, for a listening page.

Screams, hushes, growls and stutters are exactly what speech recognition mishears (it heard "a gente se vê no
próximo vídeo" for a 0.3-second "Xiu"), so these are judged by ear, not by a score. Each passage is voiced
segment by segment the way the app does it: speech and narration apart, with the app's own pauses.

    python qa/horror_page.py --write-lines      # writes qa/horror_lines.txt for HorrorLinesTest
    ./gradlew testDebugUnitTest --tests "*HorrorLinesTest*"
    python qa/horror_page.py <page.html>        # records both versions and builds the page

"Antes" is what the app sent the voice until this phase: the interjections verbatim, 350 ms after every
sentence, the scream at normal speed. "Depois" is the text PtBrNormalizer produces now, with Prosody's pauses.
"""

import base64
import json
import subprocess
import sys
import tempfile
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).parent))
import listen_check as lc  # noqa: E402

ROOT = Path(__file__).parent.parent
LINES = ROOT / "qa" / "horror_lines.txt"
NORMALIZED = ROOT / "app" / "build" / "qa" / "horror_normalized.jsonl"
VOICE = Path.home() / "qa-models" / "vits-piper-pt_BR-faber-medium"
BASE_SPEED = 0.70

# Prosody's numbers, so the recording paces the passage exactly as the app will.
SENTENCE, FRAGMENT, CUT_OFF, SPEAKER, PARAGRAPH = 350, 600, 200, 480, 700
SCREAM_SPEED = 0.45

# (title, what to listen for, segments). A segment is (printed text, starts a new paragraph).
PASSAGES = [
    ("Sussurro", "“Shhhh” era soletrado letra por letra",
     [("Shhhh.", False), ("Ele está ouvindo.", False)]),
    ("Gagueira de medo", "a letra solta saía com o nome dela: “ene, ene, não”",
     [("— N-n-não fui eu", True), ("— gaguejou Billy.", False), ("— Eu j-juro.", False)]),
    ("Batidas", "cada “Tum.” agora é uma batida, com o silêncio que ela pede",
     [("Tum.", True), ("Tum.", False), ("Tum.", False), ("Alguma coisa batia na porta do porão.", False)]),
    ("Fala cortada", "quem interrompe não espera: a resposta vem na hora",
     [("— Eu só queria—", True), ("— Cala a boca!", True), ("— gritou o pai.", False)]),
    ("Grito", "o grito era espremido em menos de um segundo",
     [("Aaaaaaaah!", True)]),
    ("Porta rangendo", "“nhéééééc” embolava as vogais",
     [("A porta do quarto se abriu devagar, nhéééééc, e o corredor estava vazio.", True)]),
    ("Rosnado", "“Grrrrr” era soletrado letra por letra",
     [("Grrrrr.", True), ("O cachorro mostrou os dentes.", False)]),
    ("Pensamento", "“Hmmm” virava um nome próprio inventado",
     [("Hmmm, pensou ela.", True), ("Tem alguma coisa errada aqui.", False)]),
    ("Zumbido", "“Zzzzz” saía como “zê, zê, zê, zê”",
     [("Zzzzz.", True), ("A lâmpada do porão zumbia sem parar.", False)]),
    ("Não esticado", "“Nããããão” quebrava em sílabas soltas",
     [("Nããããão!", True)]),
    ("Silêncio", "as frases de uma palavra respiram",
     [("Ele contou até dez.", True), ("Nada.", False), ("Silêncio.", False), ("Depois, o riso.", False)]),
]


def spoken_text(printed):
    # BookBuilder.ttsTextOf: the dialogue dashes and quotes around a segment are not read.
    return printed.strip().strip("—–-“”\"«» ").strip()


def old_spoken(printed):
    # What the normalizer did with these before: the sounds verbatim, capitals read as words.
    text = spoken_text(printed)
    return " ".join(w.lower() if len(w) > 1 and w.isupper() else w for w in text.split())


def is_speech(printed):
    return printed.lstrip().startswith(("—", "–"))


def new_pause(segments, i):
    printed, _ = segments[i]
    if i + 1 >= len(segments):
        return 0
    next_printed, next_new_paragraph = segments[i + 1]
    text = spoken_text(printed)
    if printed.rstrip().endswith(("—", "–")) and not printed.rstrip()[-2].isspace():
        return CUT_OFF
    if next_new_paragraph:
        return PARAGRAPH
    words = [w for w in text.split() if any(c.isalpha() for c in w)]
    if text.endswith(".") and not text.endswith("..") and 1 <= len(words) <= 2:
        return FRAGMENT
    if is_speech(printed) != is_speech(next_printed):
        return SPEAKER
    return SENTENCE


def old_pause(segments, i):
    if i + 1 >= len(segments):
        return 0
    return PARAGRAPH if segments[i + 1][1] else SENTENCE


def is_stretched(text):
    import re
    words = text.split()
    return bool(re.search(r"([aeiouáàâãéêíóôõú])\1{2,}", text, re.IGNORECASE)) and len(words) <= 3


def write_lines():
    lines = [spoken_text(p) for _, _, segs in PASSAGES for p, _ in segs]
    LINES.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"{len(lines)} segments -> {LINES}")


def render(tts, pieces):
    sr = tts.sample_rate
    parts = []
    for text, speed, pause in pieces:
        if text.strip():
            parts.append(lc.trim_silence(lc.synth(tts, text, 0, BASE_SPEED * speed, 0), sr))
        if pause:
            parts.append(np.zeros(int(sr * pause / 1000), dtype=np.float32))
    return np.concatenate(parts) if parts else np.zeros(1, dtype=np.float32), sr


def to_ogg_uri(audio, sr):
    with tempfile.TemporaryDirectory() as tmp:
        wav, ogg = Path(tmp) / "a.wav", Path(tmp) / "a.ogg"
        lc.save_wav(wav, audio, sr)
        subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", str(wav), "-ac", "1", "-c:a", "libvorbis",
                        "-q:a", "4", str(ogg)], check=True)
        return "data:audio/ogg;base64," + base64.b64encode(ogg.read_bytes()).decode()


def build(dest):
    now = {}
    for raw in NORMALIZED.read_text(encoding="utf-8").splitlines():
        row = json.loads(raw)
        now[row["text"]] = row["spoken"]
    tts = lc.piper_tts(VOICE, 8)

    cards = []
    for title, note, segments in PASSAGES:
        before = [(old_spoken(p), 1.0, old_pause(segments, i)) for i, (p, _) in enumerate(segments)]
        after = []
        for i, (printed, _) in enumerate(segments):
            text = spoken_text(printed)
            speed = SCREAM_SPEED if is_stretched(text) else 1.0
            after.append((now.get(text, text), speed, new_pause(segments, i)))
        a_audio, sr = render(tts, before)
        b_audio, _ = render(tts, after)
        cards.append({
            "title": title, "note": note,
            "printed": " ".join(p for p, _ in segments),
            "before_text": " · ".join(t for t, _, _ in before if t),
            "after_text": " · ".join(t for t, _, _ in after if t) or "(silêncio)",
            "before": to_ogg_uri(a_audio, sr), "after": to_ogg_uri(b_audio, sr),
            "before_s": round(len(a_audio) / sr, 1), "after_s": round(len(b_audio) / sr, 1),
        })
        print(f"{title:<20} antes {cards[-1]['before_s']:4.1f}s  depois {cards[-1]['after_s']:4.1f}s")

    template = (ROOT / "qa" / "horror_page.html").read_text(encoding="utf-8")
    Path(dest).write_text(template.replace("/*DATA*/null", json.dumps(cards, ensure_ascii=False)), encoding="utf-8")
    print(f"{dest}: {Path(dest).stat().st_size // 1024} KB")


if __name__ == "__main__":
    if sys.argv[1:] == ["--write-lines"]:
        write_lines()
    else:
        build(sys.argv[1])
