"""Finds where a given word is said in a recording, from the recording's known transcript.

Speech recognition is a poor guide here: it rarely writes down "hum", "uhum" or "psiu" as said, so its word
timings land on the neighbouring words. Forced alignment (Meta's MMS aligner) instead lines the transcript we
already have up with the audio, letter by letter, and reports how confident it is.

Runs in the GPU venv (torchaudio 2.6 ships the aligner):

    venv-tts\\Scripts\\python qa/align_words.py jobs.json spans.json

jobs.json: [{"id", "wav", "text", "match"}]; spans.json: {id: {"start", "end", "score"}} in seconds.
"""

import json
import sys
import unicodedata
from pathlib import Path

import soundfile as sf
import torch
import torchaudio


def romanize(text):
    """The aligner knows lower-case a-z and the apostrophe: accents are dropped, anything else separates words."""
    plain = unicodedata.normalize("NFD", text.lower())
    plain = "".join(c for c in plain if not unicodedata.combining(c))
    return "".join(c if "a" <= c <= "z" or c == "'" else " " for c in plain).split()


def main():
    jobs = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
    device = "cuda" if torch.cuda.is_available() else "cpu"
    bundle = torchaudio.pipelines.MMS_FA
    model = bundle.get_model(with_star=False).to(device).eval()
    tokenizer, aligner = bundle.get_tokenizer(), bundle.get_aligner()

    spans = {}
    for job in jobs:
        words = romanize(job["text"])
        target = romanize(job["match"])
        if not words or not target:
            continue
        # The sound's position among the transcript's words (the first place its words appear in a row).
        at = next((i for i in range(len(words) - len(target) + 1) if words[i:i + len(target)] == target), None)
        if at is None:
            continue
        audio, sr = sf.read(job["wav"], dtype="float32", always_2d=True)
        wave = torch.from_numpy(audio.mean(1)).unsqueeze(0)
        if sr != bundle.sample_rate:
            wave = torchaudio.functional.resample(wave, sr, bundle.sample_rate)
        with torch.inference_mode():
            emission, _ = model(wave.to(device))
            try:
                token_spans = aligner(emission[0], tokenizer(words))
            except Exception:
                continue
        frames = wave.size(1) / emission.size(1) / bundle.sample_rate  # seconds per emission frame
        first, last = token_spans[at], token_spans[at + len(target) - 1]
        score = sum(s.score * (s.end - s.start) for w in token_spans[at:at + len(target)] for s in w) / max(
            sum(s.end - s.start for w in token_spans[at:at + len(target)] for s in w), 1)
        spans[job["id"]] = {"start": first[0].start * frames, "end": last[-1].end * frames, "score": score}
    Path(sys.argv[2]).write_text(json.dumps(spans, indent=1), encoding="utf-8")
    print(f"{len(spans)} of {len(jobs)} aligned")


if __name__ == "__main__":
    main()
