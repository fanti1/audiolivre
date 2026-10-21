"""Chooses how the narrator says each onomatopoeia by comparing it with Brazilian readers saying it.

The references are two public corpora of Brazilian speech: BRSpeech-TTS (audiobook narration, LibriVox
readings labelled by accent) and CORAA-MUPE (spontaneous life-story interviews, labelled by birth country),
where hesitations, agreement and laughter are said the way people really say them. For every sound in the
catalogue this finds passages where a Brazilian speaker said it, cuts the sound out of the recording, and
transcribes it into phonemes. Every candidate spelling is then spoken by
the app's voice and transcribed the same way; the candidate whose phonemes sit closest to the human readings
wins. No judgement by ear is involved: the humans in the corpus are the judges.

    python qa/onomatopoeia_reference.py find       # texts.jsonl -> exemplars.json (which passages to fetch)
    python qa/onomatopoeia_reference.py fetch      # download those passages' audio, cut the sound out
    python qa/onomatopoeia_reference.py score      # phonemes of humans vs candidates -> choices.json
"""

import json
import re
import subprocess
import sys
import tempfile
import unicodedata
from collections import defaultdict
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).parent))

ROOT = Path(__file__).parent.parent
WORK = Path.home() / "qa-models" / "onoma_ref"
CORPORA = {
    "brspeech": Path.home() / "qa-models" / "brspeech" / "texts.jsonl",
    "coraa": Path.home() / "qa-models" / "coraa" / "texts.jsonl",
}
MAX_EXEMPLARS = 8

# How each sound shows up in running text (lower case, accents kept). Stretching is allowed with "+".
PATTERNS = {
    "BUUUM!": r"b+u+m+", "Pou!": r"p+o+u+|pow", "Pof!": r"p+o+f+|plof+|ploft", "Plaft!": r"pla+f+t*",
    "Tum.": r"tum+|tump", "Créc.": r"cr[eé]+[ck]+", "Crack!": r"cra+[ck]+", "Cabrum!": r"ca+bru+m+|cabum",
    "Zás!": r"z[aá]+[sz]", "Bang!": r"ba+ng+|bangue", "Pá!": r"p[aá]+(?=\s*!)", "Ratatatá!": r"ra(ta)+t[aá]",
    "Nhééééc.": r"nh[eé]+[ck]+|nheque", "Toc-toc-toc.": r"to+c+(?:[- ]to+c+)+", "Tic-tac.": r"ti+[ck]+[- ]?ta+[ck]+|tique[- ]?taque",
    "Ding-dong.": r"di[nm]+g?[- ]?do[nm]+g?|blim[- ]?bl[eé]m", "Trim!": r"tr+i+m+|trrim", "Plim.": r"pli+[mn]",
    "Click.": r"cli+[ck]+|clique", "Bip.": r"bi+p+|bipe", "Vroom!": r"vr+[uo]+m+|bru+m+", "Bzzzz.": r"bz+|zu+m+|zz+",
    "Plic-ploc.": r"pli+[ck]+[- ]?plo+[ck]+|plic|ploc", "Chuá!": r"chu[aá]+", "Splash!": r"spla+sh",
    "Glub.": r"glu+[bp]+|glu[- ]glu", "Au-au!": r"au[- ]au", "Auuuuuu!": r"au{3,}", "Miau.": r"mi+a+u+",
    "Muuuu.": r"mu{2,}|m[uú]+!", "Mééééé.": r"m[eé]{2,}|b[eé]{2,}", "Cocoricó!": r"cocoric[oó]+|c[oó]+c[oó]+ric[oó]+",
    "Quack!": r"qu[aá]+[ck]*[- ,]+qu[aá]+|quack", "Oinc.": r"[oó]inc|oink", "Grrrrr.": r"gr{2,}|rr{2,}",
    "Sssss.": r"s{3,}|chi{3,}", "Atchim!": r"atchi+m+|atchim", "Cof-cof.": r"co+f+[- ,]+co+f+|cof", "Snif.": r"sni+f+",
    "Buááá!": r"bu[aá]+|bu[eé]+", "Hic!": r"hi+c+|hique", "Argh!": r"a+r+g+h*", "Ugh.": r"u+g+h+",
    "Aff.": r"a+f{2,}", "Arf.": r"a+r+f+", "Pfff.": r"pf+|pu+f+", "Brrr.": r"br{2,}+", "Nham.": r"nha+m+",
    "Slurp.": r"slu+r+p+", "Gulp.": r"gu+l+p+", "Shhhh.": r"sh{2,}|psi+u+|xi+u+|chi+u+|pst+", "Psst.": r"ps+t+|psi+u",
    "Hmmm.": r"h+u*m{2,}|hum+", "Mmmm.": r"m{3,}|hum+", "Aham.": r"a+h?[aã]+m*|ahã|a-h[aã]", "Uhum.": r"u+h+u+m+|unhum|hum[- ]hum",
    "Tsc, tsc.": r"ts+[ck]+|tch+", "Hahaha!": r"(?:ha){2,}h?|(?:rá[ ,]*){2,}|(?:ah){2,}", "Hehehe.": r"(?:he){2,}h?|(?:r[eê][ ,]+){2,}",
    "Hihihi.": r"(?:hi){2,}h?|(?:rí[ ,]*){2,}", "Huahuahua!": r"(?:hua){2,}", "Kkkkk.": r"k{3,}", "Rsrs.": r"(?:rs){2,}",
    "Aaaaaaah!": r"a{3,}h*", "Aiiii!": r"a+i{2,}|ai[- ]ai", "Uuuuuu.": r"u{3,}h*", "Ohhh.": r"o+h{2,}|o{3,}h*",
    "Fiuuu!": r"fi+u+|fiu", "Whoosh.": r"vu+sh|whoosh|fu{3,}", "N-n-não.": r"n-n-?n?[aã]o", "S-s-sim.": r"s-s-?s?im",
    "E-eu vi.": r"e-eu",
}

