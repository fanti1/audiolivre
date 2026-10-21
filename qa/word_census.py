"""Finds which words the voice actually gets wrong, so a pronunciation dictionary is built from evidence.

Reads the results of a book run (qa/listen_check.py writes results.jsonl), aligns what was written against
what Whisper heard word by word, and ranks the words that come out wrong most often. A word that is
misheard again and again is one the phonemizer reads wrong; a word wrong once is usually noise.

    python qa/word_census.py %USERPROFILE%\\qa-models\\out\\book\\results.jsonl --top 40
"""

import argparse
import json
import sys
from collections import Counter, defaultdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import listen_check as lc  # noqa: E402
from jiwer import process_words  # noqa: E402


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("results")
    ap.add_argument("--top", type=int, default=40)
    ap.add_argument("--min-hits", type=int, default=2, help="ignore words that only went wrong once")
    args = ap.parse_args()

    wrong = Counter()
    seen = Counter()
    heard_as = defaultdict(Counter)

    for raw in Path(args.results).read_text(encoding="utf-8").splitlines():
        item = json.loads(raw)
        ref = lc.fold(item["tts"]).split()
        hyp = lc.fold(item.get("heard", "")).split()
        for word in ref:
            seen[word] += 1
        if not ref or not hyp:
            continue
        out = process_words(" ".join(ref), " ".join(hyp))
        for chunk in out.alignments[0]:
            if chunk.type == "equal":
                continue
            for i in range(chunk.ref_start_idx, chunk.ref_end_idx):
                word = ref[i]
                wrong[word] += 1
                if chunk.type == "substitute":
                    offset = i - chunk.ref_start_idx + chunk.hyp_start_idx
                    if offset < len(hyp):
                        heard_as[word][hyp[offset]] += 1
                else:
                    heard_as[word]["(sumiu)" if chunk.type == "delete" else "(extra)"] += 1

    rows = [(w, wrong[w], seen[w], wrong[w] / seen[w], heard_as[w].most_common(2))
            for w in wrong if wrong[w] >= args.min_hits and len(w) > 2]
    # A word the voice gets wrong most of the times it appears is a pronunciation bug, not bad luck.
    rows.sort(key=lambda r: (-r[1] * r[3], -r[1]))

    print(f"{'palavra':<20}{'erros':>6}{'vezes':>7}{'taxa':>7}   ouvido como")
    for word, bad, total, rate, top in rows[:args.top]:
        says = ", ".join(f"{h} ({n})" for h, n in top)
        print(f"{word:<20}{bad:>6}{total:>7}{rate:>6.0%}   {says}")
    print(f"\n{len(rows)} words wrong at least {args.min_hits} times")


if __name__ == "__main__":
    main()
