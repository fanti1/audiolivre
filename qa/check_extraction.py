"""Scores the parser against the corpus: how much of each document came out right, and what broke.

Compares what the app extracted on the device (CorpusDumpTest) against the text the PDFs were generated
from. Because the source is known exactly, this needs no human annotation and no judgement call.

    python qa/check_extraction.py %USERPROFILE%\\qa-models\\corpus_extraction.jsonl

Reports, per document:
  cobertura    share of the expected text that came out, character for character
  blocos       expected blocks matched, lost (never came out) and merged (two blocks in one paragraph)
  defeitos     the named failures: running head inside a paragraph, eaten drop cap, word broken in half,
               page number read as text, columns interleaved
"""

import argparse
import json
import re
import sys
import unicodedata
from difflib import SequenceMatcher
from pathlib import Path

CORPUS = Path(__file__).parent.parent / "app" / "src" / "androidTest" / "assets" / "corpus"
MATCH = 0.80  # below this two blocks are not the same block


def fold(text):
    text = unicodedata.normalize("NFKD", text.lower())
    text = "".join(c for c in text if not unicodedata.combining(c))
    text = text.replace("\u2014", " ").replace("\u2013", " ").replace("\u201c", '"').replace("\u201d", '"')
    return re.sub(r"[^a-z0-9 ]+", " ", text).strip()


def words(text):
    return fold(text).split()


def similarity(a, b):
    return SequenceMatcher(None, fold(a), fold(b)).ratio()


def load_expected(doc):
    rows = []
    for line in (CORPUS / f"{doc}.expected.txt").read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        kind, _, text = line.partition("\t")
        rows.append({"kind": kind, "text": text})
    return rows


def align(expected, got):
    """Greedy left-to-right alignment: each expected block takes the best match still ahead of the last one."""
    pairs = []
    cursor = 0
    for want in expected:
        best, best_score = None, 0.0
        for i in range(cursor, min(cursor + 6, len(got))):
            score = similarity(want["text"], got[i]["text"])
            if score > best_score:
                best, best_score = i, score
        if best is not None and best_score >= MATCH:
            pairs.append((want, got[best], best_score))
            cursor = best + 1
        else:
            pairs.append((want, None, best_score))
    return pairs


HEAD_WORDS = {
    "romance": ["a casa da esquina", "o que o sr almeida sabia"],
    "ciencia": ["como o cerebro mede o tempo"],
    "artigo": ["leitor automatico de pdf avaliacao"],
}


def defects(doc, expected, got, pairs):
    found = []
    joined = " ".join(g["text"] for g in got)

    # A running head that ends up inside the text instead of being dropped. The chapter's own title may
    # legitimately appear in the prose ("Marina chegou à casa da esquina"), so it only counts when the
    # block it landed in was not supposed to contain it.
    for want, have, _ in pairs:
        if have is None:
            continue
        for head in HEAD_WORDS.get(doc, []):
            if head in fold(have["text"]) and head not in fold(want["text"]):
                found.append(("cabeçalho dentro do parágrafo", have["text"][:70]))

    # A page number read as if it were text.
    for g in got:
        if re.fullmatch(r"\d{1,3}", g["text"].strip()):
            found.append(("número de página lido como texto", g["text"].strip()))

    # The first letter of a chapter swallowed by the drop cap.
    for want, have, _ in pairs:
        if have is None:
            continue
        first_want, first_have = words(want["text"])[:1], words(have["text"])[:1]
        if first_want and first_have and first_want != first_have and first_want[0].endswith(first_have[0]):
            found.append(("capitular comida", f"{want['text'][:30]} -> {have['text'][:30]}"))

    # A word left broken in half by end-of-line hyphenation.
    for g in got:
        for m in re.finditer(r"(?<=[a-zà-ÿ])- (?=[a-zà-ÿ])", g["text"]):
            found.append(("palavra partida", g["text"][max(0, m.start() - 25):m.end() + 15]))

    # Two columns interleaved: a sentence from one column continued by a line of the other.
    for want, have, score in pairs:
        if have is not None and 0.80 <= score < 0.95 and len(words(want["text"])) > 12:
            found.append(("texto embaralhado", f"{score:.2f} {have['text'][:60]}"))

    return found


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("extraction")
    ap.add_argument("--verbose", action="store_true")
    args = ap.parse_args()

    rows = [json.loads(l) for l in Path(args.extraction).read_text(encoding="utf-8").splitlines() if l.strip()]
    by_doc = {}
    for row in rows:
        by_doc.setdefault(row["doc"], []).append(row)

    total_ok = True
    for doc in sorted(by_doc):
        got = by_doc[doc]
        expected = load_expected(doc)
        pairs = align(expected, got)
        matched = [p for p in pairs if p[1] is not None]
        lost = [p[0] for p in pairs if p[1] is None]

        want_chars = sum(len(fold(e["text"])) for e in expected)
        ok_chars = sum(len(fold(w["text"])) * s for w, g, s in pairs if g is not None)
        coverage = ok_chars / max(want_chars, 1)

        # A heading the parser failed to recognise as one still reads, but the pauses around it go missing.
        heading_misses = sum(1 for w, g, _ in pairs if g is not None and (w["kind"] == "heading") != g["heading"])
        found = defects(doc, expected, got, pairs)

        print(f"\n=== {doc} ===")
        print(f"cobertura {coverage * 100:.1f}%   blocos {len(matched)}/{len(expected)} "
              f"(perdidos {len(lost)})   parágrafos extraídos {len(got)}   títulos errados {heading_misses}")
        if found:
            kinds = {}
            for kind, sample in found:
                kinds.setdefault(kind, []).append(sample)
            for kind, samples in kinds.items():
                print(f"  defeito: {kind} ({len(samples)}x)  ex: {samples[0][:70]}")
        if lost:
            for block in lost[:4]:
                print(f"  perdido: [{block['kind']}] {block['text'][:70]}")
        if args.verbose:
            for want, have, score in pairs:
                if have is None or score < 0.99:
                    print(f"  [{score:.2f}] esperado: {want['text'][:80]}")
                    print(f"         obtido: {(have['text'][:80] if have else '(nada)')}")
        if coverage < 0.98 or lost or found:
            total_ok = False

    print("\n" + ("tudo certo" if total_ok else "há defeitos a corrigir"))


if __name__ == "__main__":
    main()