LETTER_RE = r"[^\W\d_]"


def fold(text):
    return unicodedata.normalize("NFC", text.lower())


# ---- find: which passages contain each sound ---------------------------------------------------------------

def passages():
    """Every passage of both corpora said by a Brazilian speaker, in one shape."""
    out = []
    for corpus, path in CORPORA.items():
        if not path.is_file():
            continue
        for line in path.read_text(encoding="utf-8").splitlines():
            r = json.loads(line)
            if corpus == "brspeech":
                if (r.get("accent") or "").lower() != "brazilian":
                    continue
                out.append({"corpus": corpus, "file": "datasets/freds0/BRSpeech-TTS/data/" + r["file"],
                            "row": r["row"], "speaker": f"b{r['speaker']}", "text": r["text"]})
            else:
                # CORAA-MUPE is the Museu da Pessoa's Brazilian life-story interviews: every speaker is Brazilian.
                if r.get("audio_quality") != "high":
                    continue
                out.append({"corpus": corpus, "split": r["split"], "file_path": r["file_path"],
                            "speaker": f"c{r['speaker_code']}", "text": r["original_text"] or ""})
    return out


def find():
    WORK.mkdir(parents=True, exist_ok=True)
    brazilian = passages()
    print(f"{len(brazilian)} passages said by Brazilian speakers")
    found = {}
    for written, pattern in PATTERNS.items():
        rx = re.compile(rf"(?<!{LETTER_RE})(?:{pattern})(?!{LETTER_RE})", re.IGNORECASE)
        matches = []
        for r in brazilian:
            m = rx.search(fold(r["text"]))
            if m:
                matches.append(dict(r, match=m.group(0)))
        # Eight readings by one person are one reading: take different speakers, alternating between the two
        # corpora, and shorter passages first because the sound is easier to find in them.
        matches.sort(key=lambda h: len(h["text"]))
        hits, speakers = [], set()
        for round_ in range(2):
            for corpus in ("coraa", "brspeech") * MAX_EXEMPLARS:
                pool = [h for h in matches if h["corpus"] == corpus and h not in hits and
                        (round_ == 1 or h["speaker"] not in speakers)]
                if pool and len(hits) < MAX_EXEMPLARS:
                    hits.append(pool[0])
                    speakers.add(pool[0]["speaker"])
        found[written] = hits
        print(f"  {written:<14} {len(matches):5d} achados, {len(hits)} escolhidos  {[h['match'] for h in hits[:4]]}")
    (WORK / "exemplars.json").write_text(json.dumps(found, ensure_ascii=False, indent=1), encoding="utf-8")
    covered = sum(1 for v in found.values() if v)
    print(f"{covered} of {len(found)} sounds have human readings")


# ---- fetch: download the passages and cut the sound out ----------------------------------------------------

_blocks = {}


def read_audio(file, row):
    """The encoded audio of one row, read from just the row group that holds it (tens of megabytes, not the
    whole file); a block already read serves every other row in it."""
    from huggingface_hub import HfFileSystem
    import pyarrow.parquet as pq
    pf = pq.ParquetFile(HfFileSystem().open(file))
    first = 0
    for g in range(pf.metadata.num_row_groups):
        size = pf.metadata.row_group(g).num_rows
        if row < first + size:
            if (file, g) not in _blocks:
                if len(_blocks) > 4:
                    _blocks.pop(next(iter(_blocks)))
                _blocks[(file, g)] = pf.read_row_group(g, columns=["audio"]).column("audio")
            return _blocks[(file, g)][row - first].as_py()["bytes"]
        first += size
    return None


def coraa_index(splits):
    """Where each CORAA recording sits: file name -> (parquet file, row). Only the small file_path column of the
    splits that are needed is read, never the audio."""
    from huggingface_hub import HfFileSystem
    import pyarrow.parquet as pq
    cache = WORK / "coraa_index.json"
    index = {k: tuple(v) for k, v in json.loads(cache.read_text(encoding="utf-8")).items()} if cache.is_file() else {}
    done = {v[0] for v in index.values()}
    fs = HfFileSystem()
    todo = [f for f in sorted(k for k in fs.find("datasets/nilc-nlp/CORAA-MUPE-ASR") if k.endswith(".parquet"))
            if f.split("/")[-1].split("-")[0] in splits and f not in done]

    def names(f):
        # Small blocks: the default read-ahead pulled megabytes of the neighbouring audio for every column
        # chunk, and one file took three minutes instead of twenty seconds.
        with fs.open(f, block_size=64 * 1024) as fh:
            return f, pq.ParquetFile(fh).read(columns=["file_path"]).column("file_path").to_pylist()

    from concurrent.futures import ThreadPoolExecutor
    with ThreadPoolExecutor(max_workers=6) as pool:
        for f, paths in pool.map(names, todo):
            for i, path in enumerate(paths):
                index[path] = (f, i)
            cache.write_text(json.dumps(index), encoding="utf-8")
            print(f"    indice CORAA: {f.split('/')[-1]}", flush=True)
    return index


VENV_PYTHON = Path.home() / "qa-models" / "venv-tts" / "Scripts" / "python.exe"
MIN_ALIGN_SCORE = 0.2  # below this the aligner is guessing where the word is


def fetch():
    exemplars = json.loads((WORK / "exemplars.json").read_text(encoding="utf-8"))
    splits = {h["split"] for hits in exemplars.values() for h in hits if h["corpus"] == "coraa"}
    index = coraa_index(splits) if splits else {}
    clips = WORK / "clips"
    clips.mkdir(exist_ok=True)

    # 1. The passages' audio, whole.
    jobs = []
    for k, (written, hits) in enumerate(exemplars.items()):
        for n, hit in enumerate(hits):
            if hit["corpus"] == "coraa":
                hit["file"], hit["row"] = index.get(hit["file_path"], (None, None))
            if hit.get("file") is None:
                continue
            full = clips / f"{k:02d}_{n}_full.wav"
            if not full.is_file():
                try:
                    data = read_audio(hit["file"], hit["row"])
                except Exception as e:
                    print(f"    falhou {hit['file']}#{hit['row']}: {e}")
                    continue
                if not data:
                    continue
                raw = clips / f"{k:02d}_{n}_raw"
                raw.write_bytes(data)
                subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", str(raw), "-ac", "1", "-ar", "16000",
                                str(full)], check=True)
                raw.unlink()
            jobs.append({"id": f"{k:02d}_{n}", "written": written, "wav": str(full), "text": hit["text"],
                         "match": hit["match"], "speaker": hit["speaker"], "corpus": hit["corpus"]})
        print(f"  {written:<14} {sum(1 for j in jobs if j['written'] == written)} trechos baixados", flush=True)

    # 2. Where in each passage the sound is said, from the transcript we already have. Speech recognition's
    # word timings were no guide: it rarely writes "hum" or "uhum" down, so its timings landed on neighbours.
    (WORK / "jobs.json").write_text(json.dumps(jobs, ensure_ascii=False, indent=1), encoding="utf-8")
    subprocess.run([str(VENV_PYTHON), str(Path(__file__).parent / "align_words.py"), str(WORK / "jobs.json"),
                    str(WORK / "spans.json")], check=True)
    spans = json.loads((WORK / "spans.json").read_text(encoding="utf-8"))

    # 3. Cut it out, with a breath of margin so the first consonant is not clipped.
    cut = defaultdict(list)
    for job in jobs:
        span = spans.get(job["id"])
        if not span or span["score"] < MIN_ALIGN_SCORE:
            continue
        seg = clips / f"{job['id']}.wav"
        subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", job["wav"],
                        "-ss", f"{max(0.0, span['start'] - 0.03):.3f}", "-to", f"{span['end'] + 0.04:.3f}",
                        "-ac", "1", "-ar", "16000", str(seg)], check=True)
        cut[job["written"]].append({"wav": str(seg), "speaker": job["speaker"], "corpus": job["corpus"],
                                    "match": job["match"], "align_score": round(span["score"], 3)})
    for written in exemplars:
        print(f"  {written:<14} {len(cut[written])} recortes alinhados", flush=True)
    (WORK / "clips.json").write_text(json.dumps({w: cut[w] for w in exemplars}, ensure_ascii=False, indent=1),
                                     encoding="utf-8")


def similarity(a, b):
    from difflib import SequenceMatcher
    return SequenceMatcher(None, a, b).ratio()


# ---- score: phonemes of the humans against every candidate -------------------------------------------------

VOWELS = set("aeiouɐɛɔəɪʊɨyøœæɑɒʌɜɤɯ")


def phone_cost(a, b):
    if a == b:
        return 0.0
    va, vb = a[:1] in VOWELS, b[:1] in VOWELS
    if va and vb:
        return 0.5 if a[:1] != b[:1] else 0.25  # differ only by nasalisation or length
    if not va and not vb:
        return 0.7
    return 1.0


def distance(x, y):
    """How far a candidate's phones x are from a human reading y, with cheaper substitutions between similar
    sounds. The human clip is cut from running speech and can carry a phone of the neighbouring word at either
    end ("pou, que" came out "p o k i"), so those ends cost nothing: the candidate is matched against the best
    stretch inside the reading (semi-global alignment)."""
    if not x or not y:
        return 1.0
    d = np.zeros((len(x) + 1, len(y) + 1))
    d[:, 0] = np.arange(len(x) + 1)  # skipping candidate phones costs
    d[0, :] = 0                      # skipping human phones before the match is free
    for i in range(1, len(x) + 1):
        for j in range(1, len(y) + 1):
            d[i, j] = min(d[i - 1, j] + 1, d[i, j - 1] + 1, d[i - 1, j - 1] + phone_cost(x[i - 1], y[j - 1]))
    # ...and after it, by taking the best end column.
    return float(d[-1, :].min()) / len(x)


class Phonemes:
    def __init__(self):
        import torch
        from huggingface_hub import hf_hub_download
        from transformers import Wav2Vec2FeatureExtractor, Wav2Vec2ForCTC
        repo = "facebook/wav2vec2-xlsr-53-espeak-cv-ft"
        self.torch = torch
        self.features = Wav2Vec2FeatureExtractor.from_pretrained(repo)
        self.model = Wav2Vec2ForCTC.from_pretrained(repo).eval()
        vocab = json.loads(Path(hf_hub_download(repo, "vocab.json")).read_text(encoding="utf-8"))
        self.id_to_phone = {i: p for p, i in vocab.items()}

    def of(self, audio16k, start=None, end=None):
        """The phones heard, or only those heard between start and end (seconds). A short clip on its own is
        a poor input: with no speech around it the recognizer guesses (it heard Chinese tones in "Bum"), so
        the sound is recognised inside its whole sentence and its phones picked out by time."""
        inputs = self.features(audio16k, sampling_rate=16000, return_tensors="pt")
        with self.torch.no_grad():
            ids = self.model(inputs.input_values).logits.argmax(-1)[0].tolist()
        frame = len(audio16k) / 16000 / max(len(ids), 1)
        phones, previous = [], None
        for n, i in enumerate(ids):
            p = self.id_to_phone.get(i)
            if i != previous and p not in ("<pad>", "<s>", "</s>", "<unk>", None):
                t = n * frame
                if start is None or start <= t <= end:
                    phones.append(p)
            previous = i
        return phones


def read_wav16(path):
    import wave
    with wave.open(str(path), "rb") as w:
        sr, n = w.getframerate(), w.getnchannels()
        pcm = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float32) / 32768.0
    pcm = pcm.reshape(-1, n).mean(1)
    if sr != 16000:
        import torch, torchaudio
        pcm = torchaudio.functional.resample(torch.from_numpy(pcm), sr, 16000).numpy()
    return pcm


def candidates_for(written):
    from onomatopoeia_review import ALTERNATIVES, ROUND2
    current = {json.loads(l)["written"]: json.loads(l)["spoken"]
               for l in (ROOT / "app" / "build" / "qa" / "onomatopoeia.jsonl").read_text(encoding="utf-8").splitlines()}
    seen, out = set(), []
    for text in [current.get(written), written] + ALTERNATIVES.get(written, []) + ROUND2.get(written, []):
        if not text:
            continue
        text, _, factor = text.partition("@")
        key = (text, factor)
        if key not in seen:
            seen.add(key)
            out.append((text, float(factor) if factor else None))
    return out


def score():
    import listen_check as lc
    from onomatopoeia_review import BASE_SPEED, SCREAM_SPEED, drawn_out
    clips = json.loads((WORK / "clips.json").read_text(encoding="utf-8"))
    ph = Phonemes()
    tts = lc.piper_tts(Path.home() / "qa-models" / "vits-piper-pt_BR-faber-medium", 8)
    choices = {}
    for written, cuts in clips.items():
        if not cuts:
            choices[written] = {"human": [], "choice": None, "reason": "sem leitura humana no corpus"}
            continue
        spans = json.loads((WORK / "spans.json").read_text(encoding="utf-8"))
        human = []
        for c in cuts:
            job = Path(c["wav"]).stem
            span = spans[job]
            human.append(ph.of(read_wav16(WORK / "clips" / f"{job}_full.wav"), span["start"] - 0.03, span["end"] + 0.04))
        human = [h for h in human if h]
        if not human:
            choices[written] = {"human": [], "choice": None, "reason": "recortes sem fonemas reconhecíveis"}
            continue
        human_secs = float(np.median([len(read_wav16(c["wav"])) / 16000 for c in cuts]))
        ranked = []
        for text, factor in candidates_for(written):
            speed = BASE_SPEED * (factor if factor else (SCREAM_SPEED if drawn_out(text) else 1.0))
            import torch, torchaudio

            def said(t, v=BASE_SPEED):
                a = lc.trim_silence(lc.synth(tts, t, 0, v, 0), tts.sample_rate)
                return torchaudio.functional.resample(torch.from_numpy(a), tts.sample_rate, 16000).numpy()
            # As in a book: the sound is its own sentence between two others, which the recognizer hears too.
            before, a16, after = said("Então se ouviu, lá fora."), said(text, speed), said("E depois, silêncio.")
            gap = np.zeros(int(0.35 * 16000), dtype=np.float32)
            start = (len(before) + len(gap)) / 16000
            phones = ph.of(np.concatenate([before, gap, a16, gap, after]), start - 0.03, start + len(a16) / 16000 + 0.04)
            d = float(np.mean([distance(phones, h) for h in human]))
            # Matching inside the human clip would let a one-phone candidate fit anywhere; a candidate much
            # shorter than what people say (allowing a stray phone at each end of the clip) pays for it.
            typical = float(np.median([len(h) for h in human]))
            d += 0.5 * max(0.0, (typical - 2) - len(phones)) / max(typical, 1)
            secs = len(a16) / 16000
            ranked.append({"text": text, "speed": factor, "phones": " ".join(phones), "distance": round(d, 3),
                           "secs": round(secs, 2), "off_secs": round(abs(secs - human_secs), 2)})
        # Closest phones first; among near-ties, the length closest to how long people take to say it.
        ranked.sort(key=lambda r: (round(r["distance"] / 0.05), r["off_secs"]))
        choices[written] = {"human": [" ".join(h) for h in human], "human_secs": round(human_secs, 2),
                            "choice": ranked[0], "ranked": ranked}
        print(f"  {written:<14} humanos: {' | '.join(' '.join(h) for h in human[:3]):<40} -> "
              f"{ranked[0]['text']} ({ranked[0]['distance']})", flush=True)
    (WORK / "choices.json").write_text(json.dumps(choices, ensure_ascii=False, indent=1), encoding="utf-8")


if __name__ == "__main__":
    if sys.argv[1] == "index":
        WORK.mkdir(parents=True, exist_ok=True)
        coraa_index(set(sys.argv[2:]) or {"validation", "test"})
    else:
        {"find": find, "fetch": fetch, "score": score}[sys.argv[1]]()
